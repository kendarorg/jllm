package org.kendar.llm.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.agent.*;
import org.kendar.llm.harness.auth.Identity;
import org.kendar.llm.harness.context.ContextStrategy;
import org.kendar.llm.harness.context.ContextStrategyFactory;
import org.kendar.llm.harness.context.mnemonic.MnemonicStore;
import org.kendar.llm.harness.context.noise.NoiseCommandPruner;
import org.kendar.llm.harness.context.noise.NoiseQualifier;
import org.kendar.llm.harness.context.scope.ScopeStrategy;
import org.kendar.llm.harness.hooks.CommandStopHook;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.hooks.Hooks;
import org.kendar.llm.harness.instructions.InstructionLoader;
import org.kendar.llm.harness.instructions.ScopedInstructions;
import org.kendar.llm.harness.llm.LimitedModelClient;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelClients;
import org.kendar.llm.harness.llm.ProviderCatalog;
import org.kendar.llm.harness.permission.*;
import org.kendar.llm.harness.plugin.OneShotModel;
import org.kendar.llm.harness.plugin.ServiceRegistry;
import org.kendar.llm.harness.safety.ContentWarnings;
import org.kendar.llm.harness.safety.ToolOutputSanitizer;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.settings.AgentSettings;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.StopHook;
import org.kendar.llm.harness.state.StateStore;
import org.kendar.llm.harness.todo.TodoStore;
import org.kendar.llm.harness.todo.TodoTools;
import org.kendar.llm.harness.tools.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires the agent loop and its service: approvals, the context strategy, the system prompt, sub-agents, the stop hooks
 * from the settings, and the cleanup that runs when a chat is deleted.
 */
final class LoopAssembler {

    /** Everything the wiring needs; the first group comes from the builder, the second from the earlier steps. */
    record Spec(HarnessSettings settings, HarnessConfig config, ObjectMapper mapper, HarnessMetrics metrics,
                Hooks hooks, ModelClient fastClient, SubAgentRunLog subAgentRunLog, ToolApprovalPolicy approvalPolicy,
                ContentWarnings contentWarnings, ServiceRegistry services, StateStore stateStore,
                ContextStrategyFactory contextFactory, MnemonicStore mnemonicStore, ScopeStrategy.Options scopeOptions,
                String systemPrompt, String systemPromptReplace, boolean smallModel,
                List<ToolInputValidator> inputValidators, boolean defaultInputValidator,
                ModelAssembler.Models models, SandboxExecutor box, ChatSessionService sessions,
                ToolAssembler.Tools tools, Identity identity) {
    }

    record Wired(AgentLoop loop, AgentService service, ContextStrategy context) {
    }

    private LoopAssembler() {
    }

