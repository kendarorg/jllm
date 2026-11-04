package org.kendar.llm.harness.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Keeps the sub-agent runs in a delegate log and mirrors each to {@code <root>/<chatId>/subagent-<runId>/}
 * ({@code messages.jsonl} and {@code result.txt}). The run id is a UUIDv7, so the directories sort in run order.
 */
public class FileHistorySubAgentRunLog implements SubAgentRunLog {

    private static final Logger log = LoggerFactory.getLogger(FileHistorySubAgentRunLog.class);

    private final SubAgentRunLog delegate;
    private final java.util.function.Function<String, Path> chatRoot;

    public FileHistorySubAgentRunLog(SubAgentRunLog delegate, Path root) {
        this.delegate = delegate;
        Path r = root.toAbsolutePath().normalize();
        this.chatRoot = id -> r.resolve(FileHistoryChatSessionStore.safe(id));
    }

    /** Sub-agent runs of a chat go to {@code chatRoot(chatId)/subagent-<runId>/}. */
    public FileHistorySubAgentRunLog(SubAgentRunLog delegate, java.util.function.Function<String, Path> chatRoot) {
        this.delegate = delegate;
        this.chatRoot = chatRoot;
    }

    @Override
    public void record(SubAgentRunData run) {
        delegate.record(run);
        try {
            Path dir = chatRoot.apply(run.chatId())
                    .resolve("subagent-" + FileHistoryChatSessionStore.safe(run.id()));
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("messages.jsonl"), run.messagesJson() == null ? "" : run.messagesJson(),
                    StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("result.txt"), "status: " + run.status() + "\nprompt: " + run.prompt()
                    + "\n\n" + run.answer() + "\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot write the history of sub-agent run {}: {}", run.id(), e.getMessage());
        }
    }

    @Override
    public List<SubAgentRunData> list(String chatId) {
        return delegate.list(chatId);
    }

    @Override
    public List<SubAgentRunData> forToolUse(String chatId, String toolUseId) {
        return delegate.forToolUse(chatId, toolUseId);
    }

    @Override
    public void deleteChat(String chatId) {
        delegate.deleteChat(chatId);
    }
}
