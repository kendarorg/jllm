package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor.Kind;
import org.kendar.llm.harness.sandbox.WorkspaceBackups.Backup;
import org.kendar.llm.harness.tools.ExternalLinks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Lifecycle of the sandbox containers of one front end: get-or-start by scope key, creation with the resource limits
 * of the {@link Placement}, reuse, removal, the periodic sweep that applies the clean-up policy to idle containers, and
 * the sweeps of orphans and dropped users. The executor above it only deals with the chat's work dir.
 */
final class ContainerRegistry {

    private static final Logger log = LoggerFactory.getLogger(ContainerRegistry.class);
    private static final String AGENT_RESOURCE = "sandbox-agent.py";
    private static final Duration START_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(10);
    static final long SWEEP_PERIOD_SECONDS = 60;

    /** A started container and the way to talk to its daemon. */
    static final class Container {
        final String name;
        final DaemonClient client;
        final DockerEndpoint endpoint;
        final ExecTunnel tunnel;
        final Placement placement;
        volatile long lastUsed = System.currentTimeMillis();

        Container(String name, DaemonClient client, DockerEndpoint endpoint, ExecTunnel tunnel, Placement placement) {
            this.name = name;
            this.client = client;
            this.endpoint = endpoint;
            this.tunnel = tunnel;
            this.placement = placement;
        }

        void close() {
            if (tunnel != null) tunnel.close();
        }
    }

    private final HarnessConfig config;
    private final DockerCommandRunner runner;
    private final String envHost;
    private final Kind kind;
    private final Path startupDir;
    private final WorkspaceBackups backups;
    private final Consumer<String> warn;
    private final Map<String, DockerEndpoint> endpoints = new ConcurrentHashMap<>();
    private final Map<String, Container> containers = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService sweeper;

    ContainerRegistry(HarnessConfig config, DockerCommandRunner runner, String envHost, Kind kind, Path startupDir,
                      WorkspaceBackups backups, Consumer<String> warn) {
        this.config = config;
        this.runner = runner;
        this.envHost = envHost;
        this.kind = kind;
        this.startupDir = startupDir;
        this.backups = backups;
        this.warn = warn;
    }

    // ── hosts ─────────────────────────────────────────────────────────────────

    /** The docker host of a user: the override when there is one, else the main one. */
    DockerEndpoint endpoint(String host) {
        if (host == null || host.isBlank()) return endpoints.computeIfAbsent("", k -> new DockerEndpoint(config, runner, envHost, false));
        return endpoints.computeIfAbsent(host.strip(), k -> new DockerEndpoint(config, runner, k, true));
    }

    /** The endpoint a backup record names ({@code default} or the override host). */
    DockerEndpoint endpointOfRecord(String hostKey) {
        return "default".equals(hostKey) ? endpoint(null) : endpoint(hostKey);
    }

    DockerEndpoint main() {
        return endpoint(null);
    }

    // ── get-or-start ──────────────────────────────────────────────────────────

    Container current(String key) {
        return containers.get(key);
    }

    Container acquire(Placement p) throws IOException {
        Container c = containers.get(p.key());
        if (c != null) {
            c.lastUsed = System.currentTimeMillis();
            return c;
        }
        synchronized (locks.computeIfAbsent(p.key(), k -> new Object())) {
            c = containers.get(p.key());
            if (c == null) {
                c = start(p);
                containers.put(p.key(), c);
                scheduleSweep(p.docker().cleanup());
            }
            c.lastUsed = System.currentTimeMillis();
            return c;
        }
    }

    void forget(String key) {
        Container c = containers.remove(key);
        if (c != null) c.close();
    }

    Collection<Container> all() {
        return new ArrayList<>(containers.values());
    }

    private Container start(Placement p) throws IOException {
        DockerEndpoint ep = endpoint(p.dockerHost());
        ep.require();
        String wanted = configHash(p);
        JsonNode existing = ep.inspect(p.name());
        if (existing != null) {
            String have = existing.path("Config").path("Labels").path("jllm.config").asText("");
            if (have.equals(wanted)) {
                if (!existing.path("State").path("Running").asBoolean(false)) ep.docker("start", p.name());
                String token = tokenOf(existing);
                if (token != null) {
                    Container c = connect(p.name(), token, p, ep);
                    if (c != null) return c;
                }
                warn.accept("Sandbox container " + p.name() + " does not answer; recreating it.");
            } else {
                warn.accept("Sandbox container " + p.name() + " was made with other settings (image, limits, profile or "
                        + "mode); recreating it. Background processes and tools installed in it are lost"
                        + (kind == Kind.CLI ? "; project files are not (they are in the mounted directory)." : "."));
            }
            ep.removeContainer(p.name());
        }
        return create(p, ep, wanted);
    }

