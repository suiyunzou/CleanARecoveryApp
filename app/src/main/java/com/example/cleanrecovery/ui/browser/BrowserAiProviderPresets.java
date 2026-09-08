package com.example.cleanrecovery.ui.browser;

import java.net.URI;

/** Presets from the installed Via 7.2.1 r9.s, not a live provider catalogue. */
public final class BrowserAiProviderPresets {
    public static final class Preset {
        public final String name, endpoint, models, keyPage;
        Preset(String name, String endpoint, String models, String keyPage) {
            this.name = name; this.endpoint = endpoint; this.models = models; this.keyPage = keyPage;
        }
    }

    public static final Preset[] ALL = {
            new Preset("自定义", "", "", null),
            new Preset("字节火山引擎", "https://ark.cn-beijing.volces.com/api/v3", "deepseek-v3-250324\ndeepseek-r1-250120",
                    "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey?apikey=%7B%7D"),
            new Preset("阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "deepseek-v3\ndeepseek-r1",
                    "https://bailian.console.aliyun.com/?tab=model#/api-key"),
            new Preset("DeepSeek", "https://api.deepseek.com", "deepseek-chat\ndeepseek-reasoner",
                    "https://platform.deepseek.com/api_keys"),
            new Preset("硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3\ndeepseek-ai/DeepSeek-R1\nQwen/Qwen3-8B",
                    "https://cloud.siliconflow.cn/account/ak"),
            new Preset("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat-v3-0324:free\ndeepseek/deepseek-r1:free\ngoogle/gemini-2.0-flash-exp:free",
                    "https://openrouter.ai/settings/provisioning-keys"),
            new Preset("ChatGPT", "https://api.openai.com/v1", "gpt-4.1-mini\no4-mini\no3",
                    "https://platform.openai.com/api-keys"),
            new Preset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/", "gemini-2.0-flash\ngemini-1.5-pro",
                    "https://aistudio.google.com/app/apikey"),
            new Preset("Claude", "https://api.anthropic.com/v1/", "claude-3-7-sonnet-20250219",
                    "https://console.anthropic.com/account/keys")
    };

    public static String keyPage(String endpoint) {
        try {
            URI uri = URI.create(endpoint.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return null;
            for (Preset preset : ALL) if (!preset.endpoint.isEmpty()
                    && URI.create(preset.endpoint).getHost().equalsIgnoreCase(uri.getHost())) return preset.keyPage;
        } catch (IllegalArgumentException ignored) { }
        return null;
    }
}
