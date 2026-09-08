package com.example.cleanrecovery.algorithm.carve;

import com.example.cleanrecovery.algorithm.FileSignatureProbe;
import com.example.cleanrecovery.experiment.jpeg.JpegBlobCarver;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.recovery.RecoveryType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Multi-format signature carver (PhotoRec / BreadCrumb style): scan raw bytes for
 * known magics and bound each hit with a format-specific end detector.
 *
 * <p>JPEG reuses {@link JpegBlobCarver}; other formats are implemented here.
 * When {@code typeFilter} is non-null, only that {@link RecoveryType} is emitted.
 */
public final class SignatureCarver {
    public interface Progress {
        boolean isCancelled();
    }

    private static final int MAX_HITS_PER_WINDOW = 256;
    private static final int MAX_PNG_BYTES = 32 * 1024 * 1024;
    private static final int MAX_GIF_BYTES = 32 * 1024 * 1024;
    private static final int MAX_PDF_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ZIP_BYTES = 64 * 1024 * 1024;
    private static final int MAX_MP4_BYTES = 256 * 1024 * 1024;
    private static final int MAX_AVI_BYTES = 256 * 1024 * 1024;
    private static final int MAX_MKV_BYTES = 64 * 1024 * 1024;
    private static final int MAX_OGG_BYTES = 64 * 1024 * 1024;
    private static final int MAX_FLAC_BYTES = 64 * 1024 * 1024;
    private static final int MAX_AMR_BYTES = 16 * 1024 * 1024;
    private static final int MAX_BMP_BYTES = 64 * 1024 * 1024;
    private static final int MAX_WEBP_BYTES = 32 * 1024 * 1024;
    private static final int MAX_HEIF_BYTES = 64 * 1024 * 1024;

    private final JpegBlobCarver jpegCarver = new JpegBlobCarver();

