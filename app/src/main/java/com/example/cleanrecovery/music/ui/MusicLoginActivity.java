package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.api.AuthException;
import com.example.cleanrecovery.music.data.UserInfo;
import com.example.cleanrecovery.music.security.RateLimiter;

import java.util.concurrent.Executors;

public final class MusicLoginActivity extends Activity {

    private static final long CODE_COOLDOWN_MS = 60_000L;
    private static final long COUNTDOWN_INTERVAL_MS = 1_000L;

    private MusicApp app;
    private View loginForm, loginInfo;
    private EditText accountInput, passwordInput;
    private Button submitButton, logoutButton, codeButton;
    private TextView userName;
    private CountDownTimer codeCountdown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_music_login);
        app = MusicApp.init(this);

        loginForm = findViewById(R.id.login_form);
        loginInfo = findViewById(R.id.login_info);
        accountInput = findViewById(R.id.login_account);
        passwordInput = findViewById(R.id.login_password);
        submitButton = findViewById(R.id.login_submit_button);
        codeButton = findViewById(R.id.login_code_button);
        userName = findViewById(R.id.login_user_name);
        logoutButton = findViewById(R.id.login_logout_button);

        findViewById(R.id.login_back_button).setOnClickListener(v -> finish());

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                UserInfo u = app.auth.restoreSession();
                if (u != null) runOnUiThread(this::showLoggedIn);
            } catch (Exception ignored) {}
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (app.auth.isLoggedIn()) showLoggedIn(); else showLoginForm();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (codeCountdown != null) {
            codeCountdown.cancel();
            codeCountdown = null;
        }
    }

    private void showLoginForm() {
        loginForm.setVisibility(View.VISIBLE);
        loginInfo.setVisibility(View.GONE);

        codeButton.setOnClickListener(v -> {
            String phone = accountInput.getText().toString().trim();
            if (TextUtils.isEmpty(phone)) {
                Toast.makeText(this, R.string.music_login_phone_required, Toast.LENGTH_SHORT).show();
                return;
            }
            requestCode(phone);
        });

        submitButton.setOnClickListener(v -> {
            String phone = accountInput.getText().toString().trim();
            String code = passwordInput.getText().toString().trim();
            if (TextUtils.isEmpty(phone)) {
                Toast.makeText(this, R.string.music_login_phone_required, Toast.LENGTH_SHORT).show();
                return;
            }
            if (TextUtils.isEmpty(code)) {
                Toast.makeText(this, R.string.music_login_code_required, Toast.LENGTH_SHORT).show();
                return;
            }
            performLogin(phone, code);
        });
    }

    /**
     * Request the server to send an SMS verification code. The code is never
     * available on the device — the user must read it from their SMS inbox.
     * A 60-second countdown timer disables the button to prevent spam.
     */
    private void requestCode(String phone) {
        codeButton.setEnabled(false);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                app.auth.requestLoginCode(phone);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.music_login_sms_sent, Toast.LENGTH_SHORT).show();
                    startCodeCountdown();
                });
            } catch (RateLimiter.RateLimitException e) {
                runOnUiThread(() -> {
                    codeButton.setEnabled(true);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            } catch (AuthException e) {
                runOnUiThread(() -> {
                    codeButton.setEnabled(true);
                    String msg = authErrorMessage(e);
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    codeButton.setEnabled(true);
                    Toast.makeText(this,
                            getString(R.string.music_login_code_send_failed, e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** Verify the SMS code with the server and complete login. */
    private void performLogin(String phone, String code) {
        submitButton.setEnabled(false);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                app.auth.login(phone, code);
                // Sync VIP entitlement and push auth context to data source
                // so VIP songs can be resolved via the concept gateway.
                app.refreshEntitlementAsync();
                runOnUiThread(() -> {
                    submitButton.setEnabled(true);
                    showLoggedIn();
                });
            } catch (RateLimiter.RateLimitException e) {
                runOnUiThread(() -> {
                    submitButton.setEnabled(true);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } catch (AuthException e) {
                runOnUiThread(() -> {
                    submitButton.setEnabled(true);
                    Toast.makeText(this,
                            getString(R.string.music_login_failed, authErrorMessage(e)),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    submitButton.setEnabled(true);
                    Toast.makeText(this,
                            getString(R.string.music_login_failed, e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** Start the 60-second countdown on the "Get code" button. */
    private void startCodeCountdown() {
        if (codeCountdown != null) codeCountdown.cancel();
        codeButton.setEnabled(false);
        codeCountdown = new CountDownTimer(CODE_COOLDOWN_MS, COUNTDOWN_INTERVAL_MS) {
            @Override
            public void onTick(long millisUntilFinished) {
                int seconds = (int) (millisUntilFinished / 1000);
                codeButton.setText(getString(R.string.music_login_code_resend, seconds));
            }

            @Override
            public void onFinish() {
                codeButton.setEnabled(true);
                codeButton.setText(R.string.music_login_code);
            }
        }.start();
    }

    /** Map an AuthException to a user-facing message. */
    private String authErrorMessage(AuthException e) {
        switch (e.getCode()) {
            case INVALID_PHONE:
                return getString(R.string.music_login_phone_invalid);
            case INVALID_CODE:
                return getString(R.string.music_login_code_invalid);
            case CODE_MISMATCH:
                return getString(R.string.music_login_code_mismatch);
            case CODE_EXPIRED:
                return getString(R.string.music_login_code_expired);
            case RATE_LIMITED:
            case LOCKED_OUT:
                return getString(R.string.music_login_locked);
            case SMS_SEND_FAILED:
                return getString(R.string.music_login_sms_failed);
            case PHONE_NOT_ALLOWED:
                return getString(R.string.music_login_phone_not_allowed);
            case DEVICE_VERIFICATION_REQUIRED:
                return getString(R.string.music_login_device_verification_required);
            case NETWORK_ERROR:
                return getString(R.string.music_login_network_error);
            case TOKEN_EXPIRED:
            case SESSION_INVALID:
                return getString(R.string.music_login_session_invalid);
            case SERVER_ERROR:
                return getString(R.string.music_login_server_error);
            default:
                return e.getMessage() != null ? e.getMessage() : getString(R.string.music_login_unknown_error);
        }
    }

    private void showLoggedIn() {
        loginForm.setVisibility(View.GONE);
        loginInfo.setVisibility(View.VISIBLE);
        UserInfo u = app.auth.currentUser();
        if (u != null) userName.setText(getString(
                u.isVip ? R.string.music_login_user_vip : R.string.music_login_user,
                u.displayName()));
        logoutButton.setOnClickListener(v -> {
            Executors.newSingleThreadExecutor().execute(() -> {
                app.auth.logout();
                runOnUiThread(this::showLoginForm);
            });
        });
    }
}
