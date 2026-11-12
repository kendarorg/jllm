package org.kendar.llm.harness.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.util.Uuids;

import java.util.*;

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
        return create(title, null);
    }

    /** @param principal id of the user the chat belongs to, or null for single-user use */
    public ChatSessionData create(String title, String principal) {
        return store.save(ChatSessionData.create(Uuids.v7String(), agentId + "-" + Uuids.v7String(),
                title == null || title.isBlank() ? "New chat" : title, principal));
    }

    /** The principal id the chat was created for, or null. */
    public String principal(String chatId) {
        return get(chatId).principal();
    }

    public Optional<ChatSessionData> find(String chatId) {
        return store.find(chatId);
    }

    public ChatSessionData get(String chatId) {
        return store.find(chatId).orElseThrow(() -> new IllegalArgumentException("Unknown chat " + chatId));
    }

    /** A chat by full id or unique id prefix. @throws IllegalArgumentException when unknown or ambiguous */
    public ChatSessionData resolve(String idOrPrefix) {
        String p = idOrPrefix == null ? "" : idOrPrefix.strip();
        Optional<ChatSessionData> exact = p.isEmpty() ? Optional.empty() : store.find(p);
        if (exact.isPresent()) return exact.get();
        List<ChatSessionData> hits = p.isEmpty() ? List.of()
                : store.list().stream().filter(c -> c.chatId().startsWith(p)).toList();
        if (hits.isEmpty()) throw new IllegalArgumentException("Unknown chat " + idOrPrefix);
        if (hits.size() > 1) throw new IllegalArgumentException("Ambiguous chat id prefix " + idOrPrefix);
        return get(hits.get(0).chatId());
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
                if (!line.isBlank() && !ChatSessionData.isReference(line)) out.add(mapper.readTree(line));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt message log for chat " + s.chatId(), e);
        }
    }

    public synchronized void saveMessages(String chatId, ArrayNode messages) {
        saveMessages(chatId, messages, List.of());
    }

    /**
     * Saves the context messages and keeps the reference lines ({@code {"ignore":true,"replaces":<tool_use id>,...}})
     * of the log: each one stays right after the message holding the tool result it replaces, and is dropped when
     * that message is gone (compaction, /clear). {@code newReferences} are added to the existing ones.
     */
    public synchronized void saveMessages(String chatId, ArrayNode messages, List<? extends JsonNode> newReferences) {
        ChatSessionData current = get(chatId);
        Map<String, JsonNode> refs = new LinkedHashMap<>();
        for (JsonNode r : references(current)) refs.put(r.path("replaces").asText(), r);
        for (JsonNode r : newReferences) refs.put(r.path("replaces").asText(), r);
        if (refs.isEmpty()) {
            store.save(current.withMessagesJson(toJsonLines(messages)));
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (JsonNode m : messages) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(mapper.writeValueAsString(m));
                if (!m.path("content").isArray()) continue;
                for (JsonNode block : m.path("content")) {
                    if (!"tool_result".equals(block.path("type").asText())) continue;
                    JsonNode ref = refs.get(block.path("tool_use_id").asText());
                    if (ref != null) sb.append('\n').append(mapper.writeValueAsString(ref));
                }
            }
            store.save(current.withMessagesJson(sb.toString()));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize messages", e);
        }
    }

    /** The reference lines of the log (originals of rewritten tool calls), for the UI and for audit. */
    public List<JsonNode> references(String chatId) {
        return references(get(chatId));
    }

    private List<JsonNode> references(ChatSessionData s) {
        List<JsonNode> out = new ArrayList<>();
        String raw = s.messagesJson();
        if (raw == null || raw.isBlank()) return out;
        try {
            for (String line : raw.split("\n")) {
                if (!line.isBlank() && ChatSessionData.isReference(line)) out.add(mapper.readTree(line));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt message log for chat " + s.chatId(), e);
        }
        return out;
    }

    public synchronized void addQualifierUsage(String chatId, Usage u) {
        store.save(get(chatId).withQualifierUsageAdded(u.inputTokens(), u.outputTokens()));
    }

    /** One message per line. */
    public String toJsonLines(Iterable<JsonNode> messages) {
        try {
            StringBuilder sb = new StringBuilder();
            for (JsonNode m : messages) {
                if (!sb.isEmpty()) sb.append('\n');
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
