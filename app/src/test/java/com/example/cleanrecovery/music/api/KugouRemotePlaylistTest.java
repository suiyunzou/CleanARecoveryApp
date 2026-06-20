package com.example.cleanrecovery.music.api;

import com.example.cleanrecovery.music.data.RemotePlaylist;
import com.example.cleanrecovery.music.data.SongInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.List;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KugouRemotePlaylistTest {

    @Test
    public void androidLiteSignatureIsStable() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("appid", "3116");
        params.put("clienttime", "1");
        params.put("clientver", "11440");
        params.put("dfid", "-");
        params.put("mid", "123");
        params.put("plat", "1");
        params.put("token", "tok");
        params.put("userid", "42");
        params.put("uuid", "-");

        String signature = KugouDataSource.signatureAndroidParams(
                params, "{\"page\":1,\"pagesize\":30}");

        assertEquals("605a999ab79cc066698041ce65c515ba", signature);
    }

    @Test
    public void parsesRemotePlaylistShapeVariants() {
        JsonObject json = JsonParser.parseString("{\"status\":1,\"data\":{\"list\":["
                + "{\"listid\":\"100\",\"global_collection_id\":\"g100\","
                + "\"name\":\"Daily\",\"song_count\":12,\"cover\":\"http://img/{size}.jpg\"},"
                + "{\"id\":\"200\",\"list_name\":\"Road\",\"file_count\":3}"
                + "]}}").getAsJsonObject();

        List<RemotePlaylist> playlists = KugouDataSource.parseRemotePlaylists(json);

        assertEquals(2, playlists.size());
        assertEquals("100", playlists.get(0).listId);
        assertEquals("g100", playlists.get(0).globalCollectionId);
        assertEquals("Daily", playlists.get(0).name);
        assertEquals(12, playlists.get(0).songCount);
        assertEquals("http://img/240.jpg", playlists.get(0).coverUrl);
        assertEquals("200", playlists.get(1).id);
        assertEquals("Road", playlists.get(1).name);
    }

    @Test
    public void parsesRemotePlaylistSongs() {
        JsonObject json = JsonParser.parseString("{\"status\":1,\"data\":{\"list\":["
                + "{\"file_hash\":\"abcdef\",\"audio_name\":\"Song A\","
                + "\"author_name\":\"Singer\",\"albumid\":\"9\",\"timeLength\":245,"
                + "\"img\":\"http://cover/{size}.jpg\",\"pay_type\":1}"
                + "]}}").getAsJsonObject();

        List<SongInfo> songs = KugouDataSource.parseSongList(json);

        assertEquals(1, songs.size());
        SongInfo song = songs.get(0);
        assertEquals("ABCDEF", song.hash);
        assertEquals("Song A", song.title);
        assertEquals("Singer", song.artist);
        assertEquals("9", song.albumId);
        assertEquals(245, song.duration);
        assertEquals("http://cover/240.jpg", song.imgUrl);
        assertTrue(song.vipRequired);
        assertFalse(song.durationFormatted().isEmpty());
    }
}
