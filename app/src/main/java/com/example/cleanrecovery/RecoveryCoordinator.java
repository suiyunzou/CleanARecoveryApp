package com.example.cleanrecovery;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.cleanrecovery.algorithm.AlgorithmContext;
import com.example.cleanrecovery.algorithm.AlgorithmEvent;
import com.example.cleanrecovery.algorithm.AlgorithmRunner;
import com.example.cleanrecovery.algorithm.CacheProfileAlgorithm;
import com.example.cleanrecovery.algorithm.MediaStoreIndexTrashAlgorithm;
import com.example.cleanrecovery.algorithm.ScanMode;
import com.example.cleanrecovery.algorithm.SystemTrashScannerAlgorithm;
import com.example.cleanrecovery.algorithm.WechatDirectoryScannerAlgorithm;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecoveryCoordinator {
    public interface ScanCallback {
        void onWorkingChanged(boolean working);
        void onPrepareProgress(int countedSoFar, String currentPath);
        void onPrepareComplete(int totalEntries);
        void onScanTypeChanged(RecoveryType type, int typeIndex, int typeCount);
        void onScanPhaseChanged(ScanProgressTracker.Phase phase);
        void onPhaseProgress(ScanProgressTracker.Phase phase, int processedCount);
        void onProgress(int scannedCount, int foundCount, String currentPath);
        void onAlgorithmEvent(AlgorithmEvent event);
        void onAlgorithmProgress(String algorithmId, int processed, int found);
        void onItemsBatch(List<RecoveryItem> items);
        void onScanComplete(int scannedCount, int foundCount);
        void onRecoverProgress(int successCount, int failedCount);
        void onRecoverComplete(int successCount, int failedCount, File lastOutput);
    }

    private final Context context;
    private final ScanCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final ProgressThrottler progressThrottler = new ProgressThrottler(ScanLimits.PROGRESS_MIN_INTERVAL_MS);
    private final AlgorithmRunner algorithmRunner;

    RecoveryCoordinator(Context context, ScanCallback callback, AlgorithmRunner algorithmRunner) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.algorithmRunner = algorithmRunner;
    }

    public RecoveryCoordinator(Context context, ScanCallback callback) {
        this(context, callback, new AlgorithmRunner());
    }

    public void shutdown() {
        cancelled.set(true);
        executor.shutdownNow();
    }

    public void cancelCurrentWork() {
        cancelled.set(true);
    }

    public void startScan(final RecoveryType type) {
        startScanInternal(new RecoveryType[] {type}, false, ScanMode.DEFAULT);
    }

    public void startExperimentalScan(final RecoveryType type) {
        startScanInternal(new RecoveryType[] {type}, false, ScanMode.EXPERIMENTAL_ALL);
    }

    public void startScanAll() {
        startScanInternal(RecoveryType.scannableValues(), true, ScanMode.DEFAULT);
    }

    private void startScanInternal(
            final RecoveryType[] types,
            final boolean scanAll,
            final ScanMode scanMode
    ) {
        cancelled.set(false);
        progressThrottler.reset();
        callback.onWorkingChanged(true);
        ScanDiagnostics.scanStart(scanAll, types.length);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final long scanStartedAt = System.currentTimeMillis();
                final RecoveryDeduper deduper = new RecoveryDeduper();
                final ArrayList<RecoveryItem> pendingBatch = new ArrayList<>();
                final ScanSession session = new ScanSession();

                RecoveryScanner scanner = new RecoveryScanner();
                final int totalEntries = scanner.countEntries(new RecoveryScanner.PrepareCallback() {
                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }

                    @Override
                    public void onPrepareProgress(int countedSoFar, String currentPath) {
                        postPrepareProgress(countedSoFar, currentPath);
                    }

                    @Override
                    public void onError(File file, Exception exception) {
                        ScanDiagnostics.error("prepare", file.getAbsolutePath(), exception);
                    }
                });
                ScanDiagnostics.prepareComplete(totalEntries);
                if (totalEntries <= 0 && !StorageAccessController.hasStorageAccess(context)) {
                    ScanDiagnostics.permissionDenied("prepare totalEntries=0");
                }
                if (cancelled.get()) {
                    postWorkingChanged(false);
                    return;
                }
                awaitPrepareComplete(totalEntries);

                for (int typeIndex = 0; typeIndex < types.length; typeIndex++) {
                    if (cancelled.get()) {
                        break;
                    }
                    RecoveryType type = types[typeIndex];
                    if (scanAll) {
                        ScanDiagnostics.typeStart(type, typeIndex, types.length);
                        awaitScanTypeChanged(type, typeIndex, types.length);
                    }
                    runTypeScan(type, deduper, pendingBatch, session, scanMode);
                }

                flushBatch(pendingBatch);
                final int finalScanned = session.cumulativeScannedCount();
                final int finalFound = session.foundCount;
                ScanDiagnostics.scanComplete(
                        finalScanned,
                        finalFound,
                        deduper.getDuplicateCount(),
                        session.sourceVisible,
                        session.sourceMediastore,
                        session.sourceCache,
                        session.suspectedDeleted,
                        System.currentTimeMillis() - scanStartedAt
                );
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onWorkingChanged(false);
                        callback.onScanComplete(finalScanned, finalFound);
                    }
                });
            }
        });
    }

    private void runTypeScan(
            final RecoveryType type,
            final RecoveryDeduper deduper,
            final ArrayList<RecoveryItem> pendingBatch,
            final ScanSession session,
            final ScanMode scanMode
    ) {
        final AlgorithmContext algorithmContext = new AlgorithmContext(context, type);
        final PhaseTracker phaseTracker = new PhaseTracker(type);
        final String[] currentAlgorithmId = {null};
        final int[] foundAtAlgorithmStart = {0};
        algorithmRunner.run(scanMode, type, algorithmContext, new AlgorithmRunner.Delegate() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public ScanProgressTracker.Phase phaseForAlgorithm(String algorithmId) {
                if (MediaStoreIndexTrashAlgorithm.ID.equals(algorithmId)) {
                    return ScanProgressTracker.Phase.MEDIASTORE;
                }
                if (CacheProfileAlgorithm.ID.equals(algorithmId)) {
                    return ScanProgressTracker.Phase.CACHE;
                }
                if (SystemTrashScannerAlgorithm.ID.equals(algorithmId)) {
                    return ScanProgressTracker.Phase.TRASH_SCAN;
                }
                if (WechatDirectoryScannerAlgorithm.ID.equals(algorithmId)) {
                    return ScanProgressTracker.Phase.WECHAT_SCAN;
                }
                return ScanProgressTracker.Phase.FILE_SCAN;
            }

            @Override
            public void onAlgorithmPhase(ScanProgressTracker.Phase phase) {
                phaseTracker.beginPhase(phase, session);
                postScanPhase(phase);
            }

            @Override
            public void onCandidate(RecoveryCandidate candidate, ScanProgressTracker.Phase phase) {
                RecoveryItem item = RecoveryCandidateMapper.toRecoveryItem(candidate, type);
                if (item == null) {
                    return;
                }
                acceptItem(item, deduper, pendingBatch, session, phase);
                long now = System.currentTimeMillis();
                if (progressThrottler.shouldUpdate(now)) {
                    postProgress(session.cumulativeScannedCount(), session.foundCount, item.path);
                }
            }

            @Override
            public void onProgress(int processed, String currentPath, ScanProgressTracker.Phase phase) {
                phaseTracker.updateProcessed(processed);
                if (currentAlgorithmId[0] != null && scanMode == ScanMode.EXPERIMENTAL_ALL) {
                    postAlgorithmProgress(
                            currentAlgorithmId[0],
                            processed,
                            session.foundCount - foundAtAlgorithmStart[0]
                    );
                }
                if (phase == ScanProgressTracker.Phase.FILE_SCAN) {
                    session.scannedCount = processed;
                } else if (phase == ScanProgressTracker.Phase.MEDIASTORE) {
                    session.mediastoreProcessed = processed;
                    postPhaseProgress(phase, processed);
                } else if (phase == ScanProgressTracker.Phase.CACHE) {
                    session.cacheFilesScanned = processed;
                    postPhaseProgress(phase, processed);
                }
                long now = System.currentTimeMillis();
                if (progressThrottler.shouldUpdate(now)) {
                    ScanDiagnostics.phaseProgress(phase, processed, session.foundCount);
                    postProgress(session.cumulativeScannedCount(), session.foundCount, currentPath);
                }
            }

            @Override
            public int getDuplicateCount() {
                return deduper.getDuplicateCount();
            }

            @Override
            public void onAlgorithmEvent(AlgorithmEvent event) {
                if (event.kind == AlgorithmEvent.Kind.ALGORITHM_START) {
                    currentAlgorithmId[0] = event.algorithmId;
                    foundAtAlgorithmStart[0] = session.foundCount;
                } else if (event.kind == AlgorithmEvent.Kind.ALGORITHM_END
                        || event.kind == AlgorithmEvent.Kind.ALGORITHM_SKIPPED
                        || event.kind == AlgorithmEvent.Kind.ALGORITHM_ERROR) {
                    currentAlgorithmId[0] = null;
                }
                if (scanMode == ScanMode.EXPERIMENTAL_ALL) {
                    postAlgorithmEvent(event);
                }
            }
        });
        phaseTracker.closeOpenPhase(session);
        session.completedTypeScannedCount += session.scannedCount;
        session.scannedCount = 0;
    }

    private static long logPhaseStart(ScanProgressTracker.Phase phase, RecoveryType type) {
        ScanDiagnostics.phaseStart(phase, type);
        return System.currentTimeMillis();
    }

    private static void logPhaseEnd(
            ScanProgressTracker.Phase phase,
            RecoveryType type,
            long startedAt,
            int scanned,
            int foundInPhase
    ) {
        ScanDiagnostics.phaseEnd(phase, type, System.currentTimeMillis() - startedAt, scanned, foundInPhase);
    }

    public void recoverSelected(final List<RecoveryItem> selected) {
        cancelled.set(false);
        callback.onWorkingChanged(true);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                int success = 0;
                int failed = 0;
                File lastOutput = null;
                for (RecoveryItem item : selected) {
                    if (cancelled.get()) {
                        break;
                    }
                    try {
                        lastOutput = RecoveryCopier.copyToRecoveryDirectory(context, item);
                        success++;
                    } catch (Exception exception) {
                        failed++;
                    }
                    final int progressSuccess = success;
                    final int progressFailed = failed;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onRecoverProgress(progressSuccess, progressFailed);
                        }
                    });
                }

                final int finalSuccess = success;
                final int finalFailed = failed;
                final File output = lastOutput;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onWorkingChanged(false);
                        callback.onRecoverComplete(finalSuccess, finalFailed, output);
                    }
                });
            }
        });
    }

    private void acceptItem(
            RecoveryItem item,
            RecoveryDeduper deduper,
            ArrayList<RecoveryItem> pendingBatch,
            ScanSession session,
            ScanProgressTracker.Phase sourcePhase
    ) {
        if (deduper.isDuplicate(item)) {
            return;
        }
        session.foundCount++;
        session.recordSource(item);
        if (sourcePhase == ScanProgressTracker.Phase.MEDIASTORE) {
            session.mediastoreProcessed++;
            postPhaseProgress(sourcePhase, session.mediastoreProcessed);
        }
        pendingBatch.add(item);
        if (pendingBatch.size() >= ScanLimits.ITEM_BATCH_SIZE) {
            flushBatch(pendingBatch);
        }
    }

    private void flushBatch(final ArrayList<RecoveryItem> pendingBatch) {
        if (pendingBatch.isEmpty()) {
            return;
        }
        final ArrayList<RecoveryItem> batch = new ArrayList<>(pendingBatch);
        pendingBatch.clear();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onItemsBatch(batch);
            }
        });
    }

    private void awaitPrepareComplete(final int totalEntries) {
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onPrepareComplete(totalEntries);
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void postPrepareProgress(final int countedSoFar, final String currentPath) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onPrepareProgress(countedSoFar, currentPath);
            }
        });
    }

    private void awaitScanTypeChanged(final RecoveryType type, final int typeIndex, final int typeCount) {
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onScanTypeChanged(type, typeIndex, typeCount);
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void postScanPhase(final ScanProgressTracker.Phase phase) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onScanPhaseChanged(phase);
            }
        });
    }

    private void postPhaseProgress(final ScanProgressTracker.Phase phase, final int processedCount) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onPhaseProgress(phase, processedCount);
            }
        });
    }

    private void postWorkingChanged(final boolean working) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onWorkingChanged(working);
            }
        });
    }

    private void postAlgorithmEvent(final AlgorithmEvent event) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onAlgorithmEvent(event);
            }
        });
    }

    private void postAlgorithmProgress(final String algorithmId, final int processed, final int found) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onAlgorithmProgress(algorithmId, processed, found);
            }
        });
    }

    private void postProgress(final int scannedCount, final int foundCount, final String currentPath) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onProgress(scannedCount, foundCount, currentPath);
            }
        });
    }

    private final class PhaseTracker {
        private final RecoveryType type;
        private ScanProgressTracker.Phase openPhase;
        private long phaseStartedAt;
        private int foundAtPhaseStart;
        private int processedAtPhaseEnd;

        PhaseTracker(RecoveryType type) {
            this.type = type;
        }

        void beginPhase(ScanProgressTracker.Phase phase, ScanSession session) {
            closeOpenPhase(session);
            openPhase = phase;
            phaseStartedAt = logPhaseStart(phase, type);
            foundAtPhaseStart = session.foundCount;
            processedAtPhaseEnd = 0;
        }

        void updateProcessed(int processed) {
            processedAtPhaseEnd = processed;
        }

        void closeOpenPhase(ScanSession session) {
            if (openPhase == null) {
                return;
            }
            int foundInPhase = session == null ? 0 : session.foundCount - foundAtPhaseStart;
            logPhaseEnd(openPhase, type, phaseStartedAt, processedAtPhaseEnd, foundInPhase);
            openPhase = null;
        }
    }

    static final class ScanSession {
        int scannedCount;
        int completedTypeScannedCount;
        int foundCount;
        int mediastoreProcessed;
        int cacheFilesScanned;
        int sourceVisible;
        int sourceMediastore;
        int sourceCache;
        int suspectedDeleted;

        int cumulativeScannedCount() {
            return completedTypeScannedCount + scannedCount;
        }

        void recordSource(RecoveryItem item) {
            if (item.suspectedDeleted) {
                suspectedDeleted++;
            }
            RecoverySourceKind kind = item.sourceKind;
            if (kind == RecoverySourceKind.VISIBLE_SHARED_FILE
                    || kind == RecoverySourceKind.ACCESSIBLE_SIGNATURE_MATCH) {
                sourceVisible++;
            } else if (kind == RecoverySourceKind.MEDIASTORE_TRASH
                    || kind == RecoverySourceKind.MEDIASTORE_PENDING) {
                sourceMediastore++;
            } else {
                sourceCache++;
            }
        }
    }
}
