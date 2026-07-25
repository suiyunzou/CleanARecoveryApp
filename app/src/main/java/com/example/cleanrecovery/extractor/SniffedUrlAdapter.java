package com.example.cleanrecovery.extractor;

import com.example.cleanrecovery.download.HlsFD;

import java.util.ArrayList;
import java.util.List;

/**
 * 嗅探 URL 适配器（对应计划 §2.3 / D2 流水线收敛）。
 *
 * <p>把 WebView 嗅探路径产出的 {@link MediaSniffer.MediaResource} 转译为
 * {@link ExtractorResult.Format}，补 {@code protocol} / {@code formatId}，
 * 让 B/C 路径也能接入统一 {@code YoutubeDL} 编排器与后处理链。</p>
 *
 * <p>protocol 推断：</p>
 * <ul>
 *   <li>kind="hls" 或 ext 以 .m3u8/.m3u 结尾 → {@code m3u8}</li>
 *   <li>其他 → {@code http}</li>
 * </ul>
 *
 * <p>注：M1 仅做适配，B/C 实际接入编排器在 M3 完成（D2 流水线收敛）。</p>
 */
public final class SniffedUrlAdapter {

    private SniffedUrlAdapter() {}

    /**
     * 单条 MediaResource 转 Format。
     *
     * @param mr   WebView 嗅探产出的瞬态对象
     * @param pageUrl 来源页面 URL（用于补充 pageUrl 元信息）
     * @return 等价 Format，protocol/formatId 已填
     */
    public static ExtractorResult.Format toFormat(MediaSniffer.MediaResource mr, String pageUrl) {
        if (mr == null) return null;
        String protocol = inferProtocol(mr);
        String formatId = inferFormatId(mr);
        int height = parseHeight(mr.resolution);

        return new ExtractorResult.Format.Builder()
                .url(mr.url)
                .ext(mr.ext != null && !mr.ext.isEmpty() ? mr.ext : "mp4")
                .quality(height)
                .vcodec("none".equals(mr.vcodec) || mr.vcodec.isEmpty() ? "unknown" : mr.vcodec)
                .acodec("none".equals(mr.acodec) || mr.acodec.isEmpty() ? "unknown" : mr.acodec)
                .height(height)
                .description(mr.getDisplayTitle())
                .protocol(protocol)
                .formatId(formatId)
                .build();
    }

    /**
     * 批量转。
     */
    public static List<ExtractorResult.Format> toFormats(List<MediaSniffer.MediaResource> mrs,
                                                            String pageUrl) {
        List<ExtractorResult.Format> result = new ArrayList<>();
        if (mrs == null) return result;
        for (MediaSniffer.MediaResource mr : mrs) {
            ExtractorResult.Format f = toFormat(mr, pageUrl);
            if (f != null) result.add(f);
        }
        return result;
    }

    /** 把嗅探结果包装为可入队编排器的 ExtractorResult。 */
    public static ExtractorResult toExtractorResult(MediaSniffer.MediaResource mr,
                                                     String pageUrl, String pageTitle) {
        ExtractorResult.Format fmt = toFormat(mr, pageUrl);
        if (fmt == null) return null;
        String id = pageTitle != null ? pageTitle : Long.toString(System.currentTimeMillis());
        return new ExtractorResult(
                id, pageTitle, null,
                java.util.Collections.singletonList(fmt),
                pageTitle != null ? pageTitle : "sniffed",
                fmt.ext,
                0L, null, null, null, pageUrl, "Sniffed");
    }

    private static String inferProtocol(MediaSniffer.MediaResource mr) {
        if ("hls".equals(mr.kind)) return HlsFD.PROTOCOL_M3U8;
        if (mr.ext != null) {
            String lower = mr.ext.toLowerCase();
            if (lower.endsWith(".m3u8") || lower.endsWith(".m3u") || lower.equals("m3u8")) {
                return HlsFD.PROTOCOL_M3U8;
            }
        }
        return "http";
    }

    private static String inferFormatId(MediaSniffer.MediaResource mr) {
        // 从 googlevide URL 的 itag 参数提取，否则用 kind+ext
        if (mr.url != null) {
            int itagIdx = mr.url.indexOf("itag=");
            if (itagIdx >= 0) {
                int start = itagIdx + 6;
                int end = start;
                while (end < mr.url.length() && Character.isDigit(mr.url.charAt(end))) end++;
                if (end > start) return mr.url.substring(start, end);
            }
        }
        return (mr.kind != null ? mr.kind : "sniffed") + "_" + (mr.ext != null ? mr.ext : "mp4");
    }

    private static int parseHeight(String resolution) {
        if (resolution == null) return 0;
        // "1080p" → 1080
        String digits = resolution.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try { return Integer.parseInt(digits); } catch (Exception e) { return 0; }
    }
}
