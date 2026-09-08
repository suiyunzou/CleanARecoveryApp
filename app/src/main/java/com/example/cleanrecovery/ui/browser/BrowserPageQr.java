package com.example.cleanrecovery.ui.browser;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.OutputStream;

public final class BrowserPageQr {
    public static Bitmap encode(String url, int size) throws com.google.zxing.WriterException {
        return new com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(url,
                com.google.zxing.BarcodeFormat.QR_CODE, size, size,
                java.util.Collections.singletonMap(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8"));
    }

    public static Uri save(Context context, Bitmap bitmap) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "web_qr_" + System.currentTimeMillis() + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Via");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Cannot create QR image");
        try {
            try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Cannot write QR image");
            }
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (context.getContentResolver().update(uri, values, null, null) != 1) throw new IOException("Cannot publish QR image");
            }
            return uri;
        } catch (IOException | RuntimeException error) {
            context.getContentResolver().delete(uri, null, null);
            throw error;
        }
    }
}
