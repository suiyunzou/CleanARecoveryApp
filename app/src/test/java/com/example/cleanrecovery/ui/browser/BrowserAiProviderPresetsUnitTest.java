package com.example.cleanrecovery.ui.browser;

import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserAiProviderPresetsUnitTest {
    @Test public void keyManagementUsesTheServiceHostNotItsApiPath() {
        assertEquals("https://platform.openai.com/api-keys",
                BrowserAiProviderPresets.keyPage("https://api.openai.com/v1/chat/completions"));
        assertEquals("https://platform.deepseek.com/api_keys",
                BrowserAiProviderPresets.keyPage("https://api.deepseek.com/compatible/path"));
    }

    @Test public void unknownOrLookalikeEndpointsHaveNoInventedManagementLink() {
        assertNull(BrowserAiProviderPresets.keyPage("https://custom.test/v1"));
        assertNull(BrowserAiProviderPresets.keyPage("https://api.openai.com.custom.test/v1"));
        assertNull(BrowserAiProviderPresets.keyPage("https://api.openai.com@custom.test/v1"));
        assertNull(BrowserAiProviderPresets.keyPage("file://api.openai.com/v1"));
        assertNull(BrowserAiProviderPresets.keyPage(""));
    }
}
