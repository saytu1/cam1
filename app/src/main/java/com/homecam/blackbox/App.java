package com.homecam.blackbox;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import androidx.preference.PreferenceManager;

import com.serenegiant.utils.UVCUtils;

public class App extends Application {
    public static final String CHANNEL_ID = "recording";

    @Override
    public void onCreate() {
        super.onCreate();
        UVCUtils.init(this);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}
