package com.example.cleanrecovery.music.api;

/**
 * Typed authentication exception with a machine-readable error code,
 * enabling the UI layer to present appropriate messages and flows.
 */
public class AuthException extends Exception {

    public enum Code {
        /** Phone number format is invalid. */
        INVALID_PHONE,
        /** Verification code format is invalid. */
        INVALID_CODE,
        /** The verification code was rejected by the server. */
        CODE_MISMATCH,
        /** The verification code has expired. */
        CODE_EXPIRED,
        /** Too many requests — rate limited. */
        RATE_LIMITED,
        /** Account is temporarily locked due to repeated failures. */
        LOCKED_OUT,
        /** The access token has expired and must be refreshed. */
        TOKEN_EXPIRED,
        /** The refresh token is invalid — user must re-authenticate. */
        SESSION_INVALID,
        /** Network connectivity issue. */
        NETWORK_ERROR,
        /** Server returned an unexpected response. */
        SERVER_ERROR,
        /** SMS delivery failed on the server side. */
        SMS_SEND_FAILED,
        /** Phone number is not registered or not allowed. */
        PHONE_NOT_ALLOWED,
        /** Device verification required before sending SMS. */
        DEVICE_VERIFICATION_REQUIRED,
        /** Any other unspecified error. */
        UNKNOWN
    }

    private final Code code;

    public AuthException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public AuthException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
