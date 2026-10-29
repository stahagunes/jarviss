package com.jarvis.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.Calendar;

/** Daily morning briefing at the time the user picked. */
public class BriefReceiver extends BroadcastReceiver {

    static void schedule(Context c, boolean on, int hour, int minute) {
        SharedPreferences p = c.getSharedPreferences(WakeService.PREFS, Context.MODE_PRIVATE);
        p.edit().putBoolean("brief", on).putInt("briefH", hour).putInt("briefM", minute).apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 40, new Intent(c, BriefReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        if (!on) return;
        Calendar t = Calendar.getInstance();
        t.set(Calendar.HOUR_OF_DAY, hour);
        t.set(Calendar.MINUTE, minute);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        if (t.getTimeInMillis() <= System.currentTimeMillis() + 1000) t.add(Calendar.DAY_OF_MONTH, 1);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        SharedPreferences p = c.getSharedPreferences(WakeService.PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("brief", false)) return;
        schedule(c, true, p.getInt("briefH", 8), p.getInt("briefM", 0));   // tomorrow
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        Intent open = new Intent(c, MainActivity.class).setAction("com.jarvis.app.BRIEF")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("launch", "brief");
        boolean started = false;
        if (Settings.canDrawOverlays(c)) {
            try { c.startActivity(open); started = true; } catch (Exception ignored) { }
        }
        if (!started) {
            PendingIntent pi = PendingIntent.getActivity(c, 41, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, MainActivity.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("ZEUS · Günün brifingi hazır")
                    .setContentText("Dinlemek için dokun")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            try { nm.notify(41, n); } catch (SecurityException ignored) { }
        }
    }
}
