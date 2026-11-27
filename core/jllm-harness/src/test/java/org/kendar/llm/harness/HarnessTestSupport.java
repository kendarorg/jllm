package org.kendar.llm.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.agent.AgentLoop;
import org.kendar.llm.harness.agent.EventSink;
import org.kendar.llm.harness.agent.AgentDefinition;
import org.kendar.llm.harness.agent.SubAgentRequest;
import org.kendar.llm.harness.agent.SubAgentTool;
import org.kendar.llm.harness.agent.ToolSelector;
import org.kendar.llm.harness.context.ContextStrategy;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.context.compaction.CompactionStrategy;
import org.kendar.llm.harness.context.compaction.ContextManager;
import org.kendar.llm.harness.context.mnemonic.InMemoryMnemonicStore;
import org.kendar.llm.harness.context.mnemonic.MnemonicStore;
import org.kendar.llm.harness.context.mnemonic.MnemonicStrategy;
import org.kendar.llm.harness.hooks.Hooks;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.permission.PermissionGate;
import org.kendar.llm.harness.sandbox.LocalSandboxExecutor;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.kendar.llm.harness.session.InMemorySubAgentRunLog;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Shared fakes: scripted ModelClient, in-memory session store and a fully wired agent loop. */
public final class HarnessTestSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private HarnessTestSupport() {
    }

    /** Parses JSON where single quotes stand for double quotes. */
    public static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Builds a response from Anthropic-style content blocks. */
    public static ModelResponse resp(String stop, String contentJson) {
        return new ModelResponse((ArrayNode) json(contentJson), stop, new Usage(10, 5, 0, 0));
    }

    public static ModelResponse resp(String stop, String contentJson, Usage usage) {
        return new ModelResponse((ArrayNode) json(contentJson), stop, usage);
    }

    public static ModelResponse text(String t) {
        ArrayNode c = MAPPER.createArrayNode();
        c.addObject().put("type", "text").put("text", t);
        return new ModelResponse(c, "end_turn", new Usage(10, 5, 0, 0));
    }

    public static ModelResponse toolUse(String id, String name, String inputJson) {
        ArrayNode c = MAPPER.createArrayNode();
        c.addObject().put("type", "tool_use").put("id", id).put("name", name).set("input", json(inputJson));
        return new ModelResponse(c, "tool_use", new Usage(10, 5, 0, 0));
    }

    public record Recorded(String pinned, String extra, ArrayNode messages, List<String> toolNames, int maxTokens) {
    }

    /** Queue-driven (or function-driven) fake of the Anthropic client. Records deep copies of every request. */
    public static final class ScriptedModel implements ModelClient {
        public final Deque<ModelResponse> queue = new ArrayDeque<>();
        public final Deque<String> simpleReplies = new ArrayDeque<>();
        public final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
        public final List<String> simpleUserPrompts = Collections.synchronizedList(new ArrayList<>());
        public volatile Function<ModelRequest, ModelResponse> handler;
        public volatile Function<String, String> simpleHandler;

        public ScriptedModel enqueue(ModelResponse... rs) {
            synchronized (queue) {
                queue.addAll(Arrays.asList(rs));
            }
            return this;
        }

        @Override
        public ModelResponse complete(ModelRequest r) {
            requests.add(new Recorded(r.pinnedSystem(), r.extraSystem(), r.messages().deepCopy(),
                    r.tools().stream().map(HarnessTool::name).toList(), r.maxTokens()));
            if (handler != null) return handler.apply(r);
            synchronized (queue) {
                ModelResponse next = queue.poll();
                if (next == null) throw new IllegalStateException("No scripted model response left");
                return next;
            }
        }

        @Override
        public String simpleComplete(String system, String user, int maxTokens) {
            simpleUserPrompts.add(user);
            if (simpleHandler != null) return simpleHandler.apply(user);
            String s = simpleReplies.poll();
            if (s == null) throw new IllegalStateException("No scripted simple reply left");
            return s;
        }
    }

    /** Collects emitted events. */
    public static final class Events implements EventSink {
        public final List<Map.Entry<String, JsonNode>> all = Collections.synchronizedList(new ArrayList<>());
        public volatile EventSink also = EventSink.NOOP;

        @Override
        public void emit(String type, JsonNode data) {
            all.add(Map.entry(type, data));
            also.emit(type, data);
        }

        public List<JsonNode> ofType(String type) {
            synchronized (all) {
                return all.stream().filter(e -> e.getKey().equals(type)).map(Map.Entry::getValue).toList();
            }
        }

        public List<String> types() {
            synchronized (all) {
                return all.stream().map(Map.Entry::getKey).toList();
            }
        }
    }

    public static HarnessConfig config(Path baseDir) {
        return HarnessConfig.defaults().withAgentId("jllm").withLocalDirSandboxAllowed(false).withMaxTokens(1024).withApprovalTimeoutSeconds(5)
                // the sub-agent tests assert on the sub-agent's own request; the stack has its own tests
                .withUseContextStack(false)
                .withContextBudgetTokens(150_000).withSandboxBaseDir(baseDir.toString())
                .withSandboxTimeoutSeconds(10)
                // sanitization has its own tests; other tests assert on the raw tool output
                .withOutput(new HarnessConfig.Output(30_000, 2_000, "off", true, java.util.List.of()));
    }

    public static SimpleTool tool(String name, String group, ToolKind kind, SimpleTool.Body body) {
        return new SimpleTool(name, name + " tool", SimpleTool.schema("x?", "anything"), group, kind, body);
    }

    /** Counts tokens by type; everything else is ignored. */
    public static final class CountingMetrics implements HarnessMetrics {
        private final Map<String, Long> tokens = new ConcurrentHashMap<>();

        public long tokens(String type) {
            return tokens.getOrDefault(type, 0L);
        }

        @Override public void modelCall(Duration d, String outcome) { }

        @Override
        public void tokens(Usage u) {
            if (u == null) return;
            tokens.merge("input", u.inputTokens(), Long::sum);
            tokens.merge("output", u.outputTokens(), Long::sum);
            tokens.merge("cache_read", u.cacheReadTokens(), Long::sum);
            tokens.merge("cache_creation", u.cacheCreationTokens(), Long::sum);
        }

        @Override public void toolCall(String tool, String outcome, Duration d) { }
        @Override public void turn() { }
        @Override public void compaction(int tier) { }
        @Override public void approval(String kind, boolean approved) { }
    }

    /** Fully wired loop with real gate/approvals/sessions/sandbox/context and a scripted model. */
    public static final class Rig {
        public HarnessConfig config;
        public final HarnessMetrics metrics;
        public final ScriptedModel model = new ScriptedModel();
        public final ChatSessionService sessions;
        public final Map<String, Boolean> requiresApproval = new ConcurrentHashMap<>();
        public PermissionGate gate = new PermissionGate(t -> requiresApproval.getOrDefault(t.name(), false));
        public final List<HarnessTool> registered = Collections.synchronizedList(new ArrayList<>());
        public ApprovalService approvals;
        public LocalSandboxExecutor sandbox;
        public ContextManager contextManager;
        /** The strategy the loop uses: compaction over {@link #contextManager}, or mnemonic per the config. */
        public ContextStrategy context;
        public MnemonicStore mnemonicStore = new InMemoryMnemonicStore();
        public final SubAgentRunLog runLog = new InMemorySubAgentRunLog();
        public Hooks hooks = Hooks.NONE;
        public ToolSelector selector;
        public SubAgentTool subAgentTool;
        public AgentLoop loop;
        private org.kendar.llm.harness.auth.Identity identity;
        private java.util.Collection<HarnessTool> builtinFileTools = List.of();
        private org.kendar.llm.harness.instructions.ScopedInstructions scopedInstructions;
        private org.kendar.llm.harness.agent.StuckJudge stuckJudge;
        private List<String> documentExtensions = List.of();
        private org.kendar.llm.harness.agent.RunPolicy runPolicy = org.kendar.llm.harness.agent.RunPolicy.NORMAL;
        private boolean unconfined;
        private LocalSandboxExecutor customSandbox;

        public Rig(Path baseDir) {
            this(baseDir, HarnessMetrics.noop());
        }

        public Rig(Path baseDir, HarnessMetrics metrics) {
            this(baseDir, metrics, new InMemoryChatSessionStore());
        }

        /** Chats kept in {@code store}, e.g. a file store shared by two rigs to simulate a restart. */
        public Rig(Path baseDir, org.kendar.llm.harness.session.ChatSessionStore store) {
            this(baseDir, HarnessMetrics.noop(), store);
        }

        private Rig(Path baseDir, HarnessMetrics metrics, org.kendar.llm.harness.session.ChatSessionStore store) {
            this.metrics = metrics;
            this.config = config(baseDir);
            sessions = new ChatSessionService(store, config.agentId(), MAPPER);
            wire();
        }

        /** Installs settings permission rules and rebuilds the loop. */
        public Rig rules(org.kendar.llm.harness.permission.PermissionRules rules) {
            gate = new PermissionGate(t -> requiresApproval.getOrDefault(t.name(), false), rules);
            wire();
            return this;
        }

        /** Uses the local runner without the path jail ({@code --sandbox none}); rebuilds the loop. */
        public Rig unconfined() {
            unconfined = true;
            wire();
            return this;
        }

        /** Uses {@code runner} instead of the default local runner; rebuilds the loop. */
        public Rig sandbox(LocalSandboxExecutor runner) {
            customSandbox = runner;
            wire();
            return this;
        }

        /** Changes the config and rebuilds every component that captured it. */
        public Rig configure(UnaryOperator<HarnessConfig> change) {
            config = change.apply(config);
            wire();
            return this;
        }

        private void wire() {
            approvals = new ApprovalService(config.approvalTimeoutSeconds());
            sandbox = customSandbox != null ? customSandbox : unconfined ? LocalSandboxExecutor.unconfined(org.kendar.llm.harness.sandbox.DirLayout.of(config))
                    : LocalSandboxExecutor.of(config);
            contextManager = new ContextManager(config, model, approvals, MAPPER, metrics);
            if (config.contextStrategy() == ContextStrategyKind.MNEMONIC) {
                context = new MnemonicStrategy(config, MAPPER, metrics, mnemonicStore, runLog);
                hooks = new Hooks();
                context.install(hooks);
            } else {
                context = new CompactionStrategy(contextManager, config, MAPPER);
                hooks = Hooks.NONE;
            }
            selector = new ToolSelector(config, model, MAPPER);
            AgentLoop[] ref = new AgentLoop[1];
            subAgentTool = new SubAgentTool(() -> ref[0].subAgents(), MAPPER);
            loop = loopWith(model);
            ref[0] = loop;
        }

        /** A loop over this rig's components that talks to {@code client} instead of the scripted model. */
        public AgentLoop loopWith(ModelClient client) {
            return loopWith(client, null);
        }

        public AgentLoop loopWith(ModelClient client, org.kendar.llm.harness.permission.ToolClassifier classifier) {
            HarnessToolRegistry registry = mock(HarnessToolRegistry.class);
            when(registry.all()).thenAnswer(i -> new ArrayList<>(registered));
            return new AgentLoop(new AgentLoop.Deps(config, client, context, selector, gate, approvals, sessions,
                    sandbox, registry, subAgentTool, null, runLog, MAPPER, metrics, "", hooks, classifier, identity,
                    null, builtinFileTools, scopedInstructions, null, null, stuckJudge, runPolicy, () -> documentExtensions));
        }

        /** The extensions a document reader handles (for {@code output.taintDocuments}); rebuilds the loop. */
        public Rig documentExtensions(List<String> extensions) {
            documentExtensions = extensions;
            wire();
            return this;
        }

        /** The process level run switches (unattended, skip permissions); rebuilds the loop. */
        public Rig runPolicy(org.kendar.llm.harness.agent.RunPolicy policy) {
            runPolicy = policy;
            wire();
            return this;
        }

        /** The confirming model of stuck detections ({@code loop.stuck.confirm}); rebuilds the loop. */
        public Rig stuckJudge(org.kendar.llm.harness.agent.StuckJudge judge) {
            stuckJudge = judge;
            wire();
            return this;
        }

        /** The built-in file tools (free from a script) and the scoped instructions of the loop; rebuilds the loop. */
        public Rig scopedInstructions(java.util.Collection<HarnessTool> builtin,
                                      org.kendar.llm.harness.instructions.ScopedInstructions scoped) {
            builtinFileTools = builtin;
            scopedInstructions = scoped;
            wire();
            return this;
        }

        /** Who the calls are made for; rebuilds the loop. */
        public Rig identity(org.kendar.llm.harness.auth.Identity who) {
            identity = who;
            wire();
            return this;
        }

        /** A sub-agent of the chat, in the mode the chat is in, as the fan-out tool starts it. */
        public String subAgent(String chatId, String toolUseId, String prompt, AgentDefinition agentType) {
            return loop.subAgents().run(new SubAgentRequest(chatId, toolUseId, prompt, agentType,
                    org.kendar.llm.harness.permission.PermissionMode.parse(sessions.get(chatId).mode()), null,
                    org.kendar.llm.harness.util.Uuids.v7String()));
        }

        public Rig register(HarnessTool... tools) {
            registered.addAll(Arrays.asList(tools));
            return this;
        }

        public String newChat() {
            return sessions.create("t").chatId();
        }
    }
}
