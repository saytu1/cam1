package com.homecam.blackbox;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.herohan.uvcapp.CameraException;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.herohan.uvcapp.VideoCaptureConfig;
import com.homecam.blackbox.rec.SegmentRecorder;
import com.serenegiant.usb.Size;

import java.io.File;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * USB(UVC) 카메라를 열고 N분 단위 MP4 파일로 끊어서 계속 녹화하는 포그라운드 서비스.
 * 저장 공간이 부족해지면 가장 오래된 파일부터 지운다(루프 녹화).
 */
public class RecorderService extends Service {
    private static final String TAG = "RecorderService";
    public static final String ACTION_START = "com.homecam.blackbox.START";
    public static final String ACTION_STOP = "com.homecam.blackbox.STOP";
    public static final String ACTION_REFRESH = "com.homecam.blackbox.REFRESH";

    private static final int NOTIFY_ID = 1001;
    private static final long WATCHDOG_INTERVAL_MS = 30_000;

    public interface Listener {
        void onStatusChanged(RecorderService service);
    }

    public class LocalBinder extends Binder {
        public RecorderService getService() { return RecorderService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ICameraHelper camera;
    private Prefs prefs;
    private PowerManager.WakeLock wakeLock;
    @Nullable private Listener listener;
    @Nullable private Surface previewSurface;
    private boolean previewAttached;

    private boolean running;          // 사용자가 녹화를 켠 상태
    private boolean stopping;
    @Nullable private UsbDevice device;
    private boolean cameraOpened;
    private boolean recording;
    private boolean startPending;
    @Nullable private SegmentRecorder recorder;
    @Nullable private Surface recorderSurface;
    private long lastFrames = -1;
    private final ExecutorService recExecutor = Executors.newSingleThreadExecutor();
    @Nullable private File currentFile;
    private long segmentStartedAt;
    private long lastWatchSize = -1;
    private int stallCount;
    private int consecutiveErrors;
    private String statusText = "대기 중";
    private File recordDir;

    // ---------------------------------------------------------------- 외부 API

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, RecorderService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, RecorderService.class).setAction(ACTION_STOP);
        ctx.startService(i);
    }

    public void setListener(@Nullable Listener l) {
        listener = l;
        notifyListener();
    }

    public boolean isRunning() { return running; }

    public boolean isRecording() { return recording; }

    public String getStatusText() { return statusText; }

    @Nullable public File getCurrentFile() { return currentFile; }

    public long getSegmentStartedAt() { return segmentStartedAt; }

    /** 미리보기 Surface 연결/해제 (null 이면 해제) */
    public void setPreviewSurface(@Nullable Surface surface) {
        if (previewSurface != null && previewAttached && camera != null) {
            try { camera.removeSurface(previewSurface); } catch (Exception e) { Log.w(TAG, e); }
        }
        previewAttached = false;
        previewSurface = surface;
        attachPreviewIfPossible();
    }

