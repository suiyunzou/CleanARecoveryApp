package com.example.cleanrecovery.music;

import android.app.Application;
import android.content.Context;

import com.example.cleanrecovery.music.api.IAuthService;
import com.example.cleanrecovery.music.api.IMusicDataSource;
import com.example.cleanrecovery.music.api.KugouDataSource;
import com.example.cleanrecovery.music.api.RemoteAuthService;
import com.example.cleanrecovery.music.data.PlaylistStore;
import com.example.cleanrecovery.music.player.MusicPlayer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Per-process singleton — wires auth, data source, playlist store, and player. */
public final class MusicApp {

    private static volatile MusicApp instance;

    public final IAuthService auth;
    public final IMusicDataSource dataSource;
    public final PlaylistStore playlists;
    public final MusicPlayer player;
    public final Context context;
    private final ExecutorService appExecutor = Executors.newSingleThreadExecutor();

    private MusicApp(Context ctx) {
        context = ctx.getApplicationContext();
        auth = new RemoteAuthService(ctx);
        try {
            auth.restoreSession();
        } catch (Exception ignored) {
        }
        dataSource = new KugouDataSource();
        playlists = new PlaylistStore(ctx);
        player = MusicPlayer.get();
        refreshEntitlementAsync();
        MusicPlayer.setPlayUrlResolver(song -> {
            try {
                String url = dataSource.resolvePlayUrl(song);
                if (url == null || url.isEmpty()) url = dataSource.resolveTrialUrl(song);
                return url;
            } catch (Exception ignored) {
                return null;
            }
        });
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

    public void refreshEntitlementAsync() {
        appExecutor.execute(() -> {
            try {
                android.util.Log.d("MusicApp", "refreshEntitlementAsync start");
                auth.refreshEntitlement();
                android.util.Log.d("MusicApp", "refreshEntitlementAsync done");
            } catch (Exception e) {
                android.util.Log.w("MusicApp", "refreshEntitlementAsync failed", e);
            }
        });
    }
}
