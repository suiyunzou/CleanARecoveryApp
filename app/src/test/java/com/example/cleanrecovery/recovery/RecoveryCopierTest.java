package com.example.cleanrecovery.recovery;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class RecoveryCopierTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static RecoveryItem imageItem(String name) {
        return new RecoveryItem(RecoveryType.IMAGE, name, "/tmp/" + name, 100L, 0L, 0, 0, true, RecoverySourceKind.MEDIASTORE_TRASH);
    }

    @Test
    public void uniqueDestinationUsesRecoveredPrefixAndCollisionSuffix() throws IOException {
        File directory = temporaryFolder.newFolder("output");
        File first = new File(directory, "Recovered_a.jpg");
        assertTrue(first.createNewFile());

        File second = RecoveryCopier.uniqueDestination(directory, imageItem("a.jpg"));
        assertEquals("Recovered_a_1.jpg", second.getName());
    }

    @Test
    public void uniqueDestinationUsesRecoveredPrefixForFirstCollision() throws IOException {
        File directory = temporaryFolder.newFolder("fresh");

        File destination = RecoveryCopier.uniqueDestination(directory, imageItem("photo.png"));
        assertEquals("Recovered_photo.png", destination.getName());
    }

    @Test
    public void buildRecoveryNameAddsExtensionWhenMissing() {
        RecoveryItem item = imageItem("orphan_file");  // no extension
        String name = RecoveryCopier.buildRecoveryName(item);
        assertEquals("orphan_file.jpg", name);
    }

    @Test
    public void buildRecoveryNamePreservesExistingExtension() {
        RecoveryItem item = imageItem("photo.png");
        assertEquals("photo.png", RecoveryCopier.buildRecoveryName(item));
    }

    @Test
    public void parseCarvedPathExtractsOffset() {
        RecoveryCopier.CarvedSource carved = RecoveryCopier.parseCarvedPath(
                "/storage/emulated/0/MIUI/photo_blob#4096");
        assertEquals("photo_blob", carved.file.getName());
        assertEquals(4096L, carved.offset);
    }

    @Test
    public void parseCarvedPathReturnsNullForRegularPaths() {
        org.junit.Assert.assertNull(RecoveryCopier.parseCarvedPath("/storage/emulated/0/DCIM/a.jpg"));
    }
}
