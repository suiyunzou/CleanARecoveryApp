package com.example.cleanrecovery.ui.browser;

import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserAiPromptUnitTest {
    @Test public void editorSaveFlattensLineSeparatorsButPreservesOrdinarySpaces() {
        assertEquals("first second  third", BrowserAiPrompt.editorContent(" \tfirst\r\n\tsecond  third\n"));
        assertEquals("", BrowserAiPrompt.editorContent("\n\t\r "));
    }
    @Test public void systemVariablesUseTheSelectedPageSnapshot() {
        BrowserAiPrompt prompt = new BrowserAiPrompt("id", "page", "{{webpage_title}}\n{{webpage_url}}\n{{webpage_content}}", 1);
        assertTrue(prompt.needsPage());
        assertEquals("Title\nhttps://page.test/\nBody", prompt.system(new BrowserAiPrompt.Page("https://page.test/", "Title", "Body")));
        assertEquals("\n\n", prompt.system(null));
    }

    @Test public void messageTemplatesSubstituteInputOrPrefixItWhenNoInputVariableExists() {
        assertEquals("Translate hello", new BrowserAiPrompt("id", "template", "Translate {{input}}", 2).message("hello"));
        assertEquals("Be brief\nhello", new BrowserAiPrompt("id", "template", "Be brief", 2).message("hello"));
        assertFalse(new BrowserAiPrompt("id", "template", "{{webpage_url}}", 2).needsPage());
    }

    @Test public void unrecognizedVariablesRemainLiteralAsInTheInstalledViaBuild() {
        assertEquals("{{time}}\nhello", new BrowserAiPrompt("id", "template", "{{time}}", 2).message("hello"));
        assertEquals("{{input}} {{time}}", new BrowserAiPrompt("id", "system", "{{input}} {{time}}", 1).system(null));
    }

    @Test public void defaultWebConversationIncludesPageEvidenceAndPlainChatDoesNot() {
        String prompt = BrowserAiPrompt.defaultSystem(new BrowserAiPrompt.Page("https://page.test/", "Title", "Evidence"));
        assertTrue(prompt.contains("Link: https://page.test/\nTitle: Title\nContent:\nEvidence"));
        assertFalse(BrowserAiPrompt.defaultSystem(null).contains("The webpage is as follows"));
    }
}
