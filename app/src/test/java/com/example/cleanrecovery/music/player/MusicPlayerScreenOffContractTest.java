package com.example.cleanrecovery.music.player;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

/** Prevents regression of natural track completion while the device CPU is asleep. */
public class MusicPlayerScreenOffContractTest {
    @Test public void mediaPlayerHoldsPartialWakeLockDuringPlayback() throws Exception {
        Path source = Path.of("app/src/main/java/com/example/cleanrecovery/music/player/MusicPlayer.java");
        if (!Files.exists(source)) source = Path.of("src/main/java/com/example/cleanrecovery/music/player/MusicPlayer.java");
        String code = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertTrue(code.contains("player.setWakeMode(ctx, android.os.PowerManager.PARTIAL_WAKE_LOCK)"));

        Path service = source.resolveSibling("MusicService.java");
        String serviceCode = new String(Files.readAllBytes(service), StandardCharsets.UTF_8);
        assertTrue(serviceCode.contains("player.getState() == MusicPlayer.State.LOADING"));
        assertTrue(serviceCode.contains("transitionWakeLock.acquire(60_000L)"));
        assertTrue(serviceCode.contains("if (keepForeground)"));
    }
}
