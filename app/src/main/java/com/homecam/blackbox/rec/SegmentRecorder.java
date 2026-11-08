package com.homecam.blackbox.rec;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 자체 녹화 엔진.
 * 카메라 → OverlayRenderer(날짜/시간 합성) → H.264 인코더, 마이크 → AAC 인코더 → MP4.
 * 인코더는 계속 돌리고 키프레임 시점에 MediaMuxer 만 교체하므로 파일 사이 공백이 없다.
 */
public final class SegmentRecorder {
    private static final String TAG = "SegmentRecorder";
    private static final String VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final long TIMEOUT_US = 10_000;

    public static final class Config {
        public int width = 1280, height = 720, fps = 15, videoBitrate = 1_000_000;
        public boolean audio = true;
        public int audioBitrate = 32_000, sampleRate = 16_000;
        public boolean timestamp = true;
        public int iFrameIntervalSec = 2;
    }

    public interface Callback {
        /** 새 세그먼트 파일. null 이면 이번 키프레임은 건너뛰고 다음에 다시 요청 */
        @Nullable File nextFile();
        void onSegmentStarted(File file);
        void onSegmentFinished(File file);
        void onError(String message, @Nullable Throwable t);
    }

    private final Config cfg;
    private final Callback cb;

    private MediaCodec videoCodec;
    private MediaCodec audioCodec;
    private AudioRecord audioRecord;
    private Surface encoderInput;
    private OverlayRenderer renderer;
    private Thread videoThread, audioThread;
    private volatile boolean running;
    private volatile boolean audioEnabled;

    // muxer 상태 (lock = this)
    private MediaMuxer muxer;
    private File muxFile;
    private int videoTrack = -1, audioTrack = -1;
    private MediaFormat videoFormat, audioFormat;
    private long baseUs = -1;
    private long lastVideoUs = -1, lastAudioUs = -1;
    private long firstVideoUs = -1;
    private boolean rotatePending;
    private long lastNextFileTryUs = -1;

    public SegmentRecorder(Config cfg, Callback cb) {
        this.cfg = cfg;
        this.cb = cb;
    }

