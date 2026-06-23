package com.example.cleanrecovery.music;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

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
    private final SharedPreferences prefs;

    private static final String PREFS = "music_app";
    private static final String KEY_LAST_MENU_TYPE = "last_menu_type";
    private static final String KEY_LAST_MENU_NAME = "last_menu_name";
    private static final String KEY_LAST_MENU_ID = "last_menu_id";
    private static final String KEY_LAST_MENU_LIST_ID = "last_menu_list_id";
    private static final String KEY_LAST_MENU_GLOBAL_ID = "last_menu_global_id";
    private static final String KEY_LAST_MENU_COUNT = "last_menu_count";

    private MusicApp(Context ctx) {
        context = ctx.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    public void rememberLastLocalMenu(String playlistName) {
        prefs.edit()
                .putString(KEY_LAST_MENU_TYPE, "local")
                .putString(KEY_LAST_MENU_NAME, playlistName)
                .remove(KEY_LAST_MENU_ID)
                .remove(KEY_LAST_MENU_LIST_ID)
                .remove(KEY_LAST_MENU_GLOBAL_ID)
                .remove(KEY_LAST_MENU_COUNT)
                .apply();
    }

    public void rememberLastDownloadedMenu() {
        prefs.edit()
                .putString(KEY_LAST_MENU_TYPE, "downloaded")
                .remove(KEY_LAST_MENU_NAME)
                .remove(KEY_LAST_MENU_ID)
                .remove(KEY_LAST_MENU_LIST_ID)
                .remove(KEY_LAST_MENU_GLOBAL_ID)
                .remove(KEY_LAST_MENU_COUNT)
                .apply();
    }

    public void rememberLastRemoteMenu(com.example.cleanrecovery.music.data.RemotePlaylist playlist) {
        if (playlist == null) return;
        prefs.edit()
                .putString(KEY_LAST_MENU_TYPE, "remote")
                .putString(KEY_LAST_MENU_NAME, playlist.name)
                .putString(KEY_LAST_MENU_ID, playlist.id)
                .putString(KEY_LAST_MENU_LIST_ID, playlist.listId)
                .putString(KEY_LAST_MENU_GLOBAL_ID, playlist.globalCollectionId)
                .putInt(KEY_LAST_MENU_COUNT, playlist.songCount)
                .apply();
    }

    public String lastMenuType() {
        return prefs.getString(KEY_LAST_MENU_TYPE, "");
    }

    public String lastMenuName() {
        return prefs.getString(KEY_LAST_MENU_NAME, "");
    }

    public com.example.cleanrecovery.music.data.RemotePlaylist lastRemoteMenu() {
        if (!"remote".equals(lastMenuType())) return null;
        com.example.cleanrecovery.music.data.RemotePlaylist playlist =
                new com.example.cleanrecovery.music.data.RemotePlaylist();
        playlist.name = prefs.getString(KEY_LAST_MENU_NAME, "");
        playlist.id = prefs.getString(KEY_LAST_MENU_ID, "");
        playlist.listId = prefs.getString(KEY_LAST_MENU_LIST_ID, "");
        playlist.globalCollectionId = prefs.getString(KEY_LAST_MENU_GLOBAL_ID, "");
        playlist.songCount = prefs.getInt(KEY_LAST_MENU_COUNT, 0);
        return playlist;
    }
}
