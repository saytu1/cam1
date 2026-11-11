package com.homecam.blackbox;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 저장 폴더 결정, 녹화 파일 목록, 루프 녹화용 오래된 파일 자동 삭제.
 * 사용자가 고른 폴더(모든 파일 액세스 권한 필요)를 쓰고, 쓸 수 없으면 앱 전용 폴더로 대체한다.
 */
public final class StorageHelper {
    private static final String TAG = "StorageHelper";
    public static final String DIR_NAME = "BlackBox";
    public static final String PREFIX = "BB_";
    private static final long MIN_RESERVE = 1_000_000_000L; // 기기에 최소 1GB 여유 유지

    private StorageHelper() {}

    // ------------------------------------------------------------ 권한

    /** 공용 저장소(내장/SD) 아무 폴더에나 쓸 수 있는지 */
    public static boolean hasAllFilesAccess(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------ 저장소 볼륨

    public static final class Volume {
        public final String label;
        public final File root;
        public final boolean removable;

        Volume(String label, File root, boolean removable) {
            this.label = label;
            this.root = root;
            this.removable = removable;
        }
    }

    /** 내장 저장소 + 장착된 SD카드 루트 목록 */
    public static List<Volume> volumes(Context ctx) {
        List<Volume> out = new ArrayList<>();
        File[] dirs = ctx.getExternalFilesDirs(null);
        int sdCount = 0;
        for (int i = 0; i < dirs.length; i++) {
            File d = dirs[i];
            if (d == null) continue;
            String path = d.getAbsolutePath();
            int idx = path.indexOf("/Android/");
            if (idx <= 0) continue;
            File root = new File(path.substring(0, idx));
            boolean removable;
            try {
                removable = Environment.isExternalStorageRemovable(d);
                if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(d))) continue;
            } catch (Exception e) {
                removable = i > 0;
            }
            String label = removable ? (++sdCount > 1 ? "SD카드 " + sdCount : "SD카드") : "내장 저장소";
            out.add(new Volume(label, root, removable));
        }
        if (out.isEmpty()) {
            out.add(new Volume("내장 저장소", Environment.getExternalStorageDirectory(), false));
        }
        return out;
    }

    /** 경로가 속한 저장소 이름 */
    public static String volumeLabel(Context ctx, File f) {
        String p = f.getAbsolutePath();
        Volume best = null;
        for (Volume v : volumes(ctx)) {
            String r = v.root.getAbsolutePath();
            if (p.startsWith(r) && (best == null || r.length() > best.root.getAbsolutePath().length())) best = v;
        }
        return best != null ? best.label : "기기";
    }

    // ------------------------------------------------------------ 저장 폴더

    /** 앱 전용 폴더 (권한 없이 항상 쓰기 가능, 앱 삭제 시 함께 삭제) */
    public static File appFolder(Context ctx) {
        File f = ctx.getExternalFilesDir(null);
        return new File(f != null ? f : ctx.getFilesDir(), DIR_NAME);
    }

    /** 기본 저장 폴더: 내장 저장소/Movies/BlackBox */
    public static File defaultFolder() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), DIR_NAME);
    }

    /** 사용자가 원하는 폴더 (설정값 또는 기본 폴더) */
    public static File desiredFolder(Context ctx) {
        String path = new Prefs(ctx).folder();
        return path.isEmpty() ? defaultFolder() : new File(path);
    }

    private static String cachedPath;
    private static boolean cachedOk;
    private static long cachedAt;

    /** 폴더를 만들고 실제로 파일을 쓸 수 있는지 확인 (10초 캐시) */
    public static synchronized boolean isWritable(File dir) {
        long now = System.currentTimeMillis();
        String p = dir.getAbsolutePath();
        if (p.equals(cachedPath) && now - cachedAt < 10_000) return cachedOk;
        boolean ok = false;
        try {
            if (!dir.exists()) dir.mkdirs();
            if (dir.isDirectory()) {
                File t = new File(dir, ".bb_write_test");
                ok = (t.exists() || t.createNewFile()) && t.delete();
            }
        } catch (Exception e) {
            ok = false;
        }
        cachedPath = p;
        cachedOk = ok;
        cachedAt = now;
        return ok;
    }

    public static synchronized void invalidateCache() {
        cachedPath = null;
    }

    /** 실제로 녹화할 폴더. 원하는 폴더에 쓸 수 없으면 앱 전용 폴더 */
    public static File recordDir(Context ctx) {
        File want = desiredFolder(ctx);
        if (isWritable(want)) return want;
        File app = appFolder(ctx);
        if (!app.exists() && !app.mkdirs()) Log.w(TAG, "mkdirs failed: " + app);
        return app;
    }

    /** 원하는 폴더 대신 앱 전용 폴더로 대체 중인지 */
    public static boolean isFallback(Context ctx) {
        return !isWritable(desiredFolder(ctx));
    }

    // ------------------------------------------------------------ 파일 목록

    /** 폴더의 녹화 파일 목록 (오래된 순) */
    public static List<File> listRecordings(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith(PREFIX) && name.endsWith(".mp4"));
        List<File> list = new ArrayList<>();
        if (files != null) Collections.addAll(list, files);
        Collections.sort(list, (a, b) -> a.getName().compareTo(b.getName()));
        return list;
    }

    /** 현재 폴더 + 이전에 쓰던 앱 전용 폴더(내장/SD)의 모든 녹화 파일, 최신 순 */
    public static List<File> listAllRecordingsNewestFirst(Context ctx) {
        List<File> dirs = new ArrayList<>();
        dirs.add(recordDir(ctx));
        dirs.add(desiredFolder(ctx));
        dirs.add(appFolder(ctx));
        for (File d : ctx.getExternalFilesDirs(null)) if (d != null) dirs.add(new File(d, DIR_NAME));
        Set<String> seenDirs = new HashSet<>();
        List<File> all = new ArrayList<>();
        for (File d : dirs) {
            String key;
            try { key = d.getCanonicalPath(); } catch (Exception e) { key = d.getAbsolutePath(); }
            if (!seenDirs.add(key)) continue;
            all.addAll(listRecordings(d));
        }
        Collections.sort(all, (a, b) -> b.getName().compareTo(a.getName()));
        return all;
    }

    public static long totalSize(List<File> files) {
        long s = 0;
        for (File f : files) s += f.length();
        return s;
    }

    public static File newSegmentFile(File dir) {
        String name = PREFIX + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        return new File(dir, name);
    }

    // ------------------------------------------------------------ 용량

    private static long reserveFor(File dir) {
        try {
            StatFs fs = new StatFs(dir.getAbsolutePath());
            return Math.min(MIN_RESERVE, fs.getTotalBytes() / 20); // 1GB 또는 전체의 5% 중 작은 값
        } catch (Exception e) {
            return MIN_RESERVE;
        }
    }

    public static long usableBytes(File dir) {
        try {
            return new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (Exception e) {
            return dir.getUsableSpace();
        }
    }

    /** 잠기지 않은 녹화분 크기 */
    private static long unlockedSize(LockStore locks, List<File> files) {
        long s = 0;
        for (File f : files) if (!locks.isLocked(f)) s += f.length();
        return s;
    }

    /** 루프 녹화에 쓸 수 있는 용량 (잠기지 않은 녹화분 + 여유 공간 - 예약분, 최대 용량 설정 반영) */
    public static long capacityBytes(Context ctx) {
        File dir = recordDir(ctx);
        List<File> files = listRecordings(dir);
        LockStore locks = new LockStore(ctx);
        long unlocked = unlockedSize(locks, files);
        long cap = unlocked + usableBytes(dir) - reserveFor(dir);
        long max = new Prefs(ctx).maxStorageBytes();
        if (max > 0) cap = Math.min(cap, Math.max(0, max - (totalSize(files) - unlocked)));
        return Math.max(0, cap);
    }

    /**
     * 다음 세그먼트를 쓸 공간을 확보한다. 잠기지 않은 가장 오래된 파일부터 삭제.
     * @return 공간 확보 성공 여부
     */
    public static boolean ensureSpace(Context ctx, File dir, long nextSegmentBytes, @Nullable File keep) {
        Prefs p = new Prefs(ctx);
        LockStore locks = new LockStore(ctx);
        long max = p.maxStorageBytes();
        long reserve = reserveFor(dir);
        List<File> files = listRecordings(dir);
        long used = totalSize(files);
        for (File oldest : files) {
            boolean overQuota = max > 0 && used + nextSegmentBytes > max;
            boolean lowFree = usableBytes(dir) < reserve + nextSegmentBytes;
            if (!overQuota && !lowFree) break;
            if (oldest.equals(keep) || locks.isLocked(oldest)) continue;
            long len = oldest.length();
            if (deleteRecording(ctx, oldest)) {
                used -= len;
                Log.i(TAG, "deleted old recording: " + oldest.getName());
            }
        }
        return usableBytes(dir) >= nextSegmentBytes;
    }

    // ------------------------------------------------------------ 삭제 / 미디어 스캔

    public static boolean deleteRecording(Context ctx, File f) {
        boolean ok = f.delete();
        if (ok) {
            new LockStore(ctx).forget(f);
            scan(ctx, f);
        }
        return ok;
    }

    /** 갤러리/파일 앱에 바로 보이도록 미디어 스캔 (삭제된 파일은 목록에서 제거됨) */
    public static void scan(Context ctx, File f) {
        try {
            MediaScannerConnection.scanFile(ctx.getApplicationContext(),
                    new String[]{f.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ 표시

    public static String formatBytes(long b) {
        if (b >= 1_000_000_000L) return String.format(Locale.US, "%.1fGB", b / 1e9);
        if (b >= 1_000_000L) return String.format(Locale.US, "%.1fMB", b / 1e6);
        return String.format(Locale.US, "%.0fKB", b / 1e3);
    }

    public static String formatHours(double hours) {
        if (hours >= 24) return String.format(Locale.KOREA, "약 %.1f일 (%.0f시간)", hours / 24, hours);
        return String.format(Locale.KOREA, "약 %.1f시간", hours);
    }
}
