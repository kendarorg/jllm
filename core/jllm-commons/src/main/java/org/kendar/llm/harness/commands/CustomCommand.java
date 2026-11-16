package org.kendar.llm.harness.commands;

import java.util.List;

/** A slash command contributed by a plugin. The returned text is shown to the user. */
@FunctionalInterface
public interface CustomCommand {

    /** One plain-text line of the conversation (user or assistant text; tool traffic is left out). */
    record ChatLine(String role, String text) {
    }

    /**
     * @param history the conversation so far, oldest first, as plain text lines
     */
    String run(String chatId, String arg, List<ChatLine> history) throws Exception;
}