    // ---------------------------------------------------------------- 수명주기

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        startForegroundCompat("카메라 준비 중");
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlackBox:recorder");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (action == null) action = ACTION_START;
        switch (action) {
            case ACTION_STOP:
                prefs.setWantRecording(false);
                stopAll();
                break;
            case ACTION_REFRESH:
                // 앱이 전면에 있을 때 마이크 타입을 다시 붙이기 위해 호출
                startForegroundCompat(statusText);
                break;
            case ACTION_START:
            default:
                startForegroundCompat(statusText);
                prefs.setWantRecording(true);
                startAll();
                break;
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseCamera();
        recExecutor.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 시작 / 종료

    private void startAll() {
        if (running) {
            notifyListener();
            return;
        }
        running = true;
        stopping = false;
        consecutiveErrors = 0;
        if (!wakeLock.isHeld()) wakeLock.acquire();
        recordDir = StorageHelper.recordDir(this);
        if ("sd".equals(prefs.storageLocation()) && !StorageHelper.isUsingSd(this)) {
            Log.w(TAG, "SD card not available, falling back to internal");
        }
        if (camera == null) {
            camera = new CameraHelper();
            camera.setStateCallback(stateCallback);
        }
        setStatus("USB 카메라 연결 대기 중");
        handler.postDelayed(findDeviceRunnable, 1_000);
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    private void stopAll() {
        running = false;
        stopping = true;
        handler.removeCallbacksAndMessages(null);
        setStatus("녹화 종료 중");
        stopRecorder(this::finishStop);
    }

    private void finishStop() {
        releaseCamera();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        setStatus("녹화 중지됨");
        stopForeground(true);
        stopSelf();
    }

    private void releaseCamera() {
        stopRecorder(null);
        if (camera != null) {
            try {
                camera.release();
            } catch (Exception e) {
                Log.w(TAG, "release", e);
            }
            camera = null;
        }
        device = null;
        cameraOpened = false;
        recording = false;
        startPending = false;
        previewAttached = false;
        currentFile = null;
    }

    // ---------------------------------------------------------------- 카메라 상태

    private final Runnable findDeviceRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running || camera == null || device != null) return;
            List<UsbDevice> list = null;
            try { list = camera.getDeviceList(); } catch (Exception e) { Log.w(TAG, e); }
            if (list != null) {
                for (UsbDevice d : list) {
                    if (isUvc(d)) {
                        selectDevice(d);
                        return;
                    }
                }
            }
            setStatus("USB 카메라 연결 대기 중");
            handler.postDelayed(this, 5_000);
        }
    };

    private void selectDevice(UsbDevice d) {
        if (camera == null) return;
        Log.i(TAG, "selectDevice " + d.getDeviceName());
        device = d;
        setStatus("USB 카메라 권한 요청 중: " + nameOf(d));
        camera.selectDevice(d);
    }

    private final ICameraHelper.StateCallback stateCallback = new ICameraHelper.StateCallback() {
        @Override
        public void onAttach(UsbDevice d) {
            Log.i(TAG, "onAttach " + d.getDeviceName());
            if (running && device == null && isUvc(d)) {
                handler.removeCallbacks(findDeviceRunnable);
                selectDevice(d);
            }
        }

        @Override
        public void onDeviceOpen(UsbDevice d, boolean isFirstOpen) {
            Log.i(TAG, "onDeviceOpen " + d.getDeviceName());
            if (!running || camera == null) return;
            openCamera();
        }

        @Override
        public void onCameraOpen(UsbDevice d) {
            Log.i(TAG, "onCameraOpen");
            if (camera == null) return;
            cameraOpened = true;
            consecutiveErrors = 0;
            camera.startPreview();
            attachPreviewIfPossible();
            Size s = camera.getPreviewSize();
            setStatus("카메라 연결됨" + (s != null ? " (" + s.width + "x" + s.height + ")" : ""));
            scheduleSegmentStart(800);
        }

        @Override
        public void onCameraClose(UsbDevice d) {
            Log.i(TAG, "onCameraClose");
            stopRecorder(null);
            cameraOpened = false;
            recording = false;
            previewAttached = false;
            handler.removeCallbacks(rotateRunnable);
            handler.removeCallbacks(startSegmentRunnable);
            startPending = false;
            if (running) setStatus("카메라 닫힘 - 재연결 대기");
        }

        @Override
        public void onDeviceClose(UsbDevice d) {
            Log.i(TAG, "onDeviceClose");
        }

        @Override
        public void onDetach(UsbDevice d) {
            Log.i(TAG, "onDetach " + d.getDeviceName());
            if (device != null && device.getDeviceName().equals(d.getDeviceName())) {
                stopRecorder(null);
                device = null;
                cameraOpened = false;
                recording = false;
                previewAttached = false;
                handler.removeCallbacks(rotateRunnable);
                handler.removeCallbacks(startSegmentRunnable);
                startPending = false;
                if (running) {
                    setStatus("USB 카메라 분리됨 - 재연결 대기");
                    handler.removeCallbacks(findDeviceRunnable);
                    handler.postDelayed(findDeviceRunnable, 3_000);
                }
            }
        }

        @Override
        public void onCancel(UsbDevice d) {
            Log.w(TAG, "onCancel (permission denied)");
            device = null;
            if (running) {
                setStatus("USB 권한이 거부됨. 앱을 열어 카메라 권한/USB 권한을 허용하세요");
                handler.removeCallbacks(findDeviceRunnable);
                handler.postDelayed(findDeviceRunnable, 15_000);
            }
        }

        @Override
        public void onError(UsbDevice d, CameraException e) {
            Log.e(TAG, "camera error " + e.getCode(), e);
            consecutiveErrors++;
            setStatus("카메라 오류(" + e.getCode() + ") - 재시도 중");
            restartCamera(Math.min(30_000, 3_000L * consecutiveErrors));
        }
    };

    private void openCamera() {
        if (camera == null) return;
        // 녹화는 자체 엔진(SegmentRecorder)이 담당하므로 라이브러리 내장 녹화기는 최소 설정 + 마이크 미사용
        VideoCaptureConfig vc = camera.getVideoCaptureConfig();
        vc.setAudioCaptureEnable(false).setBitRate(500_000).setVideoFrameRate(prefs.fps());
        camera.setVideoCaptureConfig(vc);

        Size best = chooseSize(camera.getSupportedSizeList(), prefs.width(), prefs.height());
        setStatus("카메라 여는 중" + (best != null ? " " + best.width + "x" + best.height : ""));
        if (best != null) {
            camera.openCamera(best);
        } else {
            camera.openCamera();
        }
    }

    /** 원하는 해상도에 가장 가까운 지원 해상도 선택 (MJPEG 우선) */
    @Nullable
    static Size chooseSize(@Nullable List<Size> sizes, int w, int h) {
        if (sizes == null || sizes.isEmpty()) return null;
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        long target = (long) w * h;
        for (Size s : sizes) {
            long score = Math.abs((long) s.width * s.height - target);
            if (s.width == w && s.height == h) score = 0;
            // MJPEG(type 7)이 아니면 USB 대역폭 문제가 생기기 쉬워 약간 감점
            if (s.type != 7) score += target / 10;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private void restartCamera(long delayMs) {
        stopRecorder(null);
        handler.removeCallbacks(rotateRunnable);
        handler.removeCallbacks(startSegmentRunnable);
        startPending = false;
        recording = false;
        cameraOpened = false;
        previewAttached = false;
        handler.postDelayed(() -> {
            if (!running) return;
            releaseCamera();
            camera = new CameraHelper();
            camera.setStateCallback(stateCallback);
            handler.removeCallbacks(findDeviceRunnable);
            handler.postDelayed(findDeviceRunnable, 1_500);
        }, delayMs);
    }

    private void attachPreviewIfPossible() {
        if (camera != null && cameraOpened && previewSurface != null && !previewAttached
                && previewSurface.isValid()) {
            try {
                camera.addSurface(previewSurface, false);
                previewAttached = true;
            } catch (Exception e) {
                Log.w(TAG, "addSurface", e);
            }
        }
    }

    // ---------------------------------------------------------------- 세그먼트 녹화

    private final Runnable startSegmentRunnable = this::startRecorder;

    /** 정해진 시각(예: 5분 단위 정각)마다 다음 파일로 전환 */
    private final Runnable rotateRunnable = new Runnable() {
        @Override
        public void run() {
            if (recorder != null) {
                Log.i(TAG, "rotate segment");
                recorder.requestRotate();
            }
            scheduleRotate();
        }
    };

    private void scheduleRotate() {
        handler.removeCallbacks(rotateRunnable);
        long seg = prefs.segmentMinutes() * 60_000L;
        long now = System.currentTimeMillis();
        long local = now + TimeZone.getDefault().getOffset(now);
        long delay = seg - (local % seg);
        if (delay < 20_000) delay += seg; // 너무 짧은 첫 파일 방지
        handler.postDelayed(rotateRunnable, delay);
    }

    private void scheduleSegmentStart(long delay) {
        handler.removeCallbacks(startSegmentRunnable);
        startPending = true;
        handler.postDelayed(startSegmentRunnable, delay);
    }

    private void startRecorder() {
        startPending = false;
        if (!running || camera == null || !cameraOpened || recorder != null) return;

        Size s = camera.getPreviewSize();
        SegmentRecorder.Config cfg = new SegmentRecorder.Config();
        cfg.width = s != null ? s.width : prefs.width();
        cfg.height = s != null ? s.height : prefs.height();
        cfg.fps = prefs.fps();
        cfg.videoBitrate = prefs.videoBitrate();
        cfg.audio = prefs.audioEnabled() && hasPermission(Manifest.permission.RECORD_AUDIO);
        cfg.audioBitrate = Prefs.AUDIO_BITRATE;
        cfg.sampleRate = Prefs.AUDIO_SAMPLE_RATE;
        cfg.timestamp = prefs.timestampEnabled();

        final SegmentRecorder rec = new SegmentRecorder(cfg, new SegmentRecorder.Callback() {
            @Nullable
            @Override
            public File nextFile() {
                // 인코더 스레드에서 호출됨: 공간 확보 후 새 파일 경로 반환
                File dir = StorageHelper.recordDir(RecorderService.this);
                long segBytes = (long) (prefs.bytesPerSecond() * prefs.segmentMinutes() * 60 * 1.2);
                if (!StorageHelper.ensureSpace(RecorderService.this, dir, segBytes, null)) {
                    handler.post(() -> setStatus("저장 공간 부족 - 공간 확보 대기"));
                    return null;
                }
                recordDir = dir;
                return StorageHelper.newSegmentFile(dir);
            }

            @Override
            public void onSegmentStarted(File file) {
                handler.post(() -> {
                    currentFile = file;
                    recording = true;
                    consecutiveErrors = 0;
                    segmentStartedAt = System.currentTimeMillis();
                    lastWatchSize = -1;
                    stallCount = 0;
                    setStatus("녹화 중: " + file.getName());
                });
            }

            @Override
            public void onSegmentFinished(File file) {
                Log.i(TAG, "saved " + file.getName() + " " + file.length());
                handler.post(RecorderService.this::notifyListener);
            }

            @Override
            public void onError(String message, @Nullable Throwable t) {
                Log.e(TAG, "recorder error: " + message, t);
                handler.post(() -> {
                    if (!running) return;
                    consecutiveErrors++;
                    setStatus("녹화 오류: " + message + " - 재시작");
                    stopRecorder(null);
                    if (consecutiveErrors >= 5) restartCamera(3_000);
                    else scheduleSegmentStart(3_000);
                });
            }
        });

        try {
            Surface surface = rec.start();
            recorder = rec;
            recorderSurface = surface;
            lastFrames = -1;
            camera.addSurface(surface, true);
            setStatus("녹화 준비 중" + (rec.isAudioEnabled() ? "" : (cfg.audio ? " (마이크 사용 불가 - 영상만)" : "")));
            scheduleRotate();
        } catch (Exception e) {
            Log.e(TAG, "recorder start failed", e);
            recExecutor.execute(rec::stop);
            consecutiveErrors++;
            setStatus("녹화 시작 실패 - 재시도");
            if (consecutiveErrors >= 5) restartCamera(3_000);
            else scheduleSegmentStart(3_000);
        }
    }

    /** 녹화 엔진 정지 (파일 마무리는 백그라운드 스레드). done 은 메인 스레드에서 호출 */
    private void stopRecorder(@Nullable Runnable done) {
        handler.removeCallbacks(rotateRunnable);
        handler.removeCallbacks(startSegmentRunnable);
        startPending = false;
        final SegmentRecorder rec = recorder;
        final Surface surface = recorderSurface;
        recorder = null;
        recorderSurface = null;
        recording = false;
        if (rec == null) {
            if (done != null) done.run();
            return;
        }
        if (camera != null && surface != null) {
            try { camera.removeSurface(surface); } catch (Exception e) { Log.w(TAG, e); }
        }
        recExecutor.execute(() -> {
            rec.stop();
            if (done != null) handler.post(done);
        });
    }

    /** 파일 크기가 늘지 않으면(카메라 멈춤) 카메라를 재시작 */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (recorder != null) {
                // 카메라 프레임이 들어오지 않거나 파일이 커지지 않으면 멈춤으로 판단
                long frames = recorder.getFramesRendered();
                long size = currentFile != null ? currentFile.length() : -1;
                boolean stalled = frames == lastFrames || (recording && size == lastWatchSize);
                lastFrames = frames;
                lastWatchSize = size;
                if (stalled) {
                    stallCount++;
                    Log.w(TAG, "watchdog: stalled (" + stallCount + ")");
                    if (stallCount >= 3) {
                        stallCount = 0;
                        setStatus("영상 신호 없음 - 카메라 재시작");
                        restartCamera(3_000);
                    }
                } else {
                    stallCount = 0;
                }
            } else if (cameraOpened && !startPending && !stopping) {
                // 어떤 이유로 녹화가 예약되지 않은 경우 복구
                scheduleSegmentStart(500);
            }
            notifyListener();
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    // ---------------------------------------------------------------- 유틸

    static boolean isUvc(UsbDevice d) {
        if (d.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        }
        return false;
    }

    private static String nameOf(UsbDevice d) {
        String n = Build.VERSION.SDK_INT >= 21 ? d.getProductName() : null;
        return n != null ? n : d.getDeviceName();
    }

    private boolean hasPermission(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void setStatus(String s) {
        statusText = s;
        Log.i(TAG, "status: " + s);
        updateNotification();
        notifyListener();
    }

    private void notifyListener() {
        if (listener != null) listener.onStatusChanged(this);
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RecorderService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, App.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, getString(R.string.stop), stop)
                .build();
    }

    private void updateNotification() {
        if (stopping && !running) return;
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFY_ID, buildNotification(statusText));
        } catch (SecurityException ignored) {
            // 알림 권한 미허용
        }
    }

    private void startForegroundCompat(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            boolean wantMic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && prefs.audioEnabled() && hasPermission(Manifest.permission.RECORD_AUDIO);
            if (wantMic) {
                try {
                    startForeground(NOTIFY_ID, n, type | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                    return;
                } catch (Exception e) {
                    // 백그라운드(부팅 직후 등)에서는 마이크 타입이 거부될 수 있음 -> 영상만이라도 녹화
                    Log.w(TAG, "microphone FGS type not allowed now: " + e);
                }
            }
            try {
                startForeground(NOTIFY_ID, n, type);
            } catch (Exception e) {
                Log.e(TAG, "startForeground failed", e);
                startForeground(NOTIFY_ID, n);
            }
        } else {
            startForeground(NOTIFY_ID, n);
        }
    }
}
