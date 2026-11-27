package org.kendar.llm.web.commons;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.harness.agent.AgentService;
import org.kendar.llm.harness.commands.SlashCommands;
import org.kendar.llm.harness.identity.ChatAccess;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.session.SubAgentRunData;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.HarnessToolRegistry;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** The chat endpoints: sessions, runs, approvals and the event stream. Framework-free; errors are {@link ApiException}. */
public class ChatApi {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatApi.class);

    private static final int MAX_FILES = 50;
    /** Directory depth of the {@code @}-file suggestions. */
    private static final int MAX_DEPTH = 8;
    private static final String HIDDEN_DIR = ".jllm";

    private final AgentHarness agentService;
    private final ChatSessionService sessions;
    private final HarnessToolRegistry toolRegistry;
    private final SandboxExecutor sandbox;
    private final ObjectMapper mapper;
    private final HarnessPricing pricing;
    private final ChatAccess access;
    private final SubAgentRunLog subAgentRuns;

    /** @param access which chats the list shows to a user */
    public ChatApi(AgentHarness harness, ObjectMapper mapper, HarnessPricing pricing, ChatAccess access) {
        this.agentService = harness;
        this.sessions = harness.sessions();
        this.toolRegistry = harness.tools();
        this.sandbox = harness.sandbox();
        this.subAgentRuns = harness.subAgentRuns();
        this.mapper = mapper;
        this.pricing = pricing;
        this.access = access;
    }

    /** The slash-command caller of a web request: the user's id, admin flag; no user = no rights. */
    static SlashCommands.Caller caller(Principal user) {
        return user == null ? SlashCommands.Caller.web(null, false) : SlashCommands.Caller.web(user.id(), user.admin());
    }

    // ── sessions ──────────────────────────────────────────────────────────────

    public Map<String, Object> create(Map<String, String> body, Principal user) {
        ChatSessionData s = sessions.create(body == null ? null : body.get("title"), user == null ? null : user.id());
        agentService.commands().captureChatTools(s.chatId()); // /tools, /mcp, ... toggle within this set
        return summary(sessions.get(s.chatId()));
    }

    /** The chats the signed-in user may see. */
    public List<Map<String, Object>> list(Principal user) {
        if (user == null) return List.of();
        return sessions.list(access.visibility(user)).stream()
                .filter(s -> access.canSee(user, s)) // defence in depth: the store already filtered
                .map(this::summary).toList();
    }

    public Map<String, Object> get(String id) {
        return sessions.find(id).map(s -> {
            Map<String, Object> m = summary(s);
            m.put("messages", sessions.messages(s));
            m.put("pendingApprovals", agentService.pendingApprovals(id));
            m.put("lastSeq", agentService.lastSeq(id));
            return m;
        }).orElseThrow(() -> unknown(id));
    }

    /** Originals of the shell calls that were replaced by stubs in the context (audit; never sent to the model). */
    public List<JsonNode> references(String id) {
        known(id);
        return sessions.references(id);
    }

    public void delete(String id) {
        known(id);
        agentService.deleteChat(id); // cancel, context state, sub-agent runs, the chat row and log
        sandbox.deleteWorkDir(id);
    }

    /** Sub-agent transcripts of a chat, optionally only those spawned by one "task" tool call. */
    public List<Map<String, Object>> subAgents(String id, String toolUseId) {
        known(id);
        List<SubAgentRunData> runs = toolUseId == null ? subAgentRuns.list(id) : subAgentRuns.forToolUse(id, toolUseId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SubAgentRunData r : runs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("toolUseId", r.toolUseId());
            m.put("prompt", r.prompt());
            m.put("status", r.status());
            m.put("answer", r.answer());
            m.put("inputTokens", r.inputTokens());
            m.put("outputTokens", r.outputTokens());
            m.put("cacheReadTokens", r.cacheReadTokens());
            m.put("cacheCreationTokens", r.cacheCreationTokens());
            m.put("createdAt", r.createdAt());
            List<Object> messages = new ArrayList<>();
            if (r.messagesJson() != null) {
                for (String line : r.messagesJson().split("\n")) {
                    if (line.isBlank()) continue;
                    try {
                        messages.add(mapper.readTree(line));
                    } catch (IOException e) {
                        messages.add(mapper.nullNode());
                    }
                }
            }
            m.put("messages", messages);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> clear(String id) {
        known(id);
        if (agentService.isRunning(id)) throw new ApiException(409, "A run is in progress");
        sessions.saveMessages(id, mapper.createArrayNode());
        return Map.of("cleared", true);
    }

    public Map<String, Object> compact(String id, Map<String, String> body) {
        known(id);
        try {
            var r = agentService.compact(id, body == null ? null : body.get("focus"));
            return Map.of("changed", r.changed(), "tokensBefore", r.tokensBefore(),
                    "tokensAfter", r.tokensAfter(), "clearedToolResults", r.clearedToolResults());
        } catch (AgentService.RunAlreadyActiveException e) {
            throw new ApiException(409, "A run is in progress");
        } catch (RuntimeException e) { // boundary: handler boundary: mapped to 502
            log.warn("Compaction of chat {} failed", id, e);
            throw new ApiException(502, String.valueOf(e.getMessage()));
        }
    }

    // ── runs ──────────────────────────────────────────────────────────────────

    /** Answers 202 with {@code started}, or 200 with the output of a slash command. */
    public Reply<Map<String, Object>> send(String id, Map<String, String> body, Principal user) {
        String text = body.get("text");
        if (text == null || text.isBlank()) throw new ApiException(400, "text is required");
        known(id);
        boolean inMenu = agentService.commands().menuActive(id);
        if (inMenu || agentService.commands().handles(text.strip())) {
            var caller = caller(user);
            SlashCommands.Result r;
            try {
                r = inMenu ? agentService.commands().menuInput(id, text.strip(), caller)
                        : agentService.commands().execute(id, text.strip(), caller);
            } catch (RuntimeException e) { // boundary: handler boundary: mapped to 400
                throw new ApiException(400, String.valueOf(e.getMessage()));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("command", true);
            m.put("menu", r.menu());
            m.put("output", r.output() == null ? "" : r.output());
            m.put("switchTo", r.switchToChatId());
            m.put("refresh", r.refresh());
            return Reply.status(200, m);
        }
        try {
            agentService.start(id, text);
            return Reply.status(202, Map.of("started", true));
        } catch (AgentService.RunAlreadyActiveException e) {
            throw new ApiException(409, e.getMessage());
        }
    }

    public Map<String, Object> cancel(String id, boolean kill) {
        agentService.cancel(id, kill);
        return Map.of("running", agentService.isRunning(id));
    }

    public Map<String, Object> mode(String id, Map<String, String> body) {
        known(id);
        PermissionMode mode = PermissionMode.parse(body.get("mode"));
        try {
            sessions.setMode(id, mode);
        } catch (ChatSessionService.ModeNotAllowedException e) {
            throw new ApiException(403, e.getMessage());
        }
        return Map.of("mode", mode.name());
    }

    /**
     * Streams the chat's events to {@code sink}: first those after {@code after}, then the live ones, until the stream
     * ends (completion, timeout, a failed write), which frees the subscription.
     *
     * @return the heartbeat tick: the framework calls it at {@link #HEARTBEAT_MS} intervals; it writes a comment line,
     * so that a closed client is noticed at the next write even on an idle stream
     */
    public Runnable events(String id, long after, EventSink sink) {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<Runnable> unsubscribe = new AtomicReference<>();
        // runs once, whichever comes first: completion, timeout, error, failed write (closed client)
        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) return;
            Runnable u = unsubscribe.getAndSet(() -> { });
            if (u != null) u.run();
        };
        sink.onClose(cleanup);
        sink.onTimeout(cleanup);
        Runnable subscription = agentService.subscribe(id, after, ev -> {
            if (closed.get()) return;
            if (AgentService.DROPPED_EVENT.equals(ev.type())) { // fell behind: the client reconnects and replays
                cleanup.run();
                sink.complete();
                return;
            }
            try {
                // a streaming delta (seq 0) is not replayable: no id, so the browser's Last-Event-ID stays put
                String eventId = ev.seq() > 0 ? String.valueOf(ev.seq()) : null;
                sink.send(eventId, ev.type(), mapper.writeValueAsString(ev));
            } catch (IOException | IllegalStateException e) {
                cleanup.run();
                sink.completeWithError(e);
            }
        });
        if (!unsubscribe.compareAndSet(null, subscription)) subscription.run(); // closed while subscribing
        return () -> {
            if (closed.get()) return;
            try {
                sink.comment("ping");
            } catch (IOException | IllegalStateException e) {
                cleanup.run();
                sink.completeWithError(e);
            }
        };
    }

    /** A comment line sent this often on an idle event stream: a closed client is noticed at the next write. */
    public static final long HEARTBEAT_MS = 15_000L;

    /**
     * The files loaded at startup (settings, skills, instruction files, agent prompts) with suspicious content: the
     * page shows them once loaded. Kept in memory only, never part of a chat; files loaded during a chat come as a
     * {@code content_warning} event on its stream.
     */
    public List<JsonNode> contentWarnings() {
        return agentService.contentWarnings().stream().map(w -> (JsonNode) w.toJson(mapper)).toList();
    }

    // ── approvals ─────────────────────────────────────────────────────────────

    public Object approvals(String id) {
        return agentService.pendingApprovals(id);
    }

    public Map<String, Object> resolve(String id, String approvalId, Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        String edited = body.get("editedText") instanceof String s ? s : null;
        boolean remember = Boolean.TRUE.equals(body.get("remember"));
        boolean rememberDir = Boolean.TRUE.equals(body.get("rememberDir"));
        // "allow writes in this folder" with a typed folder: an invalid one must not approve the call at all
        if (approved && rememberDir && edited != null && !edited.isBlank()
                && ApprovalService.grantDirOf(agentService.workDir(id), edited) == null) {
            throw new ApiException(400, "Invalid folder '" + edited
                    + "': give a folder inside the project, relative to it, without '..' and not inside .git");
        }
        if (!agentService.resolveApproval(id, approvalId, approved, edited, remember, rememberDir)) {
            throw new ApiException(404, "Approval no longer pending");
        }
        return Map.of("resolved", true);
    }

    // ── side data ─────────────────────────────────────────────────────────────

    public List<String> files(String id, String q) {
        String needle = q.toLowerCase();
        var fb = sandbox.files(id, null);
        if (fb != null) { // docker daemon mode: the files are in the container
            try {
                return fb.glob("**/*").stream().filter(p -> !p.startsWith(".jllm/"))
                        .filter(p -> needle.isEmpty() || p.toLowerCase().contains(needle)).limit(MAX_FILES).toList();
            } catch (IOException | RuntimeException e) { // boundary: handler boundary: an unreachable listing is empty
                log.debug("File listing of chat {} failed: {}", id, e.toString());
                return List.of();
            }
        }
        Path root = sandbox.workDir(id).toAbsolutePath().normalize();
        return suggestions(root, needle);
    }

    /**
     * Files below {@code root} whose relative path contains {@code needle}: the harness directory is skipped,
     * unreadable directories and files are left out instead of failing the request.
     */
    static List<String> suggestions(Path root, String needle) {
        List<String> found = new ArrayList<>();
        if (!Files.isDirectory(root)) return found;
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                            if (!dir.equals(root) && HIDDEN_DIR.equalsIgnoreCase(String.valueOf(dir.getFileName()))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                            if (!a.isRegularFile()) return FileVisitResult.CONTINUE;
                            Path rel = root.relativize(file.toAbsolutePath().normalize());
                            if (rel.startsWith("..")) return FileVisitResult.CONTINUE;
                            String p = rel.toString().replace('\\', '/');
                            if (needle.isEmpty() || p.toLowerCase().contains(needle)) found.add(p);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE; // unreadable: left out
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            // whatever was found so far
            log.debug("File walk stopped early: {}", e.toString());
        }
        return found.stream().sorted().limit(MAX_FILES).toList();
    }

    public Map<String, Object> usage(String id) {
        return sessions.find(id).map(this::usageOf).orElseThrow(() -> unknown(id));
    }

    /** The slash commands the caller may use, and a prompt shortcut per tool group. */
    public List<Map<String, String>> commands(Principal user) {
        List<Map<String, String>> out = new ArrayList<>();
        for (var c : agentService.commands().list(caller(user))) out.add(command(c.name(), c.description(), "server"));
        Set<String> groups = new TreeSet<>();
        for (HarnessTool t : toolRegistry.all()) groups.add(t.group());
        for (String g : groups) {
            out.add(command("/" + g.toLowerCase().replace('/', '-'),
                    "Ask the agent to use the " + g + " tools", "prompt:Use the " + g + " tools to: "));
        }
        return out;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void known(String id) {
        if (sessions.find(id).isEmpty()) throw unknown(id);
    }

    private static ApiException unknown(String id) {
        return new ApiException(404, "Unknown chat " + id);
    }

    private Map<String, Object> summary(ChatSessionData s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chatId", s.chatId());
        m.put("title", s.title());
        m.put("mode", s.mode());
        m.put("running", agentService.isRunning(s.chatId()));
        m.put("updatedAt", String.valueOf(s.updatedAt()));
        m.put("createdAt", String.valueOf(s.createdAt()));
        m.put("messageCount", s.messageCount());
        m.put("usage", usageOf(s));
        return m;
    }

    private Map<String, Object> usageOf(ChatSessionData s) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("inputTokens", s.inputTokens());
        u.put("outputTokens", s.outputTokens());
        u.put("cacheReadTokens", s.cacheReadTokens());
        u.put("cacheCreationTokens", s.cacheCreationTokens());
        u.put("qualifierInputTokens", s.qualifierInputTokens());
        u.put("qualifierOutputTokens", s.qualifierOutputTokens());
        u.put("estimatedCostUsd", pricing.estimate(s.inputTokens(), s.outputTokens(),
                s.cacheReadTokens(), s.cacheCreationTokens()));
        return u;
    }

    private static Map<String, String> command(String name, String description, String action) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("action", action);
        return m;
    }
}
