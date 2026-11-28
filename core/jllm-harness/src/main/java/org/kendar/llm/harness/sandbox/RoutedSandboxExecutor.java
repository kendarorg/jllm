package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.identity.RoleSandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One runner per sandbox kind, chosen for each call by the chat's role: none, shared-local or docker. A sandbox that cannot
 * be resolved (chat without a user, unknown user or role, a lookup that fails) is {@link SandboxKind#NONE}: a failure
 * never opens a shell. The answer is cached for a couple of seconds because the layout methods are called very often.
 */
public final class RoutedSandboxExecutor implements SandboxExecutor {

    private static final long TTL_MILLIS = 2_000;

    private record Cached(RoleSandbox sandbox, long at) {
    }

    private final Function<String, RoleSandbox> roleSandboxOf;
    private final Map<SandboxKind, SandboxExecutor> runners;
    private final Layout layout;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * @param roleSandboxOf chat id to the sandbox of its user's role; null or a failure means none
     * @param runners       the runner of each kind; a kind without a runner behaves as none, which is required
     * @param layout        the host layout, for what no runner owns
     */
    public RoutedSandboxExecutor(Function<String, RoleSandbox> roleSandboxOf, Map<SandboxKind, SandboxExecutor> runners,
                                 Layout layout) {
        this.roleSandboxOf = roleSandboxOf;
        this.runners = Map.copyOf(runners);
        this.layout = layout;
        if (!this.runners.containsKey(SandboxKind.NONE)) {
            throw new IllegalArgumentException("A runner for the kind none is required");
        }
    }

    /** The sandbox the chat's role gives it. */
    public RoleSandbox sandboxOf(String chatId) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(chatId);
        if (c != null && now - c.at() < TTL_MILLIS) return c.sandbox();
        RoleSandbox s;
        try {
            s = roleSandboxOf == null ? RoleSandbox.NONE : roleSandboxOf.apply(chatId);
        } catch (RuntimeException e) { // boundary: a failing lookup must never open a shell
            s = RoleSandbox.NONE;
        }
        if (s == null) s = RoleSandbox.NONE;
        if (cache.size() > 10_000) cache.clear();
        cache.put(chatId, new Cached(s, now));
        return s;
    }

    /** The runner of the chat. */
    public SandboxExecutor of(String chatId) {
        SandboxExecutor r = runners.get(sandboxOf(chatId).kind());
        return r != null ? r : runners.get(SandboxKind.NONE);
    }

    /** The runner of a kind; null when there is none. */
    public SandboxExecutor runner(SandboxKind kind) {
        return runners.get(kind);
    }

    /** The container runner (both docker kinds share it); null when there is none. */
    public SandboxExecutor docker() {
        SandboxExecutor d = runners.get(SandboxKind.DOCKER_ISOLATED);
        return d != null ? d : runners.get(SandboxKind.DOCKER_OPEN);
    }

    /** Each runner once, even when two kinds share it. */
    private java.util.Collection<SandboxExecutor> distinct() {
        java.util.Set<SandboxExecutor> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        out.addAll(runners.values());
        return out;
    }

    // ── layout ────────────────────────────────────────────────────────────────

    @Override
    public Path location() {
        return layout.location();
    }

    @Override
    public Path workDir(String chatId) {
        return of(chatId).workDir(chatId);
    }

    @Override
    public Path scratchDir(String chatId, String agentId) {
        return of(chatId).scratchDir(chatId, agentId);
    }

    @Override
    public boolean sharedWorkDir() {
        return layout.sharedWorkDir();
    }

    @Override
    public Path chatStorage(String chatId) {
        return layout.chatStorage(chatId);
    }

    @Override
    public void deleteWorkDir(String chatId) {
        // the host directory belongs to the chat whatever runner it used
        layout.deleteWorkDir(chatId);
        cache.remove(chatId);
    }

    // ── exec ──────────────────────────────────────────────────────────────────

    @Override
    public ExecutionResult execute(String chatId, String command, Duration timeout) {
        return of(chatId).execute(chatId, command, timeout);
    }

    @Override
    public ExecutionResult execute(String chatId, Path runDir, Path readOnlyDir, String command, Duration timeout) {
        return of(chatId).execute(chatId, runDir, readOnlyDir, command, timeout);
    }

    @Override
    public ExecutionResult execute(String chatId, Path scratchDir, boolean planMode, String command, Duration timeout) {
        return of(chatId).execute(chatId, scratchDir, planMode, command, timeout);
    }

    @Override
    public void environmentWhitelist(List<String> patterns) {
        distinct().forEach(r -> r.environmentWhitelist(patterns));
    }

    @Override
    public void outputLogLimit(long maxBytes) {
        distinct().forEach(r -> r.outputLogLimit(maxBytes));
    }

    @Override
    public void shell(LocalShell shell) {
        distinct().forEach(r -> r.shell(shell));
    }

    /** Container paths ({@code /workspace/..}) read as such; every other path is shown as it is. */
    @Override
    public String displayPath(Path p) {
        SandboxExecutor docker = docker();
        return docker != null ? docker.displayPath(p) : String.valueOf(p);
    }

    /** The tool description is not per chat: it names the shell of each runner that has one. */
    @Override
    public String shellDescription() {
        SandboxExecutor local = runners.get(SandboxKind.SHARED_LOCAL);
        SandboxExecutor docker = docker();
        String l = local == null ? "" : local.shellDescription();
        String d = docker == null ? "" : docker.shellDescription();
        if (l.isBlank() && d.isBlank()) return "";
        if (d.isBlank()) return l;
        if (l.isBlank()) return d;
        return "Depends on the sandbox of your role. Local: " + l + " Container: " + d;
    }

    // ── files ─────────────────────────────────────────────────────────────────

    @Override
    public FileBackend files(String chatId, String agentId) {
        return of(chatId).files(chatId, agentId);
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public boolean allowLocalFallback(String chatId) {
        return of(chatId).allowLocalFallback(chatId);
    }

    @Override
    public boolean localFallbackAllowed(String chatId) {
        return of(chatId).localFallbackAllowed(chatId);
    }

    @Override
    public boolean localFallbackActive(String chatId) {
        return of(chatId).localFallbackActive(chatId);
    }

    @Override
    public String backup(String chatId) {
        return of(chatId).backup(chatId);
    }

    /** Cleans every runner: a chat whose role changed may have left a container of the former kind. */
    @Override
    public String clean(String chatId) {
        StringBuilder out = new StringBuilder();
        for (SandboxExecutor r : distinct()) {
            String s = r.clean(chatId);
            if (s != null && !s.isBlank()) out.append(out.length() == 0 ? "" : "; ").append(s);
        }
        return out.length() == 0 ? null : out.toString();
    }

    @Override
    public void close() {
        distinct().forEach(SandboxExecutor::close);
    }

    @Override
    public String sandboxStatus(String chatId) {
        return of(chatId).sandboxStatus(chatId);
    }

    @Override
    public boolean networkAccess(String chatId) {
        return of(chatId).networkAccess(chatId);
    }
}
