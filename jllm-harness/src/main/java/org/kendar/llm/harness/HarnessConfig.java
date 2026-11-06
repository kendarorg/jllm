package org.kendar.llm.harness;

import org.kendar.llm.harness.context.ContextStrategyKind;

import java.util.Set;

/** Plain configuration of the harness. Every field has a default in {@link #defaults()}. */
public record HarnessConfig(String agentId, int maxTurns, int maxTokens, boolean parallelToolUse,
                            boolean serializeRuns, int toolBatchThreshold, int approvalTimeoutSeconds,
                            int contextBudgetTokens, int keepRecentTurns, Set<String> coreGroups,
                            Sandbox sandbox, Web web, Compaction compaction, Output output,
                            ContextStrategyKind contextStrategy, Mnemonic mnemonic) {

    /** @param localDirSandboxAllowed allow falling back to a plain local directory when docker is unavailable */
    public record Sandbox(String baseDir, int timeoutSeconds, boolean localDirSandboxAllowed, Docker docker) {
        public static Sandbox defaults() {
            return new Sandbox(System.getProperty("java.io.tmpdir") + "/agent-harness", 120, true, Docker.defaults());
        }
    }

    /**
     * @param network {@code oneshot} mode only: docker network of the throwaway container ({@code none} = no network);
     *                daemon mode uses docker's default network
     * @param mode    {@code oneshot} (default: one {@code docker run --rm} per command) or {@code daemon} (a
     *                long-running container with an HTTP exec daemon, see docs/SANDBOX.md)
     * @param remote  daemon mode only: the docker daemon is not on this machine. Nothing can be bind-mounted, so the
     *                workspace is uploaded to the container when it is created and downloaded back as a backup
     * @param remoteHost address at which this machine reaches the ports published by the remote docker host; blank:
     *                taken from {@code DOCKER_HOST}
     */
    public record Docker(String binary, String image, String memory, String cpus, int pidsLimit, String user,
                         String network, String mode, boolean remote, String remoteHost) {
        public Docker(String binary, String image, String memory, String cpus, int pidsLimit, String user,
                      String network) {
            this(binary, image, memory, cpus, pidsLimit, user, network, "oneshot", false, "");
        }

        public Docker(String binary, String image, String memory, String cpus, int pidsLimit, String user,
                      String network, String mode) {
            this(binary, image, memory, cpus, pidsLimit, user, network, mode, false, "");
        }

        public static Docker defaults() {
            return new Docker("docker", "python:3.12-slim", "512m", "1.0", 256, "1000:1000", "none", "oneshot", false, "");
        }

        /** True for the long-running container with the exec daemon. */
        public boolean daemon() {
            return "daemon".equalsIgnoreCase(mode);
        }
    }

    public record Web(String searchProvider, String searchApiKey, int fetchMaxChars) {
        public static Web defaults() {
            return new Web("brave", "", 40_000);
        }
    }

    /**
     * @param auto       compact when the prompt nears the ceiling, and on a too-long rejection; off = only /compact
     * @param keepTokens newest tokens of the conversation that stay verbatim next to the summary
     * @param buffer     tokens kept free below the context window; null = max(15% of the window, 16k)
     */
    public record Compaction(boolean auto, int keepTokens, Integer buffer) {
        public static Compaction defaults() {
            return new Compaction(true, 15_000, null);
        }

        /** Prompt size at which compaction starts for a context window of {@code window} tokens. */
        public long ceiling(long window) {
            if (buffer != null && buffer > 0 && buffer < window) return window - buffer;
            long reserve = Math.round(window * 0.15);
            if (window >= 32_000) reserve = Math.max(reserve, 16_000);
            return window - reserve;
        }
    }

    /**
     * @param toolMaxChars  tool output kept in the context; the rest is cut with a "[truncated N chars]" note
     * @param eventMaxChars tool output carried by the {@code tool_result} event sent to the front end
     */
    public record Output(int toolMaxChars, int eventMaxChars, String sanitize, boolean taintGating,
                         java.util.List<String> extraPatterns) {
        public Output {
            if (sanitize == null || sanitize.isBlank()) sanitize = "wrap";
            extraPatterns = extraPatterns == null ? java.util.List.of() : java.util.List.copyOf(extraPatterns);
        }

        public Output(int toolMaxChars, int eventMaxChars) {
            this(toolMaxChars, eventMaxChars, "wrap", true, java.util.List.of());
        }

        public static Output defaults() {
            return new Output(30_000, 2_000);
        }
    }

    /**
     * Settings of the mnemonic context strategy. Sizes in tokens; null = derived from the compaction ceiling
     * ({@link Compaction#ceiling}) of the context window.
     *
     * @param maskTrigger      prompt size that starts a masking batch; null = 60% of the ceiling
     * @param maskTarget       a batch masks the oldest content until the prompt is this small; null = 30% of the ceiling
     * @param minBatchTokens   a batch is committed only if it frees at least this much; null = 20% of the ceiling
     * @param minMaskTokens    smaller items are never masked
     * @param keepRecentTokens newest tokens never masked; null = {@code compaction.keepTokens}
     * @param minAgeTurns      content younger than this many assistant turns is never masked
     * @param tailMaxTokens    verbatim tail kept by a segment switch; null = 25% of the window
     * @param notesMaxTokens   size of the handoff notes written at a segment switch
     * @param indexMaxTokens   size of the recall-id index added after the handoff notes
     * @param recallMaxChars   characters returned by one {@code history_recall} call
     * @param dropOldThinking  drop reasoning blocks of earlier user turns while masking
     */
    public record Mnemonic(Integer maskTrigger, Integer maskTarget, Integer minBatchTokens, int minMaskTokens,
                           Integer keepRecentTokens, int minAgeTurns, Integer tailMaxTokens, int notesMaxTokens,
                           int indexMaxTokens, int recallMaxChars, boolean dropOldThinking) {
        public static Mnemonic defaults() {
            return new Mnemonic(null, null, null, 300, null, 2, null, 2000, 1500, 12_000, true);
        }

        public long trigger(long ceiling) {
            return maskTrigger != null ? maskTrigger : Math.round(ceiling * 0.6);
        }

        public long target(long ceiling) {
            return maskTarget != null ? maskTarget : Math.round(ceiling * 0.3);
        }

        public long minBatch(long ceiling) {
            return minBatchTokens != null ? minBatchTokens : Math.round(ceiling * 0.2);
        }

        public long keepRecent(Compaction compaction) {
            return keepRecentTokens != null ? keepRecentTokens : compaction.keepTokens();
        }

        public long tailMax(long window) {
            return tailMaxTokens != null ? tailMaxTokens : Math.round(window * 0.25);
        }
    }

    public HarnessConfig {
        coreGroups = Set.copyOf(coreGroups);
        if (contextStrategy == null) contextStrategy = ContextStrategyKind.COMPACTION;
        if (mnemonic == null) mnemonic = Mnemonic.defaults();
    }

    public static HarnessConfig defaults() {
        return new HarnessConfig("agent", 40, 8192, true, true, 12, 600, 100_000, 6,
                Set.of("Files", "Shell"), Sandbox.defaults(), Web.defaults(), Compaction.defaults(), Output.defaults(),
                ContextStrategyKind.COMPACTION, Mnemonic.defaults());
    }

    public HarnessConfig withAgentId(String v) {
        return new HarnessConfig(v, maxTurns, maxTokens, parallelToolUse, serializeRuns, toolBatchThreshold,
                approvalTimeoutSeconds, contextBudgetTokens, keepRecentTurns, coreGroups, sandbox, web, compaction, output, contextStrategy, mnemonic);
    }

    public HarnessConfig withMaxTurns(int v) {
        return new HarnessConfig(agentId, v, maxTokens, parallelToolUse, serializeRuns, toolBatchThreshold,
                approvalTimeoutSeconds, contextBudgetTokens, keepRecentTurns, coreGroups, sandbox, web, compaction, output, contextStrategy, mnemonic);
    }

    public HarnessConfig withMaxTokens(int v) {
        return new HarnessConfig(agentId, maxTurns, v, parallelToolUse, serializeRuns, toolBatchThreshold,
                approvalTimeoutSeconds, contextBudgetTokens, keepRecentTurns, coreGroups, sandbox, web, compaction, output, contextStrategy, mnemonic);
    }

    public HarnessConfig withParallelToolUse(boolean v) {
        return new HarnessConfig(agentId, maxTurns, maxTokens, v, serializeRuns, toolBatchThreshold,
                approvalTimeoutSeconds, contextBudgetTokens, keepRecentTurns, coreGroups, sandbox, web, compaction, output, contextStrategy, mnemonic);
    }

    public HarnessConfig withSerializeRuns(boolean v) {
        return new HarnessConfig(agentId, maxTurns, maxTokens, parallelToolUse, v, toolBatchThreshold,
                approvalTimeoutSeconds, contextBudgetTokens, keepRecentTurns, coreGroups, sandbox, web, compaction, output, contextStrategy, mnemonic);
    }

    public HarnessConfig withToolBatchThreshold(int v) {
        return new HarnessConfig(agentId, maxTurns, maxTokens, parallelToolUse, serializeRuns, v,
                approvalTimeoutSeconds, contextBudgetTokens, keepRecentTurns, coreGroups, sandbox, web, compaction, output, contextStrategy, mnemonic);
    }

    public HarnessConfig withApprovalTimeoutSeconds(int v) {
        return new HarnessConfig(agentId, maxTurns, maxTokens, parallelToolUse, serializeRuns, toolBatchThreshold,
                v, contextBudgetTokens, keepRecentTurns, coreGroups, sandbox, web, compaction, output, contextStrategy, mnemonic);
    }

    public HarnessConfig withContextBudgetTokens(int v) {
        return new HarnessConfig(agentId, maxTurns, maxTokens, parallelToolUse, serializeRuns, toolBatchThreshold,
                approvalTimeoutSeconds, v, keepRecentTurns, coreGroups, sandbox, web, compaction, output, contextStrategy, mnemonic);
    }

    public HarnessConfig withKeepRecentTurns(int v) {
        return new HarnessConfig(agentId, maxTurns, maxTokens, parallelToolUse, serializeRuns, toolBatchThreshold,
                approvalTimeoutSeconds, contextBudgetTokens, v, coreGroups, sandbox, web, compaction, output, contextStrategy, mnemonic);
    }

    public HarnessConfig withCoreGroups(Set<String> v) {
        return new HarnessConfig(agentId, maxTurns, maxTokens, parallelToolUse, serializeRuns, toolBatchThreshold,
                approvalTimeoutSeconds, contextBudgetTokens, keepRecentTurns, v, sandbox, web, compaction, output, contextStrategy, mnemonic);
    }

    public HarnessConfig withSandbox(Sandbox v) {
        return new HarnessConfig(agentId, maxTurns, maxTokens, parallelToolUse, serializeRuns, toolBatchThreshold,
                approvalTimeoutSeconds, contextBudgetTokens, keepRecentTurns, coreGroups, v, web, compaction, output, contextStrategy, mnemonic);
    }

    public HarnessConfig withWeb(Web v) {
        return new HarnessConfig(agentId, maxTurns, maxTokens, parallelToolUse, serializeRuns, toolBatchThreshold,
                approvalTimeoutSeconds, contextBudgetTokens, keepRecentTurns, coreGroups, sandbox, v, compaction, output, contextStrategy, mnemonic);
    }

    public HarnessConfig withSandboxBaseDir(String dir) {
        return withSandbox(new Sandbox(dir, sandbox.timeoutSeconds(), sandbox.localDirSandboxAllowed(), sandbox.docker()));
    }

    public HarnessConfig withDocker(Docker d) {
        return withSandbox(new Sandbox(sandbox.baseDir(), sandbox.timeoutSeconds(), sandbox.localDirSandboxAllowed(), d));
    }

    public HarnessConfig withDockerImage(String image) {
        Docker d = sandbox.docker();
        return withDocker(new Docker(d.binary(), image, d.memory(), d.cpus(), d.pidsLimit(), d.user(), d.network(),
                d.mode(), d.remote(), d.remoteHost()));
    }

    public HarnessConfig withDockerMode(String mode) {
        Docker d = sandbox.docker();
        return withDocker(new Docker(d.binary(), d.image(), d.memory(), d.cpus(), d.pidsLimit(), d.user(), d.network(),
                mode, d.remote(), d.remoteHost()));
    }

    public HarnessConfig withSandboxTimeoutSeconds(int secs) {
        return withSandbox(new Sandbox(sandbox.baseDir(), secs, sandbox.localDirSandboxAllowed(), sandbox.docker()));
    }

    public HarnessConfig withLocalDirSandboxAllowed(boolean allowed) {
        return withSandbox(new Sandbox(sandbox.baseDir(), sandbox.timeoutSeconds(), allowed, sandbox.docker()));
    }

    public HarnessConfig withFetchMaxChars(int max) {
        return withWeb(new Web(web.searchProvider(), web.searchApiKey(), max));
    }

    public HarnessConfig withSearchApiKey(String key) {
        return withWeb(new Web(web.searchProvider(), key, web.fetchMaxChars()));
    }

    public HarnessConfig withCompaction(Compaction v) {
        return new HarnessConfig(agentId, maxTurns, maxTokens, parallelToolUse, serializeRuns, toolBatchThreshold,
                approvalTimeoutSeconds, contextBudgetTokens, keepRecentTurns, coreGroups, sandbox, web, v, output, contextStrategy, mnemonic);
    }

    public HarnessConfig withOutput(Output v) {
        return new HarnessConfig(agentId, maxTurns, maxTokens, parallelToolUse, serializeRuns, toolBatchThreshold,
                approvalTimeoutSeconds, contextBudgetTokens, keepRecentTurns, coreGroups, sandbox, web, compaction, v, contextStrategy, mnemonic);
    }

    public HarnessConfig withContextStrategy(ContextStrategyKind v) {
        return new HarnessConfig(agentId, maxTurns, maxTokens, parallelToolUse, serializeRuns, toolBatchThreshold,
                approvalTimeoutSeconds, contextBudgetTokens, keepRecentTurns, coreGroups, sandbox, web, compaction, output,
                v, mnemonic);
    }

    public HarnessConfig withMnemonic(Mnemonic v) {
        return new HarnessConfig(agentId, maxTurns, maxTokens, parallelToolUse, serializeRuns, toolBatchThreshold,
                approvalTimeoutSeconds, contextBudgetTokens, keepRecentTurns, coreGroups, sandbox, web, compaction, output,
                contextStrategy, v);
    }
}
