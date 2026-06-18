package com.example.cleanrecovery.music.api;

import android.content.Context;
import android.util.Log;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.data.UserInfo;
import com.example.cleanrecovery.music.security.RateLimiter;
import com.example.cleanrecovery.music.security.SecureSessionStore;

import java.util.regex.Pattern;

/**
 * Remote authentication service implementing the full enterprise login flow:
 *
 * <ol>
 *   <li><b>Request code</b> — validates phone format, enforces rate limits,
 *       then calls {@link KugouAuthClient#sendSmsCode} to trigger real SMS
 *       delivery. No code is generated or stored locally.</li>
 *
 *   <li><b>Login</b> — validates inputs, enforces verification rate limits,
 *       calls {@link KugouAuthClient#loginWithCode} to verify server-side.
 *       On success, the encrypted session (tokens + user profile) is persisted
 *       via {@link SecureSessionStore}. On failure, the attempt is recorded
 *       for brute-force protection.</li>
 *
 *   <li><b>Session restore</b> — reads the encrypted session from
 *       {@link SecureSessionStore}. If the access token is expired but the
 *       refresh token is valid, performs an automatic silent refresh.</li>
 *
 *   <li><b>Entitlement refresh</b> — after login, calls
 *       {@link KugouYouthEntitlementClient#init} to sync VIP status from the
 *       Kugou Youth/Concept entitlement gateway.</li>
 *
 *   <li><b>Logout</b> — calls {@link KugouAuthClient#logout} to invalidate
 *       tokens server-side, then clears the local encrypted session and all
 *       rate-limit state for the user's phone.</li>
 * </ol>
 *
 * <h3>Security properties</h3>
 * <ul>
 *   <li><b>No local code generation</b> — verification codes are generated
 *       and validated exclusively on the server.</li>
 *   <li><b>Encrypted session storage</b> — AES-256-GCM via Android Keystore;
 *       the key is hardware-backed and non-exportable.</li>
 *   <li><b>Device binding</b> — a SHA-256 device fingerprint is sent with
 *       every request, enabling server-side anomaly detection.</li>
 *   <li><b>Brute-force protection</b> — rate limits on both code requests
 *       (60s cooldown, 5/hour) and verification attempts (5 max, then
 *       15-minute lockout).</li>
 *   <li><b>Token rotation</b> — short-lived access tokens (1h) with
 *       long-lived refresh tokens (30d); automatic silent refresh.</li>
 *   <li><b>Server-side logout</b> — tokens are invalidated on the server
 *       before local cleanup.</li>
 * </ul>
 */
public class RemoteAuthService implements IAuthService {

    private static final String TAG = "RemoteAuthService";
    private static final Pattern MAINLAND_PHONE = Pattern.compile("^1\\d{10}$");
    private static final Pattern CODE_FORMAT = Pattern.compile("^\\d{4,6}$");

    private final Context appContext;
    private final KugouAuthClient authClient;
    private final KugouYouthEntitlementClient entitlementClient;
    private final SecureSessionStore sessionStore;
    private final RateLimiter rateLimiter;

    private volatile UserInfo current;
    private volatile String currentToken;

    public RemoteAuthService(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.authClient = new KugouAuthClient(appContext);
        this.entitlementClient = new KugouYouthEntitlementClient(appContext);
        this.sessionStore = new SecureSessionStore(appContext);
        this.rateLimiter = new RateLimiter(appContext);
    }

    // ---- IAuthService implementation --------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Remote implementation: triggers server-side SMS delivery.
     * Returns {@code null} — the code is never available on the device.</p>
     */
    @Override
    public String requestLoginCode(String phone) throws Exception {
        String normalized = normalizePhone(phone);
        if (!isValidPhone(normalized)) {
            throw new AuthException(AuthException.Code.INVALID_PHONE,
                    string(R.string.music_login_phone_invalid));
        }

        // Enforce rate limits before hitting the server
        rateLimiter.checkCodeRequestAllowed(normalized);

        try {
            authClient.sendSmsCode(normalized);
            rateLimiter.recordCodeRequest(normalized);
        } catch (AuthException e) {
            // Translate server rate-limit responses to the local limiter's message
            if (e.getCode() == AuthException.Code.RATE_LIMITED) {
                throw new RateLimiter.RateLimitException(e.getMessage());
            }
            throw e;
        }

        // Return null — the code was sent via SMS, not returned to the app
        return null;
    }

