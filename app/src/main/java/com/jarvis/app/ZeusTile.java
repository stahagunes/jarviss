package com.jarvis.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: one tap from the notification shade starts ZEUS listening. */
public class ZeusTile extends TileService {
    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t != null) { t.setState(Tile.STATE_ACTIVE); t.setLabel("ZEUS"); t.updateTile(); }
    }

    @Override
    public void onClick() {
        Intent i = new Intent(this, MainActivity.class).setAction("com.jarvis.app.LISTEN")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("launch", "listen");
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 30, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
