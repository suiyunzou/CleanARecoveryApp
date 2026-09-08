package com.example.cleanrecovery.recovery;

import com.example.cleanrecovery.experiment.CandidateLabel;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class RecoveryCandidateMapperTest {
    @Test
    public void mapsMediaStoreTrashWithHonestDeletedFlag() {
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .sourceKind(CandidateSourceKind.MEDIASTORE_TRASH)
                .sourceUriOrPath("content://media/external/images/media/42")
                .originalContainer("external_primary:DCIM/trash/photo.jpg")
                .mimeDetected("image/jpeg")
                .byteLength(1024L)
                .width(800)
                .height(600)
                .build();

        RecoveryItem item = RecoveryCandidateMapper.toRecoveryItem(candidate, RecoveryType.IMAGE);

        assertNotNull(item);
        assertEquals("photo.jpg", item.name);
        assertEquals(RecoverySourceKind.MEDIASTORE_TRASH, item.sourceKind);
        assertTrue(item.suspectedDeleted);
    }

    @Test
    public void cacheCandidateDoesNotFakeDeletedStatus() {
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .sourceKind(CandidateSourceKind.GENERIC_THUMBNAIL)
                .sourceUriOrPath("/storage/emulated/0/DCIM/.thumbnails/123")
                .mimeDetected("image/jpeg")
                .byteLength(512L)
                .build();

        RecoveryItem item = RecoveryCandidateMapper.toRecoveryItem(candidate, RecoveryType.IMAGE);

        assertNotNull(item);
        assertEquals(RecoverySourceKind.GENERIC_THUMBNAIL, item.sourceKind);
        assertTrue(item.suspectedDeleted);
    }

    @Test
    public void rejectsCandidateWhenMimeDoesNotMatchRequestedType() {
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .sourceKind(CandidateSourceKind.MEDIASTORE_TRASH)
                .sourceUriOrPath("content://media/external/video/media/7")
                .mimeDetected("video/mp4")
                .build();

        assertNull(RecoveryCandidateMapper.toRecoveryItem(candidate, RecoveryType.IMAGE));
    }

    @Test
    public void mapsCarvedBlobPathAndSourceKind() {
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .sourceKind(CandidateSourceKind.CARVED_FROM_KNOWN_BLOB)
                .sourceUriOrPath("/storage/emulated/0/MIUI/photo_blob#4096")
                .originalContainer("xiaomi_photo_blob")
                .mimeDetected("image/jpeg")
                .byteLength(2048L)
                .build();

        RecoveryItem item = RecoveryCandidateMapper.toRecoveryItem(candidate, RecoveryType.IMAGE);

        assertNotNull(item);
        assertEquals(RecoverySourceKind.CARVED_FROM_KNOWN_BLOB, item.sourceKind);
        assertEquals("/storage/emulated/0/MIUI/photo_blob#4096", item.path);
        assertTrue(item.suspectedDeleted);
    }

    @Test
    public void mapsSourceKindsForProductionLabels() {
        assertEquals(RecoverySourceKind.MEDIASTORE_PENDING,
                RecoveryCandidateMapper.mapSourceKind(CandidateSourceKind.MEDIASTORE_PENDING));
        assertEquals(RecoverySourceKind.OEM_GALLERY_CACHE,
                RecoveryCandidateMapper.mapSourceKind(CandidateSourceKind.OEM_GALLERY_CACHE));
    }

    @Test
    public void accessibleSignatureMatchDoesNotFakeDeletedStatus() {
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .sourceKind(CandidateSourceKind.ACCESSIBLE_SIGNATURE_MATCH)
                .sourceUriOrPath("/storage/emulated/0/LOST.DIR/orphan")
                .mimeDetected("image/jpeg")
                .byteLength(256L)
                .build();

        RecoveryItem item = RecoveryCandidateMapper.toRecoveryItem(candidate, RecoveryType.IMAGE);

        assertNotNull(item);
        assertEquals(RecoverySourceKind.ACCESSIBLE_SIGNATURE_MATCH, item.sourceKind);
        assertTrue(item.suspectedDeleted);
    }

    @Test
    public void staleIndexRecordIsHonestAndNotRecoverable() {
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .sourceKind(CandidateSourceKind.MEDIASTORE_STALE_RECORD)
                .sourceUriOrPath("content://media/external/images/media/99")
                .mimeDetected("image/jpeg")
                .label(CandidateLabel.METADATA_ONLY)
                .build();

        RecoveryItem item = RecoveryCandidateMapper.toRecoveryItem(candidate, RecoveryType.IMAGE);

        assertNotNull(item);
        assertEquals(RecoverySourceKind.MEDIASTORE_STALE_RECORD, item.sourceKind);
        assertTrue(item.suspectedDeleted);
        assertFalse(item.recoverable);
    }

    @Test
    public void logEvidenceOnlyIsNotRecoverable() {
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .sourceKind(CandidateSourceKind.LOG_EVIDENCE_ONLY)
                .sourceUriOrPath("content://media/external/images/media/100")
                .mimeDetected("image/jpeg")
                .build();

        RecoveryItem item = RecoveryCandidateMapper.toRecoveryItem(candidate, RecoveryType.IMAGE);

        assertNotNull(item);
        assertEquals(RecoverySourceKind.LOG_EVIDENCE_ONLY, item.sourceKind);
        assertFalse(item.recoverable);
    }

    @Test
    public void metadataOnlyLabelMarksItemNotRecoverable() {
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .sourceKind(CandidateSourceKind.MEDIASTORE_TRASH)
                .sourceUriOrPath("content://media/external/images/media/101")
                .mimeDetected("image/jpeg")
                .label(CandidateLabel.METADATA_ONLY)
                .build();

        RecoveryItem item = RecoveryCandidateMapper.toRecoveryItem(candidate, RecoveryType.IMAGE);

        assertNotNull(item);
        assertTrue(item.suspectedDeleted);
        assertFalse(item.recoverable);
    }

    @Test
    public void offlinePartitionCandidatesMapToOfflineCarveAndStayRecoverable() {
        assertEquals(RecoverySourceKind.OFFLINE_CARVE,
                RecoveryCandidateMapper.mapSourceKind(CandidateSourceKind.OFFLINE_F2FS_METADATA));
        assertEquals(RecoverySourceKind.OFFLINE_CARVE,
                RecoveryCandidateMapper.mapSourceKind(CandidateSourceKind.OFFLINE_EXT4_JOURNAL));

        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .sourceKind(CandidateSourceKind.OFFLINE_F2FS_METADATA)
                .sourceUriOrPath("/data/data/cache/offline_carve/carved_0.jpg")
                .mimeDetected("image/jpeg")
                .byteLength(4096L)
                .build();

        RecoveryItem item = RecoveryCandidateMapper.toRecoveryItem(candidate, RecoveryType.IMAGE);
        assertNotNull(item);
        assertEquals(RecoverySourceKind.OFFLINE_CARVE, item.sourceKind);
        assertTrue(item.recoverable);
    }

    @Test
    public void trashItemKeepsTimestampsFromIndex() {
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .sourceKind(CandidateSourceKind.MEDIASTORE_TRASH)
                .sourceUriOrPath("content://media/external/images/media/42")
                .originalContainer("external_primary:DCIM/Camera/photo.jpg")
                .mimeDetected("image/jpeg")
                .byteLength(1024L)
                .modifiedAt(1700000000000L)
                .expiresAt(1702592000000L)
                .build();

        RecoveryItem item = RecoveryCandidateMapper.toRecoveryItem(candidate, RecoveryType.IMAGE);

        assertNotNull(item);
        assertEquals(1700000000000L, item.modifiedAt);
        assertEquals(1702592000000L, item.expiresAt);
        assertTrue(item.recoverable);
    }

    @Test
    public void filesCollectionUnknownMimeOnlySurfacesUnderDocumentScan() {
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .sourceKind(CandidateSourceKind.MEDIASTORE_TRASH)
                .sourceUriOrPath("content://media/external/file/77")
                .extractionMethod("mediastore_query:trashed_files")
                .mimeDetected("")
                .byteLength(2048L)
                .build();

        assertNull(RecoveryCandidateMapper.toRecoveryItem(candidate, RecoveryType.IMAGE));

        RecoveryItem document = RecoveryCandidateMapper.toRecoveryItem(candidate, RecoveryType.DOCUMENT);
        assertNotNull(document);
        assertEquals(RecoveryType.DOCUMENT, document.type);
        assertEquals(RecoverySourceKind.MEDIASTORE_TRASH, document.sourceKind);
        assertTrue(document.recoverable);
    }
}
