package com.example.cleanrecovery.recovery;

public enum RecoverySourceKind {
    VISIBLE_SHARED_FILE,
    MEDIASTORE_TRASH,
    MEDIASTORE_PENDING,
    /** MediaStore row whose backing data no longer exists — index only, not recoverable. */
    MEDIASTORE_STALE_RECORD,
    GENERIC_THUMBNAIL,
    OEM_GALLERY_CACHE,
    KNOWN_CACHE_BLOB,
    CARVED_FROM_KNOWN_BLOB,
    ACCESSIBLE_SIGNATURE_MATCH,
    /** Evidence from scan logs/metadata only; no bytes were ever recovered. */
    LOG_EVIDENCE_ONLY,
    /** Candidates carved from an offline (root) partition image stream. */
    OFFLINE_CARVE
}
