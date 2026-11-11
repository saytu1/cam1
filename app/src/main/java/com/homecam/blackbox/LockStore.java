package com.homecam.blackbox;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 삭제 잠금 관리. 파일 단위(절대 경로) 잠금과 날짜 단위(yyyyMMdd) 잠금.
 * 잠긴 파일은 전체 삭제와 저장 공간 부족 시 자동 삭제에서 모두 제외된다.
 */
public final class LockStore {
    private static final String PREF = "locks";
    private static final String KEY_FILES = "files";
    private static final String KEY_DATES = "dates";

    private final SharedPreferences sp;

    public LockStore(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private Set<String> get(String key) {
        return new HashSet<>(sp.getStringSet(key, new HashSet<>()));
    }

    private void put(String key, Set<String> v) {
        sp.edit().putStringSet(key, v).apply();
    }

    /** BB_20261108_153000.mp4 → 20261108 (형식이 다르면 null) */
    public static String dateOf(String n) {
        if (n != null && n.startsWith(StorageHelper.PREFIX) && n.length() >= StorageHelper.PREFIX.length() + 8) {
            String d = n.substring(StorageHelper.PREFIX.length(), StorageHelper.PREFIX.length() + 8);
            for (int i = 0; i < 8; i++) if (!Character.isDigit(d.charAt(i))) return null;
            return d;
        }
        return null;
    }

    public boolean isFileLocked(RecStore.Rec r) { return get(KEY_FILES).contains(r.key()); }

    public boolean isDateLocked(String date) { return date != null && get(KEY_DATES).contains(date); }

    /** 파일 잠금 또는 날짜 잠금 중 하나라도 걸려 있으면 true */
    public boolean isLocked(RecStore.Rec r) {
        return isFileLocked(r) || isDateLocked(dateOf(r.name));
    }

    public void setFileLocked(RecStore.Rec r, boolean locked) {
        Set<String> s = get(KEY_FILES);
        if (locked) s.add(r.key()); else s.remove(r.key());
        put(KEY_FILES, s);
    }

    public void setDateLocked(String date, boolean locked) {
        Set<String> s = get(KEY_DATES);
        if (locked) s.add(date); else s.remove(date);
        put(KEY_DATES, s);
    }

    public Set<String> lockedDates() { return get(KEY_DATES); }

    /** 삭제된 파일의 잠금 기록 정리 */
    public void forget(String key) {
        Set<String> s = get(KEY_FILES);
        if (s.remove(key)) put(KEY_FILES, s);
    }
}
