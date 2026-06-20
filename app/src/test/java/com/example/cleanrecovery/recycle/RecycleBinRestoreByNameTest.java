package com.example.cleanrecovery.recycle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * 文件管理 V2：RecycleBin.restoreByNameSync 单元测试。
 * 验证 Snackbar 撤销机制所依赖的"按名恢复"逻辑。
 */
public class RecycleBinRestoreByNameTest {

    private File trashRoot;
    private File workspace;
    private RecycleBin bin;

    @Before
    public void setUp() throws IOException {
        workspace = Files.createTempDirectory("rb_workspace").toFile();
        trashRoot = new File(workspace, "trash");
        bin = new RecycleBin(trashRoot,
                RecycleBin.DEFAULT_RETENTION_MILLIS,
                RecycleBin.DEFAULT_MAX_BYTES);
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursively(workspace);
    }

    @Test
    public void restoreByNameSync_returnsRestoredFile_whenNameMatches() throws IOException {
        File original = new File(workspace, "photo.jpg");
        Files.write(original.toPath(), new byte[]{1, 2, 3});
        assertTrue(bin.moveToTrashSync(original));
        assertFalse(original.exists());

        File restored = bin.restoreByNameSync("photo.jpg");

        assertNotNull(restored);
        assertTrue(restored.exists());
        assertEquals("photo.jpg", restored.getName());
        assertEquals(3, restored.length());
    }

    @Test
    public void restoreByNameSync_returnsNull_whenNoMatchingEntry() throws IOException {
        File original = new File(workspace, "a.txt");
        Files.write(original.toPath(), new byte[]{1});
        assertTrue(bin.moveToTrashSync(original));

        File restored = bin.restoreByNameSync("nonexistent.txt");

        assertNull(restored);
    }

    @Test
    public void restoreByNameSync_restoresFirstMatch_whenMultipleSameName() throws IOException {
        File first = new File(workspace, "dup.txt");
        Files.write(first.toPath(), new byte[]{1});
        assertTrue(bin.moveToTrashSync(first));

        File second = new File(workspace, "dup.txt");
        Files.write(second.toPath(), new byte[]{1, 2});
        assertTrue(bin.moveToTrashSync(second));

        File restored = bin.restoreByNameSync("dup.txt");

        assertNotNull(restored);
        assertTrue(restored.exists());
        // 至少恢复一个文件，原文件名保持
        assertEquals("dup.txt", restored.getName());
    }

    @Test
    public void restoreByNameSync_returnsNull_forNullName() throws IOException {
        File restored = bin.restoreByNameSync(null);
        assertNull(restored);
    }

    @Test
    public void restoreByNameSync_clearsTrashEntry_afterRestore() throws IOException {
        File original = new File(workspace, "doc.pdf");
        Files.write(original.toPath(), new byte[]{9, 8, 7, 6});
        assertTrue(bin.moveToTrashSync(original));

        assertNotNull(bin.restoreByNameSync("doc.pdf"));

        // 二次恢复应返回 null（条目已被清理）
        assertNull(bin.restoreByNameSync("doc.pdf"));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
