package com.example.cleanrecovery.extractor;

import java.util.List;

/**
 * 提取器接口（参照 yt-dlp InfoExtractor）。
 *
 * <p>yt-dlp 的核心架构是"每个网站一个提取器"，直接调用目标平台的官方 API
 * 获取直链，不依赖任何第三方中转服务器。本接口对应 yt-dlp 的
 * {@code _real_extract(url)} 方法。</p>
 *
 * <p>实现类需通过 {@link #suitable(String)} 判断能否处理该 URL，
 * 并在 {@link #extract(String)} 中返回直链信息。</p>
 *
 * @see <a href="https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/common.py">InfoExtractor</a>
 */
public interface Extractor {

    /**
     * 提取器名称（对应 yt-dlp IE_NAME）。
     */
    String name();

    /**
     * 判断本提取器能否处理该 URL（对应 yt-dlp _match_valid_url）。
     *
     * @param url 用户输入的 URL
     * @return true 表示可以处理
     */
    boolean suitable(String url);

    /**
     * 执行提取，返回直链列表（对应 yt-dlp _real_extract）。
     *
     * <p>返回多个 Format 时，调用方可按质量排序选择最佳画质。
     * 视频和音频可能分离（DASH 格式），需分别下载。</p>
     *
     * @param url 用户输入的 URL
     * @return 提取结果，包含直链与元数据
     * @throws ExtractorException 提取失败（鉴权/地区限制/视频不存在等）
     * @throws java.io.IOException 网络错误
     */
    ExtractorResult extract(String url) throws ExtractorException, java.io.IOException;
}
