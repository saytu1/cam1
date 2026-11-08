package com.homecam.blackbox;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements RecorderService.Listener {
    private static final int REQ_PERMS = 10;

    private SurfaceView preview;
    private TextView status;
    private TextView recBadge;
    private TextView clock;
    private final java.text.SimpleDateFormat clockFmt =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private Button toggle;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private RecorderService service;
    private boolean bound;
    private SurfaceHolder holder;
    private Prefs prefs;
    private boolean autoStartChecked;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            service = ((RecorderService.LocalBinder) b).getService();
            service.setListener(MainActivity.this);
            if (holder != null && holder.getSurface().isValid()) {
                service.setPreviewSurface(holder.getSurface());
            }
            refreshUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            refreshUi();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            ui.postDelayed(this, 1_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = new Prefs(this);

        preview = findViewById(R.id.preview);
        status = findViewById(R.id.status);
        recBadge = findViewById(R.id.rec_badge);
        clock = findViewById(R.id.clock);
        toggle = findViewById(R.id.btn_toggle);

        toggle.setOnClickListener(v -> {
            if (service != null && service.isRunning()) {
                RecorderService.stop(this);
            } else {
                startRecordingWithPermissions();
            }
        });
        findViewById(R.id.btn_list).setOnClickListener(v ->
                startActivity(new Intent(this, RecordingsActivity.class)));
        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder h) {
                holder = h;
                if (service != null) service.setPreviewSurface(h.getSurface());
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder h, int format, int width, int height) { }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder h) {
                if (service != null) service.setPreviewSurface(null);
                holder = null;
            }
        });
        View container = findViewById(R.id.preview_container);
        container.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitPreview(r - l, b - t));

        requestPermissionsIfNeeded();
    }

    /** 미리보기를 설정 해상도 비율(예: 16:9)에 맞춤 */
    private void fitPreview(int cw, int ch) {
        if (cw <= 0 || ch <= 0) return;
        float ratio = (float) prefs.width() / prefs.height();
        int w = cw, h = (int) (cw / ratio);
        if (h > ch) { h = ch; w = (int) (ch * ratio); }
        ViewGroup.LayoutParams lp = preview.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(w, h);
            flp.gravity = android.view.Gravity.CENTER;
            preview.post(() -> preview.setLayoutParams(flp));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 자동 생성하지 않음: 녹화 서비스가 실행 중일 때만 연결
        bindService(new Intent(this, RecorderService.class), conn, 0);
        bound = true;
        ui.post(ticker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (service != null && service.isRunning()) {
            // 앱이 전면일 때 마이크 사용 타입을 다시 붙임 (부팅 직후 시작된 경우 대비)
            startService(new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_REFRESH));
        }
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(ticker);
        if (service != null) {
            service.setListener(null);
            service.setPreviewSurface(null);
        }
        if (bound) {
            unbindService(conn);
            bound = false;
        }
        service = null;
        super.onStop();
    }

    private List<String> missingPermissions() {
        List<String> need = new ArrayList<>();
        String[] perms = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        return need;
    }

    private void requestPermissionsIfNeeded() {
        List<String> need = missingPermissions();
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERMS);
        } else {
            maybeAutoStart();
        }
    }

    private void maybeAutoStart() {
        if (autoStartChecked) return;
        autoStartChecked = true;
        if (prefs.autoStartLaunch() || prefs.wantRecording()) {
            RecorderService.start(this);
        }
    }

    private void startRecordingWithPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            autoStartChecked = false;
            requestPermissionsIfNeeded();
            return;
        }
        RecorderService.start(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                maybeAutoStart();
            } else {
                status.setText("카메라 권한이 필요합니다 (USB 카메라 접근에 필요)");
            }
        }
    }

    @Override
    public void onStatusChanged(RecorderService s) {
        ui.post(this::refreshUi);
    }

    private void refreshUi() {
        boolean running = service != null && service.isRunning();
        boolean rec = service != null && service.isRecording();
        toggle.setText(running ? R.string.stop : R.string.start);
        recBadge.setVisibility(rec ? View.VISIBLE : View.GONE);
        // 미리보기용 현재 시각 (녹화 파일에는 좌측 상단에 새겨짐)
        clock.setVisibility(prefs.timestampEnabled() ? View.VISIBLE : View.GONE);
        clock.setText(clockFmt.format(new java.util.Date()));

        StringBuilder sb = new StringBuilder();
        sb.append(service != null ? service.getStatusText() : "녹화 꺼짐");
        if (rec) {
            long sec = (System.currentTimeMillis() - service.getSegmentStartedAt()) / 1000;
            sb.append(String.format(Locale.US, "  [%02d:%02d]", sec / 60, sec % 60));
        }
        File dir = StorageHelper.recordDir(this);
        List<File> files = StorageHelper.listRecordings(dir);
        long used = StorageHelper.totalSize(files);
        long cap = StorageHelper.capacityBytes(this);
        double hours = cap / prefs.bytesPerSecond() / 3600.0;
        sb.append("\n저장 위치: ").append(StorageHelper.isUsingSd(this) ? "SD카드" : "내장 저장소");
        if ("sd".equals(prefs.storageLocation()) && !StorageHelper.isUsingSd(this)) {
            sb.append(" (SD카드 없음 → 내장 사용)");
        }
        sb.append("\n사용: ").append(StorageHelper.formatBytes(used))
                .append(" / 녹화 가능 용량 ").append(StorageHelper.formatBytes(cap))
                .append(" (").append(files.size()).append("개 파일)")
                .append("\n최대 보관: ").append(StorageHelper.formatHours(hours))
                .append("  ·  ").append(prefs.width()).append("x").append(prefs.height())
                .append(" ").append(prefs.fps()).append("fps ")
                .append(prefs.videoBitrate() / 1000).append("kbps")
                .append(prefs.audioEnabled() ? " +음성" : "")
                .append(prefs.timestampEnabled() ? " +시간표시" : "");
        status.setText(sb.toString());
    }
}