    public List<CarvedHit> carveWindow(byte[] data, RecoveryType typeFilter, Progress progress) {
        if (data == null || data.length < 4) {
            return Collections.emptyList();
        }
        ArrayList<CarvedHit> hits = new ArrayList<>();
        boolean wantImage = typeFilter == null || typeFilter == RecoveryType.IMAGE;
        boolean wantVideo = typeFilter == null || typeFilter == RecoveryType.VIDEO;
        boolean wantAudio = typeFilter == null || typeFilter == RecoveryType.AUDIO;
        boolean wantDoc = typeFilter == null || typeFilter == RecoveryType.DOCUMENT;

        if (wantImage) {
            carveJpeg(data, hits, progress);
            scanForMagic(data, hits, progress, new Magics() {
                @Override
                boolean matches(byte[] buf, int at) {
                    return at + 8 <= buf.length && FileSignatureProbe.matchesPng(slice(buf, at, 8));
                }

                @Override
                CarvedHit bound(byte[] buf, int at) {
                    return boundPng(buf, at);
                }
            });
            scanForMagic(data, hits, progress, new Magics() {
                @Override
                boolean matches(byte[] buf, int at) {
                    return at + 6 <= buf.length && FileSignatureProbe.matchesGif(slice(buf, at, 6));
                }

                @Override
                CarvedHit bound(byte[] buf, int at) {
                    return boundGif(buf, at);
                }
            });
            scanForMagic(data, hits, progress, new Magics() {
                @Override
                boolean matches(byte[] buf, int at) {
                    return at + 12 <= buf.length && FileSignatureProbe.matchesWebp(slice(buf, at, 12));
                }

                @Override
                CarvedHit bound(byte[] buf, int at) {
                    return boundRiffSized(buf, at, RecoveryType.IMAGE, "image/webp", "webp", MAX_WEBP_BYTES);
                }
            });
            scanForMagic(data, hits, progress, new Magics() {
                @Override
                boolean matches(byte[] buf, int at) {
                    return at + 14 <= buf.length && FileSignatureProbe.matchesBmp(slice(buf, at, 2));
                }

                @Override
                CarvedHit bound(byte[] buf, int at) {
                    return boundBmp(buf, at);
                }
            });
            scanForMagic(data, hits, progress, new Magics() {
                @Override
                boolean matches(byte[] buf, int at) {
                    return at + 12 <= buf.length && FileSignatureProbe.matchesHeif(slice(buf, at, 12));
                }

                @Override
                CarvedHit bound(byte[] buf, int at) {
                    return boundIsoBmff(buf, at, RecoveryType.IMAGE, "image/heif", "heic", MAX_HEIF_BYTES);
                }
            });
        }
        if (wantVideo) {
            scanForMagic(data, hits, progress, new Magics() {
                @Override
                boolean matches(byte[] buf, int at) {
                    return at + 8 <= buf.length && FileSignatureProbe.matchesFtyp(slice(buf, at, 8))
                            && !FileSignatureProbe.matchesHeif(slice(buf, at, Math.min(12, buf.length - at)));
                }

                @Override
                CarvedHit bound(byte[] buf, int at) {
                    return boundIsoBmff(buf, at, RecoveryType.VIDEO, "video/mp4", "mp4", MAX_MP4_BYTES);
                }
            });
            scanForMagic(data, hits, progress, new Magics() {
                @Override
                boolean matches(byte[] buf, int at) {
                    return at + 12 <= buf.length && FileSignatureProbe.matchesRiff(slice(buf, at, 12));
                }

                @Override
                CarvedHit bound(byte[] buf, int at) {
                    return boundRiffSized(buf, at, RecoveryType.VIDEO, "video/avi", "avi", MAX_AVI_BYTES);
                }
            });
            scanForMagic(data, hits, progress, new Magics() {
                @Override
                boolean matches(byte[] buf, int at) {
                    return at + 4 <= buf.length && FileSignatureProbe.matchesMkv(slice(buf, at, 4));
                }

                @Override
                CarvedHit bound(byte[] buf, int at) {
                    return bounded(buf, at, RecoveryType.VIDEO, "video/x-matroska", "mkv", MAX_MKV_BYTES);
                }
            });
        }
        if (wantAudio) {
            scanForMagic(data, hits, progress, new Magics() {
                @Override
                boolean matches(byte[] buf, int at) {
                    return at + 4 <= buf.length && FileSignatureProbe.matchesOgg(slice(buf, at, 4));
                }

                @Override
                CarvedHit bound(byte[] buf, int at) {
                    return boundOgg(buf, at);
                }
            });
            scanForMagic(data, hits, progress, new Magics() {
                @Override
                boolean matches(byte[] buf, int at) {
                    return at + 4 <= buf.length && FileSignatureProbe.matchesFlac(slice(buf, at, 4));
                }

                @Override
                CarvedHit bound(byte[] buf, int at) {
                    return boundFlac(buf, at);
                }
            });
            scanForMagic(data, hits, progress, new Magics() {
                @Override
                boolean matches(byte[] buf, int at) {
                    return at + 6 <= buf.length && FileSignatureProbe.matchesAmr(slice(buf, at, 6));
                }

                @Override
                CarvedHit bound(byte[] buf, int at) {
                    return bounded(buf, at, RecoveryType.AUDIO, "audio/amr", "amr", MAX_AMR_BYTES);
                }
            });
        }
        if (wantDoc) {
            scanForMagic(data, hits, progress, new Magics() {
                @Override
                boolean matches(byte[] buf, int at) {
                    return at + 5 <= buf.length && FileSignatureProbe.matchesPdf(slice(buf, at, 5));
                }

                @Override
                CarvedHit bound(byte[] buf, int at) {
                    return boundPdf(buf, at);
                }
            });
            scanForMagic(data, hits, progress, new Magics() {
                @Override
                boolean matches(byte[] buf, int at) {
                    return at + 4 <= buf.length && FileSignatureProbe.matchesZip(slice(buf, at, 4));
                }

                @Override
                CarvedHit bound(byte[] buf, int at) {
                    return boundZip(buf, at);
                }
            });
        }
        return dedupeOverlapping(hits);
    }

    private void carveJpeg(byte[] data, List<CarvedHit> hits, Progress progress) {
        if (hits.size() >= MAX_HITS_PER_WINDOW) {
            return;
        }
        List<RecoveryCandidate> carved = jpegCarver.carveBytes("window", data, new JpegBlobCarver.Progress() {
            @Override
            public void onCandidateFound(RecoveryCandidate candidate) {
            }

            @Override
            public boolean isCancelled() {
                return progress != null && progress.isCancelled();
            }
        });
        for (RecoveryCandidate candidate : carved) {
            if (hits.size() >= MAX_HITS_PER_WINDOW) {
                break;
            }
            int start = (int) candidate.extractionOffsetStart;
            int end = (int) candidate.extractionOffsetEnd;
            if (start < 0 || end <= start || end > data.length) {
                continue;
            }
            hits.add(new CarvedHit(
                    start,
                    end,
                    RecoveryType.IMAGE,
                    "image/jpeg",
                    "jpg",
                    candidate.sha256,
                    candidate.decodeStatus == null ? "COMPLETE" : candidate.decodeStatus));
        }
    }

