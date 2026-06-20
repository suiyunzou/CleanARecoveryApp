package com.example.cleanrecovery.ui.activity;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.scan.ScanHistoryStore;
import com.example.cleanrecovery.storage.StorageAccessController;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

public final class OnboardingActivity extends Activity {
    private StorageAccessController storageAccessController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_onboarding);
        storageAccessController = new StorageAccessController(this);
        Button grantButton = findViewById(R.id.onboarding_grant_button);
        grantButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ScanHistoryStore.setOnboardingComplete(OnboardingActivity.this);
                storageAccessController.requestStorageAccess();
                setResult(RESULT_OK);
                finish();
            }
        });
    }
}
