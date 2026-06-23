package com.example.cleanrecovery.music.api;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 音频文件格式检测器。
 *
 * <p>根据技术调研报告，酷狗音乐下载的音频文件存在两种情况：</p>
 * <ul>
 *   <li><b>未加密格式</b>：通过 API CDN 直链下载的标准 MP3/FLAC 文件</li>
 *   <li><b>加密格式</b>：酷狗客户端"下载"功能默认保存的 KGM/KGMA/KGG 加密容器</li>
 * </ul>
 *
 * <p>本检测器通过文件头魔数（Magic Number）识别文件真实格式，用于：</p>
 * <ol>
 *   <li>验证通过 API 下载的文件是否为标准未加密格式</li>
 *   <li>检测用户从酷狗客户端导出的文件是否为加密格式</li>
 *   <li>防止将加密文件误判为可播放的标准音频</li>
 * </ol>
 *
 * <p>支持的格式及其文件头特征：</p>
 * <table border="1">
 *   <tr><th>格式</th><th>扩展名</th><th>文件头魔数（Hex）</th><th>加密</th></tr>
 *   <tr><td>MP3 (ID3v2)</td><td>.mp3</td><td>49 44 33 ("ID3")</td><td>否</td></tr>
 *   <tr><td>MP3 (帧头)</td><td>.mp3</td><td>FF FB / FF F3 / FF F2</td><td>否</td></tr>
 *   <tr><td>FLAC</td><td>.flac</td><td>66 4C 61 43 ("fLaC")</td><td>否</td></tr>
 *   <tr><td>Ogg/Vorbis</td><td>.ogg</td><td>4F 67 67 53 ("OggS")</td><td>否</td></tr>
 *   <tr><td>M4A/AAC</td><td>.m4a</td><td>?? ?? ?? ?? 66 74 79 70 ("ftyp")</td><td>否</td></tr>
 *   <tr><td>KGM (V1)</td><td>.kgm</td><td>4B 47 4D ("KGM")</td><td>是</td></tr>
 *   <tr><td>KGM (V2)</td><td>.kgm</td><td>4B 47 4D 21 ("KGM!")</td><td>是</td></tr>
 *   <tr><td>KGMA</td><td>.kgma</td><td>4B 47 4D 41 ("KGMA")</td><td>是</td></tr>
 *   <tr><td>KGG</td><td>.kgg</td><td>4B 47 47 ("KGG")</td><td>是（含 DRM）</td></tr>
 *   <tr><td>VPR</td><td>.vpr</td><td>56 50 52 ("VPR")</td><td>是</td></tr>
 * </table>
 */
public final class AudioFormatDetector {

    /** 检测文件头所需的最小字节数。 */
    private static final int MIN_HEADER_BYTES = 16;

    private AudioFormatDetector() {
        // 工具类，禁止实例化
    }

    /** 音频文件格式枚举。 */
    public enum Format {
        /** 标准 MP3 文件（ID3v2 标签开头） */
        MP3_ID3("MP3 (ID3v2)", false),
        /** 标准 MP3 文件（帧头开头） */
        MP3_FRAME("MP3 (Frame)", false),
        /** 标准 FLAC 无损文件 */
        FLAC("FLAC", false),
        /** Ogg Vorbis 文件 */
        OGG("Ogg/Vorbis", false),
        /** M4A/AAC 文件 */
        M4A("M4A/AAC", false),
        /** 酷狗 KGM 加密格式（V1） */
        KGM_V1("KGM V1", true),
        /** 酷狗 KGM 加密格式（V2） */
        KGM_V2("KGM V2", true),
        /** 酷狗 KGMA 加密格式 */
        KGMA("KGMA", true),
        /** 酷狗 KGG DRM 加密格式 */
        KGG("KGG (DRM)", true),
        /** 酷狗 VPR 加密格式 */
        VPR("VPR", true),
        /** 未知格式 */
        UNKNOWN("Unknown", false);

        public final String displayName;
        public final boolean encrypted;

        Format(String displayName, boolean encrypted) {
            this.displayName = displayName;
            this.encrypted = encrypted;
        }
    }

    /** 检测结果。 */
    public static class Result {
        public final Format format;
        public final boolean encrypted;
        public final boolean playable;

