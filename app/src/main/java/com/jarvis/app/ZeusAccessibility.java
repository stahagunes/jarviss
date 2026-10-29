package com.jarvis.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/** Lets ZEUS read the screen and act on it (tap, type, scroll, global actions). */
public class ZeusAccessibility extends AccessibilityService {

    static volatile ZeusAccessibility instance;
    private final List<AccessibilityNodeInfo> lastNodes = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    static boolean on() { return instance != null; }

    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(Intent intent) { instance = null; return super.onUnbind(intent); }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }

    private AccessibilityNodeInfo root() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r != null) return r;
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.isActive() && w.getRoot() != null) return w.getRoot();
            }
        } catch (Exception ignored) { }
        return null;
    }

    static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        String s = t != null ? t.toString() : "";
        if (s.trim().isEmpty() && n.getContentDescription() != null) s = n.getContentDescription().toString();
        if (s.trim().isEmpty() && n.isEditable() && n.getHintText() != null) s = n.getHintText().toString();
        return s.trim().replace('\n', ' ');
    }

    private static String shorten(String s) { return s.length() > 80 ? s.substring(0, 80) + "…" : s; }

    synchronized String read() {
        AccessibilityNodeInfo r = root();
        if (r == null) return "Ekran okunamadı (aktif pencere yok).";
        lastNodes.clear();
        StringBuilder sb = new StringBuilder();
        sb.append("Ön plandaki uygulama: ").append(r.getPackageName()).append('\n');
        walk(r, sb, 0);
        if (lastNodes.isEmpty()) sb.append("(ekranda okunabilir öğe yok)");
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 45 || lastNodes.size() >= 160) return;
        if (!n.isVisibleToUser()) return;
        String label = label(n);
        boolean edit = n.isEditable();
        boolean click = n.isClickable() || n.isLongClickable();
        boolean scroll = n.isScrollable();
        if (!label.isEmpty() || edit || scroll) {
            lastNodes.add(n);
            sb.append('[').append(lastNodes.size()).append("] ");
            if (edit) sb.append("(yazı alanı) ");
            else if (n.isCheckable()) sb.append(n.isChecked() ? "(anahtar: açık) " : "(anahtar: kapalı) ");
            else if (click) sb.append("(buton) ");
            if (scroll) sb.append("(kaydırılabilir liste) ");
            if (!label.isEmpty()) sb.append('"').append(shorten(label)).append('"');
            sb.append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), sb, depth + 1);
    }

    private AccessibilityNodeInfo find(String target) {
        String t = target == null ? "" : target.trim();
        if (t.matches("\\d+")) {
            int i = Integer.parseInt(t);
            synchronized (this) {
                if (i >= 1 && i <= lastNodes.size()) return lastNodes.get(i - 1);
            }
            return null;
        }
        read();
        String nt = MainActivity.norm(t);
        if (nt.isEmpty()) return null;
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        synchronized (this) {
            for (AccessibilityNodeInfo n : lastNodes) {
                String l = MainActivity.norm(label(n));
                if (l.isEmpty()) continue;
                int s = 0;
                if (l.equals(nt)) s = 4;
                else if (l.startsWith(nt)) s = 3;
                else if (l.contains(nt)) s = 2;
                else if (nt.contains(l) && l.length() > 2) s = 1;
                if (s > bestScore || (s == bestScore && s > 0 && n.isClickable() && (best == null || !best.isClickable()))) {
                    bestScore = s; best = n;
                }
            }
        }
        return best;
    }

    String click(String target, boolean longPress) {
        AccessibilityNodeInfo n = find(target);
        if (n == null) return "\"" + target + "\" ekranda bulunamadı. Önce ekranı oku.";
        String l = label(n);
        AccessibilityNodeInfo c = n;
        int action = longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
        while (c != null && !(longPress ? c.isLongClickable() : c.isClickable())) c = c.getParent();
        if (c != null && c.performAction(action)) return (longPress ? "Uzun basıldı: " : "Dokunuldu: ") + l;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        gesture(b.centerX(), b.centerY(), b.centerX(), b.centerY(), longPress ? 700 : 60);
        return "Dokunuldu: " + l;
    }

    String type(String text, String target) {
        AccessibilityNodeInfo n = null;
        if (target != null && !target.isEmpty()) n = find(target);
        if (n == null || !n.isEditable()) {
            AccessibilityNodeInfo r = root();
            if (r != null) n = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        if (n == null || !n.isEditable()) {
            read();
            synchronized (this) {
                for (AccessibilityNodeInfo x : lastNodes) if (x.isEditable()) { n = x; break; }
            }
        }
        if (n == null) return "Ekranda yazı alanı bulunamadı";
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle a = new Bundle();
        a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a) ? "Yazıldı: " + text : "Yazı yazılamadı";
    }

    String scroll(String dir) {
        boolean fwd = !"up".equals(dir) && !"left".equals(dir);
        read();
        AccessibilityNodeInfo best = null;
        int area = 0;
        Rect b = new Rect();
        synchronized (this) {
            for (AccessibilityNodeInfo n : lastNodes) {
                if (!n.isScrollable()) continue;
                n.getBoundsInScreen(b);
                int a = b.width() * b.height();
                if (a > area) { area = a; best = n; }
            }
        }
        if (best != null && best.performAction(fwd ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            return "Kaydırıldı";
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        switch (dir) {
            case "up": gesture(w / 2, h * 3 / 10, w / 2, h * 7 / 10, 350); break;
            case "left": gesture(w * 2 / 10, h / 2, w * 8 / 10, h / 2, 300); break;
            case "right": gesture(w * 8 / 10, h / 2, w * 2 / 10, h / 2, 300); break;
            default: gesture(w / 2, h * 7 / 10, w / 2, h * 3 / 10, 350);
        }
        return "Kaydırıldı";
    }

    private void gesture(int x1, int y1, int x2, int y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build();
        dispatchGesture(g, null, null);
    }

    String global(String name) {
        int id;
        switch (name) {
            case "back": id = GLOBAL_ACTION_BACK; break;
            case "home": id = GLOBAL_ACTION_HOME; break;
            case "recents": id = GLOBAL_ACTION_RECENTS; break;
            case "notifications": id = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": id = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power_menu": id = GLOBAL_ACTION_POWER_DIALOG; break;
            case "lock":
                if (Build.VERSION.SDK_INT < 28) return "Bu Android sürümünde desteklenmiyor";
                id = GLOBAL_ACTION_LOCK_SCREEN; break;
            case "screenshot":
                if (Build.VERSION.SDK_INT < 28) return "Bu Android sürümünde desteklenmiyor";
                id = GLOBAL_ACTION_TAKE_SCREENSHOT; break;
            default: return "Bilinmeyen işlem: " + name;
        }
        return performGlobalAction(id) ? "Tamam" : "Yapılamadı";
    }

    /** Waits for a view (e.g. WhatsApp send button) to appear and taps it. */
    void clickWhenAppears(final String viewId, final String[] descs, final int tries) {
        main.postDelayed(new Runnable() {
            int left = tries;
            @Override public void run() {
                AccessibilityNodeInfo r = root();
                if (r != null) {
                    List<AccessibilityNodeInfo> l = r.findAccessibilityNodeInfosByViewId(viewId);
                    if (l != null && !l.isEmpty()) { l.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK); return; }
                    for (String d : descs) {
                        List<AccessibilityNodeInfo> m = r.findAccessibilityNodeInfosByText(d);
                        if (m == null) continue;
                        for (AccessibilityNodeInfo n : m) {
                            CharSequence cd = n.getContentDescription();
                            if (cd != null && cd.toString().equalsIgnoreCase(d) && n.isClickable()) {
                                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                return;
                            }
                        }
                    }
                }
                if (--left > 0) main.postDelayed(this, 700);
            }
        }, 1500);
    }
}
