package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.config;

class DockerSandboxExecutorTest {

    @TempDir
    Path base;

    HarnessConfig p;
    final List<List<String>> calls = new ArrayList<>();
    BiFunction<List<String>, Duration, DockerCommandRunner.Result> handler;
    DockerSandboxExecutor ex;

    static DockerCommandRunner.Result ok(String out) {
        return new DockerCommandRunner.Result(out, "", 0, false);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneaky(Throwable t) throws T {
        throw (T) t;
    }

    @BeforeEach
    void setUp() {
        p = config(base);
        handler = (argv, t) -> ok("");
        ex = executor(null);
    }

    private DockerSandboxExecutor executor(String dockerHost) {
        return new DockerSandboxExecutor(p, (argv, t) -> {
            calls.add(argv);
            return handler.apply(argv, t);
        }, dockerHost);
    }

    @Test
    void remoteDaemonFallsBackToLocalDirWhenAllowed() {
        p = p.withLocalDirSandboxAllowed(true);
        ex = executor(null);
        DockerSandboxExecutor remote = executor("tcp://192.168.1.45:2375");
        assertTrue(remote.remoteDaemon());
        ExecutionResult r = remote.execute("fb1", "echo hi", Duration.ofSeconds(20));
        assertEquals(0, r.exitCode(), r.stderr());
        assertTrue(r.stdout().contains("hi"));
        assertTrue(calls.isEmpty(), "docker must not be invoked for a remote daemon");
    }

    @Test
    void remoteDaemonWithoutFallbackReportsHowToEnableIt() {
        p = p.withLocalDirSandboxAllowed(false);
        ex = executor(null);
        ExecutionResult r = executor("tcp://192.168.1.45:2375").execute("fb2", "echo hi", Duration.ofSeconds(5));
        assertEquals(127, r.exitCode());
        assertTrue(r.stderr().contains("remote daemon") && r.stderr().contains("localDirSandboxAllowed"), r.stderr());
        assertTrue(calls.isEmpty());
    }

    @Test
    void localDaemonHostsAreNotRemote() {
        for (String h : new String[]{null, "", "unix:///var/run/docker.sock", "npipe:////./pipe/docker_engine",
                "tcp://localhost:2375", "tcp://127.0.0.1:2375"}) {
            assertFalse(executor(h).remoteDaemon(), String.valueOf(h));
        }
    }

    @Test
    void dockerDownFallsBackToLocalDirWhenAllowed() {
        p = p.withLocalDirSandboxAllowed(true);
        ex = executor(null);
        handler = (argv, t) -> new DockerCommandRunner.Result("", "Cannot connect to the Docker daemon", 1, false);
        ExecutionResult r = ex.execute("fb3", "echo hi", Duration.ofSeconds(20));
        assertEquals(0, r.exitCode(), r.stderr());
        assertTrue(r.stdout().contains("hi"));
    }

    @Test
    void exactRunArgv() {
        ex.execute("chat1", "echo hi", Duration.ofSeconds(5));
        assertEquals(List.of("docker", "version", "--format", "{{.Server.Version}}"), calls.get(0));
        List<String> run = calls.get(1);
        String name = run.get(4);
        assertTrue(name.matches("harness-chat1-[0-9a-f-]{36}"), name);
        Path dir = base.toAbsolutePath().normalize().resolve("chat1");
        assertEquals(List.of("docker", "run", "--rm", "--name", name,
                "--network", "none", "--memory", "512m", "--cpus", "1.0", "--pids-limit", "256",
                "--read-only", "--tmpfs", "/tmp:rw,size=64m",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--user", "1000:1000", "-e", "HOME=/tmp",
                "-v", DockerSandboxExecutor.mountSource(dir) + ":/workspace", "-w", "/workspace",
                "python:3.12-slim", "sh", "-c", "echo hi"), run);
        assertTrue(java.nio.file.Files.isDirectory(dir));
    }

    @Test
    void mapsStdoutStderrExit() {
        handler = (argv, t) -> argv.contains("run") ? new DockerCommandRunner.Result("o", "e", 3, false) : ok("");
        assertEquals(new ExecutionResult("o", "e", 3, false), ex.execute("c", "x", Duration.ofSeconds(1)));
    }

    @Test
    void timeoutKillsNamedContainer() {
        handler = (argv, t) -> argv.contains("run") ? new DockerCommandRunner.Result("partial", "", -1, true) : ok("");
        ExecutionResult r = ex.execute("c", "sleep 99", Duration.ofSeconds(1));
        assertTrue(r.timedOut());
        assertEquals(-1, r.exitCode());
        assertEquals("partial", r.stdout());
        assertEquals(List.of("docker", "kill", calls.get(1).get(4)), calls.get(2));
    }

    @Test
    void interruptKillsContainerAndKeepsFlag() {
        handler = (argv, t) -> {
            if (argv.contains("run")) sneaky(new InterruptedException());
            return ok("");
        };
        ExecutionResult r = ex.execute("c", "sleep 99", Duration.ofSeconds(1));
        boolean flagged = Thread.interrupted();
        assertTrue(flagged);
        assertEquals("Interrupted", r.stderr());
        assertEquals(List.of("docker", "kill", calls.get(1).get(4)), calls.get(2));
    }

    @Test
    void missingBinaryFailsClearlyAtFirstUseAndRetries() {
        handler = (argv, t) -> {
            sneaky(new IOException("Cannot run program \"docker\""));
            return null;
        };
        ExecutionResult r = ex.execute("c", "x", Duration.ofSeconds(1));
        assertEquals(127, r.exitCode());
        assertTrue(r.stderr().contains("docker binary 'docker' not found"), r.stderr());
        assertEquals(1, calls.size());
        handler = (argv, t) -> ok("");
        assertEquals(0, ex.execute("c", "x", Duration.ofSeconds(1)).exitCode());
    }

    @Test
    void daemonDownIsReported() {
        handler = (argv, t) -> new DockerCommandRunner.Result("", "Cannot connect to the Docker daemon", 1, false);
        ExecutionResult r = ex.execute("c", "x", Duration.ofSeconds(1));
        assertEquals(127, r.exitCode());
        assertTrue(r.stderr().contains("Cannot connect"));
    }

    @Test
    void runnerFailureMapped() {
        handler = (argv, t) -> {
            if (argv.contains("run")) sneaky(new IOException("boom"));
            return ok("");
        };
        ExecutionResult r = ex.execute("c", "x", Duration.ofSeconds(1));
        assertEquals(-1, r.exitCode());
        assertTrue(r.stderr().contains("boom"));
    }

    @Test
    void rejectsBadChatIds() {
        for (String bad : new String[]{"../x", "a/b", "..", "", null, "a b"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ex.execute(bad, "x", Duration.ofSeconds(1)), String.valueOf(bad));
        }
        assertTrue(calls.isEmpty());
    }

    /** Standard ways to reach a local daemon, after DOCKER_HOST: default socket/pipe, then loopback tcp. */
    private static final List<String> STANDARD_HOSTS = List.of(
            "", "unix:///var/run/docker.sock", "npipe:////./pipe/docker_engine",
            "tcp://localhost:2375", "tcp://127.0.0.1:2375");

    private static boolean reachable(ProcessDockerCommandRunner real, String host) {
        List<String> argv = host.isEmpty() ? List.of("docker", "version")
                : List.of("docker", "-H", host, "version");
        try {
            return real.run(argv, Duration.ofSeconds(10)).exitCode() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void realDockerIntegration() throws Exception {
        ProcessDockerCommandRunner real = new ProcessDockerCommandRunner();
        String envHost = System.getenv("DOCKER_HOST");
        List<String> candidates = new java.util.ArrayList<>();
        if (envHost != null && !envHost.isBlank()) candidates.add(envHost);
        for (String h : STANDARD_HOSTS) {
            if (h.isEmpty() && envHost != null && !envHost.isBlank()) continue; // default == DOCKER_HOST then
            candidates.add(h);
        }
        String found = null;
        for (String h : candidates) {
            if (reachable(real, h) && !DockerSandboxExecutor.isRemote(h.isEmpty() ? null : h)) {
                found = h;
                break;
            }
        }
        if (found == null) System.err.println("WARNING: docker not available: skipping realDockerIntegration: tried "
                + candidates.stream().map(h -> h.isEmpty() ? "<default>" : h).toList()
                + " (needs a local docker daemon that can bind-mount this machine's directories).");
        Assumptions.assumeTrue(found != null, "docker not available");
        final String host = found;
        DockerCommandRunner pinned = (argv, t) -> {
            if (host.isEmpty() || argv.isEmpty() || !"docker".equals(argv.get(0))) return real.run(argv, t);
            List<String> a = new java.util.ArrayList<>(argv);
            a.addAll(1, List.of("-H", host));
            return real.run(a, t);
        };
        DockerSandboxExecutor d = new DockerSandboxExecutor(p, pinned, host.isEmpty() ? null : host);
        Assumptions.assumeFalse(d.remoteDaemon(), "remote docker daemon cannot bind-mount the local work dir");
        boolean winTcp = System.getProperty("os.name", "").toLowerCase().contains("win")
                && host.startsWith("tcp:");
        if (winTcp) System.err.println("WARNING: skipping realDockerIntegration: a Windows client with a tcp daemon "
                + "(e.g. the docker in WSL) cannot bind-mount Windows paths.");
        Assumptions.assumeFalse(winTcp, "a Windows client with a tcp daemon cannot bind-mount Windows paths");
        ExecutionResult r = d.execute("it1", "echo hi > f.txt && cat f.txt", Duration.ofSeconds(120));
        assertEquals(0, r.exitCode(), r.stderr());
        assertTrue(r.stdout().contains("hi"));
    }
}
