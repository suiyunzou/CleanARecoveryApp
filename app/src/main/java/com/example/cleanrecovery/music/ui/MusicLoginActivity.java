package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.SystemUiHelper;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.UserInfo;

import java.util.concurrent.Executors;

public final class MusicLoginActivity extends Activity {

    private MusicApp app;
    private View loginForm, loginInfo;
    private EditText accountInput, passwordInput;
    private Button submitButton, logoutButton;
    private TextView userName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_music_login);
        app = MusicApp.get();

        loginForm = findViewById(R.id.login_form);
        loginInfo = findViewById(R.id.login_info);
        accountInput = findViewById(R.id.login_account);
        passwordInput = findViewById(R.id.login_password);
        submitButton = findViewById(R.id.login_submit_button);
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

    private void showLoginForm() {
        loginForm.setVisibility(View.VISIBLE);
        loginInfo.setVisibility(View.GONE);
        submitButton.setOnClickListener(v -> {
            String acct = accountInput.getText().toString().trim();
            String pwd = passwordInput.getText().toString().trim();
            if (TextUtils.isEmpty(acct)) { Toast.makeText(this, "Enter account", Toast.LENGTH_SHORT).show(); return; }
            submitButton.setEnabled(false);
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    app.auth.login(acct, pwd);
                    runOnUiThread(() -> { submitButton.setEnabled(true); showLoggedIn(); });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        submitButton.setEnabled(true);
                        Toast.makeText(this, "Login failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });
    }

    private void showLoggedIn() {
        loginForm.setVisibility(View.GONE);
        loginInfo.setVisibility(View.VISIBLE);
        UserInfo u = app.auth.currentUser();
        if (u != null) userName.setText(u.displayName() + (u.isVip ? " · VIP" : ""));
        logoutButton.setOnClickListener(v -> {
            app.auth.logout();
            showLoginForm();
        });
    }
}
