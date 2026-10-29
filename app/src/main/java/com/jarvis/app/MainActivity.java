package com.jarvis.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    static final String CHANNEL_ID = "jarvis_reminders";
    static final Locale TR = new Locale("tr", "TR");

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingSpeech = null;
    private float speechRate = 1.0f;
    private float speechPitch = 1.0f;
    private String voiceName = "";
    private MediaPlayer player;
    private SpeechRecognizer recognizer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean torchOn = false;
    private long lastRms = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#02070D"));

        createChannel();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#02070D"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(TR);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                pickBestVoice();
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { js("window.onSpeakState && onSpeakState(true)"); }
                    @Override public void onDone(String id) { js("window.onSpeakState && onSpeakState(false)"); }
                    @Override public void onError(String id) { js("window.onSpeakState && onSpeakState(false)"); }
                });
                ttsReady = true;
                if (pendingSpeech != null) {
                    speakNow(pendingSpeech);
                    pendingSpeech = null;
                }
            }
        });

        requestStartupPermissions();
    }

    private void requestStartupPermissions() {
        List<String> need = new ArrayList<>();
        String[] wanted = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE};
        for (String p : wanted) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Hatırlatmalar", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("J.A.R.V.I.S hatırlatmaları");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    void js(final String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    // ---------------- Speech ----------------

    private void pickBestVoice() {
        try {
            Voice best = null;
            for (Voice v : tts.getVoices()) {
                if (!"tr".equals(v.getLocale().getLanguage())) continue;
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                if (best == null || v.getQuality() > best.getQuality()) best = v;
            }
            if (best != null) tts.setVoice(best);
        } catch (Exception ignored) { }
    }

    private void applyVoice() {
        if (voiceName == null || voiceName.isEmpty()) return;
        try {
            for (Voice v : tts.getVoices()) {
                if (v.getName().equals(voiceName)) { tts.setVoice(v); return; }
            }
        } catch (Exception ignored) { }
    }

    private void stopPlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
    }

    private void speakNow(String text) {
        stopPlayer();
        applyVoice();
        tts.setSpeechRate(speechRate);
        tts.setPitch(speechPitch);
        Bundle params = new Bundle();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "j" + SystemClock.uptimeMillis());
    }

    private void startListening() {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            js("onListenError('mic')");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("onListenError('unavailable')");
            return;
        }
        if (tts != null && tts.isSpeaking()) tts.stop();
        if (recognizer != null) {
            recognizer.destroy();
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { js("onListenState('listening')"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rms) {
                long now = SystemClock.uptimeMillis();
                if (now - lastRms > 60) { lastRms = now; js("onRms(" + rms + ")"); }
            }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { js("onListenState('processing')"); }
            @Override public void onError(int error) { js("onListenError('" + error + "')"); }
            @Override public void onResults(Bundle results) {
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = (list != null && !list.isEmpty()) ? list.get(0) : "";
                js("onSpeech(" + q(text) + ")");
            }
            @Override public void onPartialResults(Bundle partial) {
                ArrayList<String> list = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) js("onPartial(" + q(list.get(0)) + ")");
            }
            @Override public void onEvent(int type, Bundle params) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recognizer.startListening(i);
    }

    // ---------------- Helpers ----------------

    static String norm(String s) {
        if (s == null) return "";
        String n = s.toLowerCase(TR)
                .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
                .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c');
        n = Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("[^a-z0-9 ]", "").trim();
    }

    private boolean launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String findContactNumber(String name) {
        if (!has(Manifest.permission.READ_CONTACTS)) return null;
        String target = norm(name);
        String best = null;
        int bestScore = 0;
        try (Cursor c = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                String dn = norm(c.getString(0));
                String num = c.getString(1);
                int score = 0;
                if (dn.equals(target)) score = 3;
                else if (dn.startsWith(target) || target.startsWith(dn)) score = 2;
                else if (dn.contains(target) || target.contains(dn)) score = 1;
                if (score > bestScore) { bestScore = score; best = num; }
            }
        } catch (Exception ignored) { }
        return best;
    }

    // ---------------- JS Bridge ----------------

    class Bridge {

        @JavascriptInterface
        public void listen() { main.post(MainActivity.this::startListening); }

        @JavascriptInterface
        public void stopListening() {
            main.post(() -> { if (recognizer != null) recognizer.stopListening(); });
        }

        @JavascriptInterface
        public void speak(String text, float rate, float pitch) {
            speechRate = rate <= 0 ? 1.0f : rate;
            speechPitch = pitch <= 0 ? 1.0f : pitch;
            if (ttsReady) speakNow(text); else pendingSpeech = text;
        }

        @JavascriptInterface
        public void stopSpeaking() {
            if (tts != null) tts.stop();
            main.post(MainActivity.this::stopPlayer);
        }

        @JavascriptInterface
        public void setVoice(String name) { voiceName = name == null ? "" : name; }

        @JavascriptInterface
        public String voices() {
            JSONArray arr = new JSONArray();
            if (!ttsReady) return "[]";
            try {
                for (Voice v : tts.getVoices()) {
                    if (!"tr".equals(v.getLocale().getLanguage())) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", v.getName());
                    o.put("quality", v.getQuality());
                    o.put("network", v.isNetworkConnectionRequired());
                    o.put("installed", v.getFeatures() == null || !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
                    arr.put(o);
                }
            } catch (Exception ignored) { }
            return arr.toString();
        }

        /** Downloads speech audio from a cloud TTS API and plays it. */
        @JavascriptInterface
        public void cloudSpeak(final String url, final String headersJson, final String body) {
            new Thread(() -> {
                HttpURLConnection con = null;
                try {
                    con = (HttpURLConnection) new URL(url).openConnection();
                    con.setRequestMethod("POST");
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(60000);
                    JSONObject h = new JSONObject(headersJson);
                    Iterator<String> keys = h.keys();
                    while (keys.hasNext()) { String k = keys.next(); con.setRequestProperty(k, h.getString(k)); }
                    con.setDoOutput(true);
                    try (OutputStream os = con.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                    int status = con.getResponseCode();
                    if (status >= 400) {
                        StringBuilder sb = new StringBuilder();
                        InputStream es = con.getErrorStream();
                        if (es != null) {
                            try (BufferedReader br = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8))) {
                                String line; while ((line = br.readLine()) != null) sb.append(line);
                            }
                        }
                        js("onCloudTtsError(" + status + "," + q(sb.toString()) + ")");
                        return;
                    }
                    final File f = new File(getCacheDir(), "speech_" + SystemClock.uptimeMillis() + ".mp3");
                    try (InputStream is = con.getInputStream(); FileOutputStream fo = new FileOutputStream(f)) {
                        byte[] buf = new byte[16384]; int n;
                        while ((n = is.read(buf)) > 0) fo.write(buf, 0, n);
                    }
                    main.post(() -> {
                        try {
                            stopPlayer();
                            if (tts != null) tts.stop();
                            player = new MediaPlayer();
                            player.setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                            player.setDataSource(f.getAbsolutePath());
                            player.setOnPreparedListener(mp -> { mp.start(); js("onSpeakState(true)"); });
                            player.setOnCompletionListener(mp -> { js("onSpeakState(false)"); stopPlayer(); f.delete(); });
                            player.setOnErrorListener((mp, w, e) -> { js("onCloudTtsError(0,'oynatma hatası')"); stopPlayer(); f.delete(); return true; });
                            player.prepareAsync();
                        } catch (Exception e) {
                            js("onCloudTtsError(0," + q(e.getMessage()) + ")");
                        }
                    });
                } catch (Exception e) {
                    js("onCloudTtsError(0," + q("Bağlantı hatası: " + e.getMessage()) + ")");
                } finally {
                    if (con != null) con.disconnect();
                }
            }).start();
        }

        @JavascriptInterface
        public void http(final String id, final String method, final String url, final String headersJson, final String body) {
            new Thread(() -> {
                int status = 0;
                String resp;
                HttpURLConnection con = null;
                try {
                    con = (HttpURLConnection) new URL(url).openConnection();
                    con.setRequestMethod(method);
                    con.setConnectTimeout(20000);
                    con.setReadTimeout(90000);
                    if (headersJson != null && headersJson.length() > 2) {
                        JSONObject h = new JSONObject(headersJson);
                        Iterator<String> keys = h.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            con.setRequestProperty(k, h.getString(k));
                        }
                    }
                    if (body != null && !body.isEmpty() && !"GET".equals(method)) {
                        con.setDoOutput(true);
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        try (OutputStream os = con.getOutputStream()) { os.write(bytes); }
                    }
                    status = con.getResponseCode();
                    InputStream is = status >= 400 ? con.getErrorStream() : con.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    if (is != null) {
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = br.readLine()) != null) sb.append(line).append('\n');
                        }
                    }
                    resp = sb.toString();
                } catch (Exception e) {
                    resp = "{\"error\":{\"message\":" + q("Bağlantı hatası: " + e.getMessage()) + "}}";
                } finally {
                    if (con != null) con.disconnect();
                }
                js("onHttp(" + q(id) + "," + status + "," + q(resp) + ")");
            }).start();
        }

        @JavascriptInterface
        public String flashlight(boolean on) {
            try {
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                for (String id : cm.getCameraIdList()) {
                    Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (flash != null && flash) {
                        cm.setTorchMode(id, on);
                        torchOn = on;
                        return on ? "Fener açıldı" : "Fener kapatıldı";
                    }
                }
                return "Bu cihazda fener bulunamadı";
            } catch (Exception e) {
                return "Fener kontrol edilemedi: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String openApp(String name) {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = pm.queryIntentActivities(q, 0);
            String target = norm(name);
            ResolveInfo best = null;
            int bestScore = 0;
            for (ResolveInfo ri : apps) {
                String label = norm(String.valueOf(ri.loadLabel(pm)));
                int score = 0;
                if (label.equals(target)) score = 4;
                else if (label.startsWith(target)) score = 3;
                else if (label.contains(target)) score = 2;
                else if (target.contains(label) && label.length() > 2) score = 1;
                if (score > bestScore) { bestScore = score; best = ri; }
            }
            if (best == null) return "\"" + name + "\" adında bir uygulama bulunamadı";
            Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
            if (launch != null && launch(launch)) return best.loadLabel(pm) + " açıldı";
            return "Uygulama açılamadı";
        }

        @JavascriptInterface
        public String setAlarm(int hour, int minute, String label) {
            Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
            i.putExtra(AlarmClock.EXTRA_HOUR, hour);
            i.putExtra(AlarmClock.EXTRA_MINUTES, minute);
            if (label != null && !label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            return launch(i) ? String.format(TR, "Alarm %02d:%02d için kuruldu", hour, minute) : "Alarm kurulamadı";
        }

        @JavascriptInterface
        public String setTimer(int seconds, String label) {
            Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
            i.putExtra(AlarmClock.EXTRA_LENGTH, seconds);
            if (label != null && !label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            return launch(i) ? "Zamanlayıcı " + seconds + " saniyeye kuruldu" : "Zamanlayıcı kurulamadı";
        }

        @JavascriptInterface
        public String call(String nameOrNumber) {
            String digits = nameOrNumber.replaceAll("[^0-9+]", "");
            String number;
            String who;
            if (digits.length() >= 3) {
                number = digits;
                who = digits;
            } else {
                number = findContactNumber(nameOrNumber);
                who = nameOrNumber;
                if (number == null) {
                    if (!has(Manifest.permission.READ_CONTACTS)) {
                        main.post(() -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 3));
                        return "Rehbere erişim izni gerekiyor";
                    }
                    return "Rehberde \"" + nameOrNumber + "\" bulunamadı";
                }
            }
            Uri uri = Uri.parse("tel:" + Uri.encode(number));
            if (has(Manifest.permission.CALL_PHONE) && launch(new Intent(Intent.ACTION_CALL, uri))) {
                return who + " aranıyor";
            }
            return launch(new Intent(Intent.ACTION_DIAL, uri)) ? who + " için arama ekranı açıldı" : "Arama yapılamadı";
        }

        @JavascriptInterface
        public String volume(String action, int percent) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int stream = AudioManager.STREAM_MUSIC;
            int max = am.getStreamMaxVolume(stream);
            switch (action) {
                case "up":
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0);
                    break;
                case "down":
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0);
                    break;
                case "mute":
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
                    return "Ses kapatıldı";
                case "unmute":
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
                    break;
                default:
                    int p = Math.max(0, Math.min(100, percent));
                    am.setStreamVolume(stream, Math.round(max * p / 100f), AudioManager.FLAG_SHOW_UI);
            }
            int now = am.getStreamVolume(stream);
            return "Ses seviyesi %" + Math.round(now * 100f / max);
        }

        @JavascriptInterface
        public String media(String action) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int code;
            switch (action) {
                case "next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
                case "previous": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
                case "pause": code = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
                case "play": code = KeyEvent.KEYCODE_MEDIA_PLAY; break;
                default: code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            }
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            return "Medya komutu gönderildi";
        }

        @JavascriptInterface
        public String openUrl(String url) {
            return launch(new Intent(Intent.ACTION_VIEW, Uri.parse(url))) ? "Açıldı" : "Bağlantı açılamadı";
        }

        @JavascriptInterface
        public String openSettings(String page) {
            String action;
            switch (page) {
                case "wifi": action = Settings.ACTION_WIFI_SETTINGS; break;
                case "bluetooth": action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
                case "display": action = Settings.ACTION_DISPLAY_SETTINGS; break;
                case "sound": action = Settings.ACTION_SOUND_SETTINGS; break;
                case "location": action = Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
                case "battery": action = Intent.ACTION_POWER_USAGE_SUMMARY; break;
                case "tts": action = "com.android.settings.TTS_SETTINGS"; break;
                case "app": {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                    return launch(i) ? "Uygulama ayarları açıldı" : "Ayarlar açılamadı";
                }
                default: action = Settings.ACTION_SETTINGS;
            }
            return launch(new Intent(action)) ? "Ayarlar açıldı" : "Ayarlar açılamadı";
        }

        @JavascriptInterface
        public String battery() {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return "{}";
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            int pct = Math.round(level * 100f / scale);
            return "{\"level\":" + pct + ",\"charging\":" + charging + "}";
        }

        @JavascriptInterface
        public String location() {
            if (!has(Manifest.permission.ACCESS_COARSE_LOCATION) && !has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                return "";
            }
            try {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                Location best = null;
                for (String p : lm.getProviders(true)) {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
                if (best == null) return "";
                return "{\"lat\":" + best.getLatitude() + ",\"lon\":" + best.getLongitude() + "}";
            } catch (SecurityException e) {
                return "";
            }
        }

        @JavascriptInterface
        public String remind(double delayMinutes, String text) {
            long when = System.currentTimeMillis() + Math.round(delayMinutes * 60000);
            Intent i = new Intent(MainActivity.this, ReminderReceiver.class);
            i.putExtra("text", text);
            int reqCode = (int) (when % Integer.MAX_VALUE);
            PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, reqCode, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            return "ok";
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void requestPerms() { main.post(MainActivity.this::requestStartupPermissions); }
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        stopPlayer();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null) {
            web.evaluateJavascript("window.onBack ? onBack() : false", v -> {
                if (!"true".equals(v)) MainActivity.super.onBackPressed();
            });
        } else {
            super.onBackPressed();
        }
    }
}
