package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.HarnessConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Docker backend with a long-running container and a small HTTP daemon inside ({@code sandbox-agent.py}). Every tool
 * (bash and the file tools) runs through the daemon, so files, installed packages and background processes persist
 * between calls and a command does not pay container startup.
 * <p>
 * Layout: {@link Kind#CLI} keeps one container per startup directory, with that directory bind-mounted at
 * {@code /workspace}; {@link Kind#WEB} has one container per chat with {@code /workspace} on an anonymous volume that
 * is not on the host. The directory layout on the host (history, sessions) comes from the wrapped
 * {@link LocalDirSandboxExecutor}. The daemon listens on {@code 127.0.0.1} only (published port) and every request
 * carries a per-container token; see docs/SANDBOX.md.
 */
public class RemoteExecSandboxExecutor implements SandboxExecutor {

    /** Which front end owns the containers. */
    public enum Kind {
        CLI, WEB;

        String label() {
            return name().toLowerCase();
        }
    }

    /** Work directory in the container. */
    public static final String WORKSPACE = "/workspace";
    /** Scratchpad in the container; each agent has a directory below it. */
    public static final String SCRATCHPAD = "/workspace/.kendhar/scratchpad";
    static final int DAEMON_PORT = 8080;
    private static final String AGENT_RESOURCE = "sandbox-agent.py";
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration START_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofSeconds(20);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(10);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(RemoteExecSandboxExecutor.class);

    private final HarnessConfig config;
    private final DockerCommandRunner runner;
    private final String dockerHost;
    private final LocalDirSandboxExecutor layout;
    private final Kind kind;
    private final Path startupDir;
    private final Map<String, Container> containers = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private volatile Consumer<String> warn = log::warn;
    private final java.util.Set<String> localChats = ConcurrentHashMap.newKeySet();
    private volatile boolean verified;

    private record Container(String name, DaemonClient client) {
    }

    /**
     * @param layout     directory layout on the host (a {@link CwdSandboxExecutor} for the CLI, a
     *                   {@link ChatDirSandboxExecutor} for the web)
     * @param startupDir CLI: the directory mounted at {@code /workspace}; ignored for the web
     */
    public RemoteExecSandboxExecutor(HarnessConfig config, DockerCommandRunner runner, LocalDirSandboxExecutor layout,
                                     Kind kind, Path startupDir) {
        this(config, runner, System.getenv("DOCKER_HOST"), layout, kind, startupDir);
    }

    RemoteExecSandboxExecutor(HarnessConfig config, DockerCommandRunner runner, String dockerHost,
                              LocalDirSandboxExecutor layout, Kind kind, Path startupDir) {
        this.config = config;
        this.runner = runner;
        this.dockerHost = dockerHost;
        this.layout = layout;
        this.kind = kind;
        this.startupDir = startupDir == null ? layout.location() : startupDir.toAbsolutePath().normalize();
    }

    /** Where warnings go (default: the log); the CLI prints them. */
    public RemoteExecSandboxExecutor onWarning(Consumer<String> warn) {
        this.warn = warn;
        return this;
    }

    // ── layout ────────────────────────────────────────────────────────────────

    @Override
    public Path location() {
        return layout.location();
    }

    /** The work dir inside the container (display only; the file tools go through {@link #files}). */
    @Override
    public boolean allowLocalFallback(String chatId) {
        if (!config.sandbox().localDirSandboxAllowed()) return false;
        localChats.add(chatId);
        return true;
    }

    @Override
    public void environmentWhitelist(java.util.List<String> patterns) {
        layout.environmentWhitelist(patterns); // only the local fallback runs commands on the host
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
        return Path.of(WORKSPACE);
    }

    @Override
    public Path scratchDir(String chatId, String agentId) {
        if (localFallbackActive(chatId)) return layout.scratchDir(chatId, agentId);
        if (agentId == null || !agentId.matches("[A-Za-z0-9._-]{1,64}") || agentId.contains("..")) {
            throw new IllegalArgumentException("Invalid agentId");
        }
        return Path.of(SCRATCHPAD, agentId);
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
    public String shellEnvironment() {
        return "The bash tool runs sh -c (POSIX sh, not bash) in a long-running Linux container ("
                + config.sandbox().docker().image() + ") with the working directory at " + WORKSPACE
                + ". Files, installed tools and background processes persist between commands; each command is a "
                + "fresh shell, so cd and exported variables do not carry over. The file tools work in the same "
                + "container. Network: docker's default.";
    }

    @Override
    public FileBackend files(String chatId, String agentId) {
        if (localFallbackActive(chatId)) return null;
        return new RemoteFileBackend(() -> client(chatId), WORKSPACE, SCRATCHPAD, agentId);
    }

    // ── commands ──────────────────────────────────────────────────────────────

    @Override
    public ExecutionResult execute(String chatId, String command, Duration timeout) {
        if (localFallbackActive(chatId)) return layout.execute(chatId, command, timeout);
        return exec(chatId, WORKSPACE, SCRATCHPAD + "/main", command, timeout);
    }

    @Override
    public ExecutionResult execute(String chatId, Path runDir, Path readOnlyDir, String command, Duration timeout) {
        if (localFallbackActive(chatId)) return layout.execute(chatId, runDir, readOnlyDir, command, timeout);
        return exec(chatId, posix(runDir), posix(runDir), command, timeout);
    }

    @Override
    public ExecutionResult execute(String chatId, Path scratchDir, boolean planMode, String command, Duration timeout) {
        if (localFallbackActive(chatId)) return layout.execute(chatId, scratchDir, planMode, command, timeout);
        String scratch = scratchDir == null ? SCRATCHPAD + "/main" : posix(scratchDir);
        return exec(chatId, planMode ? scratch : WORKSPACE, scratch, command, timeout);
    }

    private ExecutionResult exec(String chatId, String cwd, String logDir, String command, Duration timeout) {
        String id = null;
        DaemonClient c = null;
        try {
            c = client(chatId);
            Map<String, Object> body = Map.of("command", command, "cwd", cwd, "logDir", logDir,
                    "timeoutSeconds", Math.max(1, timeout.toSeconds()));
            DaemonClient.Response r = c.call("POST", "/exec", null, JSON.writeValueAsBytes(body),
                    "application/json", CONTROL_TIMEOUT);
            if (r.status() != 200) return new ExecutionResult("", "Sandbox error " + r.status() + ": " + r.text(), -1, false);
            id = JSON.readTree(r.body()).path("id").asText();
            long deadline = System.nanoTime() + timeout.plusSeconds(60).toNanos();
            while (true) {
                DaemonClient.Response p = c.call("GET", "/exec/" + id, Map.of("wait", String.valueOf(POLL.toSeconds())),
                        null, null, POLL.plusSeconds(20));
                if (p.status() != 200) return new ExecutionResult("", "Sandbox error " + p.status() + ": " + p.text(), -1, false);
                JsonNode n = JSON.readTree(p.body());
                if (!n.path("running").asBoolean(false)) {
                    String logPath = n.path("logPath").isNull() ? null : n.path("logPath").asText(null);
                    return new ExecutionResult(n.path("stdout").asText(""), "", n.path("exitCode").asInt(-1),
                            n.path("timedOut").asBoolean(false), n.path("omittedLines").asLong(0),
                            logPath == null || logPath.isEmpty() ? null : Path.of(logPath));
                }
                if (System.nanoTime() > deadline) return new ExecutionResult("", "Sandbox command did not finish", -1, true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecutionResult("", "Interrupted", -1, false);
        } catch (IOException e) {
            containers.remove(owner(chatId)); // the daemon may be gone: check the container again next time
            return new ExecutionResult("", "Sandbox unavailable: " + e.getMessage(), 127, false);
        } finally {
            if (id != null && c != null) release(c, id);
        }
    }

    /** Kills (if still running) and forgets a command; the container stays up. */
    private static void release(DaemonClient c, String id) {
        boolean interrupted = Thread.interrupted();
        try {
            c.call("DELETE", "/exec/" + id, null, null, null, CONTROL_TIMEOUT);
        } catch (InterruptedException e) {
            interrupted = true;
        } catch (IOException ignored) {
            // daemon gone: nothing to release
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    // ── container lifecycle ───────────────────────────────────────────────────

    private String owner(String chatId) {
        return kind == Kind.WEB ? chatId : hash(startupDir.toString()).substring(0, 12);
    }

    static String containerName(Kind kind, String owner) {
        return "jllm-" + kind.label() + "-" + owner;
    }

    private DaemonClient client(String chatId) throws IOException {
        String owner = owner(chatId);
        Container c = containers.get(owner);
        if (c != null) return c.client();
        synchronized (locks.computeIfAbsent(owner, k -> new Object())) {
            c = containers.get(owner);
            if (c == null) {
                c = start(chatId, owner);
                containers.put(owner, c);
            }
            return c.client();
        }
    }

    private Container start(String chatId, String owner) throws IOException {
        requireDocker();
        String name = containerName(kind, owner);
        String wanted = configHash();
        JsonNode existing = inspect(name);
        if (existing != null) {
            String have = existing.path("Config").path("Labels").path("jllm.config").asText("");
            if (have.equals(wanted)) {
                if (!existing.path("State").path("Running").asBoolean(false)) docker("start", name);
                String token = tokenOf(existing);
                if (token != null) {
                    Container c = new Container(name, new DaemonClient(daemonHost(), hostPort(name), token));
                    if (waitHealthy(c, name, Duration.ofSeconds(20))) return c;
                }
                warn.accept("Sandbox container " + name + " does not answer; recreating it.");
            } else {
                warn.accept("Sandbox container " + name + " was made with other settings (image, limits or mode); "
                        + "recreating it. Background processes and tools installed in it are lost"
                        + (kind == Kind.CLI ? "; project files are not (they are in the mounted directory)." : "."));
            }
            removeContainer(name);
        }
        return create(chatId, owner, name, wanted);
    }

    /** Remote docker: nothing can be bind-mounted, the daemon script travels in the environment. */
    private boolean remote() {
        return config.sandbox().docker().remote();
    }

    /** Where this machine reaches the published port: 127.0.0.1, or the remote docker host. */
    private String daemonHost() {
        String set = config.sandbox().docker().remoteHost();
        if (set != null && !set.isBlank()) return set.strip();
        if (remote() && DockerSandboxExecutor.isRemote(dockerHost)) {
            String h = java.net.URI.create(dockerHost.strip()).getHost();
            if (h != null) return h;
            throw new SandboxUnavailableException("Cannot read a host from DOCKER_HOST=" + dockerHost
                    + "; set sandbox.docker.remoteHost.");
        }
        return "127.0.0.1";
    }

    /** The zip with the workspace to start from: the project directory (CLI) or the last backup (web); null: none. */
    private byte[] initialWorkspace(String chatId) throws IOException {
        if (kind == Kind.CLI) return WorkspaceArchive.hasContent(startupDir) ? WorkspaceArchive.pack(startupDir) : null;
        Path f = backupFile(chatId);
        return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
    }

    private Path backupFile(String chatId) {
        return layout.location().resolve(chatId).resolve(".kendhar").resolve("workspace-backup.zip");
    }

    private Container create(String chatId, String owner, String name, String configHash) throws IOException {
        Path agentDir = remote() ? null : installAgent();
        String token = hash(UUID.randomUUID().toString());
        HarnessConfig.Docker d = config.sandbox().docker();
        List<String> a = new ArrayList<>(List.of("create", "--name", name,
                "--label", "jllm=1", "--label", "jllm.kind=" + kind.label(), "--label", "jllm.owner=" + owner,
                "--label", "jllm.config=" + configHash,
                "--memory", d.memory(), "--cpus", d.cpus(), "--pids-limit", String.valueOf(d.pidsLimit()),
                "--read-only", "--tmpfs", "/tmp:rw,size=64m",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--user", d.user(),
                "-e", "HOME=/tmp", "-e", "JLLM_TOKEN=" + token, "-e", "JLLM_PORT=" + DAEMON_PORT,
                "-e", "JLLM_BIND=0.0.0.0", "-e", "JLLM_WORKSPACE=" + WORKSPACE,
                "-p", (remote() ? "0.0.0.0" : "127.0.0.1") + "::" + DAEMON_PORT));
        if (remote()) {
            a.addAll(List.of("-e", "JLLM_AGENT_B64=" + agentPayload()));
        } else {
            a.addAll(List.of("-v", DockerSandboxExecutor.mountSource(agentDir) + ":/agent:ro"));
        }
        if (kind == Kind.CLI && !remote()) {
            a.addAll(List.of("-v", DockerSandboxExecutor.mountSource(startupDir) + ":" + WORKSPACE));
        } else {
            a.addAll(List.of("-v", WORKSPACE));
        }
        a.addAll(List.of("-w", WORKSPACE, d.image(), "python"));
        if (remote()) {
            a.addAll(List.of("-c", "import base64,zlib,os;exec(compile(zlib.decompress(base64.b64decode("
                    + "os.environ['JLLM_AGENT_B64'])),'sandbox-agent.py','exec'),{'__name__':'__main__'})"));
        } else {
            a.add("/agent/" + AGENT_RESOURCE);
        }
        docker(a.toArray(String[]::new));
        try {
            if (kind == Kind.WEB || remote()) {
                // a fresh volume is owned by root: hand it to the container user before the daemon starts
                docker("run", "--rm", "-u", "0", "--cap-drop", "ALL", "--cap-add", "CHOWN",
                        "--security-opt", "no-new-privileges", "--volumes-from", name, d.image(),
                        "chown", d.user(), WORKSPACE);
            }
            docker("start", name);
            Container c = new Container(name, new DaemonClient(daemonHost(), hostPort(name), token));
            if (!waitHealthy(c, name, START_TIMEOUT)) {
                throw new IOException("The sandbox daemon in " + name + " did not become ready. "
                        + "Does the image have python3 and sh? " + logsTail(name));
            }
            if (remote()) upload(c, chatId);
            return c;
        } catch (IOException | RuntimeException e) {
            removeContainer(name);
            throw e;
        }
    }

    /** Sends the starting workspace (remote docker only) to a container that was just created. */
    private void upload(Container c, String chatId) throws IOException {
        byte[] zip = initialWorkspace(chatId);
        if (zip == null) return;
        log.info("Uploading the workspace ({} KB) to {}", zip.length / 1024, c.name());
        try {
            DaemonClient.Response r = c.client().call("POST", "/setup", null, zip, "application/zip", TRANSFER_TIMEOUT);
            if (r.status() != 200) throw new IOException("Workspace upload failed: " + r.status() + " " + r.text());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** Downloads the container's workspace into the project directory (CLI) or the chat's backup file (web). */
    @Override
    public String backup(String chatId) {
        if (!remote()) return null;
        Container c = containers.get(owner(chatId));
        if (c == null) return "No running sandbox container to back up.";
        try {
            DaemonClient.Response r = c.client().call("GET", "/backup", null, null, null, TRANSFER_TIMEOUT);
            if (r.status() != 200) return "Backup failed: " + r.status() + " " + r.text();
            if (kind == Kind.CLI) {
                int files = WorkspaceArchive.unpack(r.body(), startupDir);
                return "Backed up " + files + " file(s) from " + c.name() + " into " + startupDir + ".";
            }
            Path f = backupFile(chatId);
            Files.createDirectories(f.getParent());
            Path tmp = Files.createTempFile(f.getParent(), "backup", ".tmp");
            Files.write(tmp, r.body());
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return "Backed up " + r.body().length / 1024 + " KB from " + c.name() + " to " + f + ".";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Backup interrupted.";
        } catch (IOException | RuntimeException e) {
            return "Backup failed: " + e.getMessage();
        }
    }

    private static String agentPayload() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream z = new java.util.zip.DeflaterOutputStream(out)) {
            z.write(agentBytes());
        }
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private boolean waitHealthy(Container c, String name, Duration max) throws IOException {
        long end = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < end) {
            try {
                if (c.client().call("GET", "/health", null, null, null, Duration.ofSeconds(3)).status() == 200) return true;
            } catch (IOException ignored) {
                // not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
            JsonNode st = inspect(name);
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

    private String logsTail(String name) {
        try {
            DockerCommandRunner.Result r = runner.run(
                    List.of(config.sandbox().docker().binary(), "logs", "--tail", "20", name), CONTROL_TIMEOUT);
            return (r.stdout() + r.stderr()).strip();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "";
        }
    }

    private int hostPort(String name) throws IOException {
        String out = docker("port", name, DAEMON_PORT + "/tcp").strip();
        for (String line : out.split("\\R")) {
            int colon = line.lastIndexOf(':');
            if (colon > 0) return Integer.parseInt(line.substring(colon + 1).strip());
        }
        throw new IOException("No published port for container " + name + ": " + out);
    }

    /** Inspect document of a container, or null when it does not exist. */
    private JsonNode inspect(String name) throws IOException {
        DockerCommandRunner.Result r = run("inspect", name);
        if (r.exitCode() != 0) return null;
        JsonNode n = JSON.readTree(r.stdout());
        return n.isArray() && !n.isEmpty() ? n.get(0) : null;
    }

    private static String tokenOf(JsonNode inspect) {
        for (JsonNode e : inspect.path("Config").path("Env")) {
            String s = e.asText();
            if (s.startsWith("JLLM_TOKEN=")) return s.substring("JLLM_TOKEN=".length());
        }
        return null;
    }

    private void removeContainer(String name) {
        try {
            run("rm", "-f", "-v", name);
        } catch (IOException ignored) {
            // already gone
        }
    }

    private DockerCommandRunner.Result run(String... args) throws IOException {
        List<String> argv = new ArrayList<>();
        argv.add(config.sandbox().docker().binary());
        argv.addAll(List.of(args));
        try {
            return runner.run(argv, CONTROL_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** Runs a docker command that must succeed; returns its stdout. */
    private String docker(String... args) throws IOException {
        DockerCommandRunner.Result r = run(args);
        if (r.exitCode() != 0 || r.timedOut()) {
            throw new IOException("docker " + args[0] + " failed: " + (r.stderr().isBlank() ? r.stdout() : r.stderr()).strip());
        }
        return r.stdout();
    }

    /** @throws SandboxUnavailableException when docker cannot be used; the agent loop offers to run locally instead */
    private void requireDocker() throws IOException {
        if (!remote() && DockerSandboxExecutor.isRemote(dockerHost)) {
            throw new SandboxUnavailableException("Docker daemon mode needs a local docker daemon: DOCKER_HOST=" + dockerHost
                    + " is remote (its published port and bind mounts would not be reachable). "
                    + "Allow it with sandbox.docker.remote (--docker-remote): the workspace is then uploaded and backed up.");
        }
        if (verified) return;
        String bin = config.sandbox().docker().binary();
        try {
            DockerCommandRunner.Result r = runner.run(List.of(bin, "version", "--format", "{{.Server.Version}}"),
                    CONTROL_TIMEOUT);
            if (r.exitCode() != 0 || r.timedOut()) {
                throw new SandboxUnavailableException("Docker sandbox unavailable: '" + bin
                        + " version' failed (is the docker daemon running?): " + r.stderr().strip());
            }
            verified = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } catch (IOException e) {
            throw new SandboxUnavailableException("Docker sandbox unavailable: docker binary '" + bin
                    + "' not found or not executable (" + e.getMessage() + ")");
        }
    }

    // ── cleaning ──────────────────────────────────────────────────────────────

    @Override
    public void deleteWorkDir(String chatId) {
        if (kind == Kind.WEB) {
            String owner = owner(chatId);
            containers.remove(owner);
            removeContainer(containerName(kind, owner));
        }
        layout.deleteWorkDir(chatId);
    }

    /** CLI: removes the container of the startup directory (the next command makes a new one). */
    @Override
    public String clean(String chatId) {
        if (kind != Kind.CLI) return "Not available for web chats: deleting the chat removes its container and files.";
        String owner = owner(chatId);
        String name = containerName(kind, owner);
        String saved = remote() ? backup(chatId) : null; // nothing is mounted: keep the files before removing
        containers.remove(owner);
        try {
            requireDocker();
            if (inspect(name) == null) return "No sandbox container for " + startupDir;
        } catch (IOException | RuntimeException e) {
            return "Cannot clean the sandbox: " + e.getMessage();
        }
        removeContainer(name);
        return (saved == null ? "" : saved + "\n") + "Removed sandbox container " + name
                + (remote() ? "." : " (project files untouched).");
    }

    /** Web: removes the containers this executor started. CLI containers are kept for the next run. */
    @Override
    public void close() {
        if (remote()) { // nothing is mounted: bring the files home before the session (or the web container) ends
            for (String owner : new ArrayList<>(containers.keySet())) {
                String chatId = kind == Kind.WEB ? owner : "-";
                String done = backup(chatId);
                if (done != null) log.info(done);
            }
        }
        if (kind != Kind.WEB) return;
        for (Container c : new ArrayList<>(containers.values())) removeContainer(c.name());
        containers.clear();
    }

    /**
     * Web, at startup: removes the {@code jllm.kind=web} containers whose chat no longer exists. CLI containers are
     * never touched. Returns the names removed.
     */
    public List<String> sweepOrphans(Predicate<String> chatExists) {
        List<String> removed = new ArrayList<>();
        if (kind != Kind.WEB) return removed;
        try {
            requireDocker();
            // {{.Labels}} is "a=b,c=d": no quotes in the template, which survive neither wsl.exe nor cmd
            String out = docker("ps", "-a", "--filter", "label=jllm.kind=web", "--format", "{{.Names}} {{.Labels}}");
            java.util.regex.Pattern owner = java.util.regex.Pattern.compile("jllm\\.owner=([^,\\s]*)");
            for (String line : out.split("\\R")) {
                String[] f = line.strip().split("\\s+", 2);
                java.util.regex.Matcher m = f.length < 2 ? null : owner.matcher(f[1]);
                if (m == null || !m.find()) continue;
                if (!chatExists.test(m.group(1))) {
                    removeContainer(f[0]);
                    removed.add(f[0]);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Orphan sweep skipped: {}", e.getMessage());
        }
        return removed;
    }

    /** CLI flag {@code --sandbox-clean}: removes the container of {@code startupDir}. Returns the names removed. */
    public static List<String> cleanStartupDir(HarnessConfig config, DockerCommandRunner runner, Path startupDir)
            throws IOException {
        String owner = hash(startupDir.toAbsolutePath().normalize().toString()).substring(0, 12);
        return removeAll(config, runner, "name=^" + containerName(Kind.CLI, owner) + "$");
    }

    /** CLI flag {@code --sandbox-clean-all}: removes every {@code jllm.kind=cli} container. */
    public static List<String> cleanAllCli(HarnessConfig config, DockerCommandRunner runner) throws IOException {
        return removeAll(config, runner, "label=jllm.kind=cli");
    }

    private static List<String> removeAll(HarnessConfig config, DockerCommandRunner runner, String filter)
            throws IOException {
        String bin = config.sandbox().docker().binary();
        try {
            DockerCommandRunner.Result ps = runner.run(
                    List.of(bin, "ps", "-a", "--filter", "label=jllm=1", "--filter", filter, "--format", "{{.Names}}"),
                    CONTROL_TIMEOUT);
            if (ps.exitCode() != 0) throw new IOException("docker ps failed: " + ps.stderr().strip());
            List<String> removed = new ArrayList<>();
            for (String name : ps.stdout().split("\\R")) {
                if (name.isBlank()) continue;
                DockerCommandRunner.Result rm = runner.run(List.of(bin, "rm", "-f", "-v", name.strip()), CONTROL_TIMEOUT);
                if (rm.exitCode() == 0) removed.add(name.strip());
            }
            return removed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Hash of what a container is made of; a different value recreates a reused container. */
    private String configHash() throws IOException {
        HarnessConfig.Docker d = config.sandbox().docker();
        return hash(String.join("|", d.image(), d.memory(), d.cpus(), String.valueOf(d.pidsLimit()), d.user(),
                "daemon", String.valueOf(remote()), agentHash())).substring(0, 12);
    }

    private static volatile String agentHash;

    private static String agentHash() throws IOException {
        if (agentHash == null) agentHash = hash(new String(agentBytes(), StandardCharsets.UTF_8));
        return agentHash;
    }

    private static byte[] agentBytes() throws IOException {
        try (InputStream in = RemoteExecSandboxExecutor.class.getResourceAsStream(AGENT_RESOURCE)) {
            if (in == null) throw new IOException("Missing resource " + AGENT_RESOURCE);
            return in.readAllBytes();
        }
    }

    /**
     * Writes the daemon script to a stable per-version directory under the user's home (a reused container keeps
     * bind-mounting it after this JVM is gone) and returns the directory.
     */
    static synchronized Path installAgent() throws IOException {
        Path dir = Path.of(System.getProperty("jllm.home", System.getProperty("user.home")), ".jllm", "sandbox-agent", agentHash().substring(0, 12));
        Path file = dir.resolve(AGENT_RESOURCE);
        byte[] bytes = agentBytes();
        if (!Files.isRegularFile(file) || !java.util.Arrays.equals(Files.readAllBytes(file), bytes)) {
            Files.createDirectories(dir);
            Files.write(file, bytes);
        }
        return dir;
    }

    static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
