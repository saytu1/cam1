package com.homecam.blackbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

/** 예약 시각(시작/종료)에 서비스를 깨워 녹화 상태를 다시 판정하게 함 */
public class ScheduleReceiver extends BroadcastReceiver {
    public static final String ACTION_ALARM = "com.homecam.blackbox.SCHEDULE_ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs p = new Prefs(context);
        Log.i("ScheduleReceiver", "alarm fired, want=" + p.wantRecording());
        if (!p.wantRecording()) return;
        try {
            Intent i = new Intent(context, RecorderService.class).setAction(RecorderService.ACTION_SCHEDULE_CHECK);
            ContextCompat.startForegroundService(context, i);
        } catch (Exception e) {
            Log.e("ScheduleReceiver", "service start failed", e);
        }
        Schedule.armAlarm(context);
    }
}
