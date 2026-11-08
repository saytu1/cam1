package com.homecam.blackbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs p = new Prefs(context);
        Log.i("BootReceiver", "onReceive " + intent.getAction() + " autoBoot=" + p.autoStartBoot()
                + " want=" + p.wantRecording());
        if (p.autoStartBoot() && p.wantRecording()) {
            RecorderService.start(context);
        }
    }
}
