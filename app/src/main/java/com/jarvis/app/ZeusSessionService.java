package com.jarvis.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** Long-press home/power: open ZEUS already listening. */
public class ZeusSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new Session(this);
    }

    static class Session extends VoiceInteractionSession {
        Session(Context c) { super(c); }

        @Override
        public void onShow(Bundle args, int showFlags) {
            super.onShow(args, showFlags);
            Intent i = new Intent(getContext(), MainActivity.class).setAction("com.jarvis.app.LISTEN")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("launch", "assist");
            try { startAssistantActivity(i); }
            catch (Exception e) { try { getContext().startActivity(i); } catch (Exception ignored) { } }
            hide();
        }
    }
}