    private void scanForMagic(byte[] data, List<CarvedHit> hits, Progress progress, Magics magics) {
        for (int i = 0; i + 4 <= data.length && hits.size() < MAX_HITS_PER_WINDOW; i++) {
            if (progress != null && progress.isCancelled()) {
                return;
            }
            if (!magics.matches(data, i)) {
                continue;
            }
            if (overlapsExisting(hits, i)) {
                continue;
            }
            CarvedHit hit = magics.bound(data, i);
            if (hit == null || hit.length() <= 0) {
                continue;
            }
            hits.add(hit);
            // Skip past this hit to avoid O(n^2) re-matches inside the same span.
            i = Math.max(i, hit.end - 1);
        }
    }

    private static boolean overlapsExisting(List<CarvedHit> hits, int start) {
        for (CarvedHit hit : hits) {
            if (start >= hit.start && start < hit.end) {
                return true;
            }
        }
        return false;
    }

    private static CarvedHit boundPng(byte[] data, int start) {
        if (start + 8 > data.length) {
            return null;
        }
        int pos = start + 8;
        int limit = Math.min(data.length, start + MAX_PNG_BYTES);
        while (pos + 12 <= limit) {
            int chunkLen = readBe32(data, pos);
            if (chunkLen < 0 || pos + 12 + chunkLen > limit) {
                break;
            }
            boolean iend = data[pos + 4] == 'I'
                    && data[pos + 5] == 'E'
                    && data[pos + 6] == 'N'
                    && data[pos + 7] == 'D';
            pos += 12 + chunkLen;
            if (iend) {
                return hit(data, start, pos, RecoveryType.IMAGE, "image/png", "png");
            }
        }
        return null;
    }

    private static CarvedHit boundGif(byte[] data, int start) {
        int limit = Math.min(data.length, start + MAX_GIF_BYTES);
        for (int i = start + 6; i < limit; i++) {
            if (data[i] == 0x3B) {
                return hit(data, start, i + 1, RecoveryType.IMAGE, "image/gif", "gif");
            }
        }
        return null;
    }

    private static CarvedHit boundBmp(byte[] data, int start) {
        if (start + 6 > data.length) {
            return null;
        }
        long size = readLe32(data, start + 2);
        if (size < 14 || size > MAX_BMP_BYTES) {
            return null;
        }
        int end = (int) Math.min(data.length, start + size);
        if (end - start < 14) {
            return null;
        }
        return hit(data, start, end, RecoveryType.IMAGE, "image/bmp", "bmp");
    }

    private static CarvedHit boundRiffSized(
            byte[] data,
            int start,
            RecoveryType type,
            String mime,
            String ext,
            int maxBytes
    ) {
        if (start + 8 > data.length) {
            return null;
        }
        long chunkSize = readLe32(data, start + 4);
        // RIFF size is bytes after the size field (4 + payload).
        long total = 8L + chunkSize;
        if (total < 12 || total > maxBytes) {
            return null;
        }
        int end = (int) Math.min(data.length, start + total);
        if (end - start < 12) {
            return null;
        }
        return hit(data, start, end, type, mime, ext);
    }

    private static CarvedHit boundIsoBmff(
            byte[] data,
            int start,
            RecoveryType type,
            String mime,
            String ext,
            int maxBytes
    ) {
        if (start + 8 > data.length) {
            return null;
        }
        long total = 0;
        int pos = start;
        int limit = Math.min(data.length, start + maxBytes);
        int boxes = 0;
        while (pos + 8 <= limit && boxes < 10_000) {
            long size = readBe32Unsigned(data, pos);
            if (size == 0) {
                // Box extends to EOF of the carved window — take remaining bytes.
                total = limit - start;
                break;
            }
            if (size == 1) {
                if (pos + 16 > limit) {
                    break;
                }
                size = readBe64(data, pos + 8);
                if (size < 16) {
                    break;
                }
            } else if (size < 8) {
                break;
            }
            if (pos + size > limit) {
                break;
            }
            total += size;
            pos += (int) size;
            boxes++;
            // A complete MP4 usually ends after mdat (or last top-level box). Keep walking.
            if (total >= 32 && boxes >= 2 && looksLikeTerminalIsoBmff(data, pos - (int) size)) {
                // continue until no more valid boxes; loop condition handles end
            }
        }
        if (total < 16) {
            return null;
        }
        int end = (int) Math.min(data.length, start + total);
        return hit(data, start, end, type, mime, ext);
    }

