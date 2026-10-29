package com.jarvis.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ZEUS's own offline voice: Piper (VITS) Turkish voices via sherpa-onnx,
 * with a "depth" pitch shift and an optional hall reverb ("divine echo").
 */
public class ZeusVoice {

    public interface Listener {
        void onStart();
        void onDone();
        void onError(String message);
    }

    private static OfflineTts tts;
    private static String loadedVoice = "";
    private static volatile int generation = 0;
    private static AudioTrack track;

    static boolean available(Context c) {
        try {
            String[] a = c.getAssets().list("piper-fahrettin");
            return a != null && a.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static synchronized OfflineTts get(Context c, String voice) throws Exception {
        if (tts != null && voice.equals(loadedVoice)) return tts;
        if (tts != null) { tts.release(); tts = null; }
        File esp = new File(c.getFilesDir(), "espeak-ng-data");
        if (!new File(esp, ".ready").exists()) {
            deleteRecursive(esp);
            copyAssetDir(c.getAssets(), "espeak-ng-data", esp);
            new File(esp, ".ready").createNewFile();
        }
        String dir = "piper-" + voice;
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(dir + "/model.onnx");
        vits.setTokens(dir + "/tokens.txt");
        vits.setDataDir(esp.getAbsolutePath());
        vits.setLengthScale(1.0f);
        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setVits(vits);
        model.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
        model.setProvider("cpu");
        OfflineTtsConfig cfg = new OfflineTtsConfig();
        cfg.setModel(model);
        cfg.setMaxNumSentences(1);
        tts = new OfflineTts(c.getAssets(), cfg);
        loadedVoice = voice;
        return tts;
    }

    /** Loads the model in the background so the first answer is quick. */
    static void warmUp(final Context c, final String voice) {
        new Thread(() -> { try { get(c, voice); } catch (Throwable ignored) { } }).start();
    }

    static void stop() {
        generation++;
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); t.flush(); } catch (Exception ignored) { }
        }
    }

    static void speak(final Context c, final String text, final String voice, final float rate,
                      final float depth, final float echo, final Listener l) {
        final int myGen = ++generation;
        new Thread(() -> {
            AudioTrack t = null;
            boolean started = false;
            try {
                OfflineTts engine = get(c, voice);
                if (myGen != generation) return;
                int sr = engine.sampleRate();
                float d = Math.max(0.7f, Math.min(1.0f, depth));
                int outRate = Math.round(sr * d);
                float speed = Math.max(0.5f, Math.min(2.0f, rate / d));
                Reverb reverb = echo > 0.01f ? new Reverb(outRate, echo) : null;

                int minBuf = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(outRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(Math.max(minBuf, outRate * 4))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                synchronized (ZeusVoice.class) {
                    if (track != null) { try { track.release(); } catch (Exception ignored) { } }
                    track = t;
                }
                int written = 0;
                for (String sentence : split(text)) {
                    if (myGen != generation) break;
                    GeneratedAudio a = engine.generate(sentence, 0, speed);
                    float[] s = a.getSamples();
                    if (s == null || s.length == 0) continue;
                    if (reverb != null) s = reverb.process(s);
                    if (myGen != generation) break;
                    if (!started) { t.play(); started = true; l.onStart(); }
                    written += write(t, s, myGen);
                    // a short breath between sentences
                    written += write(t, reverb != null ? reverb.process(new float[outRate / 8]) : new float[outRate / 8], myGen);
                }
                if (reverb != null && myGen == generation) written += write(t, reverb.process(new float[(int) (outRate * 0.7f)]), myGen);
                if (started && myGen == generation) {
                    // wait until the buffer has been played
                    long end = System.currentTimeMillis() + 15000;
                    while (myGen == generation && t.getPlaybackHeadPosition() < written && System.currentTimeMillis() < end) {
                        Thread.sleep(40);
                    }
                }
            } catch (Throwable e) {
                if (myGen == generation) l.onError(String.valueOf(e.getMessage()));
                return;
            } finally {
                if (t != null) {
                    try { t.stop(); } catch (Exception ignored) { }
                    synchronized (ZeusVoice.class) { if (track == t) track = null; }
                    try { t.release(); } catch (Exception ignored) { }
                }
            }
            if (started) l.onDone();
            else if (myGen == generation) l.onError("ses üretilemedi");
        }).start();
    }

    private static int write(AudioTrack t, float[] s, int gen) {
        int off = 0;
        while (off < s.length && gen == generation) {
            int n = t.write(s, off, Math.min(4096, s.length - off), AudioTrack.WRITE_BLOCKING);
            if (n <= 0) break;
            off += n;
        }
        return off;
    }

    private static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String part : text.split("(?<=[.!?…;:])\\s+")) {
            if (cur.length() > 0 && cur.length() + part.length() > 160) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(part);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Freeverb-style hall reverb (Schroeder combs + allpasses), stateful across chunks. */
    static class Reverb {
        private final float[][] comb;
        private final int[] combIdx;
        private final float[] combStore;
        private final float[][] ap;
        private final int[] apIdx;
        private final float wet, feedback = 0.80f, damp = 0.25f;

        Reverb(int sr, float amount) {
            float k = sr / 44100f;
            int[] cl = {1116, 1188, 1277, 1356, 1422, 1491};
            int[] al = {556, 441, 341};
            comb = new float[cl.length][];
            for (int i = 0; i < cl.length; i++) comb[i] = new float[Math.max(1, Math.round(cl[i] * k * 1.6f))];
            combIdx = new int[cl.length];
            combStore = new float[cl.length];
            ap = new float[al.length][];
            for (int i = 0; i < al.length; i++) ap[i] = new float[Math.max(1, Math.round(al[i] * k))];
            apIdx = new int[al.length];
            wet = Math.min(0.6f, amount);
        }

        float[] process(float[] in) {
            float[] out = new float[in.length];
            for (int n = 0; n < in.length; n++) {
                float x = in[n] * 0.015f;
                float acc = 0;
                for (int i = 0; i < comb.length; i++) {
                    float[] buf = comb[i];
                    float y = buf[combIdx[i]];
                    combStore[i] = y * (1 - damp) + combStore[i] * damp;
                    buf[combIdx[i]] = x + combStore[i] * feedback;
                    if (++combIdx[i] >= buf.length) combIdx[i] = 0;
                    acc += y;
                }
                for (int i = 0; i < ap.length; i++) {
                    float[] buf = ap[i];
                    float b = buf[apIdx[i]];
                    buf[apIdx[i]] = acc + b * 0.5f;
                    acc = b - acc;
                    if (++apIdx[i] >= buf.length) apIdx[i] = 0;
                }
                float v = in[n] * (1f - wet * 0.35f) + acc * wet * 14f;
                out[n] = Math.max(-1f, Math.min(1f, v));
            }
            return out;
        }
    }

    static void copyAssetDir(AssetManager am, String path, File out) throws Exception {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = am.open(path); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return;
        }
        out.mkdirs();
        for (String k : kids) copyAssetDir(am, path + "/" + k, new File(out, k));
    }

    static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}
