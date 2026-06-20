package com.example.cleanrecovery.ui.activity;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.recovery.RecoveryOutputPaths;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;

public final class RecoverCompleteActivity extends Activity {
    public static final String EXTRA_SUCCESS = "com.example.cleanrecovery.extra.SUCCESS";
    public static final String EXTRA_FAILED = "com.example.cleanrecovery.extra.FAILED";
    public static final String EXTRA_OUTPUT_PATH = "com.example.cleanrecovery.extra.OUTPUT_PATH";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_recover_complete);

        int success = getIntent().getIntExtra(EXTRA_SUCCESS, 0);
        int failed = getIntent().getIntExtra(EXTRA_FAILED, 0);
        String outputPathExtra = getIntent().getStringExtra(EXTRA_OUTPUT_PATH);
        final String outputPath = (outputPathExtra == null || outputPathExtra.isEmpty())
                ? RecoveryOutputPaths.primaryDisplayPath()
                : outputPathExtra;

        TextView summary = findViewById(R.id.recover_complete_summary);
        summary.setText(getString(R.string.recover_done_status, success, failed, ""));

        TextView pathView = findViewById(R.id.recover_complete_path);
        pathView.setText(getString(R.string.last_output, outputPath));

        Button openFolder = findViewById(R.id.open_folder_button);
        openFolder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FileBrowserActivity.open(RecoverCompleteActivity.this, new File(outputPath));
            }
        });

        Button backResults = findViewById(R.id.back_results_button);
        backResults.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
    }
}
