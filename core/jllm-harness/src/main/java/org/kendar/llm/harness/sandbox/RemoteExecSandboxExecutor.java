package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.sandbox.ContainerRegistry.Container;
import org.kendar.llm.harness.sandbox.WorkspaceBackups.Backup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The container runner: a long-running container with a small HTTP daemon inside ({@code sandbox-agent.py}). Every
 * tool (bash and the file tools) runs through the daemon, so files, installed packages and background processes persist
 * between calls and a command does not pay container startup.
 * <p>
 * Containers come from a {@link ContainerRegistry}, keyed by scope: {@code chat} (one container per chat, work dir
 * {@code /workspace}) or {@code user} (one container per user, work dir {@code /workspace/<chatId>}). The
 * {@code standard} profile is the container with the limits of the configuration; the {@code hardened} profile adds no
 * network by default (the daemon is then reached through an {@link ExecTunnel}). {@link Kind#CLI} bind-mounts the
 * project at {@code /workspace}; {@link Kind#WEB} uses a volume. The directory layout on the host (history, sessions)
 * comes from the {@link Layout}. The daemon listens on {@code 127.0.0.1} only (published port) and every request
 * carries a per-container token; see docs/guides/sandbox.md.
 */
public class RemoteExecSandboxExecutor implements SandboxExecutor {

    /** Which front end owns the containers. */
    public enum Kind {
        CLI, WEB;

        String label() {
            return name().toLowerCase();
        }
    }

    /** Work directory in the container ({@code chat} scope and CLI). */
    public static final String WORKSPACE = "/workspace";
    /** Scratchpad in the container ({@code chat} scope and CLI); each agent has a directory below it. */
    public static final String SCRATCHPAD = "/workspace/.jllm/scratchpad";
    static final int DAEMON_PORT = 8080;
    private static final Logger log = LoggerFactory.getLogger(RemoteExecSandboxExecutor.class);

    private final HarnessConfig config;
    private final Layout layout;
    private final Kind kind;
    private final Path startupDir;
    private volatile SandboxExecutor local;
    private volatile LocalShell fallbackShell;
    private volatile Function<String, Boolean> fallbackAllowedOf;
    private volatile Supplier<SandboxExecutor> fallbackFactory;
    private final ContainerRegistry registry;
    private final WorkspaceBackups backups;
    private final Function<String, String> userOf;
    private final Function<String, SandboxPolicy> policyOf;
    private volatile Consumer<String> warn = log::warn;
    private final Set<String> localChats = ConcurrentHashMap.newKeySet();
    private volatile long logLimit = TailCapture.DEFAULT_LOG_LIMIT;

    /** As the long form with the docker host of this process, no users, no per-user policy and file backup records. */
    public RemoteExecSandboxExecutor(HarnessConfig config, DockerCommandRunner runner, Layout layout, Kind kind,
                                     Path startupDir) {
        this(config, runner, System.getenv("DOCKER_HOST"), layout, kind, startupDir, null, null, null);
    }

    /**
     * @param dockerHost the DOCKER_HOST of this process (decides local or remote docker); null: the docker context
     * @param layout     directory layout on the host (a {@link CwdLayout} for the CLI, a {@link ChatDirLayout} for the web)
     * @param startupDir CLI: the directory mounted at {@code /workspace}; ignored for the web
     * @param userOf     chat id to user id (the key of a {@code user}-scope container); null: no users
     * @param policyOf   chat id to the policy of its user (profile, scope, limits, docker host, clean-up); null: none
     * @param store      where the backup records are kept; null: files next to the tarballs
     */
    public RemoteExecSandboxExecutor(HarnessConfig config, DockerCommandRunner runner, String dockerHost, Layout layout,
                                     Kind kind, Path startupDir, Function<String, String> userOf,
                                     Function<String, SandboxPolicy> policyOf, BackupRecordStore store) {
        this.config = config;
        this.layout = layout;
        this.kind = kind;
        this.startupDir = startupDir == null ? layout.location() : startupDir.toAbsolutePath().normalize();
        this.fallbackFactory = () -> new SharedLocalSandbox(layout, this::fallbackNetwork, null);
        this.userOf = userOf;
        this.policyOf = policyOf;
        this.backups = new WorkspaceBackups(layout, kind, this.startupDir, store);
        this.registry = new ContainerRegistry(config, runner, dockerHost, kind, this.startupDir, backups,
                s -> this.warn.accept(s));
    }

    /** Where warnings go (default: the log); the CLI prints them. */
    public RemoteExecSandboxExecutor onWarning(Consumer<String> warn) {
        this.warn = warn;
        return this;
    }

    // ── placement ─────────────────────────────────────────────────────────────

    private boolean remote() {
        return config.sandbox().docker().remote();
    }

    private String projectHash() {
        return Placement.hash(startupDir.toString()).substring(0, 12);
    }

    static String containerName(Kind kind, String owner) {
        return "jllm-" + kind.label() + "-" + owner;
    }

    /** Settings, docker host and container identity of the chat, from the configuration and the policy of its user. */
    Placement placement(String chatId) {
        SandboxPolicy pol = policyOf == null ? null : policyOf.apply(chatId);
        if (pol == null) pol = SandboxPolicy.none();
        HarnessConfig.Docker d = pol.over(config.sandbox().docker());
        String userId = userOf == null ? null : userOf.apply(chatId);
        boolean user = d.userScope();
        if (kind == Kind.WEB) {
            if (user && userId == null) {
                warn.accept("Chat " + chatId + " has no user: its container is per chat instead of per user.");
                user = false;
            }
            if (user) {
                String key = Placement.safe(userId);
                return new Placement(d, pol.dockerHost(), key, containerName(kind, "user-" + key), "jllm-" + key,
                        null, userId, true);
            }
            return new Placement(d, pol.dockerHost(), chatId, containerName(kind, chatId), null, null, userId, false);
        }
        String h = projectHash();
        String key = user ? h : h + "-" + Placement.safe(chatId);
        return new Placement(d, pol.dockerHost(), key, containerName(kind, key), null, h, userId, user);
    }

    private static boolean perChatDirs(Placement p, Kind kind) {
        return kind == Kind.WEB && p.userScope();
    }

    private String workspace(Placement p, String chatId) {
        return perChatDirs(p, kind) ? WORKSPACE + "/" + chatId : WORKSPACE;
    }

    private String scratchpad(Placement p, String chatId) {
        return workspace(p, chatId) + "/.jllm/scratchpad";
    }

    private DaemonClient clientFor(String chatId) throws IOException {
        Placement p = placement(chatId);
        Container c = registry.acquire(p);
        return perChatDirs(p, kind) ? c.client.forChat(chatId) : c.client;
    }

    // ── layout ────────────────────────────────────────────────────────────────

    @Override
    public Path location() {
        return layout.location();
    }

    @Override
    public boolean allowLocalFallback(String chatId) {
        if (!localFallbackAllowed(chatId)) return false;
        try {
            fallback();
        } catch (SandboxUnavailableException e) { // Linux without a working bwrap: fail closed
            warn.accept("Local fallback refused: " + e.getMessage());
            return false;
        }
        localChats.add(chatId);
        return true;
    }

    /**
     * Per chat: the role's {@code localFallback} when the front end gave one ({@link #fallbackAllowed}); else the
     * configuration ({@code sandbox.localFallback}, default false).
     */
    @Override
    public boolean localFallbackAllowed(String chatId) {
        Function<String, Boolean> f = fallbackAllowedOf;
        Boolean v = f == null ? null : f.apply(chatId);
        return v != null ? v : config.sandbox().localDirSandboxAllowed();
    }

    /** The per chat decision on the fallback (the web: the role's {@code localFallback}); null result: the configuration. */
    public RemoteExecSandboxExecutor fallbackAllowed(Function<String, Boolean> allowedOf) {
        this.fallbackAllowedOf = allowedOf;
        return this;
    }

    /** Replaces the runner used after a fallback (tests); the default is a {@link SharedLocalSandbox}. */
    RemoteExecSandboxExecutor fallbackRunner(Supplier<SandboxExecutor> factory) {
        this.fallbackFactory = factory;
        return this;
    }

    /** The runner of the chats that fell back: created on first use, so a missing bwrap only matters then. */
    private synchronized SandboxExecutor fallback() {
        SandboxExecutor l = local;
        if (l == null) {
            l = fallbackFactory.get();
            if (logLimit != TailCapture.DEFAULT_LOG_LIMIT) l.outputLogLimit(logLimit);
            if (fallbackShell != null) l.shell(fallbackShell);
            local = l;
        }
        return l;
    }

    /** Whether the role grants the network: a standard container has it, a hardened one only with network=default. */
    private boolean fallbackNetwork(String chatId) {
        HarnessConfig.Docker d = placement(chatId).docker();
        return !d.hardened() || "default".equalsIgnoreCase(d.network());
    }

    @Override
    public void environmentWhitelist(List<String> patterns) {
        // the fallback has a fixed minimal environment; the container has its own
    }

    @Override
    public void outputLogLimit(long maxBytes) {
        this.logLimit = maxBytes;
        SandboxExecutor l = local;
        if (l != null) l.outputLogLimit(maxBytes);
    }

    @Override
    public void shell(LocalShell shell) {
        this.fallbackShell = shell;
        SandboxExecutor l = local;
        if (l != null) l.shell(shell);
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
    public boolean localFallbackActive(String chatId) {
        return localChats.contains(chatId);
    }

    @Override
    public Path workDir(String chatId) {
        if (localFallbackActive(chatId)) return layout.workDir(chatId);
        if (kind == Kind.WEB) layout.workDir(chatId); // host directory holding the chat's history
        return Path.of(workspace(placement(chatId), chatId));
    }

    @Override
    public Path scratchDir(String chatId, String agentId) {
        if (localFallbackActive(chatId)) return layout.scratchDir(chatId, agentId);
        if (agentId == null || !agentId.matches("[A-Za-z0-9._-]{1,64}") || agentId.contains("..")) {
            throw new IllegalArgumentException("Invalid agentId");
        }
        return Path.of(scratchpad(placement(chatId), chatId), agentId);
    }

    @Override
    public String displayPath(Path p) {
        String s = posix(p);
        return s.equals(WORKSPACE) || s.startsWith(WORKSPACE + "/") ? s : String.valueOf(p);
    }

    /** {@code \workspace\x} / {@code C:\workspace\x} to {@code /workspace/x}. */
    static String posix(Path p) {
        String s = p.toString().replace('\\', '/');
        if (s.length() > 1 && s.charAt(1) == ':') s = s.substring(2);
        return s;
    }

    @Override
    public String shellDescription() {
        HarnessConfig.Docker d = config.sandbox().docker();
        boolean none = d.hardened() && !"default".equalsIgnoreCase(d.network());
        return "Runs sh -c (POSIX sh, not bash) in a long-running Linux container (" + d.image()
                + ") with the working directory at " + WORKSPACE
                + ". Files and installed tools persist between commands; each command is a fresh shell, so cd and "
                + "exported variables do not carry over. Background jobs a command starts (cmd &) are killed when "
                + "it ends: start a server and use it within the same command. The file tools work in the same "
                + "container. Network: " + (none ? "none." : "docker's default.");
    }

    @Override
    public FileBackend files(String chatId, String agentId) {
        if (localFallbackActive(chatId)) return null;
        Placement p = placement(chatId);
        return new RemoteFileBackend(() -> clientFor(chatId), workspace(p, chatId), scratchpad(p, chatId), agentId);
    }

    @Override
    public boolean networkAccess(String chatId) {
        if (localFallbackActive(chatId)) return fallback().networkAccess(chatId);
        return !placement(chatId).noNetwork();
    }

    @Override
    public String sandboxStatus(String chatId) {
        if (localFallbackActive(chatId)) return "runner: shared-local fallback | " + fallback().sandboxStatus(chatId);
        Placement p = placement(chatId);
        return "runner: container (" + p.docker().image() + ") | profile: " + (p.docker().hardened() ? "hardened" : "standard")
                + (p.noNetwork() ? ", no network" : "") + " | scope: " + (p.userScope() ? "user" : "chat")
                + " | container: " + p.name() + " (key " + p.key() + ")"
                + " | work dir: " + workspace(p, chatId) + " | run_js storage: host";
    }

    // ── commands ──────────────────────────────────────────────────────────────

    @Override
    public ExecutionResult execute(String chatId, String command, Duration timeout) {
        if (localFallbackActive(chatId)) return fallback().execute(chatId, command, timeout);
        Placement p = placement(chatId);
        return exec(chatId, p, workspace(p, chatId), scratchpad(p, chatId) + "/main", command, timeout);
    }

    @Override
    public ExecutionResult execute(String chatId, Path runDir, Path readOnlyDir, String command, Duration timeout) {
        if (localFallbackActive(chatId)) return fallback().execute(chatId, runDir, readOnlyDir, command, timeout);
        return exec(chatId, placement(chatId), posix(runDir), posix(runDir), command, timeout);
    }

    @Override
    public ExecutionResult execute(String chatId, Path scratchDir, boolean planMode, String command, Duration timeout) {
        if (localFallbackActive(chatId)) return fallback().execute(chatId, scratchDir, planMode, command, timeout);
        Placement p = placement(chatId);
        String scratch = scratchDir == null ? scratchpad(p, chatId) + "/main" : posix(scratchDir);
        return exec(chatId, p, planMode ? scratch : workspace(p, chatId), scratch, command, timeout);
    }

    private ExecutionResult exec(String chatId, Placement p, String cwd, String logDir, String command, Duration timeout) {
        try {
            Container c = registry.acquire(p);
            // CLI: the host project is what the container sees; what the host would run or read as configuration
            // is put back when the command changed it (the nested read-only mounts are the first line)
            ProjectGuard guard = kind == Kind.CLI ? ProjectGuard.snapshot(startupDir) : null;
            try {
                return DaemonExec.run(c.client, command, cwd, logDir, timeout, logLimit);
            } finally {
                if (guard != null) {
                    List<String> changed = guard.restore();
                    if (!changed.isEmpty()) {
                        String msg = "A command changed protected project files (.git hooks and config, .jllm); "
                                + "they were restored: " + String.join(", ", changed);
                        warn.accept(msg);
                    }
                }
            }
        } catch (IOException e) {
            registry.forget(p.key()); // the daemon may be gone: check the container again next time
            return new ExecutionResult("", "Sandbox unavailable: " + e.getMessage(), 127, false);
        }
    }

    // ── backup ────────────────────────────────────────────────────────────────

    /**
     * Downloads the container's workspace into the project directory (CLI, remote docker only) or the owner's tarball
     * (web) and commits an image; null when nothing needs a backup (the project is mounted).
     */
    @Override
    public String backup(String chatId) {
        if (kind == Kind.CLI && !remote()) return null;
        Placement p = placement(chatId);
        Container c = registry.current(p.key());
        if (c == null) return "No running sandbox container to back up.";
        return registry.backup(c).message();
    }

    /** How to get the files out of a container that was kept because its backup failed. */
    private String keptMessage(String name) {
        return "The container " + name + " was kept so its files are not lost. Fix the problem and back up again "
                + "(/sandbox backup or /sandbox clean), or copy the files out with: "
                + config.sandbox().docker().binary() + " cp " + name + ":" + WORKSPACE + " <directory>. "
                + "--sandbox-clean removes it without a backup.";
    }

    // ── cleaning ──────────────────────────────────────────────────────────────

    /**
     * Web: removes the container of a {@code chat}-scope chat with its backup; of a {@code user}-scope chat only its
     * directory {@code /workspace/<chatId>}. The host directory goes in both cases.
     */
    @Override
    public void deleteWorkDir(String chatId) {
        if (kind == Kind.WEB && !localFallbackActive(chatId)) {
            Placement p = placement(chatId);
            if (p.userScope()) {
                removeChatDirInContainer(chatId, p);
            } else {
                registry.remove(p);
                backups.expire(p.ownerKey(), registry::endpointOfRecord);
            }
        }
        layout.deleteWorkDir(chatId);
    }

    private void removeChatDirInContainer(String chatId, Placement p) {
        try {
            Container c = registry.acquire(p);
            Layout.resolveJailed(layout.location().toString(), chatId); // a valid chat id: nothing to inject
            ExecutionResult r = DaemonExec.run(c.client, "rm -rf -- " + chatId, WORKSPACE,
                    WORKSPACE + "/.jllm", Duration.ofSeconds(120), 0);
            if (r.exitCode() != 0) warn.accept("Cannot remove " + WORKSPACE + "/" + chatId + ": " + r.stdout() + r.stderr());
        } catch (IOException | RuntimeException e) { // boundary: the host directory is deleted anyway
            warn.accept("Cannot remove the sandbox directory of chat " + chatId + ": " + e.getMessage());
        }
    }

    /** CLI: removes the container of this session ({@code chat} scope) or project ({@code user} scope). */
    @Override
    public String clean(String chatId) {
        if (kind != Kind.CLI) return "Not available for web chats: deleting the chat removes its container and files.";
        Placement p = placement(chatId);
        DockerEndpoint ep = registry.endpoint(p.dockerHost());
        String saved = null;
        try {
            ep.require();
            JsonNode existing = ep.inspect(p.name());
            if (existing == null) {
                registry.forget(p.key());
                return "No sandbox container for " + startupDir;
            }
            if (remote()) { // nothing is mounted: keep the files before removing, and keep the container if that fails
                Container c = registry.current(p.key());
                if (c == null) c = registry.attach(p, existing);
                Backup b = c == null ? new Backup(false, "No running sandbox container to back up.") : registry.backup(c);
                if (!b.ok()) return b.message() + "\n" + keptMessage(p.name());
                saved = b.message();
            }
        } catch (IOException | RuntimeException e) { // boundary: reported to the caller as text
            return "Cannot clean the sandbox: " + e.getMessage();
        }
        registry.remove(p);
        return (saved == null ? "" : saved + "\n") + "Removed sandbox container " + p.name()
                + (remote() ? "." : " (project files untouched).");
    }

    /**
     * Web: backs the workspaces up and removes the containers this executor started. CLI: {@code chat}-scope containers
     * (one per session) are removed, {@code user}-scope ones are kept for the next run; a container whose backup failed
     * is always kept.
     */
    @Override
    public void close() {
        registry.shutdownSweeper();
        for (Container c : registry.all()) {
            Placement p = c.placement;
            boolean keep = false;
            if (kind == Kind.WEB || p.docker().remote()) { // nothing is mounted: bring the files home first
                Backup b = registry.backup(c);
                if (b.ok()) {
                    log.info(b.message());
                } else {
                    keep = true;
                    warn.accept(b.message() + " " + keptMessage(c.name));
                }
            }
            if (keep) {
                registry.forget(p.key());
            } else if (kind == Kind.WEB || !p.userScope()) {
                registry.remove(p);
            } else {
                registry.forget(p.key());
            }
        }
    }

    // ── sweeps ────────────────────────────────────────────────────────────────

    /**
     * Web, at startup: removes the {@code chat}-scope containers whose chat no longer exists. CLI containers are never
     * touched. Returns the names removed.
     */
    public List<String> sweepOrphans(Predicate<String> chatExists) {
        return registry.sweepOrphans(chatExists);
    }

    /** Web, at startup: removes the {@code user}-scope containers whose user no longer exists. */
    public List<String> sweepOrphanUsers(Predicate<String> userExists) {
        return registry.sweepOrphanUsers(userExists);
    }

    /**
     * One pass of the clean-up policy: stops or backs up and removes the containers idle for longer than their policy
     * allows, and deletes the expired backups. Runs by itself every minute once a container with a policy is started.
     * Returns what was done.
     */
    public List<String> sweep() {
        return registry.sweep(System.currentTimeMillis());
    }

    List<String> sweep(long nowMillis) {
        return registry.sweep(nowMillis);
    }

    /**
     * A user was dropped (temporary user expired, user deleted): removes all of their data: containers, volume,
     * backups, tarballs and image records. Returns what was removed.
     */
    public List<String> dropUser(String userId) {
        return registry.dropUser(userId);
    }

    /** CLI flag {@code --sandbox-clean}: removes the containers of {@code startupDir}. Returns the names removed. */
    public static List<String> cleanStartupDir(HarnessConfig config, DockerCommandRunner runner, Path startupDir)
            throws IOException {
        String h = Placement.hash(startupDir.toAbsolutePath().normalize().toString()).substring(0, 12);
        return ContainerRegistry.removeAll(config, runner, "label=jllm.project=" + h);
    }

    /** CLI flag {@code --sandbox-clean-all}: removes every {@code jllm.kind=cli} container. Returns the names removed. */
    public static List<String> cleanAllCli(HarnessConfig config, DockerCommandRunner runner) throws IOException {
        return ContainerRegistry.removeAll(config, runner, "label=jllm.kind=cli");
    }
}
