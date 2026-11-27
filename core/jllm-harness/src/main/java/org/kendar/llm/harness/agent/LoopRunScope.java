package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.hooks.HookContext;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.sandbox.SandboxUnavailableException;
import org.kendar.llm.harness.sandbox.ScratchJailBackend;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.RunScope;
import org.kendar.llm.harness.tools.ToolCatalog;
import org.kendar.llm.harness.tools.ExternalLinks;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** The view of one running agent that a script uses; the tool list follows the agent turn by turn. */
final class LoopRunScope implements RunScope {
    private static final Set<String> SCRIPT_REFUSED = Set.of("run_js", "bash", "sub_agent", "request_tool_group");

    private final ToolExecutor executor;
    private final AgentLoop.Deps deps;
    private final PermissionMode mode;
    private final EventSink sink;
    private final BooleanSupplier cancelled;
    private final RunAttendance attendance;
    private final String label;
    private final RunBudget budget;
    volatile Map<String, HarnessTool> byName = Map.of();
    volatile String intent = "";

    LoopRunScope(ToolExecutor executor, PermissionMode mode, EventSink sink, BooleanSupplier cancelled,
                 RunAttendance attendance, String label, RunBudget budget) {
        this.executor = executor;
        this.deps = executor.deps;
        this.mode = mode;
        this.sink = sink;
        this.cancelled = cancelled;
        this.attendance = attendance;
        this.label = label;
        this.budget = budget;
    }

    private boolean scriptRefused(HarnessTool t) {
        return SCRIPT_REFUSED.contains(t.name()) || "Agents".equals(t.group()) || t == deps.subAgentTool()
                || t == deps.requestToolGroup();
    }

    @Override
    public Collection<HarnessTool> tools() {
        return byName.values().stream().filter(t -> !scriptRefused(t)).toList();
    }

    @Override
    public ToolContext jail(ToolContext ctx) {
        if (!deps.sandbox().sharedWorkDir()) return ctx;
        if (ctx.files() != null) {
            return ctx.withFiles(new ScratchJailBackend(ctx.files()));
        }
        return ctx.withRoot(ctx.scratchDir()).withLinks(ExternalLinks.NONE); // a script never leaves its scratch dir
    }

    @Override
    public String display(Path path) {
        return deps.sandbox().displayPath(path);
    }

    @Override
    public boolean free(String name) {
        HarnessTool t = byName.get(name);
        return t != null && deps.builtinFileTools().contains(t);
    }

    @Override
    public String invoke(ToolContext ctx, String name, JsonNode input) {
        var mapper = deps.mapper();
        String parent = ctx.toolUseId() == null ? "run_js" : "run_js:" + ctx.toolUseId();
        ToolContext inner = ctx.withParent(parent);
        String id = (ctx.toolUseId() == null ? "js" : ctx.toolUseId()) + "/" + UUID.randomUUID();
        if (input == null || input.isNull()) input = mapper.createObjectNode();
        ObjectNode call = mapper.createObjectNode();
        call.put("id", id).put("name", name).put("parent", parent);
        call.set("input", input);
        sink.emit("tool_call", call);
        long start = System.nanoTime();
        String out = null;
        String error = null;
        try {
            HarnessTool tool = byName.get(name);
            // the list is the agent's of this turn; a tool disabled or removed since (/tools, /mcp) is refused now
            if (tool == null || scriptRefused(tool)
                    || !(deps.registry().all().contains(tool) || executor.offer.fixedTools().contains(tool))) {
                throw new ToolCatalog.ToolCallException("Unknown or unavailable tool '" + name + "'");
            }
            if (cancelled.getAsBoolean()) {
                throw new ToolCatalog.ToolCallException("cancelled by the user");
            }
            if (!budget.tryInnerCall()) {
                throw new ToolCatalog.ToolCallException(budget.refusal());
            }
            var check = executor.checkInput(tool, input, inner);
            if (!check.valid()) {
                throw new ToolCatalog.ToolCallException("Invalid arguments for '"
                        + name + "': " + String.join("; ", check.errors()) + ". Expected input schema: "
                        + tool.inputSchema());
            }
            boolean free = deps.builtinFileTools().contains(tool);
            ToolExecutor.Authz az = executor.authorize(tool, name, check.input(), inner, mode, sink, cancelled,
                    attendance, intent, label, free);
            if (az.denial() != null) {
                throw new ToolCatalog.ToolCallException(az.denial());
            }
            ObjectNode r;
            if (tool.kind() != ToolKind.READ_ONLY) {
                executor.guards.markChanged(ctx.chatId(), label);
            }
            try {
                r = executor.runTool(tool, az.input(), inner.withToolUseId(id), id, true, null);
            } catch (SandboxUnavailableException e) {
                throw new ToolCatalog.ToolCallException(e.getMessage());
            }
            out = r.path("content").asText("");
            boolean failed = r.path("is_error").asBoolean(false);
            deps.hooks().run(HookContext.postTool(ctx.chatId(), name, az.input(), out, failed,
                    (System.nanoTime() - start) / 1_000_000L, parent));
            if (failed) {
                throw new ToolCatalog.ToolCallException(out.startsWith("Error: ") ? out.substring(7) : out);
            }
            return out;
        } catch (ToolCatalog.ToolCallException e) {
            error = e.getMessage();
            throw e;
        } catch (RuntimeException e) { // boundary: tool call boundary: a hook or permission failure still ends in an error result
            // a hook or the permission check failed: the call still ends with an error result
            error = "Tool execution failed: " + e.getMessage();
            throw e;
        } finally {
            ObjectNode ev = mapper.createObjectNode();
            ev.put("id", id).put("name", name).put("parent", parent).put("isError", error != null);
            String text = error != null ? error : out == null ? "" : out;
            ev.put("size", text.length());
            int eventMax = deps.config().output().eventMaxChars();
            ev.put("output", text.length() > eventMax ? text.substring(0, eventMax) + "…" : text);
            sink.emit("tool_result", ev);
            deps.metrics().toolCall(name, error != null ? "error" : "ok", Duration.ofNanos(System.nanoTime() - start));
        }
    }
}
