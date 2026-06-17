package com.example.cleanrecovery.music;

import android.app.Application;
import android.content.Context;

import com.example.cleanrecovery.music.api.IAuthService;
import com.example.cleanrecovery.music.api.IMusicDataSource;
import com.example.cleanrecovery.music.api.KugouDataSource;
import com.example.cleanrecovery.music.api.LocalAuthService;
import com.example.cleanrecovery.music.data.PlaylistStore;
import com.example.cleanrecovery.music.player.MusicPlayer;

/** Per-process singleton — wires auth, data source, playlist store, and player. */
public final class MusicApp {

    private static volatile MusicApp instance;

    public final IAuthService auth;
    public final IMusicDataSource dataSource;
    public final PlaylistStore playlists;
    public final MusicPlayer player;

    private MusicApp(Context ctx) {
        auth = new LocalAuthService(ctx);
        dataSource = new KugouDataSource(auth);
        playlists = new PlaylistStore(ctx);
        player = MusicPlayer.get();
    }

    public static MusicApp init(Context ctx) {
        if (instance == null) {
            synchronized (MusicApp.class) {
                if (instance == null) instance = new MusicApp(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    public static MusicApp get() { return instance; }
}
