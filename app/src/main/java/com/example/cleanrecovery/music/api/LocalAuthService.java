package com.example.cleanrecovery.music.api;

import android.content.Context;
import android.content.SharedPreferences;
import com.example.cleanrecovery.music.data.UserInfo;
import java.util.UUID;

/** Local auth implementation — stores session in SharedPreferences. */
public class LocalAuthService implements IAuthService {

    private static final String PREFS = "music_auth";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_NICKNAME = "nickname";
    private static final String KEY_VIP = "is_vip";
    private static final String KEY_TOKEN = "token";

    private final SharedPreferences prefs;
    private UserInfo current;

    public LocalAuthService(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public UserInfo login(String account, String password) throws Exception {
        // Stub: local-only login. Replace with real server auth later.
        String userId = "local_" + UUID.randomUUID().toString().substring(0, 8);
        String nickname = account.contains("@") ? account.split("@")[0] : account;
        boolean isVip = false; // Guest login never grants VIP

        UserInfo u = new UserInfo(userId, nickname, isVip);
        saveSession(u);
        current = u;
        return u;
    }

    @Override
    public UserInfo restoreSession() throws Exception {
        String userId = prefs.getString(KEY_USER_ID, null);
        if (userId == null) return null;
        UserInfo u = new UserInfo(
                userId,
                prefs.getString(KEY_NICKNAME, "Guest"),
                prefs.getBoolean(KEY_VIP, false)
        );
        current = u;
        return u;
    }

    @Override
    public void logout() {
        prefs.edit().clear().apply();
        current = null;
    }

    @Override
    public boolean isLoggedIn() {
        return current != null;
    }

    @Override
    public UserInfo currentUser() {
        return current;
    }

    @Override
    public String getToken() {
        String token = prefs.getString(KEY_TOKEN, null);
        if (token == null) {
            token = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_TOKEN, token).apply();
        }
        return token;
    }

    private void saveSession(UserInfo u) {
        prefs.edit()
                .putString(KEY_USER_ID, u.userId)
                .putString(KEY_NICKNAME, u.nickname)
                .putBoolean(KEY_VIP, u.isVip)
                .putString(KEY_TOKEN, UUID.randomUUID().toString())
                .apply();
    }
}
