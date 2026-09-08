package com.example.cleanrecovery.ui.activity;

/** VIA 风格书签/历史/离线页面三栏页，默认打开离线页面。 */
public final class BrowserOfflinePagesActivity extends BrowserLibraryBaseActivity {
    @Override protected int initialMode() { return MODE_OFFLINE; }
}
