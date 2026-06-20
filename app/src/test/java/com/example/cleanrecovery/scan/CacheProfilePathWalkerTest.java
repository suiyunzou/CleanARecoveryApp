package com.example.cleanrecovery.scan;

import com.example.cleanrecovery.experiment.cache.CacheProfileRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CacheProfilePathWalkerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void visitsOnlyCacheProfileFiles() throws IOException {
        File root = temporaryFolder.newFolder("storage");
        File regular = new File(root, "DCIM/photo.jpg");
        assertTrue(regular.getParentFile().mkdirs());
        assertTrue(regular.createNewFile());

        File thumbnail = new File(root, "DCIM/.thumbnails/thumb");
        assertTrue(thumbnail.getParentFile().mkdirs());
        assertTrue(thumbnail.createNewFile());
        assertTrue(CacheProfileRegistry.matchPath(thumbnail.getAbsolutePath()) != null);

        AtomicInteger seen = new AtomicInteger();
        CacheProfilePathWalker walker = new CacheProfilePathWalker();
        int scanned = walker.walk(root, new CacheProfilePathWalker.Callback() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onProfileFile(File file, int scannedSoFar) {
                seen.incrementAndGet();
                assertTrue(file.getAbsolutePath().contains(".thumbnails"));
            }
        });

        assertEquals(1, scanned);
        assertEquals(1, seen.get());
    }
}