    @Override
    public UserInfo login(String phone, String verificationCode) throws Exception {
        String normalized = normalizePhone(phone);
        if (!isValidPhone(normalized)) {
            throw new AuthException(AuthException.Code.INVALID_PHONE,
                    string(R.string.music_login_phone_invalid));
        }
        if (verificationCode == null || verificationCode.trim().isEmpty()) {
            throw new AuthException(AuthException.Code.INVALID_CODE,
                    string(R.string.music_login_code_required));
        }
        if (!CODE_FORMAT.matcher(verificationCode.trim()).matches()) {
            throw new AuthException(AuthException.Code.INVALID_CODE,
                    string(R.string.music_login_code_invalid));
        }

        // Enforce brute-force protection
        rateLimiter.checkVerificationAllowed(normalized);

        KugouAuthClient.AuthTokens tokens;
        try {
            tokens = authClient.loginWithCode(normalized, verificationCode.trim());
        } catch (AuthException e) {
            // Record the failure for rate-limiting purposes
            if (e.getCode() == AuthException.Code.CODE_MISMATCH
                    || e.getCode() == AuthException.Code.CODE_EXPIRED) {
                rateLimiter.recordVerificationFailure(normalized);
            }
            throw e;
        }

        // Success — clear rate-limit state and build the session
        rateLimiter.recordVerificationSuccess(normalized);

        long now = System.currentTimeMillis();
        SecureSessionStore.Session session = new SecureSessionStore.Session();
        session.accessToken = tokens.accessToken;
        session.refreshToken = tokens.refreshToken;
        session.tokenType = tokens.tokenType;
        session.userId = tokens.userId;
        session.nickname = tokens.nickname != null ? tokens.nickname : maskPhone(normalized);
        session.avatarUrl = tokens.avatarUrl;
        session.phone = normalized;
        session.isVip = tokens.isVip;
        session.cookies = tokens.cookies;
        session.dfid = tokens.dfid;
        session.accessExpiresAt = now + tokens.expiresIn * 1000L;
        session.refreshExpiresAt = now + tokens.refreshExpiresIn * 1000L;

        sessionStore.save(session);

        current = new UserInfo(session.userId, session.nickname, session.isVip);
        current.avatarUrl = session.avatarUrl;
        currentToken = session.accessToken;

        // Sync VIP entitlement from the Kugou Youth gateway
        try {
            return refreshEntitlement();
        } catch (Exception e) {
            Log.w(TAG, "Entitlement refresh failed after login", e);
            return current;
        }
    }

    @Override
    public UserInfo restoreSession() throws Exception {
        SecureSessionStore.Session session = sessionStore.restore();
        if (session == null) {
            current = null;
            currentToken = null;
            return null;
        }

        // If the refresh token has expired, the session is unrecoverable
        if (!session.isRefreshable()) {
            sessionStore.clear();
            current = null;
            currentToken = null;
            return null;
        }

        // If the access token is expired, attempt a silent refresh
        if (session.isAccessExpired()) {
            try {
                session = doTokenRefresh(session);
            } catch (AuthException e) {
                if (e.getCode() == AuthException.Code.SESSION_INVALID) {
                    sessionStore.clear();
                    current = null;
                    currentToken = null;
                    return null;
                }
                // For network errors, fall through with the expired token —
                // the user can still see their profile; API calls will fail gracefully
                Log.w(TAG, "Silent token refresh failed", e);
            }
        }

        current = new UserInfo(session.userId, session.nickname, session.isVip);
        current.avatarUrl = session.avatarUrl;
        currentToken = session.accessToken;
        return current;
    }

    @Override
    public UserInfo refreshEntitlement() throws Exception {
        Log.d(TAG, "refreshEntitlement start");
        SecureSessionStore.Session session = sessionStore.restore();
        if (session == null) {
            Log.d(TAG, "refreshEntitlement: no session");
            return null;
        }
        Log.d(TAG, "refreshEntitlement: session userId=" + session.userId
                + " token=" + (session.accessToken != null ? session.accessToken.substring(0, Math.min(16, session.accessToken.length())) + "..." : "null")
                + " dfid=" + session.dfid);

        // Ensure we have a valid access token
        if (session.isAccessExpired() && session.isRefreshable()) {
            try {
                session = doTokenRefresh(session);
            } catch (Exception e) {
                Log.w(TAG, "Token refresh before entitlement failed", e);
            }
        }

        // Fetch the latest user profile from the server
        if (session.accessToken != null && !session.isAccessExpired()) {
            try {
                KugouAuthClient.UserProfile profile = authClient.getUserProfile(session.accessToken);
                // 只在 profile 字段非空时才覆盖，避免空 profile 清空 session 中的有效数据
                if (profile.userId != null && !profile.userId.isEmpty()) {
                    session.userId = profile.userId;
                }
                if (profile.nickname != null && !profile.nickname.isEmpty()) {
                    session.nickname = profile.nickname;
                }
                if (profile.avatarUrl != null && !profile.avatarUrl.isEmpty()) {
                    session.avatarUrl = profile.avatarUrl;
                }
                // profile.isVip 只在显式为 true 时才更新，避免空 profile 清除 VIP 状态
                if (profile.isVip) {
                    session.isVip = profile.isVip;
                }
            } catch (AuthException e) {
                Log.w(TAG, "Profile fetch failed, continuing with cached data", e);
            }
        }

        // Sync VIP entitlement from the Kugou Youth gateway
        try {
            // 使用与 KuGouMusicApi 一致的调用方式：token + userid + mid + dfid 作为 query 参数
            String mid = com.example.cleanrecovery.music.security.DeviceFingerprint.getMid(appContext);
            String dfid = (session.dfid != null && !session.dfid.isEmpty()) ? session.dfid : "-";
            KugouYouthEntitlementClient.Entitlement entitlement =
                    entitlementClient.claimDailyVip(session.accessToken, session.userId, mid, dfid);
            if (entitlement.effectiveVip()) {
                session.isVip = true;
            }
            if (entitlement.userId != null && !entitlement.userId.isEmpty()) {
                session.userId = entitlement.userId;
            }
            if (entitlement.nickname != null && !entitlement.nickname.isEmpty()) {
                session.nickname = entitlement.nickname;
            }
        } catch (Exception e) {
            Log.w(TAG, "Entitlement init failed", e);
        }

        sessionStore.save(session);
        current = new UserInfo(session.userId, session.nickname, session.isVip);
        current.avatarUrl = session.avatarUrl;
        currentToken = session.accessToken;
        return current;
    }

