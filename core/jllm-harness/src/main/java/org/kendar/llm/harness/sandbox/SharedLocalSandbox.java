package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.util.EnvWhitelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Commands on this machine for several users at once ({@code sandbox.backend=shared-local}, role kind
 * {@code shared-local}), without the user's own environment and, on Linux, without the rest of the file system.
 * <ul>
 *   <li>Environment: empty except {@code PATH} and {@code LANG}; {@code HOME} is the chat directory, {@code TMPDIR}
 *       the chat's scratch directory (Windows adds what it cannot start without).</li>
 *   <li>Linux: every command runs in {@code bwrap}: the chat and scratch directories read-write, minimal read-only
 *       system binds, a private {@code /tmp}, {@code /proc} and {@code /dev}, no network unless granted,
 *       {@code --die-with-parent}, {@code --new-session}. {@code bwrap} is probed at construction: missing or not
 *       working fails closed with a {@link SandboxUnavailableException}.</li>
 *   <li>Limits: CPU time, memory and processes through {@code systemd-run --user --scope} and {@code prlimit} when
 *       present; the timeout and the output cap ({@link TailCapture}) everywhere.</li>
 *   <li>Windows and macOS: no confinement is available. A warning is logged and only the environment, HOME, TMPDIR and
 *       the limits that exist there apply (testing use).</li>
 * </ul>
 */
