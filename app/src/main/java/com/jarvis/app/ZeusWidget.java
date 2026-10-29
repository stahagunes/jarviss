package com.jarvis.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Home-screen bar: "ZEUS'a sor". Tapping it opens ZEUS already listening. */
public class ZeusWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_zeus);
            Intent listen = new Intent(c, MainActivity.class).setAction("com.jarvis.app.LISTEN")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("launch", "listen");
            Intent open = new Intent(c, MainActivity.class).setAction("com.jarvis.app.OPEN")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent brief = new Intent(c, MainActivity.class).setAction("com.jarvis.app.BRIEF")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("launch", "brief");
            int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 20, listen, f));
            v.setOnClickPendingIntent(R.id.w_logo, PendingIntent.getActivity(c, 21, open, f));
            v.setOnClickPendingIntent(R.id.w_brief, PendingIntent.getActivity(c, 22, brief, f));
            m.updateAppWidget(id, v);
        }
    }
}
