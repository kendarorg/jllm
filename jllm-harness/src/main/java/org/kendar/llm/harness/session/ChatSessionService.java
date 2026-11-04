package org.kendar.llm.harness.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.util.Uuids;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Session rows are scoped strictly by chatId; nothing here reads across chats. */
public class ChatSessionService {

    private final ChatSessionStore store;
    private final String agentId;
    private final ObjectMapper mapper;

    public ChatSessionService(ChatSessionStore store, String agentId, ObjectMapper mapper) {
        this.store = store;
        this.agentId = agentId;
        this.mapper = mapper;
    }

    /** Every chat gets its own agent instance id: {@code <configured agent id>-<uuid>}; it also names the scratch dir. */
    public ChatSessionData create(String title) {
        return store.save(ChatSessionData.create(Uuids.v7String(), agentId + "-" + Uuids.v7String(),
                title == null || title.isBlank() ? "New chat" : title));
    }

    public Optional<ChatSessionData> find(String chatId) {
        return store.find(chatId);
    }

    public ChatSessionData get(String chatId) {
        return store.find(chatId).orElseThrow(() -> new IllegalArgumentException("Unknown chat " + chatId));
    }

    public List<ChatSessionData> list() {
        return store.list();
    }

    public void delete(String chatId) {
        store.delete(chatId);
    }

    /** Deterministic and independent of conversation content, so it is always a cache hit. */
    public String pinnedPrefix(ChatSessionData s) {
        return "chatId=" + s.chatId() + "; agentId=" + s.agentId();
    }

    /** The log is stored as JSON lines: one message per line. */
    public ArrayNode messages(ChatSessionData s) {
        try {
            String raw = s.messagesJson();
            ArrayNode out = mapper.createArrayNode();
            if (raw == null || raw.isBlank()) return out;
            for (String line : raw.split("\n")) {
                if (!line.isBlank()) out.add(mapper.readTree(line));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt message log for chat " + s.chatId(), e);
        }
    }

    public synchronized void saveMessages(String chatId, ArrayNode messages) {
        store.save(get(chatId).withMessagesJson(toJsonLines(messages)));
    }

    /** One message per line. */
    public String toJsonLines(Iterable<JsonNode> messages) {
        try {
            StringBuilder sb = new StringBuilder();
            for (JsonNode m : messages) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(mapper.writeValueAsString(m));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize messages", e);
        }
    }

    public synchronized void addUsage(String chatId, Usage u) {
        store.save(get(chatId).withUsageAdded(u.inputTokens(), u.outputTokens(), u.cacheReadTokens(),
                u.cacheCreationTokens()));
    }

    public synchronized void setMode(String chatId, PermissionMode mode) {
        store.save(get(chatId).withMode(mode.name()));
    }

    private static final String ALL_GROUPS = "*";

    /** True once the tool set of this chat has been fixed (possibly to "all tools"). */
    public boolean hasToolGroups(String chatId) {
        return get(chatId).toolGroups() != null;
    }

    /** The groups fixed for this chat, or null for "all tools". Only meaningful after {@link #hasToolGroups}. */
    public Set<String> toolGroups(String chatId) {
        String raw = get(chatId).toolGroups();
        if (raw == null || ALL_GROUPS.equals(raw)) return null;
        return new TreeSet<>(Arrays.asList(raw.split(",")));
    }

    /** @param groups null = all tools */
    public synchronized void setToolGroups(String chatId, Set<String> groups) {
        store.save(get(chatId).withToolGroups(groups == null ? ALL_GROUPS : String.join(",", new TreeSet<>(groups))));
    }

    /** Grows the fixed tool set; a no-op when the chat already offers every tool. */
    public synchronized void addToolGroups(String chatId, Collection<String> groups) {
        Set<String> current = toolGroups(chatId);
        if (current == null) return;
        current.addAll(groups);
        setToolGroups(chatId, current);
    }

    public synchronized void setTitle(String chatId, String title) {
        store.save(get(chatId).withTitle(title));
    }
}
