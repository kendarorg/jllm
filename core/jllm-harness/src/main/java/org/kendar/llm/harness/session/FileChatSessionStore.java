package org.kendar.llm.harness.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Durable chats. Layout below a chat directory {@code <root>/<chatId>}:
 * <pre>
 * session.json                  the chat row without the messages
 * &lt;agentId&gt;/messages.jsonl      the message log (one JSON message per line)
 * </pre>
 * The log is read lazily on {@link #find}; {@link #list()} reads only the rows. A save appends to the log file when
 * the new log extends the stored one and rewrites it otherwise. The log is written before the row, so a crash leaves a
 * row that is at most one turn behind. A corrupt row is skipped with a warning; it never fails the start.
 * A log that ends with an unanswered tool call (the process died mid-run) is repaired on load, see
 * {@link InterruptedTurnRepair}.
 */
public class FileChatSessionStore implements ChatSessionStore {

    private static final Logger log = LoggerFactory.getLogger(FileChatSessionStore.class);
    static final String ROW = "session.json";
    static final String LOG = "messages.jsonl";

    private final Function<String, Path> chatRoot;
    private final Supplier<List<Path>> chatDirs;
    private final ObjectMapper mapper;
    /** Rows without the log; filled by the first {@link #list()} and by every save. */
    private final Map<String, ChatSessionData> rows = new ConcurrentHashMap<>();
    /** The log as stored on disk, per chat; present once read or written. */
    private final Map<String, String> logs = new ConcurrentHashMap<>();
    private final Map<String, Boolean> repaired = new ConcurrentHashMap<>();
    private volatile boolean scanned;

    /** One root holding a directory per chat. */
    public FileChatSessionStore(Path root, ObjectMapper mapper) {
        Path r = root.toAbsolutePath().normalize();
        this.chatRoot = id -> r.resolve(safe(id));
        this.chatDirs = () -> {
            if (!Files.isDirectory(r)) return List.of();
            try (Stream<Path> s = Files.list(r)) {
                return s.filter(Files::isDirectory).toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        this.mapper = mapper;
    }

    /**
     * Per-chat roots ({@code chatRoot(chatId)}); {@code chatDirs} enumerates the existing ones (a directory scan, no
     * index file).
     */
    public FileChatSessionStore(Function<String, Path> chatRoot, Supplier<List<Path>> chatDirs, ObjectMapper mapper) {
        this.chatRoot = chatRoot;
        this.chatDirs = chatDirs;
        this.mapper = mapper;
    }

    @Override
    public synchronized ChatSessionData save(ChatSessionData session) {
        String id = session.chatId();
        String messages = session.messagesJson() == null ? "" : session.messagesJson();
        try {
            Path dir = chatRoot.apply(id);
            Path logFile = dir.resolve(safe(session.agentId())).resolve(LOG);
            Files.createDirectories(logFile.getParent());
            String known = logs.get(id);
            if (known == null && Files.isRegularFile(logFile)) known = Files.readString(logFile, StandardCharsets.UTF_8);
            if (!messages.equals(known)) writeLog(logFile, known, messages);
            logs.put(id, messages);
            writeRow(dir, session);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save chat " + id, e);
        }
        rows.put(id, session.withoutMessages());
        return session;
    }

    private static void writeLog(Path file, String known, String messages) throws IOException {
        if (known != null && !known.isEmpty() && messages.length() > known.length() && messages.startsWith(known)
                && Files.isRegularFile(file)) {
            Files.writeString(file, messages.substring(known.length()), StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } else {
            Files.writeString(file, messages, StandardCharsets.UTF_8);
        }
    }

    private void writeRow(Path dir, ChatSessionData s) throws IOException {
        ObjectNode n = mapper.createObjectNode();
        n.put("chatId", s.chatId());
        n.put("agentId", s.agentId());
        n.put("title", s.title());
        n.put("mode", s.mode());
        if (s.toolGroups() != null) n.put("toolGroups", s.toolGroups());
        n.put("inputTokens", s.inputTokens());
        n.put("outputTokens", s.outputTokens());
        n.put("cacheReadTokens", s.cacheReadTokens());
        n.put("cacheCreationTokens", s.cacheCreationTokens());
        n.put("createdAt", s.createdAt().toString());
        n.put("updatedAt", s.updatedAt().toString());
        n.put("messageCount", s.messageCount());
        n.put("qualifierInputTokens", s.qualifierInputTokens());
        n.put("qualifierOutputTokens", s.qualifierOutputTokens());
        if (s.principal() != null) n.put("principal", s.principal());
        Files.createDirectories(dir);
        Path tmp = dir.resolve(ROW + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), n);
        try {
            Files.move(tmp, dir.resolve(ROW), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, dir.resolve(ROW), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private ChatSessionData readRow(Path dir) {
        Path f = dir.resolve(ROW);
        if (!Files.isRegularFile(f)) return null;
        try {
            JsonNode n = mapper.readTree(f.toFile());
            String chatId = req(n, "chatId");
            String agentId = req(n, "agentId");
            return new ChatSessionData(chatId, agentId, n.path("title").asText("New chat"),
                    n.path("mode").asText("DEFAULT"), "", n.hasNonNull("toolGroups") ? n.get("toolGroups").asText() : null,
                    n.path("inputTokens").asLong(), n.path("outputTokens").asLong(), n.path("cacheReadTokens").asLong(),
                    n.path("cacheCreationTokens").asLong(), Instant.parse(req(n, "createdAt")),
                    Instant.parse(req(n, "updatedAt")), n.path("messageCount").asInt(),
                    n.path("qualifierInputTokens").asLong(), n.path("qualifierOutputTokens").asLong(),
                    n.hasNonNull("principal") ? n.get("principal").asText() : null);
        } catch (Exception e) {
            log.warn("Skipping chat in {}: cannot read {}: {}", dir, ROW, e.getMessage());
            return null;
        }
    }

    private static String req(JsonNode n, String field) {
        if (!n.hasNonNull(field)) throw new IllegalStateException("missing " + field);
        return n.get(field).asText();
    }

    @Override
    public synchronized Optional<ChatSessionData> find(String chatId) {
        ChatSessionData row = rows.get(chatId);
        if (row == null) {
            Path dir = chatRoot.apply(chatId);
            row = readRow(dir);
            if (row == null || !row.chatId().equals(chatId)) return Optional.empty();
            rows.put(chatId, row);
        }
        String text = logs.get(chatId);
        if (text == null) {
            Path f = chatRoot.apply(chatId).resolve(safe(row.agentId())).resolve(LOG);
            try {
                text = Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
            } catch (IOException e) {
                log.warn("Cannot read the log of chat {}: {}", chatId, e.getMessage());
                return Optional.empty();
            }
            String fixed = InterruptedTurnRepair.repair(text, mapper);
            if (fixed != null) {
                try {
                    writeLog(f, text, fixed);
                    repaired.put(chatId, Boolean.TRUE);
                } catch (IOException e) {
                    log.warn("Cannot repair the log of chat {}: {}", chatId, e.getMessage());
                }
                text = fixed;
            }
            logs.put(chatId, text);
        }
        return Optional.of(row.withLoadedMessages(text));
    }

    /** True when the log of the chat was repaired after an interrupted run (the chat has been loaded). */
    public boolean wasRepaired(String chatId) {
        return repaired.containsKey(chatId);
    }

    @Override
    public synchronized List<ChatSessionData> list() {
        if (!scanned) {
            for (Path dir : chatDirs.get()) {
                ChatSessionData row = readRow(dir);
                if (row != null) rows.putIfAbsent(row.chatId(), row);
            }
            scanned = true;
        }
        List<ChatSessionData> out = new ArrayList<>(rows.values());
        out.sort(Comparator.comparing(ChatSessionData::updatedAt).reversed());
        return out;
    }

    @Override
    public synchronized void delete(String chatId) {
        rows.remove(chatId);
        logs.remove(chatId);
        repaired.remove(chatId);
        deleteTree(chatRoot.apply(chatId));
    }

    /** {@code <root>/<chatId>/<agentId>} */
    public Path historyDir(String chatId, String agentId) {
        return chatRoot.apply(chatId).resolve(safe(agentId));
    }

    static String safe(String name) {
        String s = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "_");
        return s.isEmpty() ? "_" : s;
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete history " + dir, e);
        }
    }
}