    /** The daemon of a running container, over its published port or the exec tunnel; null when it does not answer. */
    private Container connect(String name, String token, Placement p, DockerEndpoint ep) throws IOException {
        return connect(name, token, p, ep, Duration.ofSeconds(20));
    }

    private Container connect(String name, String token, Placement p, DockerEndpoint ep, Duration wait)
            throws IOException {
        ExecTunnel tunnel = null;
        DaemonClient client;
        if (p.noNetwork()) {
            List<String> tail = ExecTunnel.relayCommand(RemoteExecSandboxExecutor.DAEMON_PORT);
            String[] argv = new String[3 + tail.size()];
            argv[0] = "exec";
            argv[1] = "-i";
            argv[2] = name;
            for (int i = 0; i < tail.size(); i++) argv[3 + i] = tail.get(i);
            tunnel = new ExecTunnel(() -> ep.start(argv));
            client = new DaemonClient(p.docker(), "127.0.0.1", tunnel.port(), token);
        } else {
            client = new DaemonClient(p.docker(), ep.daemonHost(), ep.hostPort(name), token);
        }
        Container c = new Container(name, client, ep, tunnel, p);
        if (waitHealthy(c, wait)) return c;
        c.close();
        return null;
    }

    private Container create(Placement p, DockerEndpoint ep, String configHash) throws IOException {
        HarnessConfig.Docker d = p.docker();
        boolean remote = d.remote();
        String token = Placement.hash(UUID.randomUUID().toString());
        String image = backups.usableImage(p, ep);
        if (image != null) log.info("Restoring {} from the image {}", p.name(), image);
        boolean freshVolume = p.volume() == null || !volumeExists(ep, p.volume());
        List<String> a = new ArrayList<>(List.of("create", "--name", p.name(),
                "--label", "jllm=1", "--label", "jllm.kind=" + kind.label(), "--label", "jllm.owner=" + p.key(),
                "--label", "jllm.scope=" + (p.userScope() ? "user" : "chat"),
                "--label", "jllm.config=" + configHash,
                "--memory", d.memory(), "--cpus", d.cpus(), "--pids-limit", String.valueOf(d.pidsLimit()),
                "--read-only", "--tmpfs", "/tmp:rw,size=64m",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--user", d.user(),
                "-e", "HOME=/tmp", "-e", "JLLM_TOKEN=" + token, "-e", "JLLM_PORT=" + RemoteExecSandboxExecutor.DAEMON_PORT,
                "-e", "JLLM_BIND=0.0.0.0", "-e", "JLLM_WORKSPACE=" + RemoteExecSandboxExecutor.WORKSPACE,
                "-e", "JLLM_EXTERNAL_LINKS=" + externalLinks().settingValue()));
        if (p.project() != null) a.addAll(List.of("--label", "jllm.project=" + p.project()));
        if (p.userId() != null) a.addAll(List.of("--label", "jllm.user=" + p.userId()));
        if (d.hardened()) a.addAll(List.of("--label", "jllm.profile=hardened"));
        if (p.noNetwork()) {
            a.addAll(List.of("--network", "none"));
        } else {
            // a network of its own per container (so per user or per chat): never the default bridge shared by all
            ep.ensureNetwork(p.name(), p.key());
            a.addAll(List.of("--network", p.name()));
            a.addAll(List.of("-p", (remote ? "0.0.0.0" : "127.0.0.1") + "::" + RemoteExecSandboxExecutor.DAEMON_PORT));
        }
        // the daemon script travels in the environment, never as a file of the host the container could reach
        a.addAll(List.of("-e", "JLLM_AGENT_B64=" + agentPayload()));
        if (kind == Kind.CLI && !remote) {
            a.addAll(List.of("-v", DockerHosts.mountSource(startupDir) + ":" + RemoteExecSandboxExecutor.WORKSPACE));
            // after the parent: docker applies the mounts parent first, the child hides that part of it
            a.addAll(ProjectMounts.args(startupDir, RemoteExecSandboxExecutor.WORKSPACE, DockerHosts::mountSource));
        } else if (p.volume() != null) {
            a.addAll(List.of("-v", p.volume() + ":" + RemoteExecSandboxExecutor.WORKSPACE));
        } else {
            a.addAll(List.of("-v", RemoteExecSandboxExecutor.WORKSPACE));
        }
        a.addAll(List.of("-w", RemoteExecSandboxExecutor.WORKSPACE, image != null ? image : d.image(), "python"));
        a.addAll(List.of("-c", "import base64,zlib,os;exec(compile(zlib.decompress(base64.b64decode("
                + "os.environ['JLLM_AGENT_B64'])),'sandbox-agent.py','exec'),{'__name__':'__main__'})"));
        ep.docker(a.toArray(String[]::new));
        try {
            if (kind == Kind.WEB || remote) {
                // a fresh volume is owned by root: hand it to the container user before the daemon starts
                ep.docker("run", "--rm", "-u", "0", "--cap-drop", "ALL", "--cap-add", "CHOWN",
                        "--security-opt", "no-new-privileges", "--volumes-from", p.name(), d.image(),
                        "chown", d.user(), RemoteExecSandboxExecutor.WORKSPACE);
            }
            ep.docker("start", p.name());
            Container c = connect(p.name(), token, p, ep, START_TIMEOUT);
            if (c == null) {
                throw new IOException("The sandbox daemon in " + p.name() + " did not become ready. "
                        + "Does the image have python3 and sh? " + ep.logsTail(p.name()));
            }
            if ((remote || kind == Kind.WEB) && freshVolume) upload(c, p);
            return c;
        } catch (IOException | RuntimeException e) { // boundary: remove the container, rethrown unchanged
            ep.removeContainer(p.name());
            throw e;
        }
    }