    private static boolean looksLikeTerminalIsoBmff(byte[] data, int boxStart) {
        if (boxStart + 8 > data.length) {
            return false;
        }
        return data[boxStart + 4] == 'm'
                && data[boxStart + 5] == 'd'
                && data[boxStart + 6] == 'a'
                && data[boxStart + 7] == 't';
    }

    private static CarvedHit boundOgg(byte[] data, int start) {
        int pos = start;
        int limit = Math.min(data.length, start + MAX_OGG_BYTES);
        int pages = 0;
        while (pos + 27 <= limit && pages < 100_000) {
            if (data[pos] != 'O' || data[pos + 1] != 'g' || data[pos + 2] != 'g' || data[pos + 3] != 'S') {
                break;
            }
            int segments = data[pos + 26] & 0xFF;
            int headerSize = 27 + segments;
            if (pos + headerSize > limit) {
                break;
            }
            int body = 0;
            for (int i = 0; i < segments; i++) {
                body += data[pos + 27 + i] & 0xFF;
            }
            int pageSize = headerSize + body;
            if (pos + pageSize > limit) {
                break;
            }
            pos += pageSize;
            pages++;
        }
        if (pos <= start + 27) {
            return null;
        }
        return hit(data, start, pos, RecoveryType.AUDIO, "audio/ogg", "ogg");
    }