    static Wired assemble(Spec s) {
        HarnessSettings settings = s.settings();
        HarnessConfig config = s.config();
        ObjectMapper mapper = s.mapper();
        ModelClient model = s.models().client();
        ChatSessionService sessions = s.sessions();
        HarnessToolRegistry registry = s.tools().registry();
        ApprovalService approvals = new ApprovalService(config.approvalTimeoutSeconds(), s.stateStore());
        ModelClient fast = s.fastClient() != null ? s.fastClient()
                : settings.fastModel() != null ? ModelClients.from(settings.fastModel(), mapper) : null;
        ModelClient summary = fast != null ? fast : model;
        ContextStrategy context = ContextAssembler.create(s.contextFactory(),
                new ContextStrategyFactory.Deps(config, model, summary, approvals, mapper, s.metrics(),
                        s.subAgentRunLog()),
                settings, s.mnemonicStore(), s.scopeOptions(), s.stateStore(), s.box(), sessions);
        context.install(s.hooks());
        // todos with any strategy, unless the strategy offers them already (scope)
        List<HarnessTool> todoTools = settings.todos()
                && context.tools().stream().noneMatch(t -> TodoTools.WRITE.equals(t.name()))
                ? TodoTools.create(new TodoStore(s.stateStore(), mapper), mapper, false) : List.of();
        ToolSelector selector = new ToolSelector(config, summary, mapper);
        RequestToolGroupTool requestToolGroup = new RequestToolGroupTool(registry, sessions, mapper);
        AgentLoop[] ref = new AgentLoop[1];
        int maxParallel = s.models().maxParallel();
        AgentCatalog catalog = agentCatalog(settings, mapper, maxParallel, s.models().providers(),
                s.contentWarnings());
        if (s.services().lookup(OneShotModel.class).isEmpty()) {
            s.services().publish(OneShotModel.class,
                    new AgentOneShotModel(catalog, model, fast, () -> ref[0].subAgents(), sessions));
        }
        SubAgentTool subAgentTool = new SubAgentTool(() -> ref[0].subAgents(), mapper, catalog, maxParallel != 1);
        List<InstructionLoader.Loaded> instructionFiles = InstructionLoader.read(settings.instructionFiles());
        instructionFiles.forEach(l -> s.contentWarnings().report(l.path().toString(), l.findings()));
        // SYSTEM.md / APPEND_SYSTEM.md / --system-prompt*: the embedder joined them, the files are not known here
        if (s.systemPromptReplace() != null) {
            s.contentWarnings().check("system prompt replacement", s.systemPromptReplace());
        }
        s.contentWarnings().check("system prompt addition", s.systemPrompt());
        String prompt = SystemPromptComposer.compose(s.systemPromptReplace(), s.smallModel() || settings.isSmallModel(),
                s.systemPrompt(), context.kind(), !todoTools.isEmpty(), InstructionLoader.render(instructionFiles));
        NoiseCommandPruner noisePruner = null;
        if (settings.noiseCommands() && NoiseCommandPruner.appliesTo(context.kind())) {
            noisePruner = new NoiseCommandPruner(sessions, new NoiseQualifier(summary), mapper);
        }
        ScopedInstructions scopedInstructions = settings.scopedInstructions()
                ? new ScopedInstructions(settings.instructionFiles(), settings.scopedInstructionsDepth(),
                instructionFiles) : null;
        AgentLoop loop = new AgentLoop(new AgentLoop.Deps(config, model, context, selector,
                new PermissionGate(s.approvalPolicy(), s.tools().permissionRules()), approvals, sessions, s.box(),
                registry, subAgentTool, requestToolGroup, s.subAgentRunLog(), mapper, s.metrics(), prompt, s.hooks(),
                fast == null ? null : new ToolClassifier(fast, mapper), s.identity(), todoTools,
                s.tools().fileTools(), scopedInstructions, inputValidator(s), noisePruner,
                fast == null ? null : new StuckJudge(fast, mapper)));
        ref[0] = loop;
        stopHooks(s, loop);
        AgentService service = new AgentService(loop, sessions, approvals, mapper, config, maxParallel);
        StorageQuota quota = s.tools().quota();
        // whatever path deletes a chat (REST, /chats, retention): drop what is kept for it in memory
        sessions.onDelete(id -> {
            context.forget(id);
            approvals.forget(id);
            loop.taint().forget(id);
            loop.injections().forgetContentWarnings(id);
            quota.forget(id);
        });
        return new Wired(loop, service, context);
    }

    private static ToolInputValidator inputValidator(Spec s) {
        if (s.inputValidators().isEmpty() && s.defaultInputValidator()) return null;
        List<ToolInputValidator> chain = new ArrayList<>();
        if (s.defaultInputValidator()) chain.add(new JsonSchemaToolInputValidator());
        chain.addAll(s.inputValidators());
        return ToolInputValidator.chain(chain);
    }

    /** Verify-on-stop from the settings: after the hooks added in code and by plugins. */
    private static void stopHooks(Spec s, AgentLoop loop) {
        if (s.settings().stopHooks().isEmpty()) return;
        var sanitizer = new ToolOutputSanitizer(ToolOutputSanitizer.Mode.parse(s.config().output().sanitize()),
                s.config().output().extraPatterns());
        for (StopHook h : s.settings().stopHooks()) {
            s.hooks().add(HookEvent.STOP, new CommandStopHook(h, s.box(), loop.guards()::changedInRun, sanitizer));
        }
    }

    private static AgentCatalog agentCatalog(HarnessSettings settings, ObjectMapper mapper, int maxParallel,
                                             ProviderCatalog providers, ContentWarnings warnings) {
        List<AgentDefinition> defs = new ArrayList<>();
        for (AgentSettings a : settings.agents()) {
            String prompt = a.prompt();
            if ((prompt == null || prompt.isBlank()) && a.promptFile() != null) {
                try {
                    prompt = Files.readString(Path.of(a.promptFile()), StandardCharsets.UTF_8);
                    warnings.check(Path.of(a.promptFile()).toString(), prompt);
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot read prompt file of agent " + a.name(), e);
                }
            }
            ModelClient own = a.provider() != null ? providers.client(a.provider())
                    : a.model() == null ? null : ModelClients.from(a.model(), mapper);
            if (own != null && maxParallel > 0) own = new LimitedModelClient(own, maxParallel);
            defs.add(new AgentDefinition(a.name(), a.description() == null ? "" : a.description(), a.tools(),
                    prompt, Boolean.TRUE.equals(a.inheritContext()), own, Boolean.TRUE.equals(a.readOnly()),
                    a.temperature(), a.thinking()));
        }
        return new AgentCatalog(defs);
    }
}
