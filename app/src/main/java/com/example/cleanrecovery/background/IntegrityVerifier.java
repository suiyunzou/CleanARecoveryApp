package com.example.cleanrecovery.background;

import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 视频完整性校验器（后台下载模块）。
 *
 * <p>提供多层完整性验证：</p>
 * <ul>
 *   <li><b>哈希校验</b>：计算文件 SHA-256 摘要，用于去重和完整性比对</li>
 *   <li><b>大小校验</b>：检查文件大小是否合理（非零、非过小）</li>
 *   <li><b>可播放性验证</b>：使用 {@link MediaMetadataRetriever} 解析视频元数据，
 *       确认文件可被播放器识别</li>
 *   <li><b>文件头校验</b>：检查文件魔数（magic bytes）是否符合视频格式</li>
 * </ul>
 */
public final class IntegrityVerifier {
    private static final String TAG = "IntegrityVerifier";

    /** 校验结果。 */
    public static final class VerifyResult {
        public final boolean valid;
        public final String hash;
        public final long fileSize;
        public final String mimeType;
        public final long durationMs;
        public final int width;
        public final int height;
        public final String errorMessage;

        VerifyResult(boolean valid, String hash, long fileSize, String mimeType,
                     long durationMs, int width, int height, String errorMessage) {
            this.valid = valid;
            this.hash = hash;
            this.fileSize = fileSize;
            this.mimeType = mimeType;
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
            this.errorMessage = errorMessage;
        }

        @Override
        public String toString() {
            return valid ? String.format("有效[hash=%s,size=%d,%dx%d,duration=%dms]",
                    hash != null ? hash.substring(0, 12) : "null", fileSize, width, height, durationMs)
                    : "无效[" + errorMessage + "]";
        }
    }

    /** 最小有效文件大小（1KB），低于此值视为下载失败。 */
    private static final long MIN_VALID_SIZE = 1024;

    private IntegrityVerifier() {
    }

    /**
     * 全面校验视频文件。
     *
     * @param file 待校验文件
     * @return 校验结果
     */
    public static VerifyResult verify(File file) {
        if (file == null || !file.exists()) {
            return new VerifyResult(false, null, 0, null, 0, 0, 0, "文件不存在");
        }

        long size = file.length();
        if (size < MIN_VALID_SIZE) {
            return new VerifyResult(false, null, size, null, 0, 0, 0,
                    "文件过小(" + size + "字节)，可能下载失败");
        }

        // 1. 计算哈希
        String hash = computeSha256(file);
        if (hash == null) {
            return new VerifyResult(false, null, size, null, 0, 0, 0, "哈希计算失败");
        }

        // 2. 文件头校验
        String headerError = checkFileHeader(file);
        if (headerError != null) {
            return new VerifyResult(false, hash, size, null, 0, 0, 0, headerError);
        }

        // 3. 可播放性验证（MediaMetadataRetriever）
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);

            long duration = durationStr != null ? Long.parseLong(durationStr) : 0;
            int width = widthStr != null ? Integer.parseInt(widthStr) : 0;
            int height = heightStr != null ? Integer.parseInt(heightStr) : 0;

            // 音频文件可能没有宽高，但有时长
            if (duration <= 0 && width <= 0 && height <= 0) {
                return new VerifyResult(false, hash, size, mime, 0, 0, 0,
                        "无法提取元数据，文件可能损坏");
            }

            Log.d(TAG, "校验通过: " + file.getName() + " " + size + "字节 "
                    + (duration > 0 ? duration + "ms " : "") + (width > 0 ? width + "x" + height : ""));
            return new VerifyResult(true, hash, size, mime, duration, width, height, null);
        } catch (Exception e) {
            Log.w(TAG, "可播放性验证失败: " + e.getMessage());
            return new VerifyResult(false, hash, size, null, 0, 0, 0,
                    "元数据解析失败: " + e.getMessage());
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 计算文件 SHA-256 哈希。 */
    public static String computeSha256(File file) {
        return computeHash(file, "SHA-256");
    }

    /** 计算文件 MD5 哈希。 */
    public static String computeMd5(File file) {
        return computeHash(file, "MD5");
    }

    private static String computeHash(File file, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = fis.read(buffer)) > 0) {
                    digest.update(buffer, 0, n);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            Log.w(TAG, "哈希计算失败[" + algorithm + "]: " + e.getMessage());
            return null;
        }
    }

    /** 检查文件头魔数。 */
    private static String checkFileHeader(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[16];
            int read = fis.read(header);
            if (read < 4) return "文件头过短";

            // MP4/M4A/MOV: ftyp box 在偏移 4-7
            if (read >= 12 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
                return null; // 有效的 MP4 系列
            }
            // WebM/MKV: 0x1A 0x45 0xDF 0xA3
            if (header[0] == 0x1A && header[1] == 0x45 && header[2] == (byte) 0xDF && header[3] == (byte) 0xA3) {
                return null; // 有效的 WebM/MKV
            }
            // FLV: 'F' 'L' 'V'
            if (header[0] == 'F' && header[1] == 'L' && header[2] == 'V') {
                return null;
            }
            // MPEG-TS: 0x47 同步字节
            if (header[0] == 0x47) {
                return null; // 有效的 TS 流
            }
            // MP3: ID3 标签或 0xFF 0xFB
            if ((header[0] == 'I' && header[1] == 'D' && header[2] == '3')
                    || ((header[0] & 0xFF) == 0xFF && (header[1] & 0xE0) == 0xE0)) {
                return null;
            }
            // AAC ADTS: 0xFF 0xF1 或 0xFF 0xF9
            if ((header[0] & 0xFF) == 0xFF && ((header[1] & 0xFF) == 0xF1 || (header[1] & 0xFF) == 0xF9)) {
                return null;
            }
            // 允许未知格式通过（可能是加密流或特殊格式）
            Log.d(TAG, "未知文件头格式: " + bytesToHex(header, Math.min(read, 8)));
            return null;
        } catch (IOException e) {
            return "文件头读取失败: " + e.getMessage();
        }
    }

    private static String bytesToHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x ", bytes[i]));
        }
        return sb.toString().trim();
    }
}
