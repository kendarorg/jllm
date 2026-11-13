package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.tools.FileSystemAgentTools;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link RemoteExecSandboxExecutor} against the real {@code sandbox-agent.py} (needs python and sh on the
 * PATH, otherwise skipped). A fake docker CLI answers the container commands and starts the daemon on {@code start}.
 */
class RemoteExecSandboxExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;
    FakeDocker docker;
    HarnessConfig config = HarnessConfig.defaults().withDockerMode("daemon");

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(has("python", "--version") || has("python3", "--version"), "python needed");
        Assumptions.assumeTrue(has("sh", "-c", "true"), "sh needed");
        System.setProperty("jllm.home", tmp.resolve("home").toString());
        docker = new FakeDocker(tmp.resolve("ws"));
    }

    @AfterEach
    void tearDown() {
        if (docker != null) docker.stopDaemon();
        System.clearProperty("jllm.home");
    }

    private static boolean has(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private RemoteExecSandboxExecutor cli(Path project) {
        return new RemoteExecSandboxExecutor(config, docker, null,
                new CwdSandboxExecutor(config, project), RemoteExecSandboxExecutor.Kind.CLI, project);
    }

    private RemoteExecSandboxExecutor web(Path base) {
        return new RemoteExecSandboxExecutor(config, docker, null,
                new ChatDirSandboxExecutor(config, base), RemoteExecSandboxExecutor.Kind.WEB, base);
    }

    private static ToolContext ctx(RemoteExecSandboxExecutor box, String chat, boolean plan) {
        return new ToolContext(chat, box.workDir(chat), box.scratchDir(chat, "main"), plan, null,
                org.kendar.llm.harness.permission.PermissionMode.DEFAULT, null, box.files(chat, "main"));
    }

    private static String run(String tool, ToolContext ctx, String json) throws Exception {
        for (HarnessTool t : new FileSystemAgentTools().tools()) {
            if (t.name().equals(tool)) return t.execute(ctx, JSON.readTree(json));
        }
        throw new IllegalArgumentException(tool);
    }

    @Test
    void cliCommandsRunInOneContainerAndStatePersists() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        RemoteExecSandboxExecutor box = cli(project);
        ExecutionResult r = box.execute("c1", "echo hi > a.txt; echo done", Duration.ofSeconds(30));
        assertEquals(0, r.exitCode(), r.stderr());
        assertTrue(r.stdout().contains("done"));
        assertEquals("hi", new String(box.files("c1", "main").read(
                box.files("c1", "main").readTarget("a.txt"), 0, -1)).strip());
        box.execute("c1", "sleep 0", Duration.ofSeconds(30));
        assertEquals(1, docker.count("create"), "one container for all commands");
    }

    @Test
    void containerIsCreatedWithTheSecurityFlagsAndATokenOnlyInTheEnvironment() throws Exception {
        RemoteExecSandboxExecutor box = cli(Files.createDirectories(tmp.resolve("project")));
        box.execute("c1", "true", Duration.ofSeconds(30));
        String create = String.join(" ", docker.first("create"));
        for (String flag : List.of("--read-only", "--cap-drop ALL", "no-new-privileges", "-p 127.0.0.1::8080",
                "--label jllm=1", "--label jllm.kind=cli", "-e JLLM_TOKEN=", ":/agent:ro", ":/workspace")) {
            assertTrue(create.contains(flag), flag + " in " + create);
        }
        assertFalse(create.contains("--network"), "docker's default network");
        assertEquals(64, docker.token.length());
        ExecutionResult env = box.execute("c1", "env", Duration.ofSeconds(30));
        assertFalse(env.stdout().contains(docker.token));
    }

    @Test
    void cliContainerIsReusedAndRecreatedWhenTheConfigChanges() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        cli(project).execute("c1", "true", Duration.ofSeconds(30));
        docker.stopDaemonKeepContainer();
        cli(project).execute("c1", "true", Duration.ofSeconds(30)); // reuse: started again, not created
        assertEquals(1, docker.count("create"));
        List<String> warnings = new ArrayList<>();
        config = config.withDockerImage("other:image");
        cli(project).onWarning(warnings::add).execute("c1", "true", Duration.ofSeconds(30));
        assertEquals(2, docker.count("create"));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("other settings"));
    }

    @Test
    void cleanRemovesTheCliContainerOnly() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        RemoteExecSandboxExecutor box = cli(project);
        box.execute("c1", "true", Duration.ofSeconds(30));
        assertTrue(box.clean("c1").startsWith("Removed sandbox container jllm-cli-"));
        assertTrue(docker.count("rm") >= 1);
        assertTrue(box.clean("c1").startsWith("No sandbox container"));
        assertTrue(web(tmp.resolve("x")).clean("c1").startsWith("Not available"));
    }

    @Test
    void webChatsGetOwnContainersOnAVolumeAndDeleteRemovesThem() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base);
        box.execute("chat-1", "true", Duration.ofSeconds(30));
        String create = String.join(" ", docker.first("create"));
        assertTrue(create.contains("--name jllm-web-chat-1"), create);
        assertTrue(create.contains("-v /workspace"), create);
        assertTrue(create.contains("--label jllm.owner=chat-1"), create);
        assertEquals(1, docker.count("run"), "volume handed to the container user");
        box.deleteWorkDir("chat-1");
        assertEquals(List.of("rm", "-f", "-v", "jllm-web-chat-1"), docker.last("rm").subList(0, 4));
        assertFalse(Files.exists(base.resolve("chat-1")));
    }

    @Test
    void sweepRemovesOnlyOrphanedWebContainers() throws Exception {
        RemoteExecSandboxExecutor box = web(Files.createDirectories(tmp.resolve("web")));
        docker.listing = "jllm-web-live jllm=1,jllm.kind=web,jllm.owner=live,jllm.config=abc\n"
                + "jllm-web-gone jllm=1,jllm.kind=web,jllm.owner=gone\n";
        List<String> removed = box.sweepOrphans("live"::equals);
        assertEquals(List.of("jllm-web-gone"), removed);
        assertTrue(cli(Files.createDirectories(tmp.resolve("p"))).sweepOrphans(c -> false).isEmpty());
    }

    @Test
    void fileToolsRunThroughTheDaemon() throws Exception {
        RemoteExecSandboxExecutor box = cli(Files.createDirectories(tmp.resolve("project")));
        ToolContext c = ctx(box, "c1", false);
        assertTrue(run("write_file", c, "{\"path\":\"src/A.java\",\"content\":\"one\\ntwo\\nthree\\n\"}")
                .startsWith("Wrote"));
        assertTrue(run("read_file", c, "{\"path\":\"src/A.java\"}").contains("2\ttwo"));
        run("write_file", c, "{\"path\":\"src/A.java\",\"content\":\"four\\n\",\"append\":true}");
        run("edit_file", c, "{\"path\":\"src/A.java\",\"old_string\":\"two\",\"new_string\":\"2\"}");
        String read = run("read_file", c, "{\"path\":\"src/A.java\",\"offset\":2,\"limit\":2}");
        assertTrue(read.contains("2\t2") && read.contains("3\tthree"), read);
        assertEquals("src/A.java", run("glob", c, "{\"pattern\":\"**/*.java\"}"));
        assertEquals("src/A.java:4:four", run("grep", c, "{\"pattern\":\"four\"}"));
        assertThrows(IllegalArgumentException.class, () -> run("read_file", c, "{\"path\":\"../x\"}"));
    }

    @Test
    void planModeWritesGoToTheScratchDirectory() throws Exception {
        RemoteExecSandboxExecutor box = cli(Files.createDirectories(tmp.resolve("project")));
        ToolContext plan = ctx(box, "c1", true);
        run("write_file", plan, "{\"path\":\"plan.md\",\"content\":\"p\"}");
        String scratch = RemoteExecSandboxExecutor.SCRATCHPAD + "/main/plan.md";
        assertEquals("1\tp\n", run("read_file", plan, "{\"path\":\"" + scratch + "\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> run("write_file", plan, "{\"path\":\"/workspace/real.txt\",\"content\":\"x\"}"));
        ExecutionResult r = box.execute("c1", box.scratchDir("c1", "main"), true, "pwd", Duration.ofSeconds(30));
        assertTrue(r.stdout().strip().endsWith("scratchpad" + java.io.File.separator + "main")
                || r.stdout().strip().endsWith("scratchpad/main"), r.stdout());
    }

    @Test
    void displayPathUsesContainerPaths() {
        RemoteExecSandboxExecutor box = cli(tmp);
        assertEquals("/workspace", box.displayPath(box.workDir("c")));
        assertEquals("/workspace/.jllm/scratchpad/main", box.displayPath(box.scratchDir("c", "main")));
        assertThrows(IllegalArgumentException.class, () -> box.scratchDir("c", ".."));
    }

    @Test
    void dockerMissingThrowsSoTheAgentLoopCanAskTheUser() {
        docker.versionFails = true;
        RemoteExecSandboxExecutor box = cli(tmp);
        SandboxUnavailableException e = assertThrows(SandboxUnavailableException.class,
                () -> box.execute("c1", "true", Duration.ofSeconds(5)));
        assertTrue(e.getMessage().contains("Docker sandbox unavailable"), e.getMessage());
        assertThrows(SandboxUnavailableException.class, () -> box.files("c1", "main").stat(
                new FileBackend.Target(FileBackend.Root.WORKSPACE, "x")));
    }

    @Test
    void afterTheUserAgreesTheChatRunsOnTheHost() throws Exception {
        docker.versionFails = true;
        RemoteExecSandboxExecutor box = cli(Files.createDirectories(tmp.resolve("project")));
        assertFalse(box.localFallbackActive("c1"));
        assertTrue(box.allowLocalFallback("c1"));
        assertNull(box.files("c1", "main"), "the file tools use the host");
        assertEquals(tmp.resolve("project").toAbsolutePath().normalize(), box.workDir("c1"));
        assertEquals(0, box.execute("c1", "echo local", Duration.ofSeconds(30)).exitCode());
        assertFalse(box.localFallbackActive("other-chat"));
        assertEquals(0, docker.count("create"));
    }

    @Test
    void fallbackIsRefusedWhenTheConfigForbidsIt() {
        config = config.withSandbox(new HarnessConfig.Sandbox(config.sandbox().baseDir(), 120, false,
                config.sandbox().docker()));
        assertFalse(cli(tmp).allowLocalFallback("c1"));
    }

    @Test
    void remoteDockerHostIsRefused() {
        RemoteExecSandboxExecutor box = new RemoteExecSandboxExecutor(config, docker, "tcp://10.1.2.3:2375",
                new CwdSandboxExecutor(config, tmp), RemoteExecSandboxExecutor.Kind.CLI, tmp);
        SandboxUnavailableException e = assertThrows(SandboxUnavailableException.class,
                () -> box.execute("c1", "true", Duration.ofSeconds(5)));
        assertTrue(e.getMessage().contains("remote"), e.getMessage());
    }

    @Test
    void checkpointsAndUndoWorkInsideTheContainer() throws Exception {
        RemoteExecSandboxExecutor box = cli(Files.createDirectories(tmp.resolve("project")));
        org.kendar.llm.harness.tools.Checkpoints cp = new org.kendar.llm.harness.tools.Checkpoints();
        FileSystemAgentTools fs = new FileSystemAgentTools(null, cp);
        ToolContext c = ctx(box, "c1", false);
        box.execute("c1", "printf 'orig' > keep.txt", Duration.ofSeconds(30));
        cp.beginTurn("c1", c.files());
        for (HarnessTool t : fs.tools()) {
            if (t.name().equals("write_file")) {
                t.execute(c, JSON.readTree("{\"path\":\"keep.txt\",\"content\":\"changed\"}"));
                t.execute(c, JSON.readTree("{\"path\":\"new.txt\",\"content\":\"fresh\"}"));
            }
        }
        assertEquals("changed", run("read_file", c, "{\"path\":\"keep.txt\"}").split("	")[1].strip());
        var r = cp.undo("c1", c.files(), 1);
        assertEquals(1, r.turns());
        assertEquals(List.of("keep.txt"), r.restored());
        assertEquals(List.of("new.txt"), r.deleted());
        assertEquals("orig", run("read_file", c, "{\"path\":\"keep.txt\"}").split("	")[1].strip());
        assertEquals("", run("glob", c, "{\"pattern\":\"new.txt\"}"));
    }

    private void remoteConfig() {
        var d = config.sandbox().docker();
        config = config.withDocker(new HarnessConfig.Docker(d.binary(), d.image(), d.memory(), d.cpus(), d.pidsLimit(),
                d.user(), d.network(), "daemon", true, "127.0.0.1"));
    }

    @Test
    void remoteCliUploadsTheProjectAndBacksItUp() throws Exception {
        remoteConfig();
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve("file.txt"), "local\n");
        Files.createDirectories(project.resolve(".jllm/scratchpad/main"));
        Files.writeString(project.resolve(".jllm/scratchpad/main/tmp.txt"), "scratch");
        RemoteExecSandboxExecutor box = new RemoteExecSandboxExecutor(config, docker, "tcp://10.9.9.9:2375",
                new CwdSandboxExecutor(config, project), RemoteExecSandboxExecutor.Kind.CLI, project);
        ExecutionResult r = box.execute("c1", "cat file.txt; ls .jllm/scratchpad 2>&1 | head -1", Duration.ofSeconds(30));
        assertTrue(r.stdout().startsWith("local"), r.stdout());
        assertFalse(r.stdout().contains("tmp.txt"), "scratchpad is not uploaded");
        String create = String.join(" ", docker.first("create"));
        assertTrue(create.contains("-e JLLM_AGENT_B64="), "daemon script travels in the environment");
        assertTrue(create.contains("0.0.0.0::8080"), create);
        assertTrue(create.contains(" -v /workspace "), "volume, not a bind mount: " + create);
        assertFalse(create.contains(project.toString().replace('\\', '/')), "project not mounted: " + create);
        assertFalse(create.contains(":/agent"), create);

        box.execute("c1", "echo remote >> file.txt; echo new > added.txt", Duration.ofSeconds(30));
        assertTrue(box.backup("c1").startsWith("Backed up"));
        assertEquals("local\nremote\n", Files.readString(project.resolve("file.txt")).replace("\r\n", "\n"));
        assertEquals("new", Files.readString(project.resolve("added.txt")).strip());
        assertEquals("scratch", Files.readString(project.resolve(".jllm/scratchpad/main/tmp.txt")));
    }

    private RemoteExecSandboxExecutor remoteCli(Path project) {
        return new RemoteExecSandboxExecutor(config, docker, "tcp://10.9.9.9:2375",
                new CwdSandboxExecutor(config, project), RemoteExecSandboxExecutor.Kind.CLI, project);
    }

    @Test
    void remoteCleanKeepsTheContainerWhenTheBackupFails() throws Exception {
        remoteConfig();
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path other = Files.createDirectories(tmp.resolve("other-repo"));
        RemoteExecSandboxExecutor box = remoteCli(project);
        box.execute("c1", "mkdir shared && echo x > shared/f.txt", Duration.ofSeconds(30));
        // the host has "shared" as a link: unpacking would write into other-repo, so the backup fails
        boolean linked;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", project.resolve("shared").toString(),
                    other.toString()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            linked = p.waitFor() == 0;
        } else {
            try {
                Files.createSymbolicLink(project.resolve("shared"), other);
                linked = true;
            } catch (IOException | UnsupportedOperationException e) {
                linked = false;
            }
        }
        Assumptions.assumeTrue(linked, "cannot create a link");
        try {
            long removed = docker.count("rm");
            String out = box.clean("c1");
            assertTrue(out.startsWith("Backup failed") && out.contains("was kept") && out.contains(" cp "), out);
            assertEquals(removed, docker.count("rm"), "container kept");
            assertFalse(Files.exists(other.resolve("f.txt")));
        } finally {
            Files.delete(project.resolve("shared"));
        }
        assertTrue(box.clean("c1").contains("Removed sandbox container"), "backup works again once fixed");
        assertEquals("x", Files.readString(project.resolve("shared/f.txt")).strip());
    }

    @Test
    void remoteCleanInANewSessionBacksUpTheExistingContainerFirst() throws Exception {
        remoteConfig();
        Path project = Files.createDirectories(tmp.resolve("project"));
        remoteCli(project).execute("c1", "echo made > made.txt", Duration.ofSeconds(30));
        String out = remoteCli(project).clean("c1"); // not connected yet: attaches to the container
        assertTrue(out.startsWith("Backed up") && out.contains("Removed sandbox container"), out);
        assertEquals("made", Files.readString(project.resolve("made.txt")).strip());
    }

    @Test
    void remoteWebKeepsABackupAndRestoresItInTheNextContainer() throws Exception {
        remoteConfig();
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base);
        box.execute("chat-1", "echo kept > keep.txt", Duration.ofSeconds(30));
        box.close(); // backs up, then removes the container
        assertTrue(Files.isRegularFile(base.resolve("chat-1/.jllm/workspace-backup.zip")));
        assertTrue(docker.count("rm") >= 1);
        docker.stopDaemon();
        ExecutionResult r = web(base).execute("chat-1", "cat keep.txt", Duration.ofSeconds(30));
        assertEquals("kept", r.stdout().strip());
        assertEquals(2, docker.count("create"));
    }

    @Test
    void remoteDockerNeedsTheSetting() {
        RemoteExecSandboxExecutor box = new RemoteExecSandboxExecutor(config, docker, "tcp://10.1.2.3:2375",
                new CwdSandboxExecutor(config, tmp), RemoteExecSandboxExecutor.Kind.CLI, tmp);
        SandboxUnavailableException e = assertThrows(SandboxUnavailableException.class,
                () -> box.execute("c1", "true", Duration.ofSeconds(5)));
        assertTrue(e.getMessage().contains("--docker-remote"), e.getMessage());
        assertNull(box.backup("c1"), "no backup unless remote is allowed");
    }

    @Test
    void workspaceArchiveSkipsPrivateDataAndRefusesTraversal() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Files.createDirectories(src.resolve("a/b"));
        Files.writeString(src.resolve("a/b/f.txt"), "f");
        Files.createDirectories(src.resolve(".jllm/history"));
        Files.writeString(src.resolve(".jllm/history/h"), "h");
        Files.createDirectories(src.resolve(".jllm/scratchpad"));
        Files.writeString(src.resolve(".jllm/scratchpad/s"), "s");
        Files.createDirectories(src.resolve(".jllm/checkpoints/c"));
        Files.writeString(src.resolve(".jllm/checkpoints/c/k"), "k");
        byte[] zip = WorkspaceArchive.pack(src);
        Path dst = tmp.resolve("dst");
        assertEquals(2, WorkspaceArchive.unpack(zip, dst));
        assertTrue(Files.exists(dst.resolve("a/b/f.txt")));
        assertTrue(Files.exists(dst.resolve(".jllm/checkpoints/c/k")));
        assertFalse(Files.exists(dst.resolve(".jllm/history")));
        assertFalse(Files.exists(dst.resolve(".jllm/scratchpad")));
        assertTrue(WorkspaceArchive.hasContent(src));
        assertFalse(WorkspaceArchive.hasContent(Files.createDirectories(tmp.resolve("empty"))));

        var bad = new java.io.ByteArrayOutputStream();
        try (var z = new java.util.zip.ZipOutputStream(bad)) {
            z.putNextEntry(new java.util.zip.ZipEntry("../evil.txt"));
            z.write(1);
            z.closeEntry();
        }
        assertThrows(IOException.class, () -> WorkspaceArchive.unpack(bad.toByteArray(), dst));
    }

    /** Fake docker CLI: remembers the calls and starts the real daemon on {@code start}. */
    static final class FakeDocker implements DockerCommandRunner {
        final Path workspace;
        final List<List<String>> calls = new CopyOnWriteArrayList<>();
        volatile boolean versionFails;
        volatile String listing = "";
        String token;
        String labels = "";
        String env = "";
        boolean exists;
        int port;
        Process daemon;
        String agentDir;

        FakeDocker(Path workspace) throws IOException {
            this.workspace = Files.createDirectories(workspace);
        }

        long count(String cmd) {
            return calls.stream().filter(c -> c.size() > 1 && c.get(1).equals(cmd)).count();
        }

        List<String> first(String cmd) {
            return calls.stream().filter(c -> c.size() > 1 && c.get(1).equals(cmd)).findFirst().orElseThrow();
        }

        List<String> last(String cmd) {
            List<List<String>> all = calls.stream().filter(c -> c.size() > 1 && c.get(1).equals(cmd)).toList();
            return all.get(all.size() - 1).subList(1, all.get(all.size() - 1).size());
        }

        void stopDaemon() {
            stopDaemonKeepContainer();
            exists = false;
        }

        void stopDaemonKeepContainer() {
            if (daemon != null) daemon.destroyForcibly();
            daemon = null;
        }

        @Override
        public synchronized Result run(List<String> argv, Duration timeout) throws IOException {
            calls.add(argv);
            String cmd = argv.get(1);
            switch (cmd) {
                case "version":
                    return versionFails ? new Result("", "Cannot connect", 1, false) : new Result("25.0", "", 0, false);
                case "inspect":
                    if (!exists) return new Result("", "No such object", 1, false);
                    return new Result("[{\"State\":{\"Running\":" + (daemon != null) + "},\"Config\":{\"Labels\":{"
                            + "\"jllm.config\":\"" + labels + "\"},\"Env\":[\"JLLM_TOKEN=" + token + "\"]}}]", "", 0, false);
                case "create":
                    exists = true;
                    for (int i = 0; i < argv.size(); i++) {
                        String a = argv.get(i);
                        if (a.startsWith("JLLM_TOKEN=")) token = a.substring("JLLM_TOKEN=".length());
                        if (a.startsWith("jllm.config=")) labels = a.substring("jllm.config=".length());
                        if (a.endsWith(":/agent:ro")) agentDir = a.substring(0, a.length() - ":/agent:ro".length());
                        if (a.startsWith("JLLM_AGENT_B64=")) agentDir = unpackAgent(a.substring("JLLM_AGENT_B64=".length()));
                    }
                    return new Result("id", "", 0, false);
                case "start":
                    if (daemon == null) startDaemon();
                    return new Result("", "", 0, false);
                case "port":
                    return new Result("127.0.0.1:" + port + "\n[::]:" + port + "\n", "", 0, false);
                case "rm":
                    stopDaemon();
                    return new Result("", "", 0, false);
                case "ps":
                    return new Result(listing, "", 0, false);
                default:
                    return new Result("", "", 0, false);
            }
        }

        private String unpackAgent(String b64) {
            try {
                byte[] raw = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(
                        java.util.Base64.getDecoder().decode(b64))).readAllBytes();
                Path dir = Files.createTempDirectory(workspace.getParent(), "agent");
                Files.write(dir.resolve("sandbox-agent.py"), raw);
                return dir.toString();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        private void startDaemon() throws IOException {
            try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
                port = s.getLocalPort();
            }
            ProcessBuilder pb = new ProcessBuilder(has("python", "--version") ? "python" : "python3",
                    Path.of(agentDir, "sandbox-agent.py").toString());
            pb.environment().put("JLLM_TOKEN", token);
            pb.environment().put("JLLM_PORT", String.valueOf(port));
            pb.environment().put("JLLM_BIND", "127.0.0.1");
            pb.environment().put("JLLM_WORKSPACE", workspace.toString());
            pb.environment().put("JLLM_ALIAS", "/workspace");
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            daemon = pb.start();
        }
    }
}
