package com.homecam.blackbox;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/** 설정값 접근 헬퍼 */
public final class Prefs {
    public static final String KEY_FOLDER = "record_folder";
    public static final String KEY_TREE_URI = "record_tree_uri";
    public static final String KEY_SCHEDULE_ENABLED = "schedule_enabled";
    public static final String KEY_SCHEDULES = "schedules";
    public static final String KEY_MAX_GB = "max_storage_gb";
    public static final String KEY_SEGMENT = "segment_minutes";
    public static final String KEY_RESOLUTION = "resolution";
    public static final String KEY_FPS = "fps";
    public static final String KEY_BITRATE = "video_bitrate_kbps";
    public static final String KEY_AUDIO = "audio_enabled";
    public static final String KEY_TIMESTAMP = "timestamp_enabled";
    public static final String KEY_AUTO_LAUNCH = "auto_start_launch";
    public static final String KEY_AUTO_BOOT = "auto_start_boot";
    /** 사용자가 마지막으로 녹화를 켜두었는지 (부팅 시 복원용) */
    public static final String KEY_WANT_RECORDING = "want_recording";

    public static final int AUDIO_BITRATE = 32000;
    public static final int AUDIO_SAMPLE_RATE = 16000;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = PreferenceManager.getDefaultSharedPreferences(ctx.getApplicationContext());
    }

    public SharedPreferences raw() { return sp; }

    /** 사용자가 선택한 저장 폴더 절대 경로 ("" = 기본 폴더) */
    public String folder() { return sp.getString(KEY_FOLDER, ""); }

    /** 일반 폴더 선택 (시스템 선택기 폴더 설정은 해제) */
    public void setFolder(String path) { sp.edit().putString(KEY_FOLDER, path).putString(KEY_TREE_URI, "").apply(); }

    /** 시스템 폴더 선택기(SAF)로 고른 폴더 URI ("" = 사용 안 함) */
    public String treeUri() { return sp.getString(KEY_TREE_URI, ""); }

    public void setTreeUri(String uri) { sp.edit().putString(KEY_TREE_URI, uri).apply(); }

    public boolean scheduleEnabled() { return sp.getBoolean(KEY_SCHEDULE_ENABLED, false); }

    public void setScheduleEnabled(boolean v) { sp.edit().putBoolean(KEY_SCHEDULE_ENABLED, v).apply(); }

    public java.util.List<Schedule> schedules() { return Schedule.parse(sp.getString(KEY_SCHEDULES, "")); }

    public void setSchedules(java.util.List<Schedule> list) {
        sp.edit().putString(KEY_SCHEDULES, Schedule.toJson(list)).apply();
    }

    /** 예약 기능을 고려해 지금 녹화해야 하는지 */
    public boolean shouldRecordNow() {
        return !scheduleEnabled() || Schedule.isActive(schedules(), System.currentTimeMillis());
    }

    public long maxStorageBytes() {
        double gb = parseDouble(sp.getString(KEY_MAX_GB, "0"), 0);
        return gb <= 0 ? 0 : (long) (gb * 1_000_000_000L);
    }

    public int segmentMinutes() { return Math.max(1, parseInt(sp.getString(KEY_SEGMENT, "5"), 5)); }

    public int width() { return parseInt(resolution()[0], 1280); }

    public int height() { return parseInt(resolution()[1], 720); }

    private String[] resolution() {
        String[] p = sp.getString(KEY_RESOLUTION, "1280x720").split("x");
        return p.length == 2 ? p : new String[]{"1280", "720"};
    }

    public int fps() { return parseInt(sp.getString(KEY_FPS, "15"), 15); }

    public int videoBitrate() { return parseInt(sp.getString(KEY_BITRATE, "1000"), 1000) * 1000; }

    public boolean audioEnabled() { return sp.getBoolean(KEY_AUDIO, true); }

    public boolean timestampEnabled() { return sp.getBoolean(KEY_TIMESTAMP, true); }

    public boolean autoStartLaunch() { return sp.getBoolean(KEY_AUTO_LAUNCH, true); }

    public boolean autoStartBoot() { return sp.getBoolean(KEY_AUTO_BOOT, true); }

    public boolean wantRecording() { return sp.getBoolean(KEY_WANT_RECORDING, false); }

    public void setWantRecording(boolean v) { sp.edit().putBoolean(KEY_WANT_RECORDING, v).apply(); }

    /** 초당 예상 바이트 (영상 + 음성 + 컨테이너 오버헤드 약 3%) */
    public double bytesPerSecond() {
        double bits = videoBitrate() + (audioEnabled() ? AUDIO_BITRATE : 0);
        return bits / 8.0 * 1.03;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }
}
