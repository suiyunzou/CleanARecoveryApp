package com.example.cleanrecovery.ytdlp;

/** 后处理异常（对应 yt-dlp PostProcessor 执行失败）。 */
public class PostProcessorException extends Exception {
    public PostProcessorException(String message) { super(message); }
    public PostProcessorException(String message, Throwable cause) { super(message, cause); }
}
