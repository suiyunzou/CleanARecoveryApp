package com.example.cleanrecovery.recovery;

import com.example.cleanrecovery.R;

import android.os.Environment;

public enum RecoveryType {
    IMAGE(R.string.type_images, Environment.DIRECTORY_PICTURES),
    VIDEO(R.string.type_videos, Environment.DIRECTORY_MOVIES),
    AUDIO(R.string.type_audio, Environment.DIRECTORY_MUSIC),
    DOCUMENT(R.string.type_documents, Environment.DIRECTORY_DOCUMENTS);

    public final int labelResId;
    public final String publicDirectory;

    RecoveryType(int labelResId, String publicDirectory) {
        this.labelResId = labelResId;
        this.publicDirectory = publicDirectory;
    }

    public static RecoveryType[] scannableValues() {
        return values();
    }

    public static int scannableCount() {
        return values().length;
    }
}
