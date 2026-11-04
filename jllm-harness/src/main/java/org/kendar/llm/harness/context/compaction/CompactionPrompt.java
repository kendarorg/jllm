package org.kendar.llm.harness.context.compaction;

import org.kendar.llm.harness.Prompts;

/** Instructions of the summarization request (modelled on opencode's compaction). */
final class CompactionPrompt {

    private CompactionPrompt() {
    }

    static final String SUMMARY_TEMPLATE = Prompts.load("compaction-template.md") + "\n";

    private static final String RULES = Prompts.load("compaction-rules.md") + "\n";

    static final String SYSTEM = Prompts.load("compaction-system.md");

    /** First compaction of a conversation. */
    static String create(String focus) {
        return Prompts.load("compaction-create.md") + "\n" + RULES + SUMMARY_TEMPLATE + focus(focus);
    }

    /** Later compaction: merge the existing checkpoint with the newer history. */
    static String update(String focus) {
        return Prompts.load("compaction-update.md") + "\n" + RULES + SUMMARY_TEMPLATE + focus(focus);
    }

    private static String focus(String focus) {
        return focus == null || focus.isBlank() ? "" : "\nPay particular attention to: " + focus.strip() + "\n";
    }
}
