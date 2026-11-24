package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.context.ClearedResult;
import org.kendar.llm.harness.session.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per chat, the tool_use ids whose results carry untrusted output. The chat stays tainted while one of them is
 * still in its context. The set is stored with the chat ({@link ChatSessionService#taintedToolUses}), so a
 * restarted harness keeps gating. {@code "*"} stands for output that cannot be tied to a call of the log;
 * it lasts until the next user message.
 */
public final class TaintTracker {
    private static final Logger log = LoggerFactory.getLogger(TaintTracker.class);
    private static final String TAINT_RUN = "*";

    private final ChatSessionService sessions;
    private final Map<String, Set<String>> tainted = new ConcurrentHashMap<>();

    public TaintTracker(ChatSessionService sessions) {
        this.sessions = sessions;
    }

    /** The tainted ids of the chat, loaded from the stored chat on first use. */
    private Set<String> taintSet(String chatId) {
        return tainted.computeIfAbsent(chatId, k -> {
            Set<String> s = ConcurrentHashMap.newKeySet();
            try {
                s.addAll(sessions.taintedToolUses(k));
            } catch (RuntimeException e) { // boundary: persisted taint is best effort
                log.warn("Cannot load the taint state of chat {}: {}", k, e.toString());
            }
            return s;
        });
    }

    private void save(String chatId) {
        Set<String> ids = tainted.get(chatId);
        try {
            sessions.setTaintedToolUses(chatId, ids == null ? List.of() : new ArrayList<>(ids));
        } catch (RuntimeException e) { // boundary: persisted taint is best effort
            log.warn("Cannot store the taint state of chat {}: {}", chatId, e.toString());
        }
    }

    /** Drops the in-memory taint state of a chat (the chat was deleted). */
    public void forget(String chatId) {
        if (chatId != null) tainted.remove(chatId);
    }

    boolean isTainted(String chatId) {
        return !taintSet(chatId).isEmpty();
    }

    void taint(String chatId, String toolUseId) {
        // a call made by a script ("<run_js id>/<uuid>") is answered through the run_js result
        String id = toolUseId == null ? null : toolUseId.contains("/")
                ? toolUseId.substring(0, toolUseId.indexOf('/')) : toolUseId;
        if (id == null || id.isEmpty() || id.equals("js")) id = TAINT_RUN;
        if (taintSet(chatId).add(id)) save(chatId);
    }

    /** Untrusted output a sub-agent read reaches the parent through the result of its task call. */
    void passToParent(String chatId, Set<String> ownToolUses, String parentToolUseId) {
        Set<String> ids = taintSet(chatId);
        if (ids.removeIf(ownToolUses::contains)) {
            taint(chatId, parentToolUseId);
            save(chatId); // taint() saves only when the parent id is new
        }
    }

    /**
     * Drops the tainted calls whose result left the log. A result cleared by compaction keeps its taint until the
     * next user message: the model may still act on what it read before the clearing.
     */
    void prune(String chatId, ArrayNode messages, boolean newUserMessage) {
        Set<String> ids = taintSet(chatId);
        if (ids.isEmpty()) return;
        boolean changed = newUserMessage && ids.remove(TAINT_RUN);
        Set<String> inContext = new HashSet<>();
        Set<String> cleared = new HashSet<>();
        for (JsonNode m : messages) {
            for (JsonNode b : m.path("content")) {
                if ("tool_result".equals(b.path("type").asText())) {
                    String id = b.path("tool_use_id").asText();
                    if (ClearedResult.is(b.path("content").asText(""))) cleared.add(id);
                    else inContext.add(id);
                }
            }
        }
        changed |= ids.removeIf(id -> !TAINT_RUN.equals(id) && !inContext.contains(id)
                && !(cleared.contains(id) && !newUserMessage));
        if (changed) save(chatId);
    }
}