    @Override
    public void logout() {
        SecureSessionStore.Session session = sessionStore.restore();
        if (session != null && session.accessToken != null) {
            try {
                authClient.logout(session.accessToken);
            } catch (AuthException e) {
                Log.w(TAG, "Server logout failed (best-effort)", e);
            }
            if (session.phone != null) {
                rateLimiter.clear(session.phone);
            }
        }
        sessionStore.clear();
        current = null;
        currentToken = null;
    }

    @Override
    public boolean isLoggedIn() {
        if (current != null && currentToken != null) return true;
        SecureSessionStore.Session s = sessionStore.restore();
        return s != null && s.isRefreshable();
    }

    @Override
    public boolean hasVip() {
        if (current != null) return current.isVip;
        SecureSessionStore.Session s = sessionStore.restore();
        return s != null && s.isVip;
    }

    @Override
    public UserInfo currentUser() {
        if (current != null) return current;
        try {
            return restoreSession();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getToken() {
        if (currentToken != null) return currentToken;
        SecureSessionStore.Session s = sessionStore.restore();
        return s != null ? s.accessToken : null;
    }

    // ---- Internal helpers --------------------------------------------------

    /**
     * Exchange the refresh token for a new access token, persisting the
     * updated session. Returns the refreshed session.
     */
    private SecureSessionStore.Session doTokenRefresh(SecureSessionStore.Session session)
            throws AuthException {
        KugouAuthClient.AuthTokens tokens = authClient.refreshToken(session.refreshToken);

        long now = System.currentTimeMillis();
        session.accessToken = tokens.accessToken;
        if (tokens.refreshToken != null && !tokens.refreshToken.isEmpty()) {
            session.refreshToken = tokens.refreshToken;
        }
        session.tokenType = tokens.tokenType;
        session.accessExpiresAt = now + tokens.expiresIn * 1000L;
        session.refreshExpiresAt = now + tokens.refreshExpiresIn * 1000L;

        try {
            sessionStore.save(session);
        } catch (Exception e) {
            // Persistence failure is non-fatal — the in-memory session remains
            // valid for this process lifetime; it just won't survive a restart.
            Log.w(TAG, "Failed to persist refreshed session", e);
        }
        currentToken = session.accessToken;
        return session;
    }

    // ---- Validation utilities ---------------------------------------------

    private static String normalizePhone(String phone) {
        return phone == null ? "" : phone.replace(" ", "").replace("-", "").trim();
    }

    private static boolean isValidPhone(String phone) {
        return MAINLAND_PHONE.matcher(phone).matches();
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) return "Phone user";
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /**
     * 构造酷狗概念版 Youth Gateway 所需的 Cookie。
     *
     * <p>酷狗登录态 Cookie 主要包含：
     * <ul>
     *   <li>kg_mid: 设备 MID（必填）</li>
     *   <li>kg_u_list: 用户标识（基于 userid）</li>
     *   <li>musicwenji: 用户 token（部分接口需要）</li>
     * </ul>
     * 这里使用 accessToken 作为 musicwenji，userid 作为 kg_u_list。</p>
     */
    private String buildKugouCookie(SecureSessionStore.Session session) {
        if (session == null) return "";
        StringBuilder sb = new StringBuilder();
        // kg_mid: 设备标识
        String mid = com.example.cleanrecovery.music.security.DeviceFingerprint
                .getMid(appContext);
        if (mid != null && !mid.isEmpty()) {
            sb.append("kg_mid=").append(mid).append("; ");
        }
        // kg_u_list: 用户标识
        if (session.userId != null && !session.userId.isEmpty()) {
            sb.append("kg_u_list=").append(session.userId).append("; ");
        }
        // musicwenji: 登录 token
        if (session.accessToken != null && !session.accessToken.isEmpty()) {
            sb.append("musicwenji=").append(session.accessToken).append("; ");
        }
        // vip_type: 占位
        sb.append("vip_type=0");
        return sb.toString();
    }

    private String string(int resId) {
        return appContext.getString(resId);
    }
}
