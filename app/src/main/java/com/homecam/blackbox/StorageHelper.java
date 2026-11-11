package com.homecam.blackbox;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.DocumentsContract;
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
        addStorageManagerVolumes(ctx, out);
        addMountedStorageDirs(out);
        return out;
    }

    private static boolean containsRoot(List<Volume> out, File root) {
        String p = root.getAbsolutePath();
        for (Volume v : out) if (v.root.getAbsolutePath().equals(p)) return true;
        return false;
    }

    /** OTG 로 연결한 USB 메모리·카드리더 등 시스템이 알려주는 모든 저장장치 */
    private static void addStorageManagerVolumes(Context ctx, List<Volume> out) {
        try {
            android.os.storage.StorageManager sm =
                    (android.os.storage.StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            if (sm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
            for (android.os.storage.StorageVolume sv : sm.getStorageVolumes()) {
                if (!Environment.MEDIA_MOUNTED.equals(sv.getState())) continue;
                File dir = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    dir = sv.getDirectory();
                } else {
                    try {
                        dir = (File) sv.getClass().getMethod("getPathFile").invoke(sv);
                    } catch (Exception e) {
                        try {
                            dir = new File((String) sv.getClass().getMethod("getPath").invoke(sv));
                        } catch (Exception ignored) { }
                    }
                }
                if (dir == null || !dir.isDirectory() || containsRoot(out, dir)) continue;
                String desc = sv.getDescription(ctx);
                String label = (desc != null && !desc.isEmpty()) ? desc : "외부 저장장치";
                if (!label.toUpperCase(Locale.ROOT).contains("USB") && sv.isRemovable()) label = "USB/외부 " + label;
                out.add(new Volume(label, dir, sv.isRemovable()));
            }
        } catch (Exception e) {
            Log.w(TAG, "storage volumes", e);
        }
    }

    /** 일부 기기에서 위 목록에 빠지는 장치 대비: /storage 아래 마운트된 폴더 직접 확인 */
    private static void addMountedStorageDirs(List<Volume> out) {
        File[] dirs = new File("/storage").listFiles();
        if (dirs == null) return;
        for (File d : dirs) {
            String n = d.getName();
            if (n.equals("emulated") || n.equals("self") || n.startsWith(".")) continue;
            if (!d.isDirectory() || !d.canRead() || containsRoot(out, d)) continue;
            out.add(new Volume("외부 저장장치 (" + n + ")", d, true));
        }
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
        cachedStore = null;
    }

    // ------------------------------------------------------------ 저장소 선택

    /** 사용자가 원하는 저장소 (시스템 선택기로 고른 USB 폴더 또는 일반 폴더) */
    public static RecStore desiredStore(Context ctx) {
        Prefs p = new Prefs(ctx);
        String tree = p.treeUri();
        if (!tree.isEmpty()) {
            try {
                return new RecStore.SafStore(ctx, Uri.parse(tree));
            } catch (Exception e) {
                Log.w(TAG, "bad tree uri", e);
            }
        }
        return new RecStore.FileStore(desiredFolder(ctx));
    }

    private static RecStore cachedStore;
    private static long cachedStoreAt;

    /** 실제로 녹화할 저장소. 원하는 곳을 쓸 수 없으면(장치 분리·권한 없음) 앱 전용 폴더 (10초 캐시) */
    public static synchronized RecStore currentStore(Context ctx) {
        long now = System.currentTimeMillis();
        if (cachedStore != null && now - cachedStoreAt < 10_000) return cachedStore;
        RecStore want = desiredStore(ctx);
        boolean ok;
        if (want instanceof RecStore.SafStore) {
            ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ((RecStore.SafStore) want).isAccessible();
        } else {
            ok = isWritable(((RecStore.FileStore) want).dir);
        }
        RecStore store = ok ? want : new RecStore.FileStore(appFolder(ctx));
        if (!ok) {
            File app = appFolder(ctx);
            if (!app.exists() && !app.mkdirs()) Log.w(TAG, "mkdirs failed: " + app);
        }
        cachedStore = store;
        cachedStoreAt = now;
        return store;
    }

    public static synchronized void invalidateStore() {
        cachedStore = null;
        cachedPath = null;
    }

    /** 원하는 저장소 대신 앱 전용 폴더로 대체 중인지 */
    public static boolean isFallback(Context ctx) {
        return !currentStore(ctx).id().equals(desiredStore(ctx).id());
    }

    // ------------------------------------------------------------ 파일 목록

    /** 현재 저장소 + 원하는 저장소 + 이전에 쓰던 앱 전용 폴더의 모든 녹화 파일, 최신 순 */
    public static List<RecStore.Rec> listAllNewestFirst(Context ctx) {
        List<RecStore> stores = new ArrayList<>();
        stores.add(currentStore(ctx));
        RecStore want = desiredStore(ctx);
        if (!(want instanceof RecStore.SafStore) || ((RecStore.SafStore) want).isAccessible()) stores.add(want);
        stores.add(new RecStore.FileStore(appFolder(ctx)));
        for (File d : ctx.getExternalFilesDirs(null)) if (d != null) stores.add(new RecStore.FileStore(new File(d, DIR_NAME)));
        Set<String> seenStores = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        List<RecStore.Rec> all = new ArrayList<>();
        for (RecStore st : stores) {
            String id = st.id();
            if (st instanceof RecStore.FileStore) {
                try { id = "file:" + ((RecStore.FileStore) st).dir.getCanonicalPath(); } catch (Exception ignored) { }
            }
            if (!seenStores.add(id)) continue;
            for (RecStore.Rec r : st.list()) if (seenKeys.add(r.key())) all.add(r);
        }
        Collections.sort(all, (a, b) -> b.name.compareTo(a.name));
        return all;
    }

    public static long totalSize(List<RecStore.Rec> recs) {
        long s = 0;
        for (RecStore.Rec r : recs) s += r.size;
        return s;
    }

    public static String newSegmentName() {
        return PREFIX + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
    }

    // ------------------------------------------------------------ 용량

    private static long reserveFor(RecStore store) {
        long total = store.totalBytes();
        return total > 0 ? Math.min(MIN_RESERVE, total / 20) : MIN_RESERVE; // 1GB 또는 전체의 5% 중 작은 값
    }

    public static long usableBytes(File dir) {
        try {
            return new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (Exception e) {
            return dir.getUsableSpace();
        }
    }

    /** 루프 녹화에 쓸 수 있는 용량 (잠기지 않은 녹화분 + 여유 공간 - 예약분, 최대 용량 설정 반영) */
    public static long capacityBytes(Context ctx) {
        RecStore store = currentStore(ctx);
        List<RecStore.Rec> recs = store.list();
        return capacityBytes(ctx, store, recs);
    }

    public static long capacityBytes(Context ctx, RecStore store, List<RecStore.Rec> recs) {
        LockStore locks = new LockStore(ctx);
        long unlocked = 0;
        for (RecStore.Rec r : recs) if (!locks.isLocked(r)) unlocked += r.size;
        long usable = store.usableBytes();
        long cap = usable >= 0 ? unlocked + usable - reserveFor(store) : Long.MAX_VALUE / 4;
        long max = new Prefs(ctx).maxStorageBytes();
        if (max > 0) cap = Math.min(cap, Math.max(0, max - (totalSize(recs) - unlocked)));
        return Math.max(0, cap);
    }

    /**
     * 다음 세그먼트를 쓸 공간을 확보한다. 잠기지 않은 가장 오래된 파일부터 삭제.
     * @return 공간 확보 성공 여부
     */
    public static boolean ensureSpace(Context ctx, RecStore store, long nextSegmentBytes) {
        Prefs p = new Prefs(ctx);
        LockStore locks = new LockStore(ctx);
        long max = p.maxStorageBytes();
        long reserve = reserveFor(store);
        List<RecStore.Rec> recs = store.list();
        long used = totalSize(recs);
        for (RecStore.Rec oldest : recs) {
            long usable = store.usableBytes();
            boolean overQuota = max > 0 && used + nextSegmentBytes > max;
            boolean lowFree = usable >= 0 && usable < reserve + nextSegmentBytes;
            if (!overQuota && !lowFree) break;
            if (locks.isLocked(oldest)) continue;
            if (deleteRecording(ctx, oldest)) {
                used -= oldest.size;
                Log.i(TAG, "deleted old recording: " + oldest.name);
            }
        }
        long usable = store.usableBytes();
        return usable < 0 || usable >= nextSegmentBytes;
    }

    // ------------------------------------------------------------ 삭제 / 미디어 스캔

    public static boolean deleteRecording(Context ctx, RecStore.Rec r) {
        boolean ok;
        if (r.file != null) {
            ok = r.file.delete();
            if (ok) scan(ctx, r.file);
        } else if (r.uri != null) {
            try {
                ok = DocumentsContract.deleteDocument(ctx.getContentResolver(), r.uri);
            } catch (Exception e) {
                ok = false;
            }
        } else {
            ok = false;
        }
        if (ok) new LockStore(ctx).forget(r.key());
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
