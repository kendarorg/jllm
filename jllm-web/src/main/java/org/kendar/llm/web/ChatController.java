package org.kendar.llm.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.harness.agent.AgentService;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.session.SubAgentRunData;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@RestController
public class ChatController {

    private static final int MAX_FILES = 50;
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
    /** A comment line sent this often on an idle event stream: a closed client is noticed at the next write. */
    static final long HEARTBEAT_MS = 15_000L;
    /** Directory depth of the {@code @}-file suggestions. */
    private static final int MAX_DEPTH = 8;
    private static final String HIDDEN_DIR = ".jllm";

    private static final java.util.concurrent.ScheduledExecutorService HEARTBEATS =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private final AgentHarness agentService;
    private final ChatSessionService sessions;
    private final HarnessToolRegistry toolRegistry;
    private final SandboxExecutor sandbox;
    private final ObjectMapper mapper;
    private final HarnessPricing pricing;
    private final UserInterceptor users;
    private final SubAgentRunLog subAgentRuns;

    public ChatController(AgentHarness harness, ObjectMapper mapper, HarnessPricing pricing) {
        this(harness, mapper, pricing, null);
    }

    /** @param users resolves the signed-in user on the open {@code /api/chat/commands} endpoint; may be null */
    @org.springframework.beans.factory.annotation.Autowired
    public ChatController(AgentHarness harness, ObjectMapper mapper, HarnessPricing pricing, UserInterceptor users) {
        this.agentService = harness;
        this.sessions = harness.sessions();
        this.toolRegistry = harness.tools();
        this.sandbox = harness.sandbox();
        this.subAgentRuns = harness.subAgentRuns();
        this.mapper = mapper;
        this.pricing = pricing;
        this.users = users;
    }

    /** The slash-command caller of a web request: the user's id, admin flag; no user = no rights. */
    static org.kendar.llm.harness.commands.SlashCommands.Caller caller(WebUser user) {
        return user == null ? org.kendar.llm.harness.commands.SlashCommands.Caller.web(null, false)
                : org.kendar.llm.harness.commands.SlashCommands.Caller.web(user.id(), user.admin());
    }

// ── sessions ──────────────────────────────────────────────────────────────

    @PostMapping("/api/chat")
    public Map<String, Object> create(@RequestBody(required = false) Map<String, String> body,
                                      @RequestAttribute(value = UserInterceptor.ATTR, required = false) WebUser user) {
        ChatSessionData s = sessions.create(body == null ? null : body.get("title"), user == null ? null : user.id());
        agentService.commands().captureChatTools(s.chatId()); // /tools, /mcp, ... toggle within this set
        return summary(sessions.get(s.chatId()));
    }

    /** The chats of the signed-in user; the admin sees all of them. */
    @GetMapping("/api/chat")
    public List<Map<String, Object>> list(
            @RequestAttribute(value = UserInterceptor.ATTR, required = false) WebUser user) {
        if (user == null) return List.of();
        return sessions.list().stream()
                .filter(s -> s.principal() != null && (user.admin() || user.id().equals(s.principal())))
                .map(this::summary).toList();
    }

