package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** Bidirectional WebDAV merge used by manual and launch-time sync. */
public final class BrowserWebdavSynchronizer {
    private static final String REMOTE = "via-backup.zip.b64";

    private BrowserWebdavSynchronizer() { }

    public static BrowserWebdav.Result sync(Context context) {
        BrowserPrefs prefs = new BrowserPrefs(context);
        int mask = prefs.webdavSyncData();
        if (prefs.webdavUrl().trim().isEmpty() || mask == 0)
            return new BrowserWebdav.Result(false, "WebDAV 未配置", -1);
        try {
            BrowserWebdav.Result remote = BrowserWebdav.get(prefs.webdavUrl(), prefs.webdavUser(),
                    prefs.webdavPass(), REMOTE);
            if (remote.ok && remote.body != null && !remote.body.trim().isEmpty()) {
                byte[] archive = Base64.decode(remote.body.trim(), Base64.DEFAULT);
                BrowserBackupManager.importBackup(context, new ByteArrayInputStream(archive));
            } else if (remote.code != 404) {
                return remote;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            BrowserBackupManager.exportBackup(context, output, mask);
            return BrowserWebdav.put(prefs.webdavUrl(), prefs.webdavUser(), prefs.webdavPass(),
                    REMOTE, Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
        } catch (Exception error) {
            return new BrowserWebdav.Result(false, String.valueOf(error.getMessage()), -1);
        }
    }
}
