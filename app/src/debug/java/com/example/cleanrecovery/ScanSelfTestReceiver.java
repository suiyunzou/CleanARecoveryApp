package com.example.cleanrecovery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ScanSelfTestReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.example.cleanrecovery.SELF_TEST_SCAN";

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
        final RecoveryCoordinator coordinator = new RecoveryCoordinator(appContext, new LoggingScanCallback(running));
        coordinator.startScanAll();
    }

    private static final class LoggingScanCallback implements RecoveryCoordinator.ScanCallback {
        private final AtomicBoolean running;

        LoggingScanCallback(AtomicBoolean running) {
            this.running = running;
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
        public void onItemsBatch(java.util.List<RecoveryItem> items) {
        }

        @Override
        public void onScanComplete(int scannedCount, int foundCount) {
            ScanDiagnostics.trackerEvent("selfTest uiComplete scanned=" + scannedCount + " found=" + foundCount);
            running.set(false);
        }

        @Override
        public void onRecoverProgress(int successCount, int failedCount) {
        }

        @Override
        public void onRecoverComplete(int successCount, int failedCount, java.io.File lastOutput) {
        }
    }
}
