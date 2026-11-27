package org.kendar.llm.harness.sandbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.util.EnvWhitelist;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SharedLocalSandboxTest {

    private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @TempDir
    Path base;

    /** A machine as the test describes it: no process is started. */
    static final class FakeHost implements SharedLocalSandbox.Host {
        boolean linux = true;
        boolean windows;
        Map<String, String> tools = new LinkedHashMap<>(Map.of("bwrap", "/usr/bin/bwrap"));
        int probeResult;
        Map<String, String> env = new LinkedHashMap<>();

        FakeHost() {
            env.put("PATH", "/usr/local/bin:/home/someone/bin:/usr/bin:/bin");
            env.put("LANG", "en_US.UTF-8");
            env.put("HOME", "/home/someone");
            env.put("OPENAI_API_KEY", "sk-secret");
            env.put("JLLM_ADMIN_PASSWORD", "pw");
            env.put("AWS_SECRET_ACCESS_KEY", "s");
            env.put("TERM", "xterm");
        }

        @Override
        public boolean linux() {
            return linux;
        }

        @Override
        public boolean windows() {
            return windows;
        }

        @Override
        public String which(String name) {
            return tools.get(name);
        }

        @Override
        public int probe(List<String> argv) {
            return probeResult;
        }

        @Override
        public Map<String, String> env() {
            return env;
        }
    }

    private SharedLocalSandbox sandbox(FakeHost host, java.util.function.Function<String, Boolean> net) {
        return new SharedLocalSandbox(new ChatDirLayout(base), net, null, host);
    }

    private static List<String> setenvs(List<String> argv) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < argv.size(); i++) if (argv.get(i).equals("--setenv")) out.add(argv.get(i + 1) + "=" + argv.get(i + 2));
        return out;
    }

    // ── bwrap command line (any OS: nothing is started) ───────────────────────

    @Test
    void theBwrapCommandLineConfinesTheChat() {
        SharedLocalSandbox box = sandbox(new FakeHost(), null);
        Path chat = box.workDir("c1").toAbsolutePath().normalize();
        Path scratch = box.scratchDir("c1", "main").toAbsolutePath().normalize();

        List<String> argv = box.argv("c1", chat, scratch, null, "echo hi");

        assertEquals("/usr/bin/bwrap", argv.get(argv.indexOf("/usr/bin/bwrap")));
        for (String flag : List.of("--die-with-parent", "--new-session", "--unshare-net", "--unshare-pid", "--clearenv")) {
            assertTrue(argv.contains(flag), flag + " in " + argv);
        }
        assertTrue(String.join(" ", argv).contains("--tmpfs /tmp"), "private /tmp");
        assertTrue(String.join(" ", argv).contains("--proc /proc"), "private /proc");
        assertTrue(String.join(" ", argv).contains("--bind " + chat + " " + chat), "the chat dir is read-write");
        assertFalse(argv.contains("/etc/passwd"), "no /etc/passwd");
        assertFalse(String.join(" ", argv).contains("--ro-bind /etc /etc"), "no whole /etc");
        assertFalse(String.join(" ", argv).contains("--bind " + base + " " + base), "not the base directory");
        assertEquals("echo hi", argv.get(argv.size() - 1));
    }

    @Test
    void theEnvironmentIsEmptyExceptPathLangHomeAndTmpdir() {
        SharedLocalSandbox box = sandbox(new FakeHost(), null);
        Path chat = box.workDir("c1").toAbsolutePath().normalize();
        Path scratch = box.scratchDir("c1", "main").toAbsolutePath().normalize();

        List<String> env = setenvs(box.argv("c1", chat, scratch, null, "env"));

        assertEquals(List.of("PATH=/usr/local/bin:/usr/bin:/bin", "LANG=en_US.UTF-8", "HOME=" + chat, "TMPDIR=" + scratch), env);
        assertTrue(env.stream().noneMatch(e -> EnvWhitelist.isSecret(e.substring(0, e.indexOf('=')))));
    }

    @Test
    void thePathKeepsOnlySystemDirectories() {
        FakeHost host = new FakeHost();
        host.env.put("PATH", "/home/someone/bin:/opt/x");
        SharedLocalSandbox box = sandbox(host, null);
        Path chat = box.workDir("c1");

        assertEquals("PATH=/usr/local/bin:/usr/bin:/bin",
                setenvs(box.argv("c1", chat, box.scratchDir("c1", "main"), null, "env")).get(0));
    }

    @Test
    void networkIsGrantedPerRole() {
        SharedLocalSandbox box = sandbox(new FakeHost(), c -> c.equals("online"));
        Path off = box.workDir("offline");
        Path on = box.workDir("online");

        assertTrue(box.argv("offline", off, box.scratchDir("offline", "main"), null, "x").contains("--unshare-net"));
        assertFalse(box.argv("online", on, box.scratchDir("online", "main"), null, "x").contains("--unshare-net"));
        assertTrue(box.sandboxStatus("online").contains("network"));
        assertTrue(box.sandboxStatus("offline").contains("no network"));
    }

    @Test
    void aFailingNetworkLookupDoesNotGrantNetwork() {
        SharedLocalSandbox box = sandbox(new FakeHost(), c -> {
            throw new IllegalStateException("db down");
        });

        assertTrue(box.argv("c", box.workDir("c"), box.scratchDir("c", "main"), null, "x").contains("--unshare-net"));
    }

    @Test
    void planModeSeesTheWorkDirReadOnly() {
        SharedLocalSandbox box = sandbox(new FakeHost(), null);
        Path work = box.workDir("c1").toAbsolutePath().normalize();
        Path scratch = box.scratchDir("c1", "plan").toAbsolutePath().normalize();

        String line = String.join(" ", box.argv("c1", scratch, scratch, work, "x"));

        assertTrue(line.contains("--ro-bind " + work + " " + work), line);
        assertFalse(line.contains("--bind " + work + " " + work), line);
        assertTrue(line.contains("--bind " + scratch + " " + scratch), line);
    }

    @Test
    void limitsUseSystemdRunAndPrlimitWhenPresent() {
        FakeHost host = new FakeHost();
        host.tools.put("systemd-run", "/usr/bin/systemd-run");
        host.tools.put("prlimit", "/usr/bin/prlimit");
        SharedLocalSandbox box = new SharedLocalSandbox(new ChatDirLayout(base), null,
                new SharedLocalSandbox.Limits(30, 512L * 1024 * 1024, 64), host);

        String line = String.join(" ", box.argv("c", box.workDir("c"), box.scratchDir("c", "main"), null, "x"));

        assertTrue(line.startsWith("/usr/bin/systemd-run --user --scope"), line);
        assertTrue(line.contains("MemoryMax=" + 512L * 1024 * 1024), line);
        assertTrue(line.contains("TasksMax=64"), line);
        assertTrue(line.contains("/usr/bin/prlimit --cpu=30 -- /usr/bin/bwrap"), line);
    }

    @Test
    void withoutSystemdPrlimitCarriesAllLimits() {
        FakeHost host = new FakeHost();
        host.tools.put("prlimit", "/usr/bin/prlimit");
        host.probeResult = 0;
        SharedLocalSandbox box = new SharedLocalSandbox(new ChatDirLayout(base), null,
                new SharedLocalSandbox.Limits(30, 1000, 64), host);

        String line = String.join(" ", box.argv("c", box.workDir("c"), box.scratchDir("c", "main"), null, "x"));

        assertTrue(line.startsWith("/usr/bin/prlimit --cpu=30 --as=1000 --nproc=64 -- /usr/bin/bwrap"), line);
    }

    // ── fail closed ───────────────────────────────────────────────────────────

    @Test
    void linuxWithoutBwrapRefusesToStart() {
        FakeHost host = new FakeHost();
        host.tools.clear();

        SandboxUnavailableException e = assertThrows(SandboxUnavailableException.class, () -> sandbox(host, null));

        assertTrue(e.getMessage().contains("bwrap"), e.getMessage());
    }

    @Test
    void linuxWithABrokenBwrapRefusesToStart() {
        FakeHost host = new FakeHost();
        host.probeResult = 1;

        SandboxUnavailableException e = assertThrows(SandboxUnavailableException.class, () -> sandbox(host, null));

        assertTrue(e.getMessage().contains("does not work"), e.getMessage());
    }

    @Test
    void windowsAndMacStartWithAWarningAndNoBwrap() {
        for (boolean windows : new boolean[]{true, false}) {
            FakeHost host = new FakeHost();
            host.linux = false;
            host.windows = windows;
            host.tools.clear();
            ListAppender<ILoggingEvent> logs = capture();
            try {
                SharedLocalSandbox box = sandbox(host, null);

                assertFalse(box.confined());
                assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("no confinement")), logs.list.toString());
                Path chat = box.workDir("c");
                Path scratch = box.scratchDir("c", "main");
                List<String> argv = box.argv("c", chat, scratch, null, "echo hi");
                assertFalse(argv.contains("bwrap"));
                assertTrue(argv.get(argv.size() - 1).endsWith("echo hi"));
                Map<String, String> env = box.env(chat.toAbsolutePath(), scratch.toAbsolutePath(), false);
                assertEquals(chat.toAbsolutePath().toString(), env.get("HOME"));
                assertEquals(scratch.toAbsolutePath().toString(), env.get("TMPDIR"));
                assertTrue(env.keySet().stream().noneMatch(EnvWhitelist::isSecret), env.toString());
            } finally {
                release(logs);
            }
        }
    }

    // ── real processes ────────────────────────────────────────────────────────

    /** The real sandbox of this machine; the test is skipped on Linux without a working bwrap. */
    private SharedLocalSandbox real() {
        try {
            return new SharedLocalSandbox(new ChatDirLayout(base), c -> false, null);
        } catch (SandboxUnavailableException e) {
            Assumptions.abort("no working bwrap here: " + e.getMessage());
            return null;
        }
    }

    @Test
    void windowsAndMacLogTheWarningAndTheSandboxRuns() {
        Assumptions.assumeFalse(LINUX, "Windows and macOS only");
        ListAppender<ILoggingEvent> logs = capture();
        try {
            SharedLocalSandbox box = new SharedLocalSandbox(new ChatDirLayout(base), c -> false, null);

            assertFalse(box.confined());
            assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("no confinement")), logs.list.toString());
            ExecutionResult r = box.execute("c1", "echo hello", Duration.ofSeconds(30));
            assertEquals(0, r.exitCode(), r.stdout() + r.stderr());
            assertTrue(r.stdout().contains("hello"));
        } finally {
            release(logs);
        }
    }

    @Test
    void envOfARealCommandHasNoSecretsAndHomeIsTheChatDir() {
        SharedLocalSandbox box = real();
        String list = WINDOWS ? "set" : "env";

        ExecutionResult r = box.execute("c1", list, Duration.ofSeconds(30));

        assertEquals(0, r.exitCode(), r.stdout() + r.stderr());
        List<String> names = r.stdout().lines().filter(l -> l.contains("=")).map(l -> l.substring(0, l.indexOf('='))).toList();
        assertTrue(names.stream().noneMatch(EnvWhitelist::isSecret), names.toString());
        String home = r.stdout().lines().filter(l -> l.startsWith("HOME=")).findFirst().orElseThrow();
        assertEquals(box.workDir("c1").toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT),
                home.substring(5).trim().toLowerCase(Locale.ROOT));
        if (LINUX) {
            java.util.Set<String> allowed = java.util.Set.of("PATH", "LANG", "HOME", "TMPDIR", "PWD", "SHLVL", "_", "OLDPWD");
            assertTrue(allowed.containsAll(names), names.toString());
        }
    }

    @Test
    void linuxWithoutBwrapRefusesToStartForReal() {
        Assumptions.assumeTrue(LINUX, "Linux only");
        Assumptions.assumeTrue(new FakeHostLookup().which("bwrap") == null, "bwrap is installed");

        assertThrows(SandboxUnavailableException.class, () -> new SharedLocalSandbox(new ChatDirLayout(base), null, null));
    }

    private static final class FakeHostLookup {
        String which(String name) {
            String path = System.getenv("PATH");
            if (path == null) return null;
            for (String d : path.split(java.io.File.pathSeparator)) {
                if (!d.isBlank() && Files.isExecutable(Path.of(d, name))) return d;
            }
            return null;
        }
    }

    @Test
    void linuxCommandsCannotReadSecretsOfTheServer() throws Exception {
        Assumptions.assumeTrue(LINUX, "Linux only");
        SharedLocalSandbox box = real();
        Files.createDirectories(base.resolve("webauth"));
        Files.writeString(base.resolve("webauth").resolve("users.json"), "SECRET-USERS");
        Files.writeString(base.resolve("jllm.db"), "SECRET-DB");
        Path chat = box.workDir("c1");
        Files.writeString(chat.resolve("mine.txt"), "MINE");

        assertNotEquals(0, box.execute("c1", "cat " + base.resolve("webauth").resolve("users.json"), Duration.ofSeconds(30)).exitCode());
        assertNotEquals(0, box.execute("c1", "cat " + base.resolve("jllm.db"), Duration.ofSeconds(30)).exitCode());
        assertNotEquals(0, box.execute("c1", "cat /etc/passwd", Duration.ofSeconds(30)).exitCode());
        assertNotEquals(0, box.execute("c1", "ls " + base.resolve("c2"), Duration.ofSeconds(30)).exitCode());
        ExecutionResult own = box.execute("c1", "cat mine.txt && echo more > new.txt && echo $HOME", Duration.ofSeconds(30));
        assertEquals(0, own.exitCode(), own.stdout());
        assertTrue(own.stdout().contains("MINE"));
        assertTrue(own.stdout().contains(chat.toAbsolutePath().normalize().toString()));
        assertEquals("more", Files.readString(chat.resolve("new.txt")).trim());
    }

    @Test
    void linuxHasNoNetworkWithoutTheGrant() {
        Assumptions.assumeTrue(LINUX, "Linux only");
        SharedLocalSandbox box = real();

        ExecutionResult r = box.execute("c1", "bash -c 'exec 3<>/dev/tcp/1.1.1.1/53' 2>&1; echo rc=$?", Duration.ofSeconds(30));

        assertFalse(r.stdout().contains("rc=0"), r.stdout());
    }

    @Test
    void linuxTmpIsPrivate() throws Exception {
        Assumptions.assumeTrue(LINUX, "Linux only");
        SharedLocalSandbox box = real();
        Path marker = Path.of("/tmp", "jllm-shared-local-" + System.nanoTime());
        Files.writeString(marker, "x");
        try {
            assertNotEquals(0, box.execute("c1", "cat " + marker, Duration.ofSeconds(30)).exitCode());
            assertEquals(0, box.execute("c1", "echo y > /tmp/inside && cat /tmp/inside", Duration.ofSeconds(30)).exitCode());
            assertFalse(Files.exists(Path.of("/tmp/inside")));
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    // ── log capture ───────────────────────────────────────────────────────────

    private static ListAppender<ILoggingEvent> capture() {
        ListAppender<ILoggingEvent> a = new ListAppender<>();
        a.start();
        ch.qos.logback.classic.Logger l = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SharedLocalSandbox.class);
        l.setLevel(Level.WARN); // the test configuration switches logging off
        l.addAppender(a);
        return a;
    }

    private static void release(ListAppender<ILoggingEvent> a) {
        ch.qos.logback.classic.Logger l = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SharedLocalSandbox.class);
        l.detachAppender(a);
        l.setLevel(null);
        a.stop();
    }

    @Test
    void forHostFailsTheStartOnLinuxWithoutBwrapWhenInUse() {
        FakeHost host = new FakeHost();
        host.tools.clear();
        var e = assertThrows(SandboxUnavailableException.class,
                () -> SharedLocalSandbox.forHost(new ChatDirLayout(base), null, true, host));
        assertTrue(e.getMessage().contains("bwrap") && e.getMessage().contains("Startup aborted"), e.getMessage());

        host.tools.put("bwrap", "/usr/bin/bwrap");
        host.probeResult = 1;
        assertThrows(SandboxUnavailableException.class,
                () -> SharedLocalSandbox.forHost(new ChatDirLayout(base), null, true, host));
    }

    @Test
    void forHostOnlyLogsWhenNotInUseAndStartsWhenBwrapWorks() {
        FakeHost host = new FakeHost();
        assertTrue(SharedLocalSandbox.forHost(new ChatDirLayout(base), null, true, host).isPresent());
        host.tools.clear();
        assertTrue(SharedLocalSandbox.forHost(new ChatDirLayout(base), null, false, host).isEmpty());
        host.linux = false;
        assertTrue(SharedLocalSandbox.forHost(new ChatDirLayout(base), null, true, host).isPresent());
    }
}
