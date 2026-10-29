package com.jarvis.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

import java.util.List;

/**
 * Required for a default assistant. When ZEUS is the assistant, other apps that use the
 * default recognizer are forwarded to Google's (or another installed) recognizer, so
 * voice typing elsewhere keeps working.
 */
public class ZeusRecognitionService extends RecognitionService {
    private SpeechRecognizer inner;

    static ComponentName otherRecognizer(android.content.Context c) {
        List<ResolveInfo> list = c.getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        ComponentName best = null;
        for (ResolveInfo ri : list) {
            if (ri.serviceInfo == null || c.getPackageName().equals(ri.serviceInfo.packageName)) continue;
            ComponentName cn = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
            if (ri.serviceInfo.packageName.startsWith("com.google")) return cn;
            if (best == null) best = cn;
        }
        return best;
    }

    @Override
    protected void onStartListening(Intent intent, final Callback cb) {
        ComponentName other = otherRecognizer(this);
        if (other == null) { try { cb.error(SpeechRecognizer.ERROR_CLIENT); } catch (RemoteException ignored) { } return; }
        if (inner != null) inner.destroy();
        inner = SpeechRecognizer.createSpeechRecognizer(this, other);
        inner.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { try { cb.readyForSpeech(p); } catch (RemoteException ignored) { } }
            @Override public void onBeginningOfSpeech() { try { cb.beginningOfSpeech(); } catch (RemoteException ignored) { } }
            @Override public void onRmsChanged(float v) { try { cb.rmsChanged(v); } catch (RemoteException ignored) { } }
            @Override public void onBufferReceived(byte[] b) { try { cb.bufferReceived(b); } catch (RemoteException ignored) { } }
            @Override public void onEndOfSpeech() { try { cb.endOfSpeech(); } catch (RemoteException ignored) { } }
            @Override public void onError(int e) { try { cb.error(e); } catch (RemoteException ignored) { } }
            @Override public void onResults(Bundle r) { try { cb.results(r); } catch (RemoteException ignored) { } }
            @Override public void onPartialResults(Bundle r) { try { cb.partialResults(r); } catch (RemoteException ignored) { } }
            @Override public void onEvent(int t, Bundle p) { }
        });
        inner.startListening(intent);
    }

    @Override protected void onStopListening(Callback cb) { if (inner != null) inner.stopListening(); }
    @Override protected void onCancel(Callback cb) { if (inner != null) inner.cancel(); }
    @Override public void onDestroy() { if (inner != null) inner.destroy(); super.onDestroy(); }
}
