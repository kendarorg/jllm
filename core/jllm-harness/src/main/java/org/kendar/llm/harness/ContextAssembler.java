package org.kendar.llm.harness;

import org.kendar.llm.harness.agent.SubAgentRunner;
import org.kendar.llm.harness.context.ContextStrategy;
import org.kendar.llm.harness.context.ContextStrategyFactory;
import org.kendar.llm.harness.context.compaction.CompactionStrategy;
import org.kendar.llm.harness.context.compaction.ContextManager;
import org.kendar.llm.harness.context.mnemonic.MnemonicStore;
import org.kendar.llm.harness.context.mnemonic.MnemonicStrategy;
import org.kendar.llm.harness.context.scope.ScopeStrategy;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.state.StateStore;
import org.kendar.llm.harness.todo.TodoStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Creates the context strategy of a harness: the custom factory, or the one {@code context.strategy} names. */
final class ContextAssembler {

    private ContextAssembler() {
    }

    static ContextStrategy create(ContextStrategyFactory custom, ContextStrategyFactory.Deps deps,
                                  HarnessSettings settings, MnemonicStore mnemonicStore,
                                  ScopeStrategy.Options scopeOptions, StateStore stateStore, SandboxExecutor box,
                                  ChatSessionService sessions) {
        if (custom != null) return custom.create(deps);
        return switch (deps.config().contextStrategy()) {
            case MNEMONIC -> new MnemonicStrategy(deps.config(), deps.mapper(), deps.metrics(), mnemonicStore,
                    deps.subAgentRuns(),
                    chat -> sessions.find(chat).map(ChatSessionData::agentId).orElse(null),
                    chat -> sessions.find(chat).map(sessions::messages).orElse(null));
            case SCOPE -> new ScopeStrategy(deps.config(),
                    new ContextManager(deps.config(), deps.summaryModel(), deps.approvals(), deps.mapper(),
                            deps.metrics()), deps.mapper(),
                    new TodoStore(stateStore, deps.mapper()), settings.scopeOptions(scopeOptions), offloadSink(box));
            case COMPACTION, CUSTOM -> new CompactionStrategy(new ContextManager(deps.config(), deps.summaryModel(),
                    deps.approvals(), deps.mapper(), deps.metrics()), deps.config(), deps.mapper());
        };
    }

    /**
     * Offloaded tool results go where the agent's file tools read: through the sandbox's file backend when it has
     * one (a container), else to the scratch directory on the host.
     */
    private static ScopeStrategy.OffloadSink offloadSink(SandboxExecutor box) {
        var host = ScopeStrategy.OffloadSink.host(
                (chat, agent) -> box.scratchDir(chat, SubAgentRunner.scratchId(agent)), box::displayPath);
        return (chat, agent, name, text) -> {
            var files = box.files(chat, SubAgentRunner.scratchId(agent));
            if (files == null) return host.store(chat, agent, name, text);
            String dir = files.scratchRoot() + "/offload";
            // planMode: the scratch root, writable whatever the mode
            try {
                files.mkdir(files.writeTarget(dir, true));
            } catch (IOException e) {
                // it may exist already: the write below tells
            }
            files.write(files.writeTarget(dir + "/" + name, true), text.getBytes(StandardCharsets.UTF_8), false);
            return dir + "/" + name;
        };
    }
}
