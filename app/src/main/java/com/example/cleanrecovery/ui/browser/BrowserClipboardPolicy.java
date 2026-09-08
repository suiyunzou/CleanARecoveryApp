package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import com.example.cleanrecovery.R;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Via's write/copy policy; clipboard reads retain the WebView's native permissions. */
final class BrowserClipboardPolicy {
    private final String script;

    BrowserClipboardPolicy(Context context) {
        try (InputStream input = context.getAssets().open("browser_clipboard.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            script = new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Missing bundled clipboard script", error);
        }
    }

    String script(Context context, String mode) {
        return "(function(mode,message){" + script + "})(" + mode + ","
                + JSONObject.quote(context.getString(R.string.via_clipboard_copy_confirm)) + ");";
    }
}