    /** 녹화 시작. 카메라가 그려야 할 Surface 를 반환 */
    public Surface start() throws Exception {
        audioEnabled = cfg.audio && setupAudio();

        MediaFormat vf = MediaFormat.createVideoFormat(VIDEO_MIME, cfg.width, cfg.height);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, cfg.videoBitrate);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, cfg.iFrameIntervalSec);
        videoCodec = MediaCodec.createEncoderByType(VIDEO_MIME);
        videoCodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderInput = videoCodec.createInputSurface();
        videoCodec.start();

        renderer = new OverlayRenderer(encoderInput, cfg.width, cfg.height, cfg.fps, cfg.timestamp);
        Surface cameraSurface = renderer.start();

        running = true;
        videoThread = new Thread(this::videoLoop, "rec-video");
        videoThread.start();
        if (audioEnabled) {
            audioThread = new Thread(this::audioLoop, "rec-audio");
            audioThread.start();
        }
        return cameraSurface;
    }

    /** 다음 키프레임에서 새 파일로 전환 */
    public void requestRotate() {
        synchronized (this) {
            rotatePending = true;
        }
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            videoCodec.setParameters(b);
        } catch (Exception e) {
            Log.w(TAG, "request sync frame failed", e);
        }
    }

    public long getFramesRendered() {
        return renderer != null ? renderer.getFramesRendered() : 0;
    }

    public boolean isAudioEnabled() { return audioEnabled; }

    /** 정지 (블로킹, 최대 수 초). 메인 스레드에서 호출하지 말 것 */
    public void stop() {
        running = false;
        if (audioThread != null) join(audioThread, 3000);
        if (renderer != null) renderer.stop();
        try {
            videoCodec.signalEndOfInputStream();
        } catch (Exception e) {
            Log.w(TAG, "signalEndOfInputStream", e);
        }
        if (videoThread != null) join(videoThread, 3000);
        synchronized (this) {
            closeMuxer();
        }
        release(videoCodec);
        release(audioCodec);
        if (audioRecord != null) {
            try { audioRecord.release(); } catch (Exception ignored) { }
        }
        if (encoderInput != null) encoderInput.release();
    }

    // ------------------------------------------------------------ video

    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long stopDeadline = 0;
        try {
            while (true) {
                if (!running) {
                    if (stopDeadline == 0) stopDeadline = System.currentTimeMillis() + 2500;
                    else if (System.currentTimeMillis() > stopDeadline) break;
                }
                int idx = videoCodec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (this) {
                        videoFormat = videoCodec.getOutputFormat();
                    }
                } else if (idx >= 0) {
                    ByteBuffer buf = videoCodec.getOutputBuffer(idx);
                    if (buf != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        writeVideo(buf, info);
                    }
                    videoCodec.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "video loop", e);
            if (running) cb.onError("영상 인코더 오류", e);
        }
    }

    private synchronized void writeVideo(ByteBuffer buf, MediaCodec.BufferInfo info) {
        if (videoFormat == null) return;
        long pts = info.presentationTimeUs;
        if (firstVideoUs < 0) firstVideoUs = pts;
        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

        if (key && (muxer == null || rotatePending)) {
            boolean waitAudio = audioEnabled && audioFormat == null;
            if (waitAudio && pts - firstVideoUs > 5_000_000) {
                // 오디오 인코더가 응답하지 않으면 영상만 저장
                Log.w(TAG, "audio format not ready, disabling audio");
                audioEnabled = false;
                waitAudio = false;
            }
            if (!waitAudio) {
                // 공간 부족 등으로 파일을 못 만들 때 너무 자주 재시도하지 않도록 1초 간격
                if (muxer != null || lastNextFileTryUs < 0 || pts - lastNextFileTryUs > 1_000_000) {
                    lastNextFileTryUs = pts;
                    closeMuxer();
                    openMuxer(pts);
                    rotatePending = false;
                }
            }
        }
        if (muxer == null || pts <= lastVideoUs) return;
        lastVideoUs = pts;
        info.presentationTimeUs = pts - baseUs;
        try {
            muxer.writeSampleData(videoTrack, buf, info);
        } catch (Exception e) {
            Log.e(TAG, "writeSampleData video", e);
            closeMuxer();
            cb.onError("파일 쓰기 오류", e);
        } finally {
            info.presentationTimeUs = pts;
        }
    }

    // ------------------------------------------------------------ audio

    @SuppressLint("MissingPermission")
    private boolean setupAudio() {
        try {
            int ch = AudioFormat.CHANNEL_IN_MONO;
            int enc = AudioFormat.ENCODING_PCM_16BIT;
            int min = AudioRecord.getMinBufferSize(cfg.sampleRate, ch, enc);
            if (min <= 0) return false;
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, cfg.sampleRate, ch, enc, min * 4);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release();
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, cfg.sampleRate, ch, enc, min * 4);
            }
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release();
                audioRecord = null;
                return false;
            }
            MediaFormat af = MediaFormat.createAudioFormat(AUDIO_MIME, cfg.sampleRate, 1);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, cfg.audioBitrate);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            audioCodec = MediaCodec.createEncoderByType(AUDIO_MIME);
            audioCodec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioCodec.start();
            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "AudioRecord not recording");
                release(audioCodec);
                audioCodec = null;
                audioRecord.release();
                audioRecord = null;
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "audio setup failed, video only", e);
            if (audioRecord != null) {
                try { audioRecord.release(); } catch (Exception ignored) { }
                audioRecord = null;
            }
            release(audioCodec);
            audioCodec = null;
            return false;
        }
    }

    private void audioLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean eosSent = false;
        long lastPts = 0;
        long deadline = 0;
        try {
            while (true) {
                if (!eosSent) {
                    int in = audioCodec.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        ByteBuffer ib = audioCodec.getInputBuffer(in);
                        int n = 0;
                        if (ib != null) {
                            ib.clear();
                            int want = Math.min(ib.capacity(), 2048);
                            n = audioRecord.read(ib, want);
                            if (n < 0) n = 0;
                        }
                        long pts = System.nanoTime() / 1000 - (long) (n / 2) * 1_000_000L / cfg.sampleRate;
                        if (pts <= lastPts) pts = lastPts + 1;
                        lastPts = pts;
                        boolean eos = !running;
                        audioCodec.queueInputBuffer(in, 0, n, pts, eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        if (eos) {
                            eosSent = true;
                            deadline = System.currentTimeMillis() + 1500;
                        }
                    }
                } else if (System.currentTimeMillis() > deadline) {
                    break;
                }
                int out = audioCodec.dequeueOutputBuffer(info, eosSent ? TIMEOUT_US : 0);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (this) {
                        audioFormat = audioCodec.getOutputFormat();
                    }
                } else if (out >= 0) {
                    ByteBuffer ob = audioCodec.getOutputBuffer(out);
                    if (ob != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        writeAudio(ob, info);
                    }
                    audioCodec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "audio loop", e);
        }
        try { audioRecord.stop(); } catch (Exception ignored) { }
    }

    private synchronized void writeAudio(ByteBuffer buf, MediaCodec.BufferInfo info) {
        if (muxer == null || audioTrack < 0) return;
        long pts = info.presentationTimeUs;
        if (pts < baseUs || pts <= lastAudioUs) return;
        lastAudioUs = pts;
        info.presentationTimeUs = pts - baseUs;
        try {
            muxer.writeSampleData(audioTrack, buf, info);
        } catch (Exception e) {
            Log.e(TAG, "writeSampleData audio", e);
        } finally {
            info.presentationTimeUs = pts;
        }
    }

    // ------------------------------------------------------------ muxer

    private void openMuxer(long ptsUs) {
        File f = null;
        try {
            f = cb.nextFile();
            if (f == null) return;
            MediaMuxer m = new MediaMuxer(f.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            videoTrack = m.addTrack(videoFormat);
            audioTrack = (audioEnabled && audioFormat != null) ? m.addTrack(audioFormat) : -1;
            m.start();
            muxer = m;
            muxFile = f;
            baseUs = ptsUs;
            lastVideoUs = -1;
            lastAudioUs = -1;
            cb.onSegmentStarted(f);
        } catch (Exception e) {
            Log.e(TAG, "openMuxer", e);
            muxer = null;
            if (f != null && f.length() == 0) //noinspection ResultOfMethodCallIgnored
                f.delete();
            cb.onError("파일 생성 실패", e);
        }
    }

    private void closeMuxer() {
        if (muxer == null) return;
        MediaMuxer m = muxer;
        File f = muxFile;
        muxer = null;
        muxFile = null;
        boolean ok = true;
        try {
            m.stop();
        } catch (Exception e) {
            ok = false;
            Log.w(TAG, "muxer stop", e);
        }
        try { m.release(); } catch (Exception ignored) { }
        if (f != null) {
            if (!ok && f.length() < 1024) //noinspection ResultOfMethodCallIgnored
                f.delete();
            else cb.onSegmentFinished(f);
        }
    }

    // ------------------------------------------------------------ util

    private static void release(@Nullable MediaCodec c) {
        if (c == null) return;
        try { c.stop(); } catch (Exception ignored) { }
        try { c.release(); } catch (Exception ignored) { }
    }

    private static void join(Thread t, long ms) {
        try { t.join(ms); } catch (InterruptedException ignored) { }
    }
}
