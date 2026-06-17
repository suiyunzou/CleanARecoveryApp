package com.example.cleanrecovery.music.api;

import com.example.cleanrecovery.music.data.UserInfo;

/** Auth service abstraction. Default impl uses a local guest profile. */
public interface IAuthService {

    /** Log in with the given account and return user info. */
    UserInfo login(String account, String password) throws Exception;

    /** Restore a previously-saved login session. */
    UserInfo restoreSession() throws Exception;

    /** Log out and clear stored credentials. */
    void logout();

    /** Whether a logged-in session currently exists. */
    boolean isLoggedIn();

    /** Get current user info (null when not logged in). */
    UserInfo currentUser();

    /** Call after init to retrieve cached token. */
    String getToken();
}
