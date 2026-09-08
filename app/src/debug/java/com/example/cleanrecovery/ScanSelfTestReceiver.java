package com.example.cleanrecovery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.example.cleanrecovery.algorithm.AlgorithmEvent;
import com.example.cleanrecovery.recovery.RecoveryCoordinator;
import com.example.cleanrecovery.recovery.RecoveryItem;
import com.example.cleanrecovery.recovery.RecoveryType;
import com.example.cleanrecovery.recovery.TrashVault;
import com.example.cleanrecovery.scan.ScanDiagnostics;
import com.example.cleanrecovery.scan.ScanProgressTracker;
import com.example.cleanrecovery.storage.StorageAccessController;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Debug-only automation hook for device acceptance runs:
 * <pre>
 *   adb shell am broadcast -a com.example.cleanrecovery.SELF_TEST_SCAN \
 *       [-e mode normal|experimental|vault|vault_restore] [-e autorecover true]
 * </pre>
 * Writes scan/recovery outcome JSON into the app's external files dir so a host
 * script can verify item-level expectations and recovered-byte hashes.
 */
public final class ScanSelfTestReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.example.cleanrecovery.SELF_TEST_SCAN";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_AUTORECOVER = "autorecover";

    private static final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        ScanDiagnostics.selfTestTriggered();
        if (!running.compareAndSet(false, true)) {
            ScanDiagnostics.error("selfTest", "scan already running", null);
            return;
        }
        if (!StorageAccessController.hasStorageAccess(context)) {
            ScanDiagnostics.permissionDenied("selfTest missing storage access");
            running.set(false);
            return;
        }
        final Context appContext = context.getApplicationContext();
        final String mode = intent.getStringExtra(EXTRA_MODE);
        if ("vault".equalsIgnoreCase(mode)) {
            writeVaultReport(appContext, "guard");
            running.set(false);
            return;
        }
        if ("vault_restore".equalsIgnoreCase(mode)) {
            writeVaultReport(appContext, "restore");
            running.set(false);
            return;
        }
        final boolean experimental = "experimental".equalsIgnoreCase(mode);
        // `am broadcast -e` only sets String extras — parse the flag from the string.
        final boolean autorecover = "true".equalsIgnoreCase(intent.getStringExtra(EXTRA_AUTORECOVER))
                || intent.getBooleanExtra(EXTRA_AUTORECOVER, false);
        final CoordinatorCallback callback = new CoordinatorCallback(running, appContext, autorecover);
        final RecoveryCoordinator coordinator = new RecoveryCoordinator(appContext, callback);
        callback.attachCoordinator(coordinator);
        if (experimental) {
            coordinator.startExperimentalScanAll();
        } else {
            coordinator.startScanAll();
        }
    }

    /** Run a vault guard or restore pass synchronously and dump the outcome. */
    private static void writeVaultReport(Context appContext, String action) {
        JSONObject root = new JSONObject();
        try {
            if ("restore".equals(action)) {
                int restored = TrashVault.restoreAll(appContext);
                root.put("action", "restore");
                root.put("restored", restored);
            } else {
                int added = TrashVault.guardPass(appContext);
                root.put("action", "guard");
                root.put("added", added);
            }
            JSONArray entries = new JSONArray();
            for (TrashVault.VaultEntry entry : TrashVault.list(appContext)) {
                JSONObject object = new JSONObject();
                object.put("displayName", entry.displayName);
                object.put("relativePath", entry.relativePath);
                object.put("size", entry.size);
                object.put("sha256", entry.sha256);
                object.put("vaultedAt", entry.vaultedAt);
                entries.put(object);
            }
            root.put("vault", entries);
        } catch (Exception exception) {
            try {
                root.put("error", exception.getMessage());
            } catch (Exception ignored) {
                // report is best-effort
            }
        }
        File outDir = appContext.getExternalFilesDir(null);
        if (outDir == null) {
            outDir = appContext.getFilesDir();
        }
        File out = new File(outDir, "self_test_vault.json");
        try (FileOutputStream output = new FileOutputStream(out);
             OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            writer.write(root.toString(2));
            ScanDiagnostics.trackerEvent("selfTest vaultReport=" + out.getAbsolutePath());
        } catch (Exception exception) {
            ScanDiagnostics.error("selfTest", "vault report write failed", exception);
        }
    }

    private static final class CoordinatorCallback implements RecoveryCoordinator.ScanCallback {
        private final AtomicBoolean running;
        private final Context context;
        private final boolean autorecover;
        private final ArrayList<RecoveryItem> scanned = new ArrayList<>();
        private final ArrayList<RecoveryItem> recovered = new ArrayList<>();
        private final ArrayList<String> failures = new ArrayList<>();
        private RecoveryCoordinator coordinator;

        CoordinatorCallback(AtomicBoolean running, Context context, boolean autorecover) {
            this.running = running;
            this.context = context;
            this.autorecover = autorecover;
        }

        void attachCoordinator(RecoveryCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override
        public void onWorkingChanged(boolean working) {
            ScanDiagnostics.trackerEvent("selfTest working=" + working);
        }

        @Override
        public void onPrepareProgress(int countedSoFar, String currentPath) {
        }

        @Override
        public void onPrepareComplete(int totalEntries) {
        }

        @Override
        public void onScanTypeChanged(RecoveryType type, int typeIndex, int typeCount) {
        }

        @Override
        public void onScanPhaseChanged(ScanProgressTracker.Phase phase) {
        }

        @Override
        public void onPhaseProgress(ScanProgressTracker.Phase phase, int processedCount) {
        }

        @Override
        public void onProgress(int scannedCount, int foundCount, String currentPath) {
        }

        @Override
        public void onAlgorithmEvent(AlgorithmEvent event) {
        }

        @Override
        public void onAlgorithmProgress(String algorithmId, int processed, int found) {
        }

        @Override
        public void onItemsBatch(List<RecoveryItem> items) {
            synchronized (scanned) {
                scanned.addAll(items);
            }
        }

        @Override
        public void onScanComplete(int scannedCount, int foundCount) {
            ScanDiagnostics.trackerEvent("selfTest uiComplete scanned=" + scannedCount + " found=" + foundCount);
            if (autorecover && coordinator != null) {
                ArrayList<RecoveryItem> targets = new ArrayList<>();
                synchronized (scanned) {
                    for (RecoveryItem item : scanned) {
                        if (item.recoverable) {
                            item.selected = true;
                            targets.add(item);
                        }
                    }
                }
                ScanDiagnostics.trackerEvent("selfTest autorecover targets=" + targets.size());
                coordinator.recoverSelected(targets);
                return;
            }
            writeReport(true);
            running.set(false);
        }

        @Override
        public void onRecoverProgress(int successCount, int failedCount) {
        }

        @Override
        public void onRecoverComplete(int successCount, int failedCount, File lastOutput) {
            ScanDiagnostics.trackerEvent("selfTest autorecover done ok=" + successCount + " failed=" + failedCount);
            writeReport(false);
            running.set(false);
        }

        private void writeReport(boolean scanOnly) {
            File outDir = context.getExternalFilesDir(null);
            if (outDir == null) {
                outDir = context.getFilesDir();
            }
            File out = new File(outDir, "self_test_results.json");
            try {
                JSONObject root = new JSONObject();
                root.put("finishedAt", System.currentTimeMillis());
                root.put("sdk", Build.VERSION.SDK_INT);
                root.put("scanOnly", scanOnly);
                JSONArray array = new JSONArray();
                synchronized (scanned) {
                    for (RecoveryItem item : scanned) {
                        JSONObject object = new JSONObject();
                        object.put("name", item.name);
                        object.put("path", item.path);
                        object.put("sourceFilePath", item.sourceFilePath == null ? "" : item.sourceFilePath);
                        object.put("size", item.size);
                        object.put("sourceKind", item.sourceKind.name());
                        object.put("recoverable", item.recoverable);
                        object.put("type", item.type.name());
                        array.put(object);
                    }
                }
                root.put("items", array);
                JSONArray recoveredNames = new JSONArray();
                for (RecoveryItem item : recovered) {
                    recoveredNames.put(item.name);
                }
                root.put("recovered", recoveredNames);
                JSONArray failedNames = new JSONArray();
                for (String failure : failures) {
                    failedNames.put(failure);
                }
                root.put("failures", failedNames);
                try (FileOutputStream output = new FileOutputStream(out);
                     OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                    writer.write(root.toString(2));
                }
                ScanDiagnostics.trackerEvent("selfTest report=" + out.getAbsolutePath());
            } catch (Exception exception) {
                ScanDiagnostics.error("selfTest", "report write failed", exception);
            }
        }
    }
}
