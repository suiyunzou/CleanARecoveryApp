package com.example.cleanrecovery;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.experiment.cache.CacheProfileScanner;
import com.example.cleanrecovery.experiment.mediastore.MediaStoreExperimentScanner;
import com.example.cleanrecovery.experiment.mediastore.MediaStoreExperimentScanner.Callback;

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
        void onScanPhaseChanged(ScanProgressTracker.Phase phase);
        void onPhaseProgress(ScanProgressTracker.Phase phase, int processedCount);
        void onProgress(int scannedCount, int foundCount, String currentPath);
        void onItemsBatch(List<RecoveryItem> items);
        void onResultCapReached();
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

    public RecoveryCoordinator(Context context, ScanCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void shutdown() {
        cancelled.set(true);
        executor.shutdownNow();
    }

    public void cancelCurrentWork() {
        cancelled.set(true);
    }

    public void startScan(final RecoveryType type) {
        cancelled.set(false);
        progressThrottler.reset();
        callback.onWorkingChanged(true);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final RecoveryDeduper deduper = new RecoveryDeduper();
                final ArrayList<RecoveryItem> pendingBatch = new ArrayList<>();
                final ScanSession session = new ScanSession();
                ScanProgressTracker.Phase currentPhase = ScanProgressTracker.Phase.FILE_SCAN;

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
                        // Keep counting other readable directories.
                    }
                });
                if (cancelled.get()) {
                    postWorkingChanged(false);
                    return;
                }
                awaitPrepareComplete(totalEntries);
                currentPhase = ScanProgressTracker.Phase.FILE_SCAN;
                postScanPhase(currentPhase);

                final ScanProgressTracker.Phase fileScanPhase = currentPhase;
                scanner.scan(type, new RecoveryScanner.Callback() {
                    @Override
                    public boolean isCancelled() {
                        return cancelled.get() || session.atCap();
                    }

                    @Override
                    public void onProgress(int scannedCount, int foundCount, String currentPath) {
                        session.scannedCount = scannedCount;
                        long now = System.currentTimeMillis();
                        if (!progressThrottler.shouldUpdate(now)) {
                            return;
                        }
                        postProgress(session.scannedCount, session.foundCount, currentPath);
                    }

                    @Override
                    public void onItemFound(RecoveryItem item) {
                        acceptItem(item, deduper, pendingBatch, session, fileScanPhase);
                    }

                    @Override
                    public void onDone(int scannedCount, int foundCount) {
                        session.scannedCount = scannedCount;
                    }

                    @Override
                    public void onError(File file, Exception exception) {
                        // Keep scanning other readable directories.
                    }
                });

                if (!cancelled.get() && !session.atCap() && type == RecoveryType.IMAGE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    currentPhase = ScanProgressTracker.Phase.MEDIASTORE;
                    runMediaStorePhase(type, deduper, pendingBatch, session, currentPhase);
                }
                if (!cancelled.get() && !session.atCap() && type == RecoveryType.IMAGE) {
                    currentPhase = ScanProgressTracker.Phase.CACHE;
                    runCachePhase(type, deduper, pendingBatch, session, currentPhase);
                }

                flushBatch(pendingBatch);
                if (session.atCap()) {
                    postResultCapReached();
                }
                final int finalScanned = session.scannedCount;
                final int finalFound = session.foundCount;
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

    private void runMediaStorePhase(
            RecoveryType type,
            RecoveryDeduper deduper,
            ArrayList<RecoveryItem> pendingBatch,
            ScanSession session,
            ScanProgressTracker.Phase phase
    ) {
        postScanPhase(phase);
        MediaStoreExperimentScanner mediaStoreScanner = new MediaStoreExperimentScanner(context);
        mediaStoreScanner.scan(new Callback() {
            @Override
            public boolean isCancelled() {
                return cancelled.get() || session.atCap();
            }

            @Override
            public void onCandidate(RecoveryCandidate candidate) {
                RecoveryItem item = RecoveryCandidateMapper.toRecoveryItem(candidate, type);
                if (item == null) {
                    return;
                }
                acceptItem(item, deduper, pendingBatch, session, phase);
                long now = System.currentTimeMillis();
                if (progressThrottler.shouldUpdate(now)) {
                    postProgress(session.scannedCount, session.foundCount, item.path);
                }
            }

            @Override
            public void onProgress(String message) {
                // MediaStore cursor progress is surfaced via candidate count.
            }
        });
    }

    private void runCachePhase(
            RecoveryType type,
            RecoveryDeduper deduper,
            ArrayList<RecoveryItem> pendingBatch,
            ScanSession session,
            ScanProgressTracker.Phase phase
    ) {
        postScanPhase(phase);
        final CacheProfileScanner cacheScanner = new CacheProfileScanner();
        final CacheProfilePathWalker walker = new CacheProfilePathWalker();
        walker.walk(new CacheProfilePathWalker.Callback() {
            @Override
            public boolean isCancelled() {
                return cancelled.get() || session.atCap();
            }

            @Override
            public void onProfileFile(File file, int scannedSoFar) {
                cacheScanner.scanFile(file, new CacheProfileScanner.Callback() {
                    @Override
                    public boolean isCancelled() {
                        return cancelled.get() || session.atCap();
                    }

                    @Override
                    public void onCandidate(RecoveryCandidate candidate) {
                        RecoveryItem item = RecoveryCandidateMapper.toRecoveryItem(candidate, type);
                        if (item == null) {
                            return;
                        }
                        acceptItem(item, deduper, pendingBatch, session, phase);
                    }
                });
                session.cacheFilesScanned = scannedSoFar;
                postPhaseProgress(phase, scannedSoFar);
                long now = System.currentTimeMillis();
                if (progressThrottler.shouldUpdate(now)) {
                    postProgress(session.scannedCount, session.foundCount, file.getAbsolutePath());
                }
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
        if (session.atCap() || deduper.isDuplicate(item)) {
            return;
        }
        session.foundCount++;
        if (sourcePhase == ScanProgressTracker.Phase.MEDIASTORE) {
            session.mediastoreProcessed++;
            postPhaseProgress(sourcePhase, session.mediastoreProcessed);
        }
        pendingBatch.add(item);
        if (pendingBatch.size() >= ScanLimits.ITEM_BATCH_SIZE) {
            flushBatch(pendingBatch);
        }
        if (session.atCap()) {
            cancelled.set(true);
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

    private void postProgress(final int scannedCount, final int foundCount, final String currentPath) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onProgress(scannedCount, foundCount, currentPath);
            }
        });
    }

    private void postResultCapReached() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onResultCapReached();
            }
        });
    }

    static final class ScanSession {
        int scannedCount;
        int foundCount;
        int mediastoreProcessed;
        int cacheFilesScanned;

        boolean atCap() {
            return ScanLimits.isAtCap(foundCount);
        }
    }
}