    @GetMapping("/api/chat/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return sessions.find(id).map(s -> {
            Map<String, Object> m = summary(s);
            m.put("messages", sessions.messages(s));
            m.put("pendingApprovals", agentService.pendingApprovals(id));
            m.put("lastSeq", agentService.lastSeq(id));
            return ResponseEntity.ok(m);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Originals of the shell calls that were replaced by stubs in the context (audit; never sent to the model). */
    @GetMapping("/api/chat/{id}/references")
    public ResponseEntity<List<com.fasterxml.jackson.databind.JsonNode>> references(@PathVariable String id) {
        if (sessions.find(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(sessions.references(id));
    }

    @DeleteMapping("/api/chat/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (sessions.find(id).isEmpty()) return ResponseEntity.notFound().build();
        agentService.deleteChat(id); // cancel, context state, sub-agent runs, the chat row and log
        sandbox.deleteWorkDir(id);
        return ResponseEntity.noContent().build();
    }

    /** Sub-agent transcripts of a chat, optionally only those spawned by one "task" tool call. */
    @GetMapping("/api/chat/{id}/subagents")
    public ResponseEntity<List<Map<String, Object>>> subAgents(@PathVariable String id,
                                                               @RequestParam(required = false) String toolUseId) {
        if (sessions.find(id).isEmpty()) return ResponseEntity.notFound().build();
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
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/chat/{id}/clear")
    public ResponseEntity<Map<String, Object>> clear(@PathVariable String id) {
        if (sessions.find(id).isEmpty()) return ResponseEntity.notFound().build();
        if (agentService.isRunning(id)) return conflict("A run is in progress");
        sessions.saveMessages(id, mapper.createArrayNode());
        return ResponseEntity.ok(Map.of("cleared", true));
    }

    @PostMapping("/api/chat/{id}/compact")
    public ResponseEntity<Map<String, Object>> compact(@PathVariable String id,
                                                       @RequestBody(required = false) Map<String, String> body) {
        if (sessions.find(id).isEmpty()) return ResponseEntity.notFound().build();
        try {
            var r = agentService.compact(id, body == null ? null : body.get("focus"));
            return ResponseEntity.ok(Map.of("changed", r.changed(), "tokensBefore", r.tokensBefore(),
                    "tokensAfter", r.tokensAfter(), "clearedToolResults", r.clearedToolResults()));
        } catch (AgentService.RunAlreadyActiveException e) {
            return conflict("A run is in progress");
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // ── runs ──────────────────────────────────────────────────────────────────

    @PostMapping("/api/chat/{id}/messages")
    public ResponseEntity<Map<String, Object>> send(@PathVariable String id, @RequestBody Map<String, String> body,
                                                    @RequestAttribute(value = UserInterceptor.ATTR, required = false)
                                                    WebUser user) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        if (sessions.find(id).isEmpty()) return ResponseEntity.notFound().build();
        boolean inMenu = agentService.commands().menuActive(id);
        if (inMenu || agentService.commands().handles(text.strip())) {
            var caller = caller(user);
            org.kendar.llm.harness.commands.SlashCommands.Result r;
            try {
                r = inMenu ? agentService.commands().menuInput(id, text.strip(), caller)
                        : agentService.commands().execute(id, text.strip(), caller);
            } catch (RuntimeException e) {
                return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("command", true);
            m.put("menu", r.menu());
            m.put("output", r.output() == null ? "" : r.output());
            m.put("switchTo", r.switchToChatId());
            m.put("refresh", r.refresh());
            return ResponseEntity.ok(m);
        }
        try {
            agentService.start(id, text);
            return ResponseEntity.accepted().body(Map.of("started", true));
        } catch (AgentService.RunAlreadyActiveException e) {
            return conflict(e.getMessage());
        }
    }

    @PostMapping("/api/chat/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id,
                                      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean kill) {
        agentService.cancel(id, kill);
        return Map.of("running", agentService.isRunning(id));
    }

    @PostMapping("/api/chat/{id}/mode")
    public ResponseEntity<Map<String, Object>> mode(@PathVariable String id, @RequestBody Map<String, String> body) {
        if (sessions.find(id).isEmpty()) return ResponseEntity.notFound().build();
        PermissionMode mode = PermissionMode.parse(body.get("mode"));
        sessions.setMode(id, mode);
        return ResponseEntity.ok(Map.of("mode", mode.name()));
    }

    @GetMapping(value = "/api/chat/{id}/events", produces = "text/event-stream")
    public SseEmitter events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Runnable> unsubscribe = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> heartbeat =
                new java.util.concurrent.atomic.AtomicReference<>();
        // runs once, whichever comes first: completion, timeout, error, failed write (closed client)
        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) return;
            var hb = heartbeat.get();
            if (hb != null) hb.cancel(false);
            Runnable u = unsubscribe.getAndSet(() -> { });
            if (u != null) u.run();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(e -> cleanup.run());
        Runnable subscription = agentService.subscribe(id, after, ev -> {
            if (closed.get()) return;
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(ev.seq()))
                        .name(ev.type())
                        .data(mapper.writeValueAsString(ev)));
            } catch (IOException | IllegalStateException e) {
                cleanup.run();
                emitter.completeWithError(e);
            }
        });
        if (!unsubscribe.compareAndSet(null, subscription)) subscription.run(); // closed while subscribing
        heartbeat.set(HEARTBEATS.scheduleAtFixedRate(() -> {
            if (closed.get()) return;
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                cleanup.run();
                try {
                    emitter.completeWithError(e);
                } catch (RuntimeException ignored) {
                    // already completed
                }
            }
        }, HEARTBEAT_MS, HEARTBEAT_MS, java.util.concurrent.TimeUnit.MILLISECONDS));
        if (closed.get()) heartbeat.get().cancel(false);
        return emitter;
    }

