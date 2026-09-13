package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Private-file transport for script source; only opaque tokens belong in Intent/Bundle. */
public final class BrowserScriptDraftStore {
    private BrowserScriptDraftStore() { }
    private static File file(Context context, String token) throws IOException {
        if (token == null || !token.matches("[a-f0-9-]{36}")) throw new IOException("脚本草稿标识无效");
        File directory = new File(context.getFilesDir(), "userscript-drafts");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建脚本草稿目录");
        return new File(directory, token + ".txt");
    }
    public static String write(Context context, String token, String source) throws IOException {
        if (token == null) token = UUID.randomUUID().toString();
        AtomicFile draft = new AtomicFile(file(context, token));
        FileOutputStream output = null;
        try {
            output = draft.startWrite();
            output.write(source.getBytes(StandardCharsets.UTF_8));
            draft.finishWrite(output);
            return token;
        } catch (IOException error) { draft.failWrite(output); throw error; }
    }
    public static String read(Context context, String token) throws IOException {
        File destination = file(context, token);
        AtomicFile draft = new AtomicFile(destination);
        // Resume pending installations created before drafts moved out of cache.
        if (!destination.exists()) {
            File previous = new File(new File(context.getCacheDir(), "userscript-drafts"), token + ".txt");
            if (previous.exists()) {
                String source = new String(new AtomicFile(previous).readFully(), StandardCharsets.UTF_8);
                write(context, token, source);
                new AtomicFile(previous).delete();
            }
        }
        return new String(draft.readFully(), StandardCharsets.UTF_8);
    }
    public static void delete(Context context, String token) {
        if (token == null) return;
        try {
            new AtomicFile(file(context, token)).delete();
            new AtomicFile(new File(new File(context.getCacheDir(), "userscript-drafts"), token + ".txt")).delete();
        } catch (IOException ignored) { }
    }
}