    private static boolean volumeExists(DockerEndpoint ep, String volume) {
        try {
            return ep.run("volume", "inspect", volume).exitCode() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Sends the starting workspace to a container that was just created. */
    private void upload(Container c, Placement p) throws IOException {
        byte[] zip = backups.initialWorkspace(p, p.docker().remote());
        if (zip == null) return;
        log.info("Uploading the workspace ({} KB) to {}", zip.length / 1024, c.name);
        try {
            DaemonClient.Response r = c.client.call("POST", "/setup", null, zip, "application/zip", TRANSFER_TIMEOUT,
                    DaemonClient.MAX_CONTROL_RESPONSE);
            if (r.status() != 200) throw new IOException("Workspace upload failed: " + r.status() + " " + r.text());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /**
     * The client of an existing container this registry is not connected to yet (a new session): started if stopped,
     * reached with the token in its environment. Null when it does not answer. Unlike {@link #start} it never
     * recreates the container, whatever its settings.
     */
    Container attach(Placement p, JsonNode existing) throws IOException {
        DockerEndpoint ep = endpoint(p.dockerHost());
        String token = tokenOf(existing);
        if (token == null) return null;
        if (!existing.path("State").path("Running").asBoolean(false)) ep.docker("start", p.name());
        return connect(p.name(), token, p, ep);
    }

    private boolean waitHealthy(Container c, Duration max) throws IOException {
        long end = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < end) {
            try {
                if (c.client.call("GET", "/health", null, null, null, Duration.ofSeconds(3),
                        DaemonClient.MAX_CONTROL_RESPONSE).status() == 200) return true;
            } catch (IOException ignored) {
                // not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
            JsonNode st = c.endpoint.inspect(c.name);
            if (st == null || !st.path("State").path("Running").asBoolean(false)) return false;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }
        return false;
    }

    private static String tokenOf(JsonNode inspect) {
        for (JsonNode e : inspect.path("Config").path("Env")) {
            String s = e.asText();
            if (s.startsWith("JLLM_TOKEN=")) return s.substring("JLLM_TOKEN=".length());
        }
        return null;
    }

    // ── removal ───────────────────────────────────────────────────────────────

    /** Removes a container (and its anonymous volume) and forgets it; the named volume of a user stays. */
    void remove(Placement p) {
        forget(p.key());
        endpoint(p.dockerHost()).removeContainer(p.name());
    }

    Backup backup(Container c) {
        return backups.backup(c.client, c.name, c.endpoint, c.placement);
    }

    // ── sweeps ────────────────────────────────────────────────────────────────

    private void scheduleSweep(HarnessConfig.Cleanup cleanup) {
        if (cleanup == null || cleanup.idleMinutes() <= 0 || sweeper != null) return;
        synchronized (this) {
            if (sweeper != null) return;
            sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jllm-sandbox-sweep");
                t.setDaemon(true);
                return t;
            });
            sweeper.scheduleWithFixedDelay(() -> {
                try {
                    sweep(System.currentTimeMillis());
                } catch (RuntimeException e) { // boundary: the sweep is best effort
                    log.warn("Sandbox sweep failed: {}", e.toString());
                }
            }, SWEEP_PERIOD_SECONDS, SWEEP_PERIOD_SECONDS, TimeUnit.SECONDS);
        }
    }

    void shutdownSweeper() {
        ScheduledExecutorService s = sweeper;
        if (s != null) s.shutdownNow();
    }

    /**
     * Applies the clean-up policy of each container to its idleness: stop it, back it up and remove it, or remove it;
     * warns about a workspace over its quota; deletes the backups whose retention is over. Returns what was done.
     */
    List<String> sweep(long now) {
        List<String> done = new ArrayList<>();
        for (Container c : all()) {
            HarnessConfig.Cleanup cl = c.placement.docker().cleanup();
            if (cl == null) continue;
            if (cl.idleMinutes() > 0 && now - c.lastUsed >= cl.idleMinutes() * 60_000L) {
                String r = reap(c, cl);
                if (r != null) done.add(r);
            } else if (cl.maxWorkspaceBytes() > 0) {
                checkQuota(c, cl.maxWorkspaceBytes());
            }
        }
        for (String key : backups.expireDue(now, this::endpointOfRecord)) done.add("expired backup " + key);
        return done;
    }

    private String reap(Container c, HarnessConfig.Cleanup cl) {
        Placement p = c.placement;
        String action = cl.action() == null ? "stop" : cl.action().toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "backup-remove" -> {
                    if (kind == Kind.WEB || p.docker().remote()) {
                        Backup b = backup(c);
                        if (!b.ok()) {
                            c.lastUsed = System.currentTimeMillis(); // retry after another idle period
                            warn.accept(b.message() + " The idle container " + c.name + " was kept.");
                            return null;
                        }
                        log.info(b.message());
                    }
                    remove(p);
                    if (p.volume() != null) endpoint(p.dockerHost()).removeVolume(p.volume());
                    return "backed up and removed " + c.name;
                }
                case "remove" -> {
                    remove(p);
                    return "removed " + c.name;
                }
                default -> {
                    forget(p.key());
                    c.endpoint.run("stop", "-t", "5", c.name);
                    return "stopped " + c.name;
                }
            }
        } catch (IOException | RuntimeException e) { // boundary: the sweep is best effort
            warn.accept("Cannot " + action + " the idle container " + c.name + ": " + e.getMessage());
            return null;
        }
    }

