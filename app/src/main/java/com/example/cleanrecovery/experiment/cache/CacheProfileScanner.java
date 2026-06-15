package com.example.cleanrecovery.experiment.cache;

import com.example.cleanrecovery.experiment.CandidateLabel;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.experiment.jpeg.JpegBlobCarver;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CacheProfileScanner {
    public interface Callback {
        boolean isCancelled();
        void onCandidate(RecoveryCandidate candidate);
    }

    private final JpegBlobCarver blobCarver = new JpegBlobCarver();

    public List<RecoveryCandidate> scanFile(File file, Callback callback) {
        ArrayList<RecoveryCandidate> results = new ArrayList<>();
        if (file == null || !file.exists() || !file.canRead()) {
            return results;
        }
        CacheProfile profile = CacheProfileRegistry.matchPath(file.getAbsolutePath());
        if (profile == null) {
            return results;
        }
        if ("jpeg_blob_carver".equals(profile.parserId) && file.isFile()) {
            try {
                blobCarver.carve(file, new JpegBlobCarver.Progress() {
                    @Override
                    public void onCandidateFound(RecoveryCandidate candidate) {
                        RecoveryCandidate labeled = relabel(candidate, profile);
                        results.add(labeled);
                        if (callback != null) {
                            callback.onCandidate(labeled);
                        }
                    }

                    @Override
                    public boolean isCancelled() {
                        return callback != null && callback.isCancelled();
                    }
                });
            } catch (Exception ignored) {
                // Keep scanning other readable files.
            }
            return results;
        }
        if (!file.isFile()) {
            return results;
        }
        CacheProfileFileProbe.Result probe = CacheProfileFileProbe.probe(file);
        if (!probe.hasImageSignature()) {
            return results;
        }
        RecoveryCandidate fileCandidate = new RecoveryCandidate.Builder()
                .candidateId(UUID.randomUUID().toString())
                .sourceKind(sourceKindFor(profile))
                .sourceUriOrPath(file.getAbsolutePath())
                .extractionMethod("cache_profile_file_reader")
                .originalContainer(profile.profileId)
                .byteLength(file.length())
                .sha256(probe.sha256)
                .mimeDetected(probe.mimeDetected)
                .decodeStatus(probe.decodeStatus)
                .label(thumbnailLabelFor(profile))
                .build();
        results.add(fileCandidate);
        if (callback != null) {
            callback.onCandidate(fileCandidate);
        }
        return results;
    }

    private static CandidateLabel thumbnailLabelFor(CacheProfile profile) {
        if ("generic_thumbnails".equals(profile.profileId)
                || "samsung_gallery_cache".equals(profile.profileId)
                || "oppo_gallery_cache".equals(profile.profileId)) {
            return CandidateLabel.THUMBNAIL;
        }
        return CandidateLabel.CACHE_COPY;
    }

    private static RecoveryCandidate relabel(RecoveryCandidate candidate, CacheProfile profile) {
        return new RecoveryCandidate.Builder()
                .candidateId(candidate.candidateId.isEmpty() ? UUID.randomUUID().toString() : candidate.candidateId)
                .sourceKind(sourceKindFor(profile))
                .sourceUriOrPath(candidate.sourceUriOrPath)
                .extractionMethod(candidate.extractionMethod)
                .originalContainer(profile.profileId)
                .byteLength(candidate.byteLength)
                .sha256(candidate.sha256)
                .perceptualHash(candidate.perceptualHash)
                .mimeDetected(candidate.mimeDetected)
                .decodeStatus(candidate.decodeStatus)
                .width(candidate.width)
                .height(candidate.height)
                .extractionOffsetStart(candidate.extractionOffsetStart)
                .extractionOffsetEnd(candidate.extractionOffsetEnd)
                .readBytes(candidate.readBytes)
                .elapsedMs(candidate.elapsedMs)
                .label(CandidateLabel.BLOB_EXTRACTED)
                .build();
    }

    private static CandidateSourceKind sourceKindFor(CacheProfile profile) {
        if ("xiaomi_photo_blob".equals(profile.profileId) || "verified_imgcache".equals(profile.profileId)) {
            return CandidateSourceKind.OEM_GALLERY_CACHE;
        }
        if ("generic_thumbnails".equals(profile.profileId)) {
            return CandidateSourceKind.GENERIC_THUMBNAIL;
        }
        if ("samsung_gallery_cache".equals(profile.profileId) || "oppo_gallery_cache".equals(profile.profileId)) {
            return CandidateSourceKind.OEM_GALLERY_CACHE;
        }
        return CandidateSourceKind.KNOWN_CACHE_BLOB;
    }
}
