package org.kendar.llm.harness.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Keeps the sub-agent runs in a delegate log and mirrors each to {@code <root>/<chatId>/subagent-<runId>/}
 * ({@code messages.jsonl} and {@code result.txt}). The run id is a UUIDv7, so the directories sort in run order.
 */
public class FileHistorySubAgentRunLog implements SubAgentRunLog {

    private static final Logger log = LoggerFactory.getLogger(FileHistorySubAgentRunLog.class);

    private final SubAgentRunLog delegate;
    private final java.util.function.Function<String, Path> chatRoot;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public FileHistorySubAgentRunLog(SubAgentRunLog delegate, Path root) {
        this.delegate = delegate;
        Path r = root.toAbsolutePath().normalize();
        this.chatRoot = id -> r.resolve(FileChatSessionStore.safe(id));
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
                    .resolve("subagent-" + FileChatSessionStore.safe(run.id()));
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("messages.jsonl"), run.messagesJson() == null ? "" : run.messagesJson(),
                    StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("result.txt"), "status: " + run.status() + "\nprompt: " + run.prompt()
                    + "\n\n" + run.answer() + "\n", StandardCharsets.UTF_8);
            var n = mapper.createObjectNode();
            n.put("id", run.id());
            n.put("chatId", run.chatId());
            if (run.toolUseId() != null) n.put("toolUseId", run.toolUseId());
            n.put("prompt", run.prompt());
            n.put("answer", run.answer());
            n.put("status", run.status());
            n.put("inputTokens", run.inputTokens());
            n.put("outputTokens", run.outputTokens());
            n.put("cacheReadTokens", run.cacheReadTokens());
            n.put("cacheCreationTokens", run.cacheCreationTokens());
            n.put("createdAt", String.valueOf(run.createdAt()));
            mapper.writeValue(dir.resolve("run.json").toFile(), n);
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot write the history of sub-agent run {}: {}", run.id(), e.getMessage());
        }
    }

    @Override
    public List<SubAgentRunData> list(String chatId) {
        Map<String, SubAgentRunData> out = new LinkedHashMap<>();
        for (SubAgentRunData r : readFromDisk(chatId)) out.put(r.id(), r);
        for (SubAgentRunData r : delegate.list(chatId)) out.put(r.id(), r); // in memory wins: same content
        return new ArrayList<>(out.values());
    }

    @Override
    public List<SubAgentRunData> forToolUse(String chatId, String toolUseId) {
        return list(chatId).stream().filter(r -> toolUseId.equals(r.toolUseId())).toList();
    }

    /** Runs of a chat written by an earlier process, oldest first (run ids are UUIDv7). */
    private List<SubAgentRunData> readFromDisk(String chatId) {
        Path chat = chatRoot.apply(chatId);
        if (!Files.isDirectory(chat)) return List.of();
        List<SubAgentRunData> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(chat)) {
            for (Path d : dirs.filter(p -> p.getFileName().toString().startsWith("subagent-"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                Path meta = d.resolve("run.json");
                if (!Files.isRegularFile(meta)) continue;
                try {
                    var n = mapper.readTree(meta.toFile());
                    Path msgs = d.resolve("messages.jsonl");
                    out.add(new SubAgentRunData(n.path("id").asText(), chatId,
                            n.hasNonNull("toolUseId") ? n.get("toolUseId").asText() : null, n.path("prompt").asText(),
                            Files.isRegularFile(msgs) ? Files.readString(msgs, StandardCharsets.UTF_8) : "",
                            n.path("answer").asText(), n.path("status").asText(), n.path("inputTokens").asLong(),
                            n.path("outputTokens").asLong(), n.path("cacheReadTokens").asLong(),
                            n.path("cacheCreationTokens").asLong(), java.time.Instant.parse(n.path("createdAt").asText())));
                } catch (Exception e) {
                    log.warn("Skipping sub-agent run in {}: {}", d, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Cannot list the sub-agent runs of chat {}: {}", chatId, e.getMessage());
        }
        return out;
    }

    @Override
    public void deleteChat(String chatId) {
        delegate.deleteChat(chatId);
    }
}
