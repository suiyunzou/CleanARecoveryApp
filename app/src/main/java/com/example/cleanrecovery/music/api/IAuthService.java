package com.example.cleanrecovery.music.api;

import com.example.cleanrecovery.music.data.UserInfo;

/** Auth service abstraction. Default impl uses a phone-verified local profile. */
public interface IAuthService {

    /** Request a phone verification code. Local builds return the generated code for on-device testing. */
    String requestLoginCode(String phone) throws Exception;

    /** Log in with the given phone and verification code, then refresh entitlements. */
    UserInfo login(String phone, String verificationCode) throws Exception;

    /** Restore a previously-saved login session. */
    UserInfo restoreSession() throws Exception;

    /** Refresh startup entitlement from the authorized Kugou Youth/Concept init flow. */
    UserInfo refreshEntitlement() throws Exception;

    /** Log out and clear stored credentials. */
    void logout();

    /** Whether a logged-in session currently exists. */
    boolean isLoggedIn();

    /** Whether the current session has VIP privileges. */
    boolean hasVip();

    /** Get current user info (null when not logged in). */
    UserInfo currentUser();

    /** Call after init to retrieve cached token. */
    String getToken();

    /** Current user id (null when not logged in). */
    String getUserId();

    /** Device fingerprint id used at login time (null when not logged in). */
    String getDfid();
}
