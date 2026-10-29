package com.jarvis.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Listens offline for "Zeus uyan" and brings ZEUS to the front. */
public class WakeService extends Service implements RecognitionListener {

    static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip";
    static final String CH_RUN = "zeus_wake";
    static final String CH_ALERT = "zeus_wake_alert";
    static final String PREFS = "zeus";

    static volatile WakeService instance;
    static volatile String state = "off";   // off, downloading, loading, listening, paused, error
    static volatile int progress = 0;
    static volatile String error = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Model model;
    private SpeechService speech;
    private boolean paused = false;
    private long lastWake = 0;

    // ---------- control from the app ----------

    static boolean enabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("wake", false);
    }

    static void setEnabled(Context c, boolean on) {
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        p.edit().putBoolean("wake", on).apply();
        if (on) {
            if (instance == null) {
                Intent i = new Intent(c, WakeService.class);
                c.startForegroundService(i);
            }
        } else if (instance != null) {
            instance.shutdownAll();
        }
    }

    /** Called when the app comes to the front: free the microphone. */
    static void appVisible(boolean visible) {
        WakeService s = instance;
        if (s == null) return;
        s.main.post(() -> {
            if (visible) s.stopListening();
            else { s.paused = false; s.resumeListening(); }
        });
    }

    // ---------- service lifecycle ----------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_RUN, "Uyandırma kelimesi", NotificationManager.IMPORTANCE_MIN));
        NotificationChannel alert = new NotificationChannel(CH_ALERT, "ZEUS uyandı", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(alert);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("wake", false).apply();
            shutdownAll();
            return START_NOT_STICKY;
        }
        startFg();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state = "error"; error = "Mikrofon izni yok";
            return START_STICKY;
        }
        if (model == null) loadModel(); else resumeListening();
        return START_STICKY;
    }

    private void startFg() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pOpen = PendingIntent.getActivity(this, 10, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, WakeService.class).setAction("stop");
        PendingIntent pStop = PendingIntent.getService(this, 11, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_RUN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("ZEUS hazır")
                .setContentText("\"Zeus uyan\" dediğinde açılır")
                .setContentIntent(pOpen)
                .addAction(new Notification.Action.Builder(null, "Kapat", pStop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(1, n);
    }

    private void shutdownAll() {
        stopListening();
        if (model != null) { model.close(); model = null; }
        state = "off";
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopListening();
        if (model != null) { model.close(); model = null; }
        instance = null;
        state = "off";
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---------- model ----------

    private void loadModel() {
        new Thread(() -> {
            try {
                File dir = new File(getFilesDir(), "vosk-tr");
                if (!new File(dir, "ready").exists()) download(dir);
                state = "loading";
                Model m = new Model(dir.getAbsolutePath());
                main.post(() -> { model = m; error = ""; resumeListening(); });
            } catch (Exception e) {
                state = "error";
                error = "Model yüklenemedi: " + e.getMessage();
            }
        }).start();
    }

    private void download(File dir) throws Exception {
        state = "downloading";
        progress = 0;
        File zip = new File(getCacheDir(), "vosk-tr.zip");
        HttpURLConnection con = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        con.setConnectTimeout(20000);
        con.setReadTimeout(60000);
        con.setInstanceFollowRedirects(true);
        int total = con.getContentLength();
        try (InputStream in = new BufferedInputStream(con.getInputStream()); OutputStream out = new FileOutputStream(zip)) {
            byte[] buf = new byte[65536];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) progress = (int) (done * 100 / total);
            }
        } finally {
            con.disconnect();
        }
        deleteRecursive(dir);
        dir.mkdirs();
        String base = dir.getCanonicalPath();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue;
                String rel = name.substring(slash + 1);
                if (rel.isEmpty()) continue;
                File out = new File(dir, rel);
                if (!out.getCanonicalPath().startsWith(base)) continue;
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
        zip.delete();
        new File(dir, "ready").createNewFile();
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    // ---------- listening ----------

    private void resumeListening() {
        if (model == null) return;
        if (paused || MainActivity.visible) { stopListening(); state = "paused"; return; }
        if (speech != null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state = "error"; error = "Mikrofon izni yok"; return;
        }
        try {
            Recognizer rec = new Recognizer(model, 16000.0f);
            speech = new SpeechService(rec, 16000.0f);
            speech.startListening(this);
            state = "listening";
        } catch (Exception e) {
            state = "error";
            error = "Mikrofon açılamadı: " + e.getMessage();
            speech = null;
        }
    }

    private void stopListening() {
        if (speech != null) {
            try { speech.stop(); speech.shutdown(); } catch (Exception ignored) { }
            speech = null;
        }
        if (model != null) state = "paused";
    }

    @Override public void onPartialResult(String hypothesis) { check(hypothesis, "partial"); }
    @Override public void onResult(String hypothesis) { check(hypothesis, "text"); }
    @Override public void onFinalResult(String hypothesis) { check(hypothesis, "text"); }
    @Override public void onError(Exception e) { state = "error"; error = String.valueOf(e.getMessage()); }
    @Override public void onTimeout() { }

    private void check(String json, String key) {
        String t;
        try { t = new JSONObject(json).optString(key, ""); } catch (Exception e) { return; }
        if (t.isEmpty() || !isWake(t)) return;
        long now = System.currentTimeMillis();
        if (now - lastWake < 4000) return;
        lastWake = now;
        main.post(this::wake);
    }

    static boolean isWake(String text) {
        String[] w = MainActivity.norm(text).split("\\s+");
        for (int i = 0; i < w.length; i++) {
            if (!w[i].startsWith("uyan")) continue;
            if (i == 0) return w.length <= 2;
            String p = w[i - 1];
            if (p.startsWith("z") || p.startsWith("s") || p.contains("eu") || p.contains("ev") || p.contains("ze")) return true;
            if (w.length <= 3) return true;
        }
        return false;
    }

    private void wake() {
        paused = true;
        stopListening();
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) { }

        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("wake", true);
        boolean canPop = Settings.canDrawOverlays(this);
        if (canPop) {
            try { startActivity(i); } catch (Exception ignored) { canPop = false; }
        }
        if (!canPop) {
            PendingIntent pi = PendingIntent.getActivity(this, 12, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CH_ALERT)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("ZEUS uyandı")
                    .setContentText("Konuşmak için dokun")
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .build();
            try { getSystemService(NotificationManager.class).notify(2, n); } catch (Exception ignored) { }
        }
        // If the app never comes up, go back to listening.
        main.postDelayed(() -> {
            if (!MainActivity.visible && paused) { paused = false; resumeListening(); }
        }, 15000);
    }
}
