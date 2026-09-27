package com.example.cleanrecovery.music.player;

import android.content.Context;
import android.content.SharedPreferences;

/** Preferences are shared by the player page and the service-owned overlay. */
public final class DesktopLyricsSettings {
    public static final String ENABLED = "enabled";
    public static final String LOCKED = "locked";
    public static final String FONT = "font";
    public static final String COLOR = "color";
    public static final int[] COLORS = {0xFFFFFFFF, 0xFFFFD166, 0xFF6EF0CA};
    private DesktopLyricsSettings() {}
    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences("desktop_lyrics", Context.MODE_PRIVATE);
    }
}
