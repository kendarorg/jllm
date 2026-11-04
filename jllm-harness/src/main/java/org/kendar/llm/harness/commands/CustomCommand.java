package org.kendar.llm.harness.commands;

/** A slash command contributed by a plugin. The returned text is shown to the user. */
@FunctionalInterface
public interface CustomCommand {

    String run(String chatId, String arg) throws Exception;
}
