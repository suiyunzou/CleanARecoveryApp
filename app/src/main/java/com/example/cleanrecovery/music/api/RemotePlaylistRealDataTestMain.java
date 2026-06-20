package com.example.cleanrecovery.music.api;

import com.example.cleanrecovery.music.data.RemotePlaylist;
import com.example.cleanrecovery.music.data.SongInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Optional JVM smoke test for logged-in Kugou cloud playlists. */
public final class RemotePlaylistRealDataTestMain {

    public static void main(String[] args) {
        File tokenFile = new File("latest_token.txt");
        if (!tokenFile.exists()) {
            System.out.println("SKIP: latest_token.txt not found");
            return;
        }

        try {
            String json = new String(Files.readAllBytes(tokenFile.toPath()), StandardCharsets.UTF_8);
            JsonObject tokenJson = JsonParser.parseString(json).getAsJsonObject();
            String token = str(tokenJson, "token");
            String userid = str(tokenJson, "userid");
            String mid = str(tokenJson, "mid");
            String dfid = str(tokenJson, "dfid");
            if (token.isEmpty() || userid.isEmpty()) {
                System.out.println("SKIP: latest_token.txt missing token or userid");
                return;
            }

            KugouDataSource dataSource = new KugouDataSource();
            dataSource.setAuthContext(new KugouDataSource.AuthContext(token, userid, mid, dfid));
            List<RemotePlaylist> playlists = dataSource.getUserPlaylists(1, 10);
            System.out.println("Remote playlists page1 size10: " + playlists.size());
            for (int i = 0; i < playlists.size(); i++) {
                RemotePlaylist p = playlists.get(i);
                System.out.println("  [" + i + "] " + p.name + " id=" + p.playableId()
                        + " songs=" + p.songCount);
            }
            for (int page = 1; page <= 5; page++) {
                List<RemotePlaylist> pageItems = dataSource.getUserPlaylists(page, 2);
                System.out.println("Remote playlists page" + page + " size2: " + pageItems.size());
                for (RemotePlaylist p : pageItems) {
                    System.out.println("  - " + p.name + " id=" + p.playableId()
                            + " songs=" + p.songCount);
                }
                if (pageItems.size() < 2) break;
            }
            for (int type = 0; type <= 5; type++) {
                List<RemotePlaylist> typeItems = dataSource.getUserPlaylists(1, 30, type);
                System.out.println("Remote playlists type" + type + " page1 size30: " + typeItems.size());
                for (RemotePlaylist p : typeItems) {
                    System.out.println("  * " + p.name + " id=" + p.playableId()
                            + " songs=" + p.songCount);
                }
            }
            if (playlists.isEmpty()) return;

            RemotePlaylist first = firstNonEmptyPlaylist(playlists);
            System.out.println("First playlist: " + first.name + " id=" + first.playableId());
            List<SongInfo> songs = dataSource.getUserPlaylistSongs(first, 1, 20);
            System.out.println("First playlist songs: " + songs.size());
            if (!songs.isEmpty()) {
                SongInfo song = songs.get(0);
                System.out.println("First song: " + song.title + " - " + song.artist);
                System.out.println("First song hash=" + song.hash
                        + " albumId=" + song.albumId
                        + " vipRequired=" + song.vipRequired
                        + " duration=" + song.duration);
                String playUrl = dataSource.resolvePlayUrl(song);
                System.out.println("First song play URL: " + describeUrl(playUrl));
            }
        } catch (Exception e) {
            System.out.println("FAIL: " + e.getMessage());
            e.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        return o.get(key).getAsString();
    }

    private static RemotePlaylist firstNonEmptyPlaylist(List<RemotePlaylist> playlists) {
        for (RemotePlaylist playlist : playlists) {
            if (playlist.songCount > 0) return playlist;
        }
        return playlists.get(0);
    }

    private static String describeUrl(String url) {
        if (url == null || url.isEmpty()) return "EMPTY";
        int query = url.indexOf('?');
        String noQuery = query >= 0 ? url.substring(0, query) : url;
        return noQuery.substring(0, Math.min(noQuery.length(), 160));
    }
}