    // ── approvals ─────────────────────────────────────────────────────────────

    @GetMapping("/api/chat/{id}/approvals")
    public Object approvals(@PathVariable String id) {
        return agentService.pendingApprovals(id);
    }

    @PostMapping("/api/chat/{id}/approvals/{approvalId}")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable String id,
                                                       @PathVariable String approvalId,
                                                       @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        String edited = body.get("editedText") instanceof String s ? s : null;
        boolean remember = Boolean.TRUE.equals(body.get("remember"));
        boolean rememberDir = Boolean.TRUE.equals(body.get("rememberDir"));
        // "allow writes in this folder" with a typed folder: an invalid one must not approve the call at all
        if (approved && rememberDir && edited != null && !edited.isBlank()
                && org.kendar.llm.harness.permission.ApprovalService.grantDirOf(agentService.workDir(id), edited) == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid folder '" + edited
                    + "': give a folder inside the project, relative to it, without '..' and not inside .git"));
        }
        if (!agentService.resolveApproval(id, approvalId, approved, edited, remember, rememberDir)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Approval no longer pending"));
        }
        return ResponseEntity.ok(Map.of("resolved", true));
    }

    // ── side data ─────────────────────────────────────────────────────────────

    @GetMapping("/api/chat/{id}/files")
    public List<String> files(@PathVariable String id, @RequestParam(defaultValue = "") String q) {
        String needle = q.toLowerCase();
        var fb = sandbox.files(id, null);
        if (fb != null) { // docker daemon mode: the files are in the container
            try {
                return fb.glob("**/*").stream().filter(p -> !p.startsWith(".jllm/"))
                        .filter(p -> needle.isEmpty() || p.toLowerCase().contains(needle)).limit(MAX_FILES).toList();
            } catch (IOException | RuntimeException e) {
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
            Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH,
                    new java.nio.file.SimpleFileVisitor<>() {
                        @Override
                        public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                                                                               java.nio.file.attribute.BasicFileAttributes a) {
                            if (!dir.equals(root) && HIDDEN_DIR.equalsIgnoreCase(String.valueOf(dir.getFileName()))) {
                                return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFile(Path file,
                                                                       java.nio.file.attribute.BasicFileAttributes a) {
                            if (!a.isRegularFile()) return java.nio.file.FileVisitResult.CONTINUE;
                            Path rel = root.relativize(file.toAbsolutePath().normalize());
                            if (rel.startsWith("..")) return java.nio.file.FileVisitResult.CONTINUE;
                            String p = rel.toString().replace('\\', '/');
                            if (needle.isEmpty() || p.toLowerCase().contains(needle)) found.add(p);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException e) {
                            return java.nio.file.FileVisitResult.CONTINUE; // unreadable: left out
                        }

                        @Override
                        public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException e) {
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | RuntimeException e) {
            // whatever was found so far
        }
        return found.stream().sorted().limit(MAX_FILES).toList();
    }

    @GetMapping("/api/chat/{id}/usage")
    public ResponseEntity<Map<String, Object>> usage(@PathVariable String id) {
        return sessions.find(id).map(s -> ResponseEntity.ok(usageOf(s))).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/chat/commands")
    public List<Map<String, String>> commands(jakarta.servlet.http.HttpServletRequest request) {
        WebUser user = request.getAttribute(UserInterceptor.ATTR) instanceof WebUser u ? u
                : users == null ? null : users.signedIn(request).orElse(null);
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

    private static ResponseEntity<Map<String, Object>> conflict(String msg) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", msg));
    }
}
