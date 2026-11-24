/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

/**
 * Keeps the connection to Telegram open in the background, so messages arrive and are
 * notified without push. Glassgram cannot get Firebase pushes: Telegram's servers send them
 * only to the official app. Since Android 8 a background service lives only as a foreground
 * service, which needs an ongoing notification; it goes to its own silent channel that the
 * user can hide in the system settings without stopping the service.
 */
public class NotificationsService extends Service {

    public static final String CHANNEL_ID = "glassgram_background_connection";
    private static final int NOTIFICATION_ID = 0x6c617373;

    @Override
    public void onCreate() {
        super.onCreate();
        // In the foreground first: started with startForegroundService(), the service has 10 seconds
        // (5 before Android 12) to get there or the app is closed, and the app's first start here
        // (accounts, native libraries, the language) can take longer than that on a slow phone
        startInForeground();
        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        return START_STICKY;
    }

    /** A text of the app's language, or of the phone's while the app's isn't loaded or fails. */
    private String text(int resId) {
        try {
            return LocaleController.getString(resId);
        } catch (Throwable e) {
            return getString(resId);
        }
    }

    private void startInForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            startForeground(buildNotification());
        } catch (Throwable e) {
            FileLog.e(e);
            // Whatever failed, a plain notification: each startForegroundService() needs its
            // startForeground(), or the system closes the app
            try {
                startForeground(new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setOngoing(true)
                    .build());
            } catch (Throwable e2) {
                FileLog.e(e2);
            }
        }
    }

    private void startForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // specialUse: dataSync is limited to 6 hours a day since Android 15
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        final NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                text(R.string.GlassgramBackgroundChannel), NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            manager.createNotificationChannel(channel);
        }
        // Tapped, it opens its channel's system settings: Android raises a foreground service's
        // notification to be seen however the app makes its channel, and only the user can turn
        // the channel off. The service keeps running then, and messages keep arriving.
        final Intent hide = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent contentIntent = PendingIntent.getActivity(this, 0, hide, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification)
            .setContentTitle(text(R.string.GlassgramBackgroundTitle))
            .setContentText(text(R.string.GlassgramBackgroundText))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
