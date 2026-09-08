package com.example.cleanrecovery.ui.browser;

/** Via prompt kinds and substitution scopes (r9.q/r9.s and d9.e0). */
public final class BrowserAiPrompt {
    public static final String DEFAULT_SYSTEM = "Act as an Expert Answerer. Follow these rules:\n\n"
            + "1. Concisely answer the core question within 3 sentences. Prioritize clarity.\n"
            + "2. Match the user's language (e.g., respond in Chinese if asked in Chinese).\n"
            + "3. Structure answers when answering professional content.\n"
            + "4. Omit polite filler, disclaimers, or redundant content.\n\nStay strictly on-task.";
    public final String id;
    public final String name;
    public final String content;
    public final int type;

    public BrowserAiPrompt(String id, String name, String content, int type) {
        this.id = id; this.name = name; this.content = content; this.type = type;
    }

    public boolean needsPage() {
        return type == 1 && (content.contains("{{webpage_url}}") || content.contains("{{webpage_title}}")
                || content.contains("{{webpage_content}}"));
    }

    /** Editor persistence uses Via's b9.z3.g; runtime page text retains its line breaks. */
    public static String editorContent(String content) {
        return content.replaceAll("[\\t\\n\\r]+", " ").trim();
    }

    public String system(Page page) {
        return content.replace("{{webpage_url}}", page == null ? "" : page.url)
                .replace("{{webpage_title}}", page == null ? "" : page.title)
                .replace("{{webpage_content}}", page == null ? "" : page.text);
    }

    public String message(String input) {
        return content.contains("{{input}}") ? content.replace("{{input}}", input) : content + "\n" + input;
    }

    public static String defaultSystem(Page page) {
        if (page == null) return DEFAULT_SYSTEM;
        return "You're a proficient AI assistant skilled in concise, on-point responses. Please follow the following principles:\n"
                + "1. Ground answers in the provided webpage, allowing minor thematic tangents but avoiding irrelevance. If info is missing, note gaps clearly.\n"
                + "2. Match the user's language ({{user_language}}) and tone precisely.\n"
                + "3. Prioritize clarity: lead with direct answers, add context only if critical.\n\nThe webpage is as follows:\n"
                + "Link: " + page.url + "\nTitle: " + page.title + "\nContent:\n" + page.text;
    }

    public static final class Page {
        public final String url;
        public final String title;
        public final String text;
        public Page(String url, String title, String text) {
            this.url = url; this.title = title == null ? "" : title; this.text = text == null ? "" : text;
        }
    }
}
