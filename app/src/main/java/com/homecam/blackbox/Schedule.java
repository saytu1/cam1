package com.homecam.blackbox;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 요일 + 시작~종료 시각 예약 녹화.
 * 종료 시각이 시작 시각보다 이르면 자정을 넘어 다음 날 종료 시각까지 녹화 (시작 요일 기준).
 * 시작 = 종료 이면 24시간.
 */
public final class Schedule {
    private static final String TAG = "Schedule";
    /** 요일 비트: 월=0 … 일=6 */
    public static final String[] DAY_NAMES = {"월", "화", "수", "목", "금", "토", "일"};

    public int days = 0b0011111; // 기본 월~금
    public int startMin = 9 * 60;
    public int endMin = 18 * 60;
    public boolean enabled = true;

    public Schedule() {}

    public Schedule copy() {
        Schedule s = new Schedule();
        s.days = days;
        s.startMin = startMin;
        s.endMin = endMin;
        s.enabled = enabled;
        return s;
    }

    public boolean hasDay(int idx) { return (days & (1 << idx)) != 0; }

    public int durationMin() {
        int d = endMin - startMin;
        return d <= 0 ? d + 24 * 60 : d;
    }

    public String daysText() {
        if (days == 0b1111111) return "매일";
        if (days == 0b0011111) return "평일";
        if (days == 0b1100000) return "주말";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) if (hasDay(i)) sb.append(sb.length() > 0 ? "," : "").append(DAY_NAMES[i]);
        return sb.length() == 0 ? "요일 없음" : sb.toString();
    }

    public String timeText() {
        String t = hm(startMin) + " ~ " + hm(endMin);
        if (endMin <= startMin) t += " (다음날)";
        return t;
    }

    public static String hm(int min) {
        return String.format(Locale.US, "%02d:%02d", min / 60, min % 60);
    }

    /** Calendar.DAY_OF_WEEK → 월=0 … 일=6 */
    static int dayIndex(Calendar c) {
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    /** 기준일 -1 ~ +8 일에 대해 이 예약의 녹화 구간 [시작, 종료) 목록 (epoch ms) */
    private List<long[]> windowsAround(long now) {
        List<long[]> out = new ArrayList<>();
        if (!enabled || days == 0) return out;
        for (int off = -1; off <= 8; off++) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            c.add(Calendar.DAY_OF_MONTH, off);
            if (!hasDay(dayIndex(c))) continue;
            c.set(Calendar.HOUR_OF_DAY, startMin / 60);
            c.set(Calendar.MINUTE, startMin % 60);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long start = c.getTimeInMillis();
            c.add(Calendar.MINUTE, durationMin());
            out.add(new long[]{start, c.getTimeInMillis()});
        }
        return out;
    }

    // ------------------------------------------------------------ 목록 단위 계산

    public static boolean isActive(List<Schedule> list, long now) {
        for (Schedule s : list) {
            for (long[] w : s.windowsAround(now)) {
                if (now >= w[0] && now < w[1]) return true;
            }
        }
        return false;
    }

    /** now 이후 가장 가까운 시작 또는 종료 시각. 없으면 -1 */
    public static long nextTransition(List<Schedule> list, long now) {
        long best = Long.MAX_VALUE;
        for (Schedule s : list) {
            for (long[] w : s.windowsAround(now)) {
                if (w[0] > now) best = Math.min(best, w[0]);
                if (w[1] > now) best = Math.min(best, w[1]);
            }
        }
        return best == Long.MAX_VALUE ? -1 : best;
    }

    /** now 이후 가장 가까운 녹화 시작 시각. 없으면 -1 */
    public static long nextStart(List<Schedule> list, long now) {
        long best = Long.MAX_VALUE;
        for (Schedule s : list) {
            for (long[] w : s.windowsAround(now)) {
                if (w[0] > now) best = Math.min(best, w[0]);
            }
        }
        return best == Long.MAX_VALUE ? -1 : best;
    }

    /** 현재 녹화 중인 구간의 종료 시각 (겹치는 구간은 이어서 계산). 없으면 -1 */
    public static long currentEnd(List<Schedule> list, long now) {
        if (!isActive(list, now)) return -1;
        long t = now;
        for (int guard = 0; guard < 50; guard++) {
            long n = nextTransition(list, t);
            if (n < 0) return -1;
            if (!isActive(list, n)) return n;
            t = n;
        }
        return -1;
    }

    public static String formatWhen(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return String.format(Locale.KOREA, "%d/%d(%s) %02d:%02d",
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), DAY_NAMES[dayIndex(c)],
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    // ------------------------------------------------------------ 저장

    public static List<Schedule> parse(@Nullable String json) {
        List<Schedule> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Schedule s = new Schedule();
                s.days = o.optInt("days", s.days);
                s.startMin = o.optInt("start", s.startMin);
                s.endMin = o.optInt("end", s.endMin);
                s.enabled = o.optBoolean("enabled", true);
                list.add(s);
            }
        } catch (Exception e) {
            Log.w(TAG, "parse failed", e);
        }
        return list;
    }

    public static String toJson(List<Schedule> list) {
        JSONArray a = new JSONArray();
        try {
            for (Schedule s : list) {
                JSONObject o = new JSONObject();
                o.put("days", s.days);
                o.put("start", s.startMin);
                o.put("end", s.endMin);
                o.put("enabled", s.enabled);
                a.put(o);
            }
        } catch (Exception e) {
            Log.w(TAG, "toJson failed", e);
        }
        return a.toString();
    }

    // ------------------------------------------------------------ 알람

    /** 다음 시작/종료 시각에 서비스를 깨우는 알람 등록 (예약 꺼짐이면 해제) */
    public static void armAlarm(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = alarmIntent(ctx);
        am.cancel(pi);
        Prefs p = new Prefs(ctx);
        if (!p.scheduleEnabled() || !p.wantRecording()) return;
        long next = nextTransition(p.schedules(), System.currentTimeMillis());
        if (next < 0) return;
        next += 1_000; // 경계 직후에 판정되도록 1초 여유
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, next, pi);
            }
            Log.i(TAG, "alarm armed for " + formatWhen(next));
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, next, pi);
        }
    }

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ctx, ScheduleReceiver.class).setAction(ScheduleReceiver.ACTION_ALARM);
        return PendingIntent.getBroadcast(ctx, 100, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
