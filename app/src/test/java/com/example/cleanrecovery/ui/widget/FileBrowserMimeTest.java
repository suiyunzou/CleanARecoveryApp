package com.example.cleanrecovery.ui.widget;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class FileBrowserMimeTest {
    @Test
    public void detectsCommonExtensions() {
        assertEquals(FileBrowserMime.Kind.APK, FileBrowserMime.kindForExtension("apk"));
        assertEquals(FileBrowserMime.Kind.PDF, FileBrowserMime.kindForExtension("pdf"));
        assertEquals(FileBrowserMime.Kind.ZIP, FileBrowserMime.kindForExtension("zip"));
        assertEquals(FileBrowserMime.Kind.DOC, FileBrowserMime.kindForExtension("docx"));
        assertEquals(FileBrowserMime.Kind.XLS, FileBrowserMime.kindForExtension("xlsx"));
        assertEquals(FileBrowserMime.Kind.PPT, FileBrowserMime.kindForExtension("pptx"));
        assertEquals(FileBrowserMime.Kind.VIDEO, FileBrowserMime.kindForExtension("mp4"));
        assertEquals(FileBrowserMime.Kind.IMAGE, FileBrowserMime.kindForExtension("jpg"));
        assertEquals(FileBrowserMime.Kind.AUDIO, FileBrowserMime.kindForExtension("mp3"));
        assertEquals(FileBrowserMime.Kind.TEXT, FileBrowserMime.kindForExtension("txt"));
        assertEquals(FileBrowserMime.Kind.UNKNOWN, FileBrowserMime.kindForExtension("bin"));
    }

    @Test
    public void apkMimeTypeIsPackageArchive() {
        assertEquals(
                "application/vnd.android.package-archive",
                FileBrowserMime.mimeTypeFor("sample.apk")
        );
    }

    @Test
    public void pdfMimeTypeIsApplicationPdf() {
        assertEquals("application/pdf", FileBrowserMime.mimeTypeFor("sample.pdf"));
    }
}
