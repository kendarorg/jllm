package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.harness.util.Uuids;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Fan-out: runs several independent tasks in parallel, each in its own sub-agent loop. A sub-agent runs in the mode
 * of its caller (a read-only type always as PLAN with read-only tools); approvals it needs go to the user.
 * Deliberately NOT a HarnessToolSet (the registry would then depend on the loop that depends on the registry);
 * the agent loop adds it to the main agent's tool list only. Sub-agents never see this tool.
 */
public class SubAgentTool implements HarnessTool {

    private static final int MAX_PROMPTS = 8;

    private final Supplier<AgentLoop> loop;
    private final ObjectMapper mapper;
    private final AgentCatalog agents;
    private final boolean parallel;
    /** Serial writer sub-agents: one at a time per working directory (chats with their own directory do not wait). */
    private static final java.util.concurrent.ConcurrentHashMap<Object, java.util.concurrent.locks.ReentrantLock>
            WORK_DIR_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SubAgentTool(Supplier<AgentLoop> loop, ObjectMapper mapper) {
        this(loop, mapper, new AgentCatalog(List.of()));
    }

    public SubAgentTool(Supplier<AgentLoop> loop, ObjectMapper mapper, AgentCatalog agents) {
        this(loop, mapper, agents, true);
    }

    /** @param parallel false runs the prompts one after the other, in order */
    public SubAgentTool(Supplier<AgentLoop> loop, ObjectMapper mapper, AgentCatalog agents, boolean parallel) {
        this.loop = loop;
        this.mapper = mapper;
        this.agents = agents;
        this.parallel = parallel;
    }

    @Override public String name() { return "task"; }

    @Override
    public String description() {
        return "Run several independent investigations or self-contained tasks, each by its own sub-agent with "
                + "its own context. Use for broad fan-out (e.g. one prompt per repository or per question). Each "
                + "prompt must be self-contained. Returns one result per prompt; some may have failed. Agent types: "
                + agents.all().stream().map(a -> a.name() + " (" + a.description() + ")")
                        .collect(java.util.stream.Collectors.joining("; ")) + ".";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode type = props.putObject("subagent_type");
        type.put("type", "string");
        type.put("description", "Agent type for every prompt; default " + AgentCatalog.DEFAULT);
        ArrayNode names = type.putArray("enum");
        agents.all().forEach(a -> names.add(a.name()));
        ObjectNode prompts = props.putObject("prompts");
        prompts.put("type", "array");
        prompts.put("description", "Self-contained investigation prompts (max " + MAX_PROMPTS + ")");
        prompts.putObject("items").put("type", "string");
        root.putArray("required").add("prompts");
        return root;
    }

    @Override public String group() { return "Agents"; }

    @Override public ToolKind kind() { return ToolKind.READ_ONLY; }

    @Override
    public String execute(ToolContext ctx, JsonNode input) throws Exception {
        List<String> prompts = new ArrayList<>();
        for (JsonNode p : input.path("prompts")) {
            if (p.isTextual() && !p.asText().isBlank()) prompts.add(p.asText());
        }
        if (prompts.isEmpty()) throw new IllegalArgumentException("'prompts' must be a non-empty array of strings");
        if (prompts.size() > MAX_PROMPTS) prompts = prompts.subList(0, MAX_PROMPTS);

        String typeName = input.path("subagent_type").asText(AgentCatalog.DEFAULT);
        AgentDefinition type = agents.get(typeName);
        if (type == null) throw new IllegalArgumentException("Unknown agent type '" + typeName + "'");

        AgentLoop agentLoop = loop.get();
        PermissionMode mode = ctx.mode();
        // writers that land in the working directory would race on the same files: run them one after the other
        boolean serial = !parallel
                || (!type.readOnly() && (mode == PermissionMode.EDIT || mode == PermissionMode.AUTO));
        ExecutorService batchExecutor = !serial ? executor
                : Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        java.util.concurrent.locks.ReentrantLock dirLock = serial ? WORK_DIR_LOCKS.computeIfAbsent(lockKey(ctx),
                k -> new java.util.concurrent.locks.ReentrantLock(true)) : null;
        java.util.function.BooleanSupplier cancelled = ctx.run() == null ? () -> false : ctx.run().cancelled();
        List<Future<String>> futures = new ArrayList<>();
        List<String> runIds = new ArrayList<>();
        for (String prompt : prompts) {
            String runId = Uuids.v7String();
            runIds.add(runId);
            futures.add(batchExecutor.submit(() -> {
                // serial: a sub-agent runs to its end before any other in the same directory starts, even across
                // `task` calls and chats; waiting stops on cancel or interrupt
                if (dirLock != null) {
                    while (!dirLock.tryLock(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        if (cancelled.getAsBoolean()) {
                            throw new AgentLoop.SubAgentIncompleteException("cancelled", "",
                                    "Cancelled while waiting for another sub-agent writing in the same directory");
                        }
                    }
                }
                try {
                    return agentLoop.runSubAgent(ctx.chatId(), ctx.toolUseId(), prompt, type, mode, ctx.run(), runId);
                } finally {
                    if (dirLock != null) dirLock.unlock();
                }
            }));
        }
        ArrayNode results = mapper.createArrayNode();
        try {
            for (int i = 0; i < futures.size(); i++) {
                ObjectNode r = results.addObject();
                r.put("prompt", prompts.get(i));
                r.put("runId", runIds.get(i));
                try {
                    String answer = futures.get(i).get();
                    r.put("status", "ok");
                    r.put("result", answer);
                } catch (InterruptedException e) {
                    // the caller was cancelled: stop the others too
                    Thread.currentThread().interrupt();
                    futures.forEach(f -> f.cancel(true));
                    r.put("status", "cancelled");
                    r.put("result", "");
                } catch (java.util.concurrent.CancellationException e) {
                    r.put("status", "cancelled");
                    r.put("result", "");
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof AgentLoop.SubAgentIncompleteException inc) {
                        // ended without a final answer: what it produced, and why it is incomplete
                        r.put("status", inc.status());
                        r.put("result", inc.partial());
                        r.put("warning", inc.getMessage());
                    } else {
                        r.put("status", "failed");
                        r.put("result", String.valueOf(cause.getMessage()));
                    }
                }
            }
        } finally {
            if (batchExecutor != executor) batchExecutor.shutdownNow();
        }
        return mapper.writeValueAsString(results);
    }

    /** The working directory the sub-agents write into: the key of the serial lock. */
    private static Object lockKey(ToolContext ctx) {
        if (ctx.workDir() != null) return ctx.workDir().toAbsolutePath().normalize();
        return "chat:" + ctx.chatId(); // files in a container of the chat
    }
}