    private void checkQuota(Container c, long max) {
        try {
            ExecutionResult r = DaemonExec.run(c.client, "du -sb .",
                    RemoteExecSandboxExecutor.WORKSPACE, RemoteExecSandboxExecutor.SCRATCHPAD + "/main", Duration.ofSeconds(60), 0);
            String first = r.stdout().strip().split("\\s+")[0];
            long size = Long.parseLong(first);
            if (size > max) {
                warn.accept("The workspace of " + c.name + " is " + size / 1024 + " KB, over its limit of " + max / 1024
                        + " KB: a backup will be refused until it is reduced.");
            }
        } catch (IOException | RuntimeException e) { // boundary: the quota check is best effort
            log.debug("Quota check of {} skipped: {}", c.name, e.toString());
        }
    }

    /** Web, at startup: removes the chat-scope containers whose chat no longer exists. Returns the names removed. */
    List<String> sweepOrphans(Predicate<String> chatExists) {
        return sweepLabelled("chat", chatExists);
    }

    /** Web, at startup: removes the user-scope containers whose user no longer exists. Returns the names removed. */
    List<String> sweepOrphanUsers(Predicate<String> userExists) {
        return sweepLabelled("user", userExists);
    }

    private List<String> sweepLabelled(String scope, Predicate<String> exists) {
        List<String> removed = new ArrayList<>();
        if (kind != Kind.WEB) return removed;
        try {
            DockerEndpoint ep = main();
            ep.require();
            // {{.Labels}} is "a=b,c=d": no quotes in the template, which survive neither wsl.exe nor cmd
            String out = ep.docker("ps", "-a", "--filter", "label=jllm.kind=web", "--format", "{{.Names}} {{.Labels}}");
            java.util.regex.Pattern owner = java.util.regex.Pattern.compile("jllm\\.owner=([^,\\s]*)");
            java.util.regex.Pattern sc = java.util.regex.Pattern.compile("jllm\\.scope=([^,\\s]*)");
            for (String line : out.split("\\R")) {
                String[] f = line.strip().split("\\s+", 2);
                if (f.length < 2) continue;
                java.util.regex.Matcher m = owner.matcher(f[1]);
                if (!m.find()) continue;
                java.util.regex.Matcher s = sc.matcher(f[1]);
                String containerScope = s.find() ? s.group(1) : "chat";
                if (containerScope.equals(scope) && !exists.test(m.group(1))) {
                    ep.removeContainer(f[0]);
                    removed.add(f[0]);
                }
            }
        } catch (IOException | RuntimeException e) { // boundary: the sweep is best effort
            log.debug("Orphan sweep skipped: {}", e.getMessage());
        }
        return removed;
    }

