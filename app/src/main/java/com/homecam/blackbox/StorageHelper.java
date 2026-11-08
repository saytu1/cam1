package com.homecam.blackbox;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 저장 위치(내장 / SD카드) 결정과 루프 녹화용 오래된 파일 자동 삭제.
 * 권한 없이 쓸 수 있는 앱 전용 폴더(Android/data/패키지명/files/BlackBox)를 사용한다.
 */
public final class StorageHelper {
    private static final String TAG = "StorageHelper";
    public static final String DIR_NAME = "BlackBox";
    public static final String PREFIX = "BB_";
    private static final long MIN_RESERVE = 1_000_000_000L; // 기기에 최소 1GB 여유 유지

    private StorageHelper() {}

    /** 내장 저장소의 앱 전용 폴더 */
    public static File internalRoot(Context ctx) {
        File f = ctx.getExternalFilesDir(null);
        return f != null ? f : ctx.getFilesDir();
    }

    /** 장착된 SD카드의 앱 전용 폴더. 없으면 null */
    public static File sdRoot(Context ctx) {
        File[] dirs = ctx.getExternalFilesDirs(null);
        for (int i = 1; i < dirs.length; i++) {
            File d = dirs[i];
            if (d == null) continue;
            try {
                if (Environment.isExternalStorageRemovable(d)
                        && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(d))) {
                    return d;
                }
            } catch (Exception e) {
                Log.w(TAG, "sd check failed", e);
            }
        }
        return null;
    }

    /** 설정에 따른 녹화 폴더. SD카드 선택 시 미장착이면 내장으로 대체 */
    public static File recordDir(Context ctx) {
        Prefs p = new Prefs(ctx);
        File root = null;
        if ("sd".equals(p.storageLocation())) root = sdRoot(ctx);
        if (root == null) root = internalRoot(ctx);
        File dir = new File(root, DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "mkdirs failed: " + dir);
        return dir;
    }

    public static boolean isUsingSd(Context ctx) {
        return "sd".equals(new Prefs(ctx).storageLocation()) && sdRoot(ctx) != null;
    }

    /** 녹화 파일 목록 (오래된 순) */
    public static List<File> listRecordings(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith(PREFIX) && name.endsWith(".mp4"));
        if (files == null) return new ArrayList<>();
        List<File> list = new ArrayList<>(Arrays.asList(files));
        Collections.sort(list, (a, b) -> a.getName().compareTo(b.getName()));
        return list;
    }

    /** 전체 녹화 폴더 목록 (내장 + SD) 의 파일, 최신 순 */
    public static List<File> listAllRecordingsNewestFirst(Context ctx) {
        List<File> all = new ArrayList<>(listRecordings(new File(internalRoot(ctx), DIR_NAME)));
        File sd = sdRoot(ctx);
        if (sd != null) all.addAll(listRecordings(new File(sd, DIR_NAME)));
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

    private static long reserveFor(File dir) {
        try {
            StatFs fs = new StatFs(dir.getAbsolutePath());
            long total = fs.getTotalBytes();
            return Math.min(MIN_RESERVE, total / 20); // 1GB 또는 전체의 5% 중 작은 값
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

    /** 녹화에 쓸 수 있는 전체 용량 (기존 녹화분 + 여유 공간 - 예약분, 최대 용량 설정 반영) */
    public static long capacityBytes(Context ctx) {
        File dir = recordDir(ctx);
        long used = totalSize(listRecordings(dir));
        long cap = used + usableBytes(dir) - reserveFor(dir);
        long max = new Prefs(ctx).maxStorageBytes();
        if (max > 0) cap = Math.min(cap, max);
        return Math.max(0, cap);
    }

    /**
     * 다음 세그먼트를 쓸 공간을 확보한다. 가장 오래된 파일부터 삭제.
     * @param keep 삭제하면 안 되는 파일(현재 녹화 중) - null 가능
     * @return 공간 확보 성공 여부
     */
    public static boolean ensureSpace(Context ctx, File dir, long nextSegmentBytes, File keep) {
        Prefs p = new Prefs(ctx);
        long max = p.maxStorageBytes();
        long reserve = reserveFor(dir);
        List<File> files = listRecordings(dir);
        long used = totalSize(files);
        int idx = 0;
        while (idx < files.size()) {
            boolean overQuota = max > 0 && used + nextSegmentBytes > max;
            boolean lowFree = usableBytes(dir) < reserve + nextSegmentBytes;
            if (!overQuota && !lowFree) break;
            File oldest = files.get(idx++);
            if (oldest.equals(keep)) continue;
            long len = oldest.length();
            if (oldest.delete()) {
                used -= len;
                Log.i(TAG, "deleted old recording: " + oldest.getName());
            }
        }
        return usableBytes(dir) >= nextSegmentBytes;
    }

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