    private static CarvedHit boundFlac(byte[] data, int start) {
        if (start + 8 > data.length) {
            return null;
        }
        int pos = start + 4;
        int limit = Math.min(data.length, start + MAX_FLAC_BYTES);
        while (pos + 4 <= limit) {
            int header = data[pos] & 0xFF;
            boolean last = (header & 0x80) != 0;
            int length = ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8)
                    | (data[pos + 3] & 0xFF);
            pos += 4 + length;
            if (pos > limit) {
                return null;
            }
            if (last) {
                // After metadata, optional frames follow. If none look present, keep
                // metadata-only (common for synthetic / truncated residuals).
                int end = pos;
                if (pos + 2 <= limit && isFlacFrameSync(data, pos)) {
                    end = findNextMagicOrLimit(data, pos + 2, limit);
                }
                if (end <= start + 8) {
                    return null;
                }
                return hit(data, start, end, RecoveryType.AUDIO, "audio/flac", "flac");
            }
        }
        return null;
    }

    private static CarvedHit boundPdf(byte[] data, int start) {
        int limit = Math.min(data.length, start + MAX_PDF_BYTES);
        byte[] marker = "%%EOF".getBytes(StandardCharsets.US_ASCII);
        int lastEof = -1;
        for (int i = start; i + marker.length <= limit; i++) {
            if (matchesAt(data, i, marker)) {
                lastEof = i + marker.length;
                // Allow optional CR/LF after %%EOF
                if (lastEof < limit && (data[lastEof] == '\r' || data[lastEof] == '\n')) {
                    lastEof++;
                }
                if (lastEof < limit && data[lastEof - 1] == '\r' && data[lastEof] == '\n') {
                    lastEof++;
                }
            }
        }
        if (lastEof <= start + 8) {
            return null;
        }
        return hit(data, start, lastEof, RecoveryType.DOCUMENT, "application/pdf", "pdf");
    }

    private static CarvedHit boundZip(byte[] data, int start) {
        int limit = Math.min(data.length, start + MAX_ZIP_BYTES);
        // EOCD signature PK\x05\x06
        int eocd = -1;
        for (int i = start; i + 22 <= limit; i++) {
            if (data[i] == 'P' && data[i + 1] == 'K' && data[i + 2] == 0x05 && data[i + 3] == 0x06) {
                int commentLen = (data[i + 20] & 0xFF) | ((data[i + 21] & 0xFF) << 8);
                int end = i + 22 + commentLen;
                if (end <= limit) {
                    eocd = end;
                }
            }
        }
        if (eocd <= start + 4) {
            return null;
        }
        String mime = FileSignatureProbe.zipMime(slice(data, start, Math.min(eocd - start, 4096)));
        String ext = extensionForZipMime(mime);
        return hit(data, start, eocd, RecoveryType.DOCUMENT, mime, ext);
    }

    private static String extensionForZipMime(String mime) {
        if (FileSignatureProbe.MIME_DOCX.equals(mime)) {
            return "docx";
        }
        if (FileSignatureProbe.MIME_XLSX.equals(mime)) {
            return "xlsx";
        }
        if (FileSignatureProbe.MIME_PPTX.equals(mime)) {
            return "pptx";
        }
        return "zip";
    }

    private static CarvedHit bounded(
            byte[] data,
            int start,
            RecoveryType type,
            String mime,
            String ext,
            int maxBytes
    ) {
        int end = findNextMagicOrLimit(data, start + 4, Math.min(data.length, start + maxBytes));
        if (end <= start + 4) {
            return null;
        }
        return hit(data, start, end, type, mime, ext);
    }

    private static boolean isFlacFrameSync(byte[] data, int at) {
        return at + 1 < data.length
                && (data[at] & 0xFF) == 0xFF
                && (data[at + 1] & 0xFC) == 0xF8;
    }

    private static int findNextMagicOrLimit(byte[] data, int from, int limit) {
        for (int i = from; i + 4 <= limit; i++) {
            if (i == from) {
                continue;
            }
            byte[] prefix = slice(data, i, Math.min(12, limit - i));
            if (FileSignatureProbe.matchesJpeg(prefix)
                    || FileSignatureProbe.matchesPng(prefix)
                    || FileSignatureProbe.matchesGif(prefix)
                    || FileSignatureProbe.matchesPdf(prefix)
                    || FileSignatureProbe.matchesZip(prefix)
                    || FileSignatureProbe.matchesFtyp(prefix)
                    || FileSignatureProbe.matchesOgg(prefix)
                    || FileSignatureProbe.matchesFlac(prefix)
                    || FileSignatureProbe.matchesMkv(prefix)
                    || FileSignatureProbe.matchesAmr(prefix)
                    || (prefix.length >= 12 && FileSignatureProbe.matchesWebp(prefix))
                    || (prefix.length >= 12 && FileSignatureProbe.matchesRiff(prefix))) {
                return i;
            }
        }
        return limit;
    }

    private static CarvedHit hit(
            byte[] data,
            int start,
            int end,
            RecoveryType type,
            String mime,
            String ext
    ) {
        if (end <= start || start < 0 || end > data.length) {
            return null;
        }
        byte[] slice = new byte[end - start];
        System.arraycopy(data, start, slice, 0, slice.length);
        return new CarvedHit(start, end, type, mime, ext, sha256(slice), "COMPLETE");
    }

    private static List<CarvedHit> dedupeOverlapping(List<CarvedHit> hits) {
        if (hits.size() <= 1) {
            return hits;
        }
        ArrayList<CarvedHit> sorted = new ArrayList<>(hits);
        Collections.sort(sorted, new Comparator<CarvedHit>() {
            @Override
            public int compare(CarvedHit left, CarvedHit right) {
                int byStart = Integer.compare(left.start, right.start);
                if (byStart != 0) {
                    return byStart;
                }
                return Integer.compare(right.length(), left.length());
            }
        });
        ArrayList<CarvedHit> kept = new ArrayList<>();
        for (CarvedHit hit : sorted) {
            boolean contained = false;
            for (CarvedHit existing : kept) {
                if (hit.start >= existing.start && hit.end <= existing.end) {
                    contained = true;
                    break;
                }
            }
            if (!contained) {
                kept.add(hit);
            }
        }
        return kept;
    }

    private static byte[] slice(byte[] data, int at, int length) {
        int len = Math.min(length, data.length - at);
        if (len <= 0) {
            return new byte[0];
        }
        byte[] out = new byte[len];
        System.arraycopy(data, at, out, 0, len);
        return out;
    }

    private static boolean matchesAt(byte[] data, int at, byte[] needle) {
        if (at + needle.length > data.length) {
            return false;
        }
        for (int i = 0; i < needle.length; i++) {
            if (data[at + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    private static int readBe32(byte[] data, int at) {
        return (int) readBe32Unsigned(data, at);
    }

    private static long readBe32Unsigned(byte[] data, int at) {
        return ((long) (data[at] & 0xFF) << 24)
                | ((long) (data[at + 1] & 0xFF) << 16)
                | ((long) (data[at + 2] & 0xFF) << 8)
                | (long) (data[at + 3] & 0xFF);
    }

    private static long readBe64(byte[] data, int at) {
        return (readBe32Unsigned(data, at) << 32) | readBe32Unsigned(data, at + 4);
    }

    private static long readLe32(byte[] data, int at) {
        return (data[at] & 0xFF)
                | ((data[at + 1] & 0xFF) << 8)
                | ((data[at + 2] & 0xFF) << 16)
                | ((long) (data[at + 3] & 0xFF) << 24);
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                sb.append(String.format("%02x", value));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private abstract static class Magics {
        abstract boolean matches(byte[] buf, int at);

        abstract CarvedHit bound(byte[] buf, int at);
    }
}
