package org.kendar.llm.harness.settings;

import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.context.ContextStrategyKind;

/** Applies the {@link HarnessSettings} that are set to a {@link HarnessConfig}. */
public final class HarnessConfigMapper {

    private HarnessConfigMapper() {
    }

    /** Settings that are set replace the matching value of {@code config}; the others are left as they are. */
    public static HarnessConfig apply(HarnessSettings settings, HarnessConfig config) {
        HarnessConfig c = config;
        ExecutionSettings execution = settings.execution();
        if (execution != null && execution.useContextStack() != null) {
            c = c.withUseContextStack(execution.useContextStack());
        }
        SandboxSettings sandbox = settings.sandbox();
        if (sandbox != null) {
            if (sandbox.tempDir() != null && !sandbox.tempDir().isBlank()) c = c.withSandboxBaseDir(sandbox.tempDir());
            if (sandbox.timeoutSeconds() != null) c = c.withSandboxTimeoutSeconds(sandbox.timeoutSeconds());
            if (sandbox.localFallback() != null) c = c.withLocalDirSandboxAllowed(sandbox.localFallback());
            if (sandbox.docker() != null) c = c.withDocker(sandbox.docker().over(c.sandbox().docker()));
        }
        ContextSettings context = settings.context();
        if (context != null) {
            if (context.maxTokens() != null) c = c.withContextBudgetTokens(context.maxTokens());
            if (context.keepRecentTurns() != null) c = c.withKeepRecentTurns(context.keepRecentTurns());
            HarnessConfig.Compaction k = c.compaction();
            c = c.withCompaction(new HarnessConfig.Compaction(context.auto() != null ? context.auto() : k.auto(),
                    context.keepTokens() != null ? context.keepTokens() : k.keepTokens(),
                    context.buffer() != null ? context.buffer() : k.buffer(),
                    context.deferCompactionOnThinkingOverflow() != null
                            ? context.deferCompactionOnThinkingOverflow() : k.deferOnThinkingOverflow()));
            if (context.strategy() != null && !context.strategy().isBlank()) {
                c = c.withContextStrategy(ContextStrategyKind.parse(context.strategy()));
            }
            if (context.mnemonic() != null) c = c.withMnemonic(context.mnemonic().over(c.mnemonic()));
        }
        OutputSettings output = settings.output();
        if (output != null) {
            HarnessConfig.Output o = c.output();
            c = c.withOutput(new HarnessConfig.Output(
                    output.toolMaxChars() != null ? output.toolMaxChars() : o.toolMaxChars(),
                    output.eventMaxChars() != null ? output.eventMaxChars() : o.eventMaxChars(),
                    output.sanitize() != null ? output.sanitize() : o.sanitize(),
                    output.taintGating() != null ? output.taintGating() : o.taintGating(),
                    output.extraPatterns() != null ? output.extraPatterns() : o.extraPatterns(),
                    output.validation() != null ? output.validation() : o.validation(),
                    output.taintDocuments() != null ? output.taintDocuments() : o.taintDocuments()));
        }
        c = c.withExternalLinks(settings.externalLinks());
        if (settings.loop() != null) c = c.withLoop(settings.loop().over(c.loop()));
        return c;
    }
}
