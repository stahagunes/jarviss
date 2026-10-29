package com.jarvis.app;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedList;

/** Keeps the most recent notifications so ZEUS can read them aloud. */
public class NotifListener extends NotificationListenerService {

    private static final LinkedList<String[]> recent = new LinkedList<>(); // app, title, text, time

    @Override
    public void onListenerConnected() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification s : active) add(s);
        } catch (Exception ignored) { }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) { add(sbn); }

    private void add(StatusBarNotification s) {
        if (s == null || getPackageName().equals(s.getPackageName())) return;
        Notification n = s.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle e = n.extras;
        String title = str(e.getCharSequence(Notification.EXTRA_TITLE));
        CharSequence big = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
        String text = str(big != null ? big : e.getCharSequence(Notification.EXTRA_TEXT));
        if (title.isEmpty() && text.isEmpty()) return;
        String app;
        try {
            app = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(s.getPackageName(), 0)).toString();
        } catch (Exception ex) {
            app = s.getPackageName();
        }
        synchronized (recent) {
            Iterator<String[]> it = recent.iterator();
            while (it.hasNext()) {
                String[] r = it.next();
                if (r[0].equals(app) && r[1].equals(title) && r[2].equals(text)) it.remove();
            }
            recent.addFirst(new String[]{app, title, text, String.valueOf(s.getPostTime())});
            while (recent.size() > 40) recent.removeLast();
        }
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString().trim(); }

    static String dump(int limit) {
        JSONArray arr = new JSONArray();
        synchronized (recent) {
            for (String[] r : recent) {
                if (arr.length() >= limit) break;
                try {
                    JSONObject o = new JSONObject();
                    o.put("uygulama", r[0]);
                    o.put("baslik", r[1]);
                    o.put("metin", r[2].length() > 300 ? r[2].substring(0, 300) : r[2]);
                    o.put("zaman", Long.parseLong(r[3]));
                    arr.put(o);
                } catch (Exception ignored) { }
            }
        }
        return arr.toString();
    }
}
