package com.example.cleanrecovery.music.security;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Rate limiter for authentication operations, providing brute-force protection
 * with exponential backoff and temporary lockout.
 *
 * <p>Tracks two dimensions per phone number:
 * <ul>
 *   <li><b>Code requests</b> — max 1 per 60s, max 5 per hour</li>
 *   <li><b>Verification attempts</b> — max 5 per code, then 15-minute lockout</li>
 * </ul></p>
 */
public final class RateLimiter {

    private static final String PREFS = "music_rate_limit";

    private static final long CODE_COOLDOWN_MS = 60_000L;       // 60 seconds between code requests
    private static final int  MAX_CODE_REQUESTS_PER_HOUR = 5;
    private static final long HOUR_MS = 3_600_000L;

    private static final int  MAX_VERIFY_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 900_000L;   // 15 minutes

    private final SharedPreferences prefs;

    public RateLimiter(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- Code request rate limiting ----------------------------------------

    /**
     * Check if a code can be requested for the given phone.
     * @throws RateLimitException if the request would exceed limits
     */
    public void checkCodeRequestAllowed(String phone) throws RateLimitException {
        long now = System.currentTimeMillis();
        String keyCooldown = "code_cd_" + phone;
        String keyHour = "code_hr_" + phone;

        long lastRequest = prefs.getLong(keyCooldown, 0);
        if (now - lastRequest < CODE_COOLDOWN_MS) {
            long remaining = (CODE_COOLDOWN_MS - (now - lastRequest)) / 1000;
            throw new RateLimitException("Please wait " + remaining + "s before requesting another code");
        }

        long hourStart = prefs.getLong(keyHour + "_start", 0);
        int hourCount = prefs.getInt(keyHour + "_count", 0);
        if (hourStart == 0 || now - hourStart > HOUR_MS) {
            // Reset the hourly window
            hourStart = now;
            hourCount = 0;
        }
        if (hourCount >= MAX_CODE_REQUESTS_PER_HOUR) {
            throw new RateLimitException("Too many code requests. Please try again later.");
        }
    }

    /** Record that a code was requested for the given phone. */
    public void recordCodeRequest(String phone) {
        long now = System.currentTimeMillis();
        String keyCooldown = "code_cd_" + phone;
        String keyHour = "code_hr_" + phone;

        long hourStart = prefs.getLong(keyHour + "_start", 0);
        int hourCount = prefs.getInt(keyHour + "_count", 0);
        if (hourStart == 0 || now - hourStart > HOUR_MS) {
            hourStart = now;
            hourCount = 0;
        }

        prefs.edit()
                .putLong(keyCooldown, now)
                .putLong(keyHour + "_start", hourStart)
                .putInt(keyHour + "_count", hourCount + 1)
                .apply();
    }

    /** Returns the remaining cooldown in milliseconds, or 0 if a request is allowed now. */
    public long getCodeRequestCooldownRemaining(String phone) {
        long now = System.currentTimeMillis();
        long lastRequest = prefs.getLong("code_cd_" + phone, 0);
        long remaining = CODE_COOLDOWN_MS - (now - lastRequest);
        return Math.max(0, remaining);
    }

    // ---- Verification attempt rate limiting --------------------------------

    /**
     * Check if a verification attempt is allowed for the given phone.
     * @throws RateLimitException if locked out
     */
    public void checkVerificationAllowed(String phone) throws RateLimitException {
        long now = System.currentTimeMillis();
        String keyAttempts = "verify_attempts_" + phone;
        String keyLockout = "verify_lockout_" + phone;

        long lockoutUntil = prefs.getLong(keyLockout, 0);
        if (lockoutUntil > now) {
            long remaining = (lockoutUntil - now) / 1000;
            throw new RateLimitException("Account locked. Try again in " + remaining + "s");
        }

        int attempts = prefs.getInt(keyAttempts, 0);
        if (attempts >= MAX_VERIFY_ATTEMPTS) {
            prefs.edit().putLong(keyLockout, now + LOCKOUT_DURATION_MS).apply();
            prefs.edit().remove(keyAttempts).apply();
            throw new RateLimitException("Too many failed attempts. Locked for 15 minutes.");
        }
    }

    /** Record a failed verification attempt for the given phone. */
    public void recordVerificationFailure(String phone) {
        String keyAttempts = "verify_attempts_" + phone;
        int attempts = prefs.getInt(keyAttempts, 0) + 1;
        prefs.edit().putInt(keyAttempts, attempts).apply();

        if (attempts >= MAX_VERIFY_ATTEMPTS) {
            prefs.edit().putLong("verify_lockout_" + phone,
                    System.currentTimeMillis() + LOCKOUT_DURATION_MS).apply();
            prefs.edit().remove(keyAttempts).apply();
        }
    }

    /** Record a successful verification, clearing all failure counters for the phone. */
    public void recordVerificationSuccess(String phone) {
        prefs.edit()
                .remove("verify_attempts_" + phone)
                .remove("verify_lockout_" + phone)
                .apply();
    }

    /** Clear all rate-limit state for a phone (e.g., on successful login). */
    public void clear(String phone) {
        prefs.edit()
                .remove("code_cd_" + phone)
                .remove("code_hr_" + phone)
                .remove("code_hr_" + phone + "_start")
                .remove("code_hr_" + phone + "_count")
                .remove("verify_attempts_" + phone)
                .remove("verify_lockout_" + phone)
                .apply();
    }

    /** Thrown when a rate limit is exceeded. */
    public static class RateLimitException extends Exception {
        public RateLimitException(String message) { super(message); }
    }
}
