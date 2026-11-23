package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.auth.LoginRequiredException;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.hooks.HookContext;
import org.kendar.llm.harness.hooks.HookResult;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.permission.Decision;
import org.kendar.llm.harness.permission.PermissionGate;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.permission.PermissionRules;
import org.kendar.llm.harness.permission.ToolClassifier;
import org.kendar.llm.harness.permission.ToolPermission;
import org.kendar.llm.harness.safety.ToolOutputSanitizer;
import org.kendar.llm.harness.sandbox.SandboxUnavailableException;
import org.kendar.llm.harness.sandbox.ShellPaths;
import org.kendar.llm.harness.tools.FileSystemAgentTools;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolInputValidator;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.harness.tools.TruncationMode;
import org.kendar.llm.harness.tools.ValidationMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Runs one tool call of the model: validates the arguments, decides the permission (hooks, rules, mode, approval,
 * taint), executes the tool and cleans its output.
 */
public final class ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    /** Tool groups whose output is produced by the harness or the user's own skills, not by outside content. */
    private static final Set<String> INTERNAL_GROUPS = Set.of("Agents", "Skills", "Memory");
    private static final Set<String> LOCAL_GROUPS = Set.of("Files", "Shell", "Js");

    final AgentLoop.Deps deps;
    final ToolOffer offer;
    final RunGuards guards;
    private final TaintTracker taint;
    private final ToolOutputSanitizer sanitizer;
    /** Per-chat override of {@code output.validation}, set by {@code /validation}. */
    private final Map<String, ValidationMode> validationModes = new ConcurrentHashMap<>();
    /** Sources already reported as needing a login, per run (its event sink): one event per run and source. */
    private final Map<EventSink, Set<String>> loginsRequired = Collections.synchronizedMap(new WeakHashMap<>());

    ToolExecutor(AgentLoop.Deps deps, ToolOffer offer, RunGuards guards, TaintTracker taint) {
        this.deps = deps;
        this.offer = offer;
        this.guards = guards;
        this.taint = taint;
        this.sanitizer = new ToolOutputSanitizer(ToolOutputSanitizer.Mode.parse(deps.config().output().sanitize()),
                deps.config().output().extraPatterns());
    }

    /** The validation mode of the chat: its {@code /validation} override, else the configured one. */
    public ValidationMode validationMode(String chatId) {
        ValidationMode m = validationModes.get(chatId);
        return m != null ? m : ValidationMode.parse(deps.config().output().validation());
    }

    public void validationMode(String chatId, ValidationMode mode) {
        validationModes.put(chatId, mode);
    }

    /** The view of one running agent that a script uses. */
    LoopRunScope scope(PermissionMode mode, EventSink sink, BooleanSupplier cancelled, boolean interactive,
                       String label, RunBudget budget) {
        return new LoopRunScope(this, mode, sink, cancelled, interactive, label, budget);
    }

    ObjectNode result(String id, String content, boolean isError) {
        return ToolResults.of(deps.mapper(), id, content, isError);
    }

    ObjectNode execute(JsonNode use, Map<String, HarnessTool> byName, Set<String> refused, ToolContext ctx,
                       PermissionMode mode, EventSink sink, BooleanSupplier cancelled, boolean interactive,
                       String intent, String label) {
        var mapper = deps.mapper();
        String id = use.path("id").asText();
        String name = use.path("name").asText();
        JsonNode input = use.path("input");
        ObjectNode call = mapper.createObjectNode();
        call.put("id", id);
        call.put("name", name);
        call.set("input", input);
        sink.emit("tool_call", call);

        ObjectNode result;
        long toolStart = System.nanoTime();
        HarnessTool tool = byName.get(name);
        ToolInputValidator.Outcome check = tool == null ? null : checkInput(tool, input, ctx);
        try {
            if (tool == null) {
                result = refused.contains(name)
                        ? result(id, "Tool unavailable for agent", true)
                        : result(id, "Unknown tool '" + name + "'", true);
            } else if (cancelled.getAsBoolean()) {
                result = result(id, "Cancelled by user", true);
            } else if (!check.valid()) {
                result = result(id, "Invalid arguments for '" + name + "': " + String.join("; ", check.errors())
                        + ". Expected input schema: " + tool.inputSchema(), true);
            } else {
                input = check.input();
                Authz az = authorize(tool, name, input, ctx, mode, sink, cancelled, interactive, intent, label, false);
                input = az.input();
                if (az.denial() != null) {
                    result = result(id, az.denial(), true);
                } else {
                    if (tool.kind() != ToolKind.READ_ONLY) guards.markChanged(ctx.chatId(), label);
                    try {
                        result = runTool(tool, input, ctx.withToolUseId(id), id, false);
                    } catch (SandboxUnavailableException e) {
                        result = sandboxUnavailable(e, tool, input, ctx, id, sink, cancelled, interactive, label);
                    }
                    result = withHookContext(result, az.pre().addedContext(),
                            deps.hooks().run(HookContext.postTool(ctx.chatId(), name, input,
                                    result.path("content").asText(""), result.path("is_error").asBoolean(false),
                                    (System.nanoTime() - toolStart) / 1_000_000L, ctx.parent())).addedContext());
                }
            }
        } catch (RuntimeException e) { // boundary: tool call boundary
            // a hook, the permission check or the sandbox fallback failed: the call still gets its result event
            log.warn("Tool call {} ({}) failed outside the tool: {}", id, name, e.toString());
            result = result(id, "Tool execution failed: " + e.getMessage(), true);
        }

        boolean failed = result.path("is_error").asBoolean(false);
        deps.metrics().toolCall(tool == null ? "unknown" : name, failed ? "error" : "ok",
                Duration.ofNanos(System.nanoTime() - toolStart));
        ObjectNode ev = mapper.createObjectNode();
        ev.put("id", id);
        ev.put("name", name);
        ev.put("isError", result.path("is_error").asBoolean(false));
        String out = result.path("content").asText("");
        int eventMax = deps.config().output().eventMaxChars();
        ev.put("output", out.length() > eventMax ? out.substring(0, eventMax) + "…" : out);
        sink.emit("tool_result", ev);
        return result;
    }

    /**
     * A hook or rule denial with the instruction not to reach the same result another way: models otherwise retry a
     * denied write through the shell ({@code echo x > .env}).
     */
    static String noWorkaround(String denial, HarnessTool tool, String name, JsonNode input) {
        String path = input != null && input.hasNonNull("path") ? input.get("path").asText().trim() : "";
        String what = path.isEmpty() ? "This " + name + " call"
                : (tool != null && tool.kind() == ToolKind.READ_ONLY ? "Reading " : "Writing ") + path;
        String d = denial.strip();
        String sep = d.endsWith(".") || d.endsWith("!") || d.endsWith("?") ? " " : ". ";
        return d + sep + what + " is not permitted by any means (no other tool, no shell command); report this to the "
                + "user instead.";
    }

    /** Outcome of {@link #authorize}: {@code denial} is null when the call may run. */
    record Authz(String denial, HookResult pre, JsonNode input) {
    }

    /**
     * Hooks, permission decision and approval of one call, shared by the model loop and the script scope.
     *
     * @param free built-in file tools called from a script: no gate and no prompt for read-only tools, always; for any
     *             other tool only while the chat is not tainted (an inner result taints the chat at once, so the next
     *             write after an untrusted read goes through the normal gate and prompt, and a non-interactive run
     *             denies it). Deny rules, hook denials and plan confinement apply in every case.
     */
    Authz authorize(HarnessTool tool, String name, JsonNode input, ToolContext ctx, PermissionMode mode,
                    EventSink sink, BooleanSupplier cancelled, boolean interactive, String intent,
                    String label, boolean free) {
        var mapper = deps.mapper();
        var approvals = deps.approvals();
        var gate = deps.gate();
        var sessions = deps.sessions();
        free = free && (tool.kind() == ToolKind.READ_ONLY || !taint.isTainted(ctx.chatId()));
        HookResult pre = deps.hooks().run(HookContext.preTool(ctx.chatId(), name, input, ctx.parent()));
        if (pre.modifiedInput() != null) input = pre.modifiedInput();
        // dispatch check: a call must be inside the user's tools, even if it names a hidden one; also a call made by a
        // script (run_js does not widen what the user may call)
        UserPolicy policy = deps.identity().policyOf(ctx.chatId());
        if (!policy.allows(tool)) {
            return new Authz("Not available to this user: " + name, pre, input);
        }
        if (!sessions.chatAllowsTool(ctx.chatId(), name)) {
            return new Authz("Tool disabled for this chat: " + name, pre, input);
        }
        // the administrator's decision for this tool and role: deny never runs; allow runs unattended, whatever the
        // mode, the taint and the approval policy (hooks, deny rules and plan confinement still apply)
        ToolPermission adminPermission = policy.permissionOf(tool);
        if (adminPermission == ToolPermission.DENY) {
            return new Authz("Denied by the administrator for this user's role: " + name, pre, input);
        }
        boolean adminAllowed = adminPermission == ToolPermission.ALLOW;
        PermissionGate userGate = policy.approval() == null ? gate : new PermissionGate(policy.approval(), gate.rules());
        Decision decision = free || adminAllowed ? Decision.ALLOW : userGate.check(mode, tool);
        if (adminPermission == ToolPermission.ASK && !free) decision = Decision.ASK;
        if (!free && !adminAllowed && decision == Decision.ALLOW && mode == PermissionMode.AUTO
                && deps.classifier() != null && tool.kind() != ToolKind.READ_ONLY && !pre.denied()) {
            // auto mode: the fast model may wave the call through; anything else goes to the user
            ToolClassifier.Verdict v = deps.classifier().classify(name, input, ctx.workDir(), intent);
            if (v != ToolClassifier.Verdict.SAFE) decision = Decision.ASK;
            sink.emit("classifier", mapper.createObjectNode().put("tool", name).put("verdict", v.name()));
        }
        // settings rules: deny wins in every mode and over any grant; ask forces the question; allow skips it
        PermissionRules.Match rule = gate.rules().match(name, input, ctx.workDir());
        if (!free && !adminAllowed && rule != null && rule.kind() == PermissionRules.Kind.ASK
                && decision == Decision.ALLOW) {
            decision = Decision.ASK;
        }
        // a grant only replaces the question; deny rules, hooks and plan confinement stay as they are
        if (decision == Decision.ASK && (approvals.granted(ctx.chatId(), name)
                || approvals.commandGranted(ctx.chatId(), name, commandOf(name, input))
                || approvals.dirGranted(ctx.chatId(), writeDirOf(tool, input, ctx)))) decision = Decision.ALLOW;
        if (rule != null && rule.kind() == PermissionRules.Kind.ALLOW && decision == Decision.ASK
                && mode != PermissionMode.AUTO) {
            decision = Decision.ALLOW;
        }
        // outside content is in the context: side effects need an explicit yes, even over grants and allow rules
        if (!free && !adminAllowed && decision == Decision.ALLOW && tool.kind() != ToolKind.READ_ONLY
                && taint.isTainted(ctx.chatId())
                && (rule == null || rule.kind() != PermissionRules.Kind.DENY)) {
            decision = Decision.ASK;
            sink.emit("warning", mapper.createObjectNode().put("message", "Confirming '" + name
                    + "': the conversation holds untrusted tool output that could contain injected instructions"));
        }
        if (decision == Decision.ASK && !interactive) {
            decision = Decision.DENY;
            sink.emit("approval_unavailable", mapper.createObjectNode().put("tool", name));
        }
        if (pre.denied()) {
            return new Authz(noWorkaround("Denied by a hook: " + pre.denyReason(), tool, name, input), pre, input);
        }
        if (rule != null && rule.kind() == PermissionRules.Kind.DENY) {
            return new Authz(noWorkaround("Denied by permission rule: " + rule.rule(), tool, name, input), pre, input);
        }
        // a host shell is not jailed like the file tools: a command line climbing out of the work dir is refused
        String command = commandOf(name, input);
        if (command != null && ctx.files() == null) {
            Path cwd = ctx.readOnlyWork() && ctx.scratchDir() != null ? ctx.scratchDir() : ctx.workDir();
            String escaping = ShellPaths.escapingPath(command, cwd, ctx.workDir(), ctx.scratchDir());
            if (escaping != null) {
                return new Authz(FileSystemAgentTools.escapes(escaping).getMessage(), pre, input);
            }
        }
        if (decision == Decision.DENY) return new Authz("Denied by permission policy (mode " + mode + ")", pre, input);
        if (decision == Decision.ASK) {
            String call = name + "\u0000" + input;
            // the user said no to this exact call already: asking again would only repeat the question
            if (guards.wasRejected(ctx.chatId(), label, call)) return new Authz(AgentLoop.REJECTED_AGAIN, pre, input);
            if (!approve(tool, input, ctx, sink, cancelled, label)) {
                guards.reject(ctx.chatId(), label, call);
                return new Authz(AgentLoop.REJECTED, pre, input);
            }
            approvals.grantCommand(ctx.chatId(), name, commandOf(name, input));
        }
        return new Authz(null, pre, input);
    }

    /** Validates (and in lenient mode repairs) the arguments; a failing validator never blocks the call. */
    ToolInputValidator.Outcome checkInput(HarnessTool tool, JsonNode input, ToolContext ctx) {
        try {
            var o = deps.inputValidator().validate(tool, input, validationMode(ctx.chatId()));
            return o.input() == null ? new ToolInputValidator.Outcome(input, o.errors()) : o;
        } catch (RuntimeException e) { // boundary: a failing validator never blocks the call
            log.debug("Input validator failed, the call goes on: {}", e.toString());
            return ToolInputValidator.Outcome.ok(input);
        }
    }

    /** The work-dir-relative directory a file-writing call targets, or null when none can be offered or granted. */
    private static String writeDirOf(HarnessTool tool, JsonNode input, ToolContext ctx) {
        if (tool.kind() != ToolKind.FILE_EDIT || ctx.readOnlyWork() || ctx.files() != null || input == null) return null;
        return ApprovalService.writeDirOf(ctx.workDir(), input.path("path").asText(null));
    }

    /** The shell command line of a bash call, else null: only commands are granted verbatim. */
    private static String commandOf(String toolName, JsonNode input) {
        return "bash".equals(toolName) && input != null && input.hasNonNull("command")
                ? input.get("command").asText() : null;
    }

    private boolean approve(HarnessTool tool, JsonNode input, ToolContext ctx, EventSink sink,
                            BooleanSupplier cancelled, String label) {
        var approvals = deps.approvals();
        String preview;
        try {
            preview = tool.preview(ctx, input);
        } catch (Exception e) { // boundary: tool code: the preview falls back to the raw input
            preview = input.toString();
        }
        String title = label == null ? tool.name() : "[" + label + "] " + tool.name();
        if (ctx.parent() != null) title += " (from " + ctx.parent().replaceFirst(":.*", "") + ")";
        String writeDir = writeDirOf(tool, input, ctx);
        ApprovalService.ApprovalDecision d = approvals.await(ctx.chatId(), "tool", title, preview, null, a -> {
            ObjectNode ev = deps.mapper().valueToTree(a);
            if (label != null) ev.put("agent", label);
            if (writeDir != null) ev.put("writeDir", writeDir);
            sink.emit("approval_request", ev);
        }, cancelled, () -> !taint.isTainted(ctx.chatId()) && (approvals.granted(ctx.chatId(), tool.name())
                || approvals.dirGranted(ctx.chatId(), writeDir)));
        boolean ok = d.approved();
        if (ok && d.rememberDir()) {
            // A typed path (carried in "edited") replaces the offered directory; an invalid one (outside the work
            // dir, absolute) rejects the call: the user approved a grant that cannot be given
            boolean typed = d.edited() != null && !d.edited().isBlank();
            String dir = typed ? ApprovalService.grantDirOf(ctx.workDir(), d.edited()) : writeDir;
            if (typed && dir == null) {
                sink.emit("warning", deps.mapper().createObjectNode().put("message",
                        "Invalid folder '" + d.edited() + "': the call was rejected"));
                ok = false;
            } else {
                approvals.grantDir(ctx.chatId(), dir);
            }
        }
        if (ok && d.remember()) approvals.grant(ctx.chatId(), tool.name());
        deps.metrics().approval("tool", ok);
        return ok;
    }

    /** Appends the context a hook asked to add to the content of a tool result. */
    private ObjectNode withHookContext(ObjectNode result, String... added) {
        StringBuilder content = new StringBuilder(result.path("content").asText(""));
        for (String a : added) if (a != null) content.append("\n\n").append(a);
        result.put("content", content.toString());
        return result;
    }

    /**
     * The container sandbox cannot be used: ask the user whether to continue without isolation, instead of failing.
     * Once agreed, the chat stays on the host sandbox and the tool is retried with host paths.
     */
    private ObjectNode sandboxUnavailable(SandboxUnavailableException e, HarnessTool tool, JsonNode input,
                                          ToolContext ctx, String id, EventSink sink, BooleanSupplier cancelled,
                                          boolean interactive, String label) {
        var sandbox = deps.sandbox();
        String chatId = ctx.chatId();
        if (!sandbox.localFallbackActive(chatId)) {
            if (!interactive || !deps.config().sandbox().localDirSandboxAllowed()) {
                return result(id, "Error: " + e.getMessage(), true);
            }
            String preview = e.getMessage() + "\n\nContinue WITHOUT isolation? Commands would run as your user "
                    + "directly on this machine, in " + sandbox.location() + ".";
            ApprovalService.ApprovalDecision d = deps.approvals().await(chatId, "sandbox",
                    "Docker is unavailable: run without isolation?", preview, null, a -> {
                        ObjectNode ev = deps.mapper().valueToTree(a);
                        if (label != null) ev.put("agent", label);
                        sink.emit("approval_request", ev);
                    }, cancelled);
            deps.metrics().approval("sandbox", d.approved());
            if (!d.approved() || !sandbox.allowLocalFallback(chatId)) {
                return result(id, "Error: " + e.getMessage() + " (the user declined to run without isolation)", true);
            }
        }
        String scratchId = ctx.scratchDir() == null || ctx.scratchDir().getFileName() == null ? "main"
                : ctx.scratchDir().getFileName().toString();
        ToolContext local = new ToolContext(chatId, sandbox.workDir(chatId), sandbox.scratchDir(chatId, scratchId),
                ctx.readOnlyWork(), id, ctx.mode(), ctx.run(), sandbox.files(chatId, scratchId), ctx.parent(),
                ctx.scope()).withHosts(ctx.hosts()).withLinks(ctx.links());
        ObjectNode r = runTool(tool, input, local, id, false);
        r.put("content", "[running without isolation on the host: " + sandbox.workDir(chatId) + "]\n"
                + r.path("content").asText(""));
        return r;
    }

    /** @param raw the caller wants the full output: no truncation, no sanitizing (taint is still decided) */
    ObjectNode runTool(HarnessTool tool, JsonNode input, ToolContext ctx, String id, boolean raw) {
        try {
            String out = tool.execute(ctx, input);
            if (out == null) out = "";
            if (raw) {
                sanitizeOutput(tool, ctx, out); // taint decision only
                return result(id, out, false);
            }
            int max = deps.config().output().toolMaxChars();
            if (out.length() > max) {
                out = tool.truncation() == TruncationMode.TAIL
                        ? "…[truncated " + (out.length() - max) + " chars]\n" + out.substring(out.length() - max)
                        : out.substring(0, max) + "\n…[truncated " + (out.length() - max) + " chars]";
            }
            return result(id, sanitizeOutput(tool, ctx, out), false);
        } catch (SandboxUnavailableException e) {
            throw e;
        } catch (LoginRequiredException e) {
            loginRequired(ctx, e.source());
            return result(id, "Error: " + e.getMessage(), true);
        } catch (Exception e) { // boundary: tool boundary: any failure becomes the error result of the call
            String m = e instanceof IOException ? e.getClass().getSimpleName() + ": " + e.getMessage() : e.getMessage();
            return result(id, "Error: " + m, true);
        }
    }

    /** Emits {@code login_required {source, agent?}} once per run and source; the token never appears in it. */
    private void loginRequired(ToolContext ctx, String source) {
        if (ctx == null || ctx.run() == null || ctx.run().sink() == null) return;
        EventSink sink = ctx.run().sink();
        if (!loginsRequired.computeIfAbsent(sink, k -> ConcurrentHashMap.newKeySet()).add(source)) {
            return;
        }
        ObjectNode ev = deps.mapper().createObjectNode().put("source", source);
        if (ctx.run().contextKey() != null) ev.put("agent", ctx.run().contextKey());
        try {
            sink.emit("login_required", ev);
        } catch (RuntimeException e) { // boundary: event delivery is best effort
            log.debug("Cannot emit login_required: {}", e.toString());
        }
    }

    /** Cleans and wraps the output of a tool; marks the chat tainted when it may carry outside instructions. */
    private String sanitizeOutput(HarnessTool tool, ToolContext ctx, String out) {
        if (sanitizer.mode() == ToolOutputSanitizer.Mode.OFF || INTERNAL_GROUPS.contains(tool.group())) return out;
        ToolOutputSanitizer.Trust trust = LOCAL_GROUPS.contains(tool.group())
                ? ToolOutputSanitizer.Trust.LOW : ToolOutputSanitizer.Trust.UNTRUSTED;
        ToolOutputSanitizer.Result r = sanitizer.sanitize(tool.name(), trust, out);
        if (r.tainted() && deps.config().output().taintGating()) taint.taint(ctx.chatId(), ctx.toolUseId());
        if (r.flagged()) {
            log.warn("Tool '{}' output flagged as possible prompt injection: {}", tool.name(), r.findings());
        }
        return r.text();
    }
}
