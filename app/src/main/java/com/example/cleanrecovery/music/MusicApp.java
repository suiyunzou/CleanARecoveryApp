package com.example.cleanrecovery.music;

import android.app.Application;
import android.content.Context;

import com.example.cleanrecovery.music.api.IAuthService;
import com.example.cleanrecovery.music.api.IMusicDataSource;
import com.example.cleanrecovery.music.api.KugouDataSource;
import com.example.cleanrecovery.music.api.RemoteAuthService;
import com.example.cleanrecovery.music.data.DownloadStore;
import com.example.cleanrecovery.music.data.PlaylistStore;
import com.example.cleanrecovery.music.download.DownloadManager;
import com.example.cleanrecovery.music.player.MusicPlayer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Per-process singleton — wires auth, data source, playlist store, and player. */
public final class MusicApp {

    private static volatile MusicApp instance;

    public final IAuthService auth;
    public final KugouDataSource dataSource;
    public final PlaylistStore playlists;
    public final DownloadStore downloadStore;
    public final DownloadManager downloads;
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
        downloadStore = new DownloadStore(ctx);
        downloads = new DownloadManager(ctx, dataSource, downloadStore);
        player = MusicPlayer.get();
        updateDataSourceAuth();
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
                updateDataSourceAuth();
                android.util.Log.d("MusicApp", "refreshEntitlementAsync done");
            } catch (Exception e) {
                android.util.Log.w("MusicApp", "refreshEntitlementAsync failed", e);
            }
        });
    }

    /** Push the current auth context (token/userid/mid/dfid) into the data source
     *  so VIP songs can be resolved via the concept gateway. */
    public void refreshDataSourceAuth() {
        updateDataSourceAuth();
    }

    /** Push the current auth context (token/userid/mid/dfid) into the data source
     *  so VIP songs can be resolved via the concept gateway. */
    private void updateDataSourceAuth() {
        try {
            if (!auth.isLoggedIn()) return;
            String token = auth.getToken();
            String userid = auth.getUserId();
            String mid = com.example.cleanrecovery.music.security.DeviceFingerprint.getMid(context);
            String dfid = auth.getDfid();
            if (token == null || token.isEmpty() || userid == null || userid.isEmpty()) return;
            dataSource.setAuthContext(new KugouDataSource.AuthContext(token, userid, mid, dfid));
            android.util.Log.d("MusicApp", "dataSource auth updated: userid=" + userid);
        } catch (Exception e) {
            android.util.Log.w("MusicApp", "updateDataSourceAuth failed", e);
        }
    }
}
