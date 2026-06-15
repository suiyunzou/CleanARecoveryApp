package com.example.cleanrecovery;

public enum JunkType {
    LOGS(R.string.junk_type_logs),
    TEMP(R.string.junk_type_temp),
    THUMBNAILS(R.string.junk_type_thumbnails),
    APP_CACHE(R.string.junk_type_app_cache),
    AD_ANALYTICS(R.string.junk_type_ad_analytics),
    APK(R.string.junk_type_apk),
    EMPTY(R.string.junk_type_empty),
    RESIDUE(R.string.junk_type_residue),
    RECYCLE_BIN(R.string.junk_type_recycle_bin),
    APP_MEDIA(R.string.junk_type_app_media);

    public final int labelResId;

    JunkType(int labelResId) {
        this.labelResId = labelResId;
    }
}
