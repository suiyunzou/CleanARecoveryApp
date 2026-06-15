package com.example.cleanrecovery;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public final class TextViewerActivity extends Activity {
    public static final String EXTRA_PATH = "com.example.cleanrecovery.extra.TEXT_PATH";
    public static final String EXTRA_NAME = "com.example.cleanrecovery.extra.TEXT_NAME";
    private static final int MAX_PREVIEW_BYTES = 512 * 1024;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_text_viewer);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (path == null) {
            finish();
            return;
        }
        if (name == null || name.isEmpty()) {
            name = new File(path).getName();
        }

        TextView title = findViewById(R.id.text_viewer_title);
        TextView content = findViewById(R.id.text_viewer_content);
        title.setText(name);

        ImageButton backButton = findViewById(R.id.text_viewer_back);
        backButton.setOnClickListener(v -> finish());

        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            Toast.makeText(this, R.string.preview_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        try {
            content.setText(readPreview(file));
        } catch (Exception exception) {
            Toast.makeText(this, R.string.text_viewer_read_error, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    public static void open(Activity activity, File file) {
        Intent intent = new Intent(activity, TextViewerActivity.class);
        intent.putExtra(EXTRA_PATH, file.getAbsolutePath());
        intent.putExtra(EXTRA_NAME, file.getName());
        activity.startActivity(intent);
    }

    private static String readPreview(File file) throws java.io.IOException {
        long length = file.length();
        int readBytes = (int) Math.min(length, MAX_PREVIEW_BYTES);
        byte[] buffer = new byte[readBytes];
        int totalRead = 0;
        try (FileInputStream inputStream = new FileInputStream(file)) {
            int read;
            while (totalRead < readBytes && (read = inputStream.read(buffer, totalRead, readBytes - totalRead)) >= 0) {
                totalRead += read;
            }
        }
        String text = new String(buffer, 0, totalRead, StandardCharsets.UTF_8);
        if (length > MAX_PREVIEW_BYTES) {
            text += "\n\n…";
        }
        return text;
    }
}