public class SharedLocalSandbox extends LocalSandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(SharedLocalSandbox.class);

    /**
     * Per command limits. Zero or negative: not limited.
     *
     * @param cpuSeconds  CPU time
     * @param memoryBytes address space / memory
     * @param pids        processes and threads
     */
    public record Limits(long cpuSeconds, long memoryBytes, int pids) {
        public static Limits defaults() {
            return new Limits(120, 2L * 1024 * 1024 * 1024, 256);
        }
    }

    /** What the sandbox asks of the machine: replaced in tests. */
    interface Host {
        boolean linux();

        boolean windows();

        /** Absolute path of an executable on the PATH; null when not found. */
        String which(String name);

        /** Runs a short command; the exit code, or -1 when it could not run. */
        int probe(List<String> argv);

        Map<String, String> env();
    }

    private static final class RealHost implements Host {
        private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        @Override
        public boolean linux() {
            return OS.contains("linux");
        }

        @Override
        public boolean windows() {
            return OS.contains("win");
        }

        @Override
        public String which(String name) {
            String path = System.getenv("PATH");
            if (path == null) return null;
            for (String d : path.split(java.io.File.pathSeparator)) {
                if (d.isBlank()) continue;
                Path f = Path.of(d, name);
                if (Files.isRegularFile(f) && Files.isExecutable(f)) return f.toString();
            }
            return null;
        }

        @Override
        public int probe(List<String> argv) {
            try {
                Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
                p.getOutputStream().close();
                p.getInputStream().readAllBytes();
                if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return -1;
                }
                return p.exitValue();
            } catch (IOException e) {
                return -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        @Override
        public Map<String, String> env() {
            return System.getenv();
        }
    }

    private static final List<String> SYSTEM_DIRS = List.of("/usr", "/bin", "/sbin", "/lib", "/lib64", "/lib32", "/libx32");
    private static final List<String> ETC = List.of("/etc/ld.so.cache", "/etc/ld.so.conf", "/etc/ld.so.conf.d",
            "/etc/alternatives", "/etc/nsswitch.conf", "/etc/localtime", "/etc/locale.alias", "/etc/mime.types");
    private static final List<String> ETC_NETWORK = List.of("/etc/resolv.conf", "/etc/hosts", "/etc/ssl",
            "/etc/ca-certificates", "/etc/pki");
    private static final String DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin";

    private final Host host;
    private final Function<String, Boolean> networkOf;
    private final Limits limits;
    private final String bwrap;
    private final List<String> limitPrefix;
    private volatile long logLimit = TailCapture.DEFAULT_LOG_LIMIT;
    private volatile LocalShell shell = LocalShell.osDefault();

    /**
     * @param networkOf chat id to whether the role grants network; null: never
     * @param limits    null: {@link Limits#defaults()}
     * @throws SandboxUnavailableException on Linux when {@code bwrap} is missing or does not work
     */
    public SharedLocalSandbox(Layout layout, Function<String, Boolean> networkOf, Limits limits) {
        this(layout, networkOf, limits, new RealHost());
    }

    SharedLocalSandbox(Layout layout, Function<String, Boolean> networkOf, Limits limits, Host host) {
        super(layout);
        this.host = host;
        this.networkOf = networkOf == null ? c -> false : networkOf;
        this.limits = limits == null ? Limits.defaults() : limits;
        if (host.linux()) {
            this.bwrap = probeBwrap();
            this.limitPrefix = limitPrefix();
        } else {
            this.bwrap = null;
            this.limitPrefix = List.of();
            log.warn("shared-local sandbox: no confinement is available on this operating system; commands run as this "
                    + "process's user with a minimal environment (HOME and TMPDIR in the chat directory) and the "
                    + "limits that exist here. For testing only: use a docker sandbox or Linux for real users.");
        }
    }

    /**
     * Builds the sandbox for a host that serves users. When {@code required} (the backend or a role uses
     * {@code shared-local}) a Linux machine without a working {@code bwrap} stops the start with a
     * {@link SandboxUnavailableException}, and so does any other operating system (Windows, macOS: no confinement at
     * all); otherwise the problem is logged and the result is empty (a role added later gets tool errors until it is
     * fixed). The caller passes {@code required=false} only under the explicit testing flag, which also lets a
     * non-Linux host start unconfined.
     */
    public static java.util.Optional<SharedLocalSandbox> forHost(Layout layout, Function<String, Boolean> networkOf,
                                                                 boolean required) {
        return forHost(layout, networkOf, required, new RealHost());
    }

    static java.util.Optional<SharedLocalSandbox> forHost(Layout layout, Function<String, Boolean> networkOf,
                                                          boolean required, Host host) {
        if (required && !host.linux()) {
            throw new SandboxUnavailableException("shared-local sandbox: no confinement exists on this operating system, "
                    + "commands would run as the server user on the host network. The shared-local sandbox is in use "
                    + "(backend or a role): run on Linux with bubblewrap or use a docker sandbox; for testing only, set "
                    + "jllm.web.allow-missing-bwrap=true. Startup aborted.");
        }
        try {
            return java.util.Optional.of(new SharedLocalSandbox(layout, networkOf, null, host));
        } catch (SandboxUnavailableException e) {
            if (required) {
                throw new SandboxUnavailableException(e.getMessage() + ". The shared-local sandbox is in use (backend or a "
                        + "role): install bubblewrap and enable user namespaces, or use another sandbox. Startup aborted.");
            }
            log.error("{}. Roles with the shared-local sandbox cannot run commands.", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private String probeBwrap() {
        String exe = host.which("bwrap");
        if (exe == null) {
            throw new SandboxUnavailableException("shared-local sandbox needs bubblewrap (bwrap) on the PATH and it is "
                    + "not installed: refusing to run commands unconfined");
        }
        int rc = host.probe(List.of(exe, "--ro-bind", "/", "/", "--dev", "/dev", "--unshare-net", "--unshare-pid",
                "--die-with-parent", "--new-session", "true"));
        if (rc != 0) {
            throw new SandboxUnavailableException("shared-local sandbox: bwrap does not work here (exit " + rc
                    + "; user namespaces disabled?): refusing to run commands unconfined");
        }
        return exe;
    }

    private List<String> limitPrefix() {
        List<String> p = new ArrayList<>();
        String systemd = host.which("systemd-run");
        boolean cgroup = false;
        if (systemd != null && host.probe(List.of(systemd, "--user", "--scope", "--quiet", "true")) == 0) {
            cgroup = true;
            p.addAll(List.of(systemd, "--user", "--scope", "--quiet"));
            if (limits.memoryBytes() > 0) p.addAll(List.of("-p", "MemoryMax=" + limits.memoryBytes(), "-p", "MemorySwapMax=0"));
            if (limits.pids() > 0) p.addAll(List.of("-p", "TasksMax=" + limits.pids()));
            p.add("--");
        }
        String prlimit = host.which("prlimit");
        if (prlimit != null) {
            List<String> l = new ArrayList<>();
            l.add(prlimit);
            if (limits.cpuSeconds() > 0) l.add("--cpu=" + limits.cpuSeconds());
            if (!cgroup && limits.memoryBytes() > 0) l.add("--as=" + limits.memoryBytes());
            if (!cgroup && limits.pids() > 0) l.add("--nproc=" + limits.pids());
            if (l.size() > 1) {
                p.addAll(l);
                p.add("--");
            }
        } else if (!cgroup) {
            log.warn("shared-local sandbox: neither systemd-run nor prlimit found: no CPU, memory or process limits "
                    + "(only the timeout and the output cap)");
        }
        return List.copyOf(p);
    }

    /** Whether commands are confined (Linux with a working bwrap). */
    public boolean confined() {
        return bwrap != null;
    }

    // ── status and settings ───────────────────────────────────────────────────

    @Override
    public boolean networkAccess(String chatId) {
        return !confined() || network(chatId); // unconfined (Windows, macOS): the host network
    }

    @Override
    public String sandboxStatus(String chatId) {
        return "runner: shared-local (" + (confined() ? "bwrap confinement" + (network(chatId) ? ", network" : ", no network")
                : "NOT confined: testing only") + ", minimal environment) | work dir: " + workDir(chatId)
                + " | run_js storage: host";
    }

    @Override
    public String shellDescription() {
        return shell.description() + " The environment is empty except PATH and LANG; HOME is your chat directory.";
    }

    @Override
    public void shell(LocalShell shell) {
        if (shell != null) this.shell = shell;
        super.shell(shell);
    }

    @Override
    public void outputLogLimit(long maxBytes) {
        this.logLimit = maxBytes;
        super.outputLogLimit(maxBytes);
    }

    /** The environment is fixed: the whitelist of the shell does not apply. */
    @Override
    public void environmentWhitelist(List<String> patterns) {
    }

    private boolean network(String chatId) {
        try {
            return Boolean.TRUE.equals(networkOf.apply(chatId));
        } catch (RuntimeException e) { // boundary: a failing lookup must never grant network
            return false;
        }
    }

    // ── commands ──────────────────────────────────────────────────────────────

    @Override
    public ExecutionResult execute(String chatId, String command, Duration timeout) {
        return run(chatId, workDir(chatId), scratchDir(chatId, "main"), null, command, timeout);
    }

    @Override
    public ExecutionResult execute(String chatId, Path runDir, Path readOnlyDir, String command, Duration timeout) {
        return run(chatId, runDir, runDir, readOnlyDir, command, timeout);
    }

    @Override
    public ExecutionResult execute(String chatId, Path scratchDir, boolean planMode, String command, Duration timeout) {
        if (planMode) return run(chatId, scratchDir, scratchDir, workDir(chatId), command, timeout);
        return run(chatId, workDir(chatId), scratchDir, null, command, timeout);
    }

    /**
     * The argv of a command: {@code [limits] bwrap ... shell -c command} on Linux, the shell line elsewhere.
     *
     * @param readOnly a directory the command sees but cannot change (plan mode: the work dir); null for none
     */
    List<String> argv(String chatId, Path runDir, Path scratch, Path readOnly, String command) {
        List<String> shellArgv = shell.argv(command);
        if (!confined()) {
            if (host.windows()) return shellArgv;
            return shell.argv("ulimit -t " + Math.max(1, limits.cpuSeconds()) + "; " + command); // macOS
        }
        Path own = workDir(chatId).toAbsolutePath().normalize();
        Path scr = scratch.toAbsolutePath().normalize();
        Path ro = readOnly == null ? null : readOnly.toAbsolutePath().normalize();
        Path run = runDir.toAbsolutePath().normalize();
        List<String> a = new ArrayList<>(limitPrefix);
        a.addAll(List.of(bwrap, "--die-with-parent", "--new-session", "--unshare-pid", "--unshare-ipc", "--unshare-uts"));
        boolean net = network(chatId);
        if (!net) a.add("--unshare-net");
        for (String d : SYSTEM_DIRS) bindSystem(a, d);
        for (String f : ETC) roBindTry(a, f);
        if (net) for (String f : ETC_NETWORK) roBindTry(a, f);
        a.addAll(List.of("--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp"));
        a.addAll(List.of(ro != null ? "--ro-bind" : "--bind", (ro != null ? ro : own).toString(),
                (ro != null ? ro : own).toString()));
        if (ro != null || !scr.startsWith(own)) a.addAll(List.of("--bind", scr.toString(), scr.toString()));
        a.addAll(List.of("--chdir", run.toString(), "--clearenv"));
        env(own, scr, true).forEach((k, v) -> a.addAll(List.of("--setenv", k, v)));
        a.addAll(shellArgv);
        return a;
    }

    private static void roBindTry(List<String> a, String path) {
        if (Files.exists(Path.of(path))) a.addAll(List.of("--ro-bind", path, path));
    }

    /** /bin, /lib... are symlinks into /usr on merged-usr systems: recreate the link, bind the real directories. */
    private static void bindSystem(List<String> a, String dir) {
        Path p = Path.of(dir);
        try {
            if (Files.isSymbolicLink(p)) {
                a.addAll(List.of("--symlink", Files.readSymbolicLink(p).toString(), dir));
            } else if (Files.isDirectory(p)) {
                a.addAll(List.of("--ro-bind", dir, dir));
            }
        } catch (IOException e) {
            // unreadable link: left out
        }
    }

    /** The whole environment of a command: PATH, LANG, HOME, TMPDIR (and what Windows needs); never a secret. */
    Map<String, String> env(Path chat, Path scratch, boolean inSandbox) {
        Map<String, String> src = host.env();
        Map<String, String> env = new LinkedHashMap<>();
        String path = src.get("PATH");
        if (path == null) path = src.get("Path");
        if (inSandbox) path = sandboxPath(path);
        if (path != null) env.put("PATH", path);
        String lang = src.get("LANG");
        env.put("LANG", lang == null || lang.isBlank() ? "C.UTF-8" : lang);
        env.put("HOME", chat.toString());
        env.put("TMPDIR", scratch.toString());
        if (host.windows()) {
            env.put("USERPROFILE", chat.toString());
            env.put("TEMP", scratch.toString());
            env.put("TMP", scratch.toString());
            for (String k : List.of("SystemRoot", "WINDIR", "COMSPEC", "PATHEXT")) {
                String v = src.get(k);
                if (v == null) v = src.get(k.toUpperCase(Locale.ROOT));
                if (v != null) env.put(k, v);
            }
        }
        env.entrySet().removeIf(e -> EnvWhitelist.isSecret(e.getKey(), e.getValue()));
        return env;
    }

    /** The PATH entries that exist in the sandbox (the system directories), or a default one. */
    private static String sandboxPath(String path) {
        if (path == null) return DEFAULT_PATH;
        List<String> keep = new ArrayList<>();
        for (String d : path.split(":")) {
            for (String root : SYSTEM_DIRS) {
                if (d.equals(root) || d.startsWith(root + "/")) {
                    keep.add(d);
                    break;
                }
            }
        }
        return keep.isEmpty() ? DEFAULT_PATH : String.join(":", keep);
    }

    private ExecutionResult run(String chatId, Path runDir, Path scratch, Path readOnly, String command, Duration timeout) {
        List<String> argv = argv(chatId, runDir, scratch, readOnly, command);
        ProcessBuilder pb = new ProcessBuilder(argv).directory(runDir.toFile()).redirectErrorStream(true);
        pb.environment().clear();
        if (confined()) {
            // bwrap sets the command's environment; the launcher keeps only what it needs to find bwrap and the limit tools
            Map<String, String> h = host.env();
            if (h.get("PATH") != null) pb.environment().put("PATH", h.get("PATH"));
            if (!limitPrefix.isEmpty()) {
                for (String k : List.of("XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS")) { // systemd-run --user
                    if (h.get(k) != null) pb.environment().put(k, h.get(k));
                }
            }
        } else {
            pb.environment().putAll(env(workDir(chatId).toAbsolutePath().normalize(),
                    scratch.toAbsolutePath().normalize(), false));
        }
        return ProcessRun.run(pb, timeout, logLimit);
    }
}
