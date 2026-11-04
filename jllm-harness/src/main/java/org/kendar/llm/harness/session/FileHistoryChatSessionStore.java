package org.kendar.llm.harness.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Keeps the chats in a delegate store and mirrors each conversation to {@code <root>/<chatId>/<agentId>/messages.jsonl}.
 * Writing the history never fails the chat.
 */
public class FileHistoryChatSessionStore implements ChatSessionStore {

    private static final Logger log = LoggerFactory.getLogger(FileHistoryChatSessionStore.class);

    private final ChatSessionStore delegate;
    private final Function<String, Path> chatRoot;
    private final Map<String, String> written = new ConcurrentHashMap<>();

    public FileHistoryChatSessionStore(ChatSessionStore delegate, Path root) {
        this.delegate = delegate;
        Path r = root.toAbsolutePath().normalize();
        this.chatRoot = id -> r.resolve(safe(id));
    }

    /** {@code chatRoot(chatId)/<agentId>/messages.jsonl}: the history of each chat lives in a directory of its own. */
    public FileHistoryChatSessionStore(ChatSessionStore delegate, Function<String, Path> chatRoot) {
        this.delegate = delegate;
        this.chatRoot = chatRoot;
    }

    @Override
    public ChatSessionData save(ChatSessionData session) {
        ChatSessionData saved = delegate.save(session);
        String messages = saved.messagesJson();
        if (messages != null && !messages.isEmpty() && !messages.equals(written.get(saved.chatId()))) {
            try {
                Path dir = historyDir(saved.chatId(), saved.agentId());
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("messages.jsonl"), messages, StandardCharsets.UTF_8);
                written.put(saved.chatId(), messages);
            } catch (IOException | RuntimeException e) {
                log.warn("Cannot write the history of chat {}: {}", saved.chatId(), e.getMessage());
            }
        }
        return saved;
    }

    @Override
    public Optional<ChatSessionData> find(String chatId) {
        return delegate.find(chatId);
    }

    @Override
    public List<ChatSessionData> list() {
        return delegate.list();
    }

    @Override
    public void delete(String chatId) {
        delegate.delete(chatId);
        written.remove(chatId);
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
