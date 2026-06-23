package com.example.cleanrecovery.extractor;

/**
 * 提取异常（对应 yt-dlp ExtractorError）。
 *
 * <p>区分不同失败类型，便于 UI 给出针对性提示。</p>
 */
public class ExtractorException extends Exception {

    public enum Kind {
        /** 视频不存在或已删除 */
        NOT_FOUND,
        /** 需要登录 */
        LOGIN_REQUIRED,
        /** 地区限制 */
        GEO_RESTRICTED,
        /** 仅会员可看 */
        PREMIUM_ONLY,
        /** 频率限制 */
        RATE_LIMITED,
        /** 不支持的 URL */
        UNSUPPORTED,
        /** 解析失败（页面结构变化等） */
        PARSE_FAILED,
        /** 其他错误 */
        UNKNOWN
    }

    private final Kind kind;

    public ExtractorException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ExtractorException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
