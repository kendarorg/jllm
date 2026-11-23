package org.kendar.llm.js;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolCatalog;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.harness.tools.TruncationMode;

import java.nio.file.Path;

/**
 * The {@code run_js} tool: the approval covers the script itself, the preview is the full code. The description is
 * static (no per-configuration values), so a change of the tool set or of the limits does not change the prompt prefix.
 */
final class RunJsTool implements HarnessTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String DESCRIPTION = "Run JavaScript in a sandbox with no host access: no files, network, processes "
            + "or require. The only way to reach anything is the global `tools` object, one function per tool you "
            + "can call, same name with '-' replaced by '_' (mcp__docs__search is tools.mcp__docs__search). Each "
            + "takes one object with that tool's arguments and returns its result synchronously (no await, no promises), parsed when it is JSON; it "
            + "throws an Error when the call fails or is refused. Files: use the file tools through `tools`, they "
            + "work in the script's own directory (fileRoot in the result). Write the body of a function: use "
            + "`return` for the value, console.log for output. Limits: 30 s by default (timeoutSeconds, not counting "
            + "time spent in tool calls), a total time limit that does count tool calls, a statement cap, a memory "
            + "cap, a cap on the number of tool calls, 50 KB of console output. Tool calls from a script use up the "
            + "same turn budget as your own calls: when it is used up, tool calls fail with an Error and the script "
            + "must return what it has. Nothing survives between calls except files.";

    private final SimpleTool tool;
    private final String status;

    private RunJsTool(SimpleTool tool, String status) {
        this.tool = tool;
        this.status = status;
    }

    static HarnessTool create(JsSandbox sandbox, ToolCatalog catalog, ToolLibrary library, String status) {
        return new RunJsTool(new SimpleTool("run_js", DESCRIPTION,
                SimpleTool.schema("code", "JavaScript function body",
                        "timeoutSeconds:integer?", "Time limit in seconds (default 30)"),
                "Js", ToolKind.OTHER,
                (ctx, in) -> run(sandbox, catalog, library, ctx, in),
                (ctx, in) -> in.path("code").asText()), status);
    }

    @Override
    public String name() {
        return tool.name();
    }

    @Override
    public String description() {
        return tool.description();
    }

    @Override
    public JsonNode inputSchema() {
        return tool.inputSchema();
    }

    @Override
    public String group() {
        return tool.group();
    }

    @Override
    public ToolKind kind() {
        return tool.kind();
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TruncationMode truncation() {
        return tool.truncation();
    }

    @Override
    public String execute(ToolContext ctx, JsonNode input) throws Exception {
        return tool.execute(ctx, input);
    }

    @Override
    public String preview(ToolContext ctx, JsonNode input) {
        return tool.preview(ctx, input);
    }

    private static String run(JsSandbox sandbox, ToolCatalog catalog, ToolLibrary library, ToolContext ctx,
                              JsonNode in) {
        boolean jail = sandbox.settings().fileJail();
        ToolContext jailed = jail ? catalog.jail(ctx) : ctx;
        JsSandbox.Bridge bridge = (name, argsJson) -> {
            JsonNode args;
            try {
                args = MAPPER.readTree(argsJson);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Arguments of '" + name + "' are not valid JSON");
            }
            ToolContext c = catalog.free(ctx, name) ? jailed : ctx;
            return catalog.invoke(c, name, args);
        };
        var tools = catalog.tools(ctx);
        JsResult r = sandbox.run(in.path("code").asText(),
                in.path("timeoutSeconds").asInt(JsSandbox.DEFAULT_TIMEOUT_SECONDS),
                library.source(tools), bridge, ctx.run() == null ? null : ctx.run().cancelled(),
                ctx.run() != null && ctx.run().cancelled() instanceof org.kendar.llm.harness.llm.CancelToken ct
                        ? ct::isKillRequested : null);
        return format(new JsResult(r.ok(), r.value(), r.console(), r.error(), r.truncated(), fileRoot(ctx, jailed)));
    }

    /** The jail root as the model's own file tools take it: relative to the work dir when it lies below it. */
    private static String fileRoot(ToolContext ctx, ToolContext jailed) {
        if (jailed.files() != null) return jailed.files().workRoot(); // container path, absolute
        Path root = jailed.workDir();
        if (root == null) return null;
        Path work = ctx.workDir().toAbsolutePath().normalize();
        Path r = root.toAbsolutePath().normalize();
        if (r.equals(work)) return ".";
        return r.startsWith(work) ? work.relativize(r).toString().replace('\\', '/') : r.toString();
    }

    static String format(JsResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("ok: ").append(r.ok()).append('\n');
        if (r.error() != null) sb.append("error: ").append(r.error()).append('\n');
        if (r.value() != null) sb.append("value: ").append(r.value()).append('\n');
        if (r.console() != null && !r.console().isEmpty()) {
            sb.append("console:\n").append(r.console());
            if (!r.console().endsWith("\n")) sb.append('\n');
            if (r.truncated()) sb.append("[console output truncated at 50 KB]\n");
        }
        if (r.fileRoot() != null) sb.append("fileRoot: ").append(r.fileRoot()).append('\n');
        return sb.toString().stripTrailing();
    }
}
