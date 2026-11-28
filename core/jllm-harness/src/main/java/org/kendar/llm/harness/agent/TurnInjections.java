package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.instructions.ScopedInstructions;
import org.kendar.llm.harness.safety.ContentWarnings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** User messages and instruction files the loop appends to the log at turn boundaries. */
public final class TurnInjections {
    private static final Logger log = LoggerFactory.getLogger(TurnInjections.class);

    private final AgentLoop.Deps deps;
    /** Per chat: the scoped instruction files whose suspicious content was already reported (once per chat). */
    private final Map<String, Set<String>> contentWarned = new ConcurrentHashMap<>();

    TurnInjections(AgentLoop.Deps deps) {
        this.deps = deps;
    }

    /** Drops what is kept in memory about the scoped files of a chat (the chat was deleted). */
    public void forgetContentWarnings(String chatId) {
        if (chatId != null) contentWarned.remove(chatId);
    }

    static void appendUserText(ArrayNode messages, String text) {
        ObjectNode m = messages.addObject();
        m.put("role", "user");
        m.putArray("content").addObject().put("type", "text").put("text", text);
    }

    /** Appends every queued steering message as a user message. @return whether any was applied */
    boolean steering(AgentLoop.Inbox inbox, ArrayNode messages, EventSink sink, String chatId, boolean persist) {
        if (inbox == null) return false;
        boolean any = false;
        for (String text = inbox.pollSteering(); text != null; text = inbox.pollSteering()) {
            appendUserText(messages, text);
            sink.emit("steer_applied", deps.mapper().createObjectNode().put("text", text).put("kind", "steer"));
            any = true;
        }
        if (any && persist) deps.sessions().saveMessages(chatId, messages);
        return any;
    }

    /** Appends the next follow-up as a user message. @return whether one was applied */
    boolean followUp(AgentLoop.Inbox inbox, ArrayNode messages, EventSink sink, String chatId, boolean persist) {
        if (inbox == null) return false;
        String text = inbox.pollFollowUp();
        if (text == null) return false;
        appendUserText(messages, text);
        sink.emit("steer_applied", deps.mapper().createObjectNode().put("text", text).put("kind", "followup"));
        if (persist) deps.sessions().saveMessages(chatId, messages);
        return true;
    }

    /**
     * After the built-in file tools worked on paths inside the work dir: appends to the tool results the instruction
     * files of the directories down to those paths that the conversation does not hold yet. Appended, not put in the
     * system prompt, so the cached prefix is kept; the model sees them on its next request.
     */
    void scoped(ArrayNode messages, List<JsonNode> toolUses, ArrayNode results, Map<String, HarnessTool> byName,
                ToolContext ctx, EventSink sink, String label, String chatId) {
        ScopedInstructions scoped = deps.scopedInstructions();
        if (scoped == null || ctx.files() != null || toolUses.isEmpty()) return; // remote files: nothing on this disk
        UserPolicy.Scoped scopedMode = deps.identity().policyOf(chatId).scoped();
        if (scopedMode == UserPolicy.Scoped.OFF) return;
        boolean userContent = scopedMode == UserPolicy.Scoped.USER_CONTENT;
        Set<String> failed = new HashSet<>();
        for (JsonNode r : results) {
            if (r.path("is_error").asBoolean(false)) failed.add(r.path("tool_use_id").asText());
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode use : toolUses) {
            HarnessTool t = byName.get(use.path("name").asText());
            if (t == null || !deps.builtinFileTools().contains(t) || failed.contains(use.path("id").asText())) continue;
            // plan mode: a write went to the scratch dir, not to the project
            if (t.kind() != ToolKind.READ_ONLY && ctx.readOnlyWork()) continue;
            paths.add(ScopedInstructions.pathOf(t.name(), use.path("input")));
        }
        if (paths.isEmpty()) return;
        List<ScopedInstructions.Block> added;
        try {
            added = scoped.discover(ctx.workDir(), paths, messages);
        } catch (RuntimeException e) { // boundary: best effort: the turn goes on without the scoped files
            log.warn("Cannot look up scoped instruction files: {}", e.toString());
            return;
        }
        for (var b : added) {
            results.addObject().put("type", "text").put("text", b.render(userContent));
            ObjectNode ev = deps.mapper().createObjectNode().put("file", b.file()).put("dir", b.dir());
            if (label != null) ev.put("agent", label);
            sink.emit("instructions_loaded", ev);
            if (!b.findings().isEmpty() && contentWarned
                    .computeIfAbsent(chatId, k -> ConcurrentHashMap.newKeySet()).add(b.file())) {
                // loaded unchanged; the user is told, the log gets a warning, nothing is stored or sent to the model
                ContentWarnings.logWarning(b.file(), b.findings());
                ObjectNode warn = new ContentWarnings.Warning(b.file(), b.findings()).toJson(deps.mapper());
                if (label != null) warn.put("agent", label);
                sink.emit(ContentWarnings.EVENT, warn);
            }
        }
    }
}