    /**
     * A user was dropped: removes their containers, volumes, backups, tarballs and image records, whatever the scope.
     * Returns what was removed.
     */
    List<String> dropUser(String userId) {
        List<String> removed = new ArrayList<>();
        String safe = Placement.safe(userId);
        try {
            DockerEndpoint ep = main();
            ep.require();
            String out = ep.docker("ps", "-a", "--filter", "label=jllm.user=" + userId, "--format", "{{.Names}}");
            for (String name : out.split("\\R")) {
                if (name.isBlank()) continue;
                ep.removeContainer(name.strip());
                removed.add(name.strip());
            }
        } catch (IOException | RuntimeException e) { // boundary: dropping goes on with the files
            log.debug("Docker part of dropping {} skipped: {}", userId, e.getMessage());
        }
        containers.values().removeIf(c -> {
            boolean mine = userId.equals(c.placement.userId());
            if (mine) c.close();
            return mine;
        });
        for (DockerEndpoint ep : new ArrayList<>(endpoints.values())) ep.removeVolume("jllm-" + safe);
        for (String key : new ArrayList<>(backups.store().keys())) {
            Optional<BackupRecord> r = backups.store().get(key);
            boolean mine = key.equals("user/" + userId) || (r.isPresent() && userId.equals(r.get().userId()));
            if (mine) {
                backups.expire(key, this::endpointOfRecord);
                removed.add("backup " + key);
            }
        }
        return removed;
    }

    // ── static clean-up (CLI flags) ───────────────────────────────────────────

    /** Removes the containers matching a {@code docker ps} filter among the jllm ones. Returns the names removed. */
    static List<String> removeAll(HarnessConfig config, DockerCommandRunner runner, String filter) throws IOException {
        String bin = config.sandbox().docker().binary();
        try {
            DockerCommandRunner.Result ps = runner.run(
                    List.of(bin, "ps", "-a", "--filter", "label=jllm=1", "--filter", filter, "--format", "{{.Names}}"),
                    DockerEndpoint.CONTROL_TIMEOUT);
            if (ps.exitCode() != 0) throw new IOException("docker ps failed: " + ps.stderr().strip());
            List<String> removed = new ArrayList<>();
            for (String name : ps.stdout().split("\\R")) {
                if (name.isBlank()) continue;
                DockerCommandRunner.Result rm = runner.run(List.of(bin, "rm", "-f", "-v", name.strip()),
                        DockerEndpoint.CONTROL_TIMEOUT);
                if (rm.exitCode() == 0) {
                    removed.add(name.strip());
                    runner.run(List.of(bin, "network", "rm", name.strip()), DockerEndpoint.CONTROL_TIMEOUT);
                }
            }
            return removed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** What the daemon does with a link that leaves the workspace: the web host never follows one. */
    private ExternalLinks externalLinks() {
        return kind == Kind.WEB ? ExternalLinks.NONE : config.externalLinks();
    }

    /** Hash of what a container is made of; a different value recreates a reused container. */
    private String configHash(Placement p) throws IOException {
        HarnessConfig.Docker d = p.docker();
        return Placement.hash(String.join("|", d.image(), d.memory(), d.cpus(), String.valueOf(d.pidsLimit()), d.user(),
                "daemon", String.valueOf(d.remote()), externalLinks().settingValue(), agentHash(),
                d.hardened() ? "hardened" : "standard", String.valueOf(p.noNetwork()), "own-network")).substring(0, 12);
    }

    private static volatile String agentHash;

    static String agentHash() throws IOException {
        if (agentHash == null) agentHash = Placement.hash(new String(agentBytes(), StandardCharsets.UTF_8));
        return agentHash;
    }

    private static byte[] agentBytes() throws IOException {
        try (InputStream in = RemoteExecSandboxExecutor.class.getResourceAsStream(AGENT_RESOURCE)) {
            if (in == null) throw new IOException("Missing resource " + AGENT_RESOURCE);
            return in.readAllBytes();
        }
    }

    private static String agentPayload() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream z = new java.util.zip.DeflaterOutputStream(out)) {
            z.write(agentBytes());
        }
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
