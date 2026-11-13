package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.tools.Checkpoints;
import org.kendar.llm.harness.tools.FileSystemAgentTools;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real containers. The docker daemon is found automatically: the {@code docker} of this machine as configured
 * (DOCKER_HOST or context), else a daemon listening on {@code tcp://127.0.0.1:2375} (e.g. dockerd in WSL). Force a WSL
 * distribution with {@code -Djllm.it.wsl=Ubuntu}. If no daemon answers, the tests are skipped with a warning. Windows paths cannot be bind-mounted into WSL's docker, so this exercises remote docker mode: the
 * workspace is a volume, uploaded at creation and downloaded by the backup.
 */
class DockerWslIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DISTRO = System.getProperty("jllm.it.wsl");

    @TempDir
    Path tmp;
    HarnessConfig config;
    DockerCommandRunner wslDocker;
    final List<String> created = new ArrayList<>();

    /** Local TCP endpoint of a docker daemon (e.g. dockerd in WSL listening on localhost). */
    private static final String LOCAL_TCP = "tcp://127.0.0.1:2375";

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("jllm.home", tmp.resolve("home").toString());
        ProcessDockerCommandRunner real = new ProcessDockerCommandRunner();
        // candidates in order: an explicit -Djllm.it.wsl (a distro name, or "host"), then the docker of this machine
        // as configured (DOCKER_HOST / context), then a daemon listening on localhost:2375
        List<String> prefix = null; // command words put in front of "docker"
        List<String> inserted = List.of(); // words put right after "docker"
        String tried = "";
        List<List<List<String>>> candidates = new ArrayList<>();
        if (DISTRO != null && !DISTRO.isBlank() && !DISTRO.equals("host")) {
            candidates.add(List.of(List.of("wsl", "-d", DISTRO, "-e"), List.of()));
        } else {
            candidates.add(List.of(List.of(), List.of()));
            candidates.add(List.of(List.of(), List.of("-H", LOCAL_TCP)));
        }
        for (List<List<String>> c : candidates) {
            tried += " [" + String.join(" ", c.get(0)) + " docker " + String.join(" ", c.get(1)) + "]";
            try {
                List<String> probe = new ArrayList<>(c.get(0));
                probe.add("docker");
                probe.addAll(c.get(1));
                probe.addAll(List.of("version", "--format", "{{.Server.Version}}"));
                if (real.run(probe, Duration.ofSeconds(15)).exitCode() == 0) {
                    prefix = c.get(0);
                    inserted = c.get(1);
                    break;
                }
            } catch (IOException e) {
                // no such binary: next candidate
            }
        }
        if (prefix == null) {
            String why = "No docker daemon reachable (tried" + tried + "; also localhost:2375): skipping the real-container tests.";
            System.err.println("WARNING: " + why);
            Assumptions.abort(why);
        }
        final List<String> pre = prefix;
        final List<String> ins = inserted;
        wslDocker = (argv, timeout) -> {
            if (argv.size() > 1 && argv.get(1).equals("create")) creates.incrementAndGet();
            List<String> a = new ArrayList<>(pre);
            a.add(argv.get(0));
            a.addAll(ins);
            a.addAll(argv.subList(1, argv.size()));
            return real.run(a, timeout);
        };
        var d = HarnessConfig.defaults().sandbox().docker();
        config = HarnessConfig.defaults().withDocker(new HarnessConfig.Docker(d.binary(), d.image(), d.memory(), d.cpus(),
                d.pidsLimit(), d.user(), d.network(), "daemon", true, "127.0.0.1"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (wslDocker != null) {
            for (String n : created) wslDocker.run(List.of("docker", "rm", "-f", "-v", n), Duration.ofSeconds(60));
        }
        System.clearProperty("jllm.home");
    }

    private RemoteExecSandboxExecutor cli(Path project) {
        return new RemoteExecSandboxExecutor(config, wslDocker, null, new CwdSandboxExecutor(config, project),
                RemoteExecSandboxExecutor.Kind.CLI, project);
    }

    private static String run(RemoteExecSandboxExecutor box, String tool, String chat, String json) throws Exception {
        ToolContext c = new ToolContext(chat, box.workDir(chat), box.scratchDir(chat, "main"), false, null,
                PermissionMode.DEFAULT, null, box.files(chat, "main"));
        for (HarnessTool t : new FileSystemAgentTools(null, new Checkpoints()).tools()) {
            if (t.name().equals(tool)) return t.execute(c, JSON.readTree(json));
        }
        throw new IllegalArgumentException(tool);
    }

    private String ok(RemoteExecSandboxExecutor box, String command) {
        ExecutionResult r = box.execute("c1", command, Duration.ofSeconds(120));
        assertEquals(0, r.exitCode(), r.stdout() + r.stderr());
        return r.stdout();
    }

    /** The file jail of a script in a container, CLI layout: the agent scratch directory is the whole world. */
    private static String jailed(RemoteExecSandboxExecutor box, String tool, String chat, String json) throws Exception {
        ToolContext c = new ToolContext(chat, box.workDir(chat), box.scratchDir(chat, "main"), false, null,
                PermissionMode.DEFAULT, null, new ScratchJailBackend(box.files(chat, "main")));
        for (HarnessTool t : new FileSystemAgentTools(null, new Checkpoints()).tools()) {
            if (t.name().equals(tool)) return t.execute(c, JSON.readTree(json));
        }
        throw new IllegalArgumentException(tool);
    }

    @Test
    void aScriptFileJailInAContainerSeesOnlyTheAgentScratchDirectory() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve("secret.txt"), "project data");
        RemoteExecSandboxExecutor box = cli(project);
        created.add(RemoteExecSandboxExecutor.containerName(RemoteExecSandboxExecutor.Kind.CLI,
                RemoteExecSandboxExecutor.hash(project.toAbsolutePath().normalize().toString()).substring(0, 12)));
        assertTrue(ok(box, "cat secret.txt").contains("project data"));
        // another agent of the chat has a file of its own
        run(box, "write_file", "c1", "{\"path\":\"/workspace/.jllm/scratchpad/other/theirs.txt\",\"content\":\"x\"}");

        jailed(box, "write_file", "c1", "{\"path\":\"mine.txt\",\"content\":\"hello\\n\"}");

        assertTrue(jailed(box, "read_file", "c1", "{\"path\":\"mine.txt\"}").contains("hello"));
        assertEquals("hello", ok(box, "cat .jllm/scratchpad/main/mine.txt").strip(), "written in the scratch dir");
        for (String path : new String[]{"secret.txt", "../../../secret.txt", "/workspace/secret.txt",
                "../other/theirs.txt", "/workspace/.jllm/scratchpad/other/theirs.txt"}) {
            Exception e = assertThrows(Exception.class,
                    () -> jailed(box, "read_file", "c1", "{\"path\":\"" + path + "\"}"), path);
            assertNotNull(e.getMessage());
        }
        assertEquals("mine.txt", jailed(box, "glob", "c1", "{\"pattern\":\"**/*\"}"));
        assertThrows(Exception.class, () -> jailed(box, "glob", "c1", "{\"pattern\":\"../../*\"}"));
        assertThrows(Exception.class, () -> jailed(box, "grep", "c1", "{\"pattern\":\"data\",\"path\":\"..\"}"));
        assertTrue(jailed(box, "grep", "c1", "{\"pattern\":\"hell\"}").contains("mine.txt"));
    }

    @Test
    void realContainerEndToEnd() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve("hello.txt"), "from windows\n");
        Files.createDirectories(project.resolve("bin"));
        RemoteExecSandboxExecutor box = cli(project);
        String name = RemoteExecSandboxExecutor.containerName(RemoteExecSandboxExecutor.Kind.CLI,
                RemoteExecSandboxExecutor.hash(project.toAbsolutePath().normalize().toString()).substring(0, 12));
        created.add(name);

        // upload at creation, and a Linux shell
        assertTrue(ok(box, "cat hello.txt; uname -s; id -u").contains("from windows"));
        assertTrue(ok(box, "uname -s").startsWith("Linux"));
        assertNotEquals("0", ok(box, "id -u").strip(), "not root");

        // hardening
        assertNotEquals(0, box.execute("c1", "touch /etc/x", Duration.ofSeconds(30)).exitCode(), "read-only rootfs");
        assertEquals(0, box.execute("c1", "touch /tmp/ok && touch /workspace/ok", Duration.ofSeconds(30)).exitCode());
        String inspect = wslDocker.run(List.of("docker", "inspect", name), Duration.ofSeconds(60)).stdout();
        String token = JSON.readTree(inspect).get(0).path("Config").path("Env").toString()
                .replaceAll(".*JLLM_TOKEN=([0-9a-f]+).*", "$1");
        assertEquals(64, token.length());
        assertFalse(ok(box, "env").contains(token), "token hidden from commands");
        assertEquals("0", ok(box, "ls /agent 2>/dev/null | wc -l").strip(), "script not mounted when remote");

        // the token protects the published port
        String hostPort = wslDocker.run(List.of("docker", "port", name, "8080/tcp"), Duration.ofSeconds(60)).stdout()
                .lines().findFirst().orElseThrow().replaceAll(".*:", "");
        HttpClient http = HttpClient.newHttpClient();
        var anon = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + hostPort + "/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anon.statusCode());
        assertEquals("", anon.body());
        var authed = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + hostPort + "/health"))
                .header("X-Jllm-Token", token).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, authed.statusCode());

        // state persists between commands, shell state does not
        ok(box, "echo 42 > state.txt; export FOO=1; cd /tmp");
        assertEquals("42", ok(box, "cat state.txt; echo \"[$FOO]\"").lines().findFirst().orElse(""));
        assertEquals(1, countCreates(), "one container for every call");

        // file tools in the container
        run(box, "write_file", "c1", "{\"path\":\"src/App.java\",\"content\":\"class App {}\\n\"}");
        assertTrue(run(box, "read_file", "c1", "{\"path\":\"src/App.java\"}").contains("class App"));
        run(box, "edit_file", "c1", "{\"path\":\"src/App.java\",\"old_string\":\"App\",\"new_string\":\"Main\"}");
        assertEquals("src/App.java", run(box, "glob", "c1", "{\"pattern\":\"**/*.java\"}"));
        assertEquals("src/App.java:1:class Main {}", run(box, "grep", "c1", "{\"pattern\":\"Main\"}"));
        assertThrows(IllegalArgumentException.class, () -> run(box, "read_file", "c1", "{\"path\":\"../../etc/passwd\"}"));
        assertEquals("hello", ok(box, "printf hello").strip());

        // timeout kills the command, the container stays
        ExecutionResult slow = box.execute("c1", "sleep 60", Duration.ofSeconds(2));
        assertTrue(slow.timedOut());
        assertEquals("up", ok(box, "echo up").strip());

        // backup brings the files home (scratchpad is not copied)
        ok(box, "mkdir -p .jllm/scratchpad/main && echo s > .jllm/scratchpad/main/s.txt; printf '#!/bin/sh\\necho hi' > bin/run.sh; chmod +x bin/run.sh");
        String done = box.backup("c1");
        assertTrue(done.startsWith("Backed up"), done);
        assertEquals("42", Files.readString(project.resolve("state.txt")).strip());
        assertEquals("class Main {}", Files.readString(project.resolve("src/App.java")).strip());
        assertTrue(Files.exists(project.resolve("bin/run.sh")));
        assertFalse(Files.exists(project.resolve(".jllm/scratchpad/main/s.txt")));

        // reuse: a second executor finds the running container (no create) and sees the same files
        box.close();
        RemoteExecSandboxExecutor again = cli(project);
        assertEquals("42", ok(again, "cat state.txt").strip());
        assertEquals(1, countCreates(), "reused, not recreated");

        // /sandbox clean (backs up, removes) and --sandbox-clean
        assertTrue(again.clean("c1").contains("Removed sandbox container"));
        assertTrue(inspectMissing(name));
        ok(again, "true"); // next command makes a new one, uploading the project again
        assertEquals(2, countCreates());
        assertEquals(List.of(name), RemoteExecSandboxExecutor.cleanStartupDir(config, wslDocker, project));
        assertTrue(inspectMissing(name));
        assertEquals(List.of(), RemoteExecSandboxExecutor.cleanAllCli(config, wslDocker));
    }

    @Test
    void webChatSurvivesAContainerLossThroughItsBackup() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = new RemoteExecSandboxExecutor(config, wslDocker, null,
                new ChatDirSandboxExecutor(config, base), RemoteExecSandboxExecutor.Kind.WEB, base);
        created.add("jllm-web-chat-1");
        assertEquals(0, box.execute("chat-1", "echo precious > data.txt", Duration.ofSeconds(120)).exitCode());
        assertTrue(box.backup("chat-1").startsWith("Backed up"));
        wslDocker.run(List.of("docker", "rm", "-f", "-v", "jllm-web-chat-1"), Duration.ofSeconds(60));
        RemoteExecSandboxExecutor next = new RemoteExecSandboxExecutor(config, wslDocker, null,
                new ChatDirSandboxExecutor(config, base), RemoteExecSandboxExecutor.Kind.WEB, base);
        assertEquals("precious", next.execute("chat-1", "cat data.txt", Duration.ofSeconds(120)).stdout().strip());
        assertEquals(List.of("jllm-web-chat-1"), next.sweepOrphans(id -> false));
        assertTrue(inspectMissing("jllm-web-chat-1"));
    }

    private boolean inspectMissing(String name) throws IOException, InterruptedException {
        return wslDocker.run(List.of("docker", "inspect", name), Duration.ofSeconds(60)).exitCode() != 0;
    }

    private int countCreates() {
        return creates.get();
    }

    private final java.util.concurrent.atomic.AtomicInteger creates = new java.util.concurrent.atomic.AtomicInteger();
}
