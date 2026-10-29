package com.jarvis.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;

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

/** Offline Turkish speech recognition with Whisper (downloaded on demand). */
public class OfflineAsr {

    static final String MODEL_URL = "https://github.com/stahagunes/jarviss/releases/download/models/whisper-base-tr.zip";

    public interface Listener {
        void onState(String state);      // listening, processing
        void onRms(float level);         // 0..10
        void onResult(String text);
        void onError(String code);
    }

    static volatile String dlState = "";   // "", downloading, error
    static volatile int dlProgress = 0;
    static volatile String dlError = "";
    private static OfflineRecognizer recognizer;
    private static volatile boolean stopFlag = false;
    private static volatile boolean running = false;

    static File dir(Context c) { return new File(c.getFilesDir(), "whisper"); }

    static boolean ready(Context c) { return new File(dir(c), ".ready").exists(); }

    static void download(final Context c) {
        if ("downloading".equals(dlState)) return;
        dlState = "downloading"; dlProgress = 0; dlError = "";
        new Thread(() -> {
            File zip = new File(c.getCacheDir(), "whisper.zip");
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                con.setInstanceFollowRedirects(true);
                con.setConnectTimeout(20000);
                con.setReadTimeout(60000);
                int code = con.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = con.getHeaderField("Location");
                    con.disconnect();
                    con = (HttpURLConnection) new URL(loc).openConnection();
                    con.setConnectTimeout(20000);
                    con.setReadTimeout(60000);
                }
                long total = con.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(con.getInputStream()); OutputStream out = new FileOutputStream(zip)) {
                    byte[] buf = new byte[1 << 16];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) dlProgress = (int) (done * 100 / total);
                    }
                } finally {
                    con.disconnect();
                }
                File d = dir(c);
                ZeusVoice.deleteRecursive(d);
                d.mkdirs();
                String base = d.getCanonicalPath();
                try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
                    ZipEntry e;
                    byte[] buf = new byte[1 << 16];
                    while ((e = zis.getNextEntry()) != null) {
                        if (e.isDirectory()) continue;
                        String name = new File(e.getName()).getName();
                        File out = new File(d, name);
                        if (!out.getCanonicalPath().startsWith(base)) continue;
                        try (OutputStream os = new FileOutputStream(out)) {
                            int n;
                            while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                        }
                    }
                }
                zip.delete();
                if (!new File(d, "encoder.onnx").exists() || !new File(d, "decoder.onnx").exists() || !new File(d, "tokens.txt").exists()) {
                    throw new Exception("model dosyaları eksik");
                }
                new File(d, ".ready").createNewFile();
                dlState = "";
            } catch (Exception e) {
                zip.delete();
                dlState = "error";
                dlError = String.valueOf(e.getMessage());
            }
        }).start();
    }

    static void delete(Context c) {
        synchronized (OfflineAsr.class) {
            if (recognizer != null) { recognizer.release(); recognizer = null; }
        }
        ZeusVoice.deleteRecursive(dir(c));
    }

    private static synchronized OfflineRecognizer get(Context c) {
        if (recognizer != null) return recognizer;
        File d = dir(c);
        OfflineWhisperModelConfig w = new OfflineWhisperModelConfig();
        w.setEncoder(new File(d, "encoder.onnx").getAbsolutePath());
        w.setDecoder(new File(d, "decoder.onnx").getAbsolutePath());
        w.setLanguage("tr");
        w.setTask("transcribe");
        OfflineModelConfig m = new OfflineModelConfig();
        m.setWhisper(w);
        m.setTokens(new File(d, "tokens.txt").getAbsolutePath());
        m.setModelType("whisper");
        m.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
        m.setProvider("cpu");
        OfflineRecognizerConfig rc = new OfflineRecognizerConfig();
        rc.setModelConfig(m);
        recognizer = new OfflineRecognizer(null, rc);
        return recognizer;
    }

    static void warmUp(final Context c) {
        if (!ready(c)) return;
        new Thread(() -> { try { get(c); } catch (Throwable ignored) { } }).start();
    }

    static void stop() { stopFlag = true; }

    static boolean isRunning() { return running; }

    /** Records until the speaker stops talking, then transcribes offline. */
    @SuppressLint("MissingPermission")
    static void listen(final Context c, final Listener l) {
        if (running) { stopFlag = true; return; }
        if (!ready(c)) { l.onError("offline-missing"); return; }
        stopFlag = false;
        running = true;
        new Thread(() -> {
            final int sr = 16000;
            AudioRecord rec = null;
            try {
                int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, sr));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) { l.onError("mic"); return; }
                // warm the model while the user starts speaking
                Thread loader = new Thread(() -> { try { get(c); } catch (Throwable ignored) { } });
                loader.start();
                rec.startRecording();
                l.onState("listening");
                final int frame = sr / 50;                 // 20 ms
                short[] buf = new short[frame];
                float[] all = new float[sr * 15];          // max 15 s
                int len = 0;
                float noise = 0.004f;
                boolean speech = false;
                int silentFrames = 0, frames = 0;
                while (!stopFlag) {
                    int n = rec.read(buf, 0, frame);
                    if (n <= 0) continue;
                    double sum = 0;
                    for (int i = 0; i < n; i++) {
                        float v = buf[i] / 32768f;
                        if (len < all.length) all[len++] = v;
                        sum += v * v;
                    }
                    frames++;
                    float rms = (float) Math.sqrt(sum / n);
                    if (frames % 3 == 0) l.onRms(Math.min(10f, rms * 120f));
                    if (!speech) {
                        if (frames < 10) noise = Math.max(noise, rms);
                        if (rms > Math.max(0.012f, noise * 2.6f)) { speech = true; silentFrames = 0; }
                        else if (frames > 50 * 7) break;   // nothing said for 7 s
                    } else {
                        if (rms < Math.max(0.008f, noise * 1.8f)) silentFrames++; else silentFrames = 0;
                        if (silentFrames > 45) break;      // ~0.9 s of silence
                    }
                    if (len >= all.length) break;
                }
                rec.stop();
                if (!speech) { l.onError("7"); return; }
                l.onState("processing");
                loader.join();
                OfflineRecognizer r = get(c);
                float[] audio = new float[len];
                System.arraycopy(all, 0, audio, 0, len);
                OfflineStream s = r.createStream();
                s.acceptWaveform(audio, sr);
                r.decode(s);
                String text = r.getResult(s).getText();
                s.release();
                text = text == null ? "" : text.trim();
                if (text.isEmpty()) l.onError("7"); else l.onResult(text);
            } catch (Throwable e) {
                l.onError("offline:" + e.getMessage());
            } finally {
                if (rec != null) { try { rec.release(); } catch (Exception ignored) { } }
                running = false;
            }
        }).start();
    }
}