        public Result(Format format) {
            this.format = format;
            this.encrypted = format.encrypted;
            this.playable = !format.encrypted && format != Format.UNKNOWN;
        }

        @Override
        public String toString() {
            return String.format("Format=%s, encrypted=%s, playable=%s",
                    format.displayName, encrypted, playable);
        }
    }

    /**
     * 检测文件的音频格式。
     *
     * @param file 待检测的音频文件
     * @return 检测结果，文件读取失败返回 UNKNOWN 格式
     */
    public static Result detect(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return new Result(Format.UNKNOWN);
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[MIN_HEADER_BYTES];
            int read = fis.read(header);
            if (read < 4) {
                return new Result(Format.UNKNOWN);
            }
            return detect(header, read);
        } catch (IOException e) {
            return new Result(Format.UNKNOWN);
        }
    }

    /**
     * 从输入流检测音频格式（不关闭流）。
     *
     * @param is 输入流，需支持 mark/reset
     * @return 检测结果
     */
    public static Result detect(InputStream is) throws IOException {
        if (!is.markSupported()) {
            throw new IOException("InputStream must support mark/reset");
        }
        is.mark(MIN_HEADER_BYTES);
        byte[] header = new byte[MIN_HEADER_BYTES];
        int read = is.read(header);
        is.reset();
        if (read < 4) {
            return new Result(Format.UNKNOWN);
        }
        return detect(header, read);
    }

    /**
     * 通过文件头字节数组检测音频格式。
     *
     * @param header 文件头字节数组
     * @param length 有效字节数
     * @return 检测结果
     */
    public static Result detect(byte[] header, int length) {
        if (header == null || length < 4) {
            return new Result(Format.UNKNOWN);
        }

        // 1. MP3 (ID3v2) - "ID3"
        if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
            return new Result(Format.MP3_ID3);
        }

        // 2. MP3 帧头 - 0xFF Ex (11xx xxxx)
        if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xE0) == 0xE0) {
            return new Result(Format.MP3_FRAME);
        }

        // 3. FLAC - "fLaC"
        if (header[0] == 'f' && header[1] == 'L' && header[2] == 'a' && header[3] == 'C') {
            return new Result(Format.FLAC);
        }

        // 4. OggS - Ogg/Vorbis
        if (header[0] == 'O' && header[1] == 'g' && header[2] == 'g' && header[3] == 'S') {
            return new Result(Format.OGG);
        }

        // 5. M4A/AAC - "ftyp" at offset 4
        if (length >= 8 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
            return new Result(Format.M4A);
        }

        // 6. KGM V2 - "KGM!" (4 bytes)
        if (header[0] == 'K' && header[1] == 'G' && header[2] == 'M' && header[3] == '!') {
            return new Result(Format.KGM_V2);
        }

        // 7. KGMA - "KGMA" (4 bytes)
        if (header[0] == 'K' && header[1] == 'G' && header[2] == 'M' && header[3] == 'A') {
            return new Result(Format.KGMA);
        }

        // 8. KGM V1 - "KGM" (3 bytes, 第4字节非 '!' 或 'A')
        if (header[0] == 'K' && header[1] == 'G' && header[2] == 'M' && header[3] != '!' && header[3] != 'A') {
            return new Result(Format.KGM_V1);
        }

        // 9. KGG - "KGG" (3 bytes)
        if (header[0] == 'K' && header[1] == 'G' && header[2] == 'G') {
            return new Result(Format.KGG);
        }

        // 10. VPR - "VPR" (3 bytes)
        if (header[0] == 'V' && header[1] == 'P' && header[2] == 'R') {
            return new Result(Format.VPR);
        }

        return new Result(Format.UNKNOWN);
    }

    /**
     * 判断文件是否为加密的酷狗格式（KGM/KGMA/KGG/VPR）。
     *
     * @param file 待检测的文件
     * @return true 表示文件为加密格式
     */
    public static boolean isEncrypted(File file) {
        return detect(file).encrypted;
    }

    /**
     * 判断文件是否为可直接播放的标准格式（MP3/FLAC/OGG/M4A）。
     *
     * @param file 待检测的文件
     * @return true 表示文件为标准未加密格式
     */
    public static boolean isPlayable(File file) {
        return detect(file).playable;
    }
}
