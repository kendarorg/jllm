package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutorTest.FakeDocker;
import org.kendar.llm.harness.tools.FileSystemAgentTools;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scope (chat or user), profile (standard or hardened), policy override, backups with records, quota, idle sweep and
 * dropped users, against the real {@code sandbox-agent.py} behind a fake docker CLI (python and sh needed).
 */
class SandboxScopeProfileTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration T = Duration.ofSeconds(30);

    @TempDir
    Path tmp;
    FakeDocker docker;
    HarnessConfig config = HarnessConfig.defaults();
    final List<String> warnings = new ArrayList<>();

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

    private RemoteExecSandboxExecutor web(Path base, Function<String, String> userOf,
                                          Function<String, SandboxPolicy> policy, BackupRecordStore store) {
        return new RemoteExecSandboxExecutor(config, docker, null, new ChatDirLayout(base),
                RemoteExecSandboxExecutor.Kind.WEB, base, userOf, policy, store).onWarning(warnings::add);
    }

    private RemoteExecSandboxExecutor cli(Path project) {
        return new RemoteExecSandboxExecutor(config, docker, null, new CwdLayout(config, project),
                RemoteExecSandboxExecutor.Kind.CLI, project, null, null, null);
    }

    private static HarnessConfig.Cleanup cleanup(int idle, String action, int retention, long quota) {
        return new HarnessConfig.Cleanup(idle, action, retention, quota);
    }

    private HarnessConfig withCleanup(HarnessConfig.Cleanup c) {
        return config.withDocker(new SandboxPolicy(null, null, null, null, null, null, null, c)
                .over(config.sandbox().docker()));
    }

    private static String run(RemoteExecSandboxExecutor box, String tool, String chat, String json) throws Exception {
        ToolContext c = new ToolContext(chat, box.workDir(chat), box.scratchDir(chat, "main"), false, null,
                org.kendar.llm.harness.permission.PermissionMode.DEFAULT, null, box.files(chat, "main"));
        for (HarnessTool t : new FileSystemAgentTools().tools()) {
            if (t.name().equals(tool)) return t.execute(c, JSON.readTree(json));
        }
        throw new IllegalArgumentException(tool);
    }

    // ── scope keys and names ──────────────────────────────────────────────────

    @Test
    void cliChatScopeIsOneContainerPerSessionAndUserScopeOnePerProject() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Placement chat = cli(project).placement("c1");
        String h = Placement.hash(project.toAbsolutePath().normalize().toString()).substring(0, 12);
        assertEquals(h + "-c1", chat.key());
        assertEquals("jllm-cli-" + h + "-c1", chat.name());
        assertEquals(h, chat.project());
        assertFalse(chat.userScope());
        assertNotEquals(chat.name(), cli(project).placement("c2").name());

        config = config.withDockerScope("user");
        Placement user = cli(project).placement("c1");
        assertEquals(h, user.key());
        assertEquals("jllm-cli-" + h, user.name());
        assertEquals(user.name(), cli(project).placement("c2").name(), "one container for every session");
    }

    @Test
    void webScopeKeysNamesAndVolumes() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("web"));
        Placement chat = web(base, c -> "alice", null, null).placement("chat-1");
        assertEquals("chat-1", chat.key());
        assertEquals("jllm-web-chat-1", chat.name());
        assertNull(chat.volume());
        assertEquals("alice", chat.userId());

        config = config.withDockerScope("user");
        RemoteExecSandboxExecutor box = web(base, c -> "alice", null, null);
        Placement user = box.placement("chat-1");
        assertEquals("alice", user.key());
        assertEquals("jllm-web-user-alice", user.name());
        assertEquals("jllm-alice", user.volume());
        assertEquals(user.name(), box.placement("chat-2").name());
        assertEquals("user/alice", user.ownerKey());
        assertEquals(16, Placement.safe("a b@c").length(), "odd ids are hashed");

        // no user known: stricter per-chat container, with a warning
        Placement none = web(base, c -> null, null, null).placement("chat-9");
        assertFalse(none.userScope());
        assertEquals("jllm-web-chat-9", none.name());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void policyScopeOverridesTheConfiguration() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base, c -> "alice",
                c -> c.equals("temp") ? new SandboxPolicy("hardened", "user", "128m", null, 64, null, "tcp://h:1", null)
                        : null, null);
        Placement p = box.placement("temp");
        assertTrue(p.userScope() && p.docker().hardened());
        assertEquals("128m", p.docker().memory());
        assertEquals(64, p.docker().pidsLimit());
        assertEquals("1.0", p.docker().cpus(), "unset keeps the configuration");
        assertEquals("tcp://h:1", p.dockerHost());
        Placement other = box.placement("normal");
        assertFalse(other.userScope() || other.docker().hardened());
        assertNull(other.dockerHost());
    }

    // ── user scope ────────────────────────────────────────────────────────────

    @Test
    void userScopeSharesOneContainerWithOneDirectoryPerChat() throws Exception {
        config = config.withDockerScope("user");
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base, c -> "alice", null, null);
        assertEquals("/workspace/chat-1", box.displayPath(box.workDir("chat-1")));
        assertEquals("/workspace/chat-1/.jllm/scratchpad/main", box.displayPath(box.scratchDir("chat-1", "main")));

        assertEquals(0, box.execute("chat-1", "echo one > f.txt; pwd", T).exitCode());
        assertEquals(0, box.execute("chat-2", "echo two > f.txt", T).exitCode());
        assertEquals(1, docker.count("create"), "one container for the user's chats");
        String create = String.join(" ", docker.first("create"));
        assertTrue(create.contains("--name jllm-web-user-alice"), create);
        assertTrue(create.contains("-v jllm-alice:/workspace"), create);
        assertTrue(create.contains("--label jllm.scope=user"), create);
        assertTrue(create.contains("--label jllm.user=alice"), create);
        assertEquals("one", Files.readString(tmp.resolve("ws/chat-1/f.txt")).strip());
        assertEquals("two", Files.readString(tmp.resolve("ws/chat-2/f.txt")).strip());

        // the file tools see the chat's directory (X-Jllm-Chat), not the whole volume
        assertEquals("1\tone\n", run(box, "read_file", "chat-1", "{\"path\":\"f.txt\"}"));
        assertEquals("1\ttwo\n", run(box, "read_file", "chat-2", "{\"path\":\"f.txt\"}"));
        run(box, "write_file", "chat-1", "{\"path\":\"sub/g.txt\",\"content\":\"g\"}");
        assertEquals("g", Files.readString(tmp.resolve("ws/chat-1/sub/g.txt")));
        assertThrows(IllegalArgumentException.class, () -> run(box, "read_file", "chat-1", "{\"path\":\"../chat-2/f.txt\"}"));
    }

    @Test
    void deletingAChatInUserScopeRemovesOnlyItsDirectory() throws Exception {
        config = config.withDockerScope("user");
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base, c -> "alice", null, null);
        box.execute("chat-1", "echo one > f.txt", T);
        box.execute("chat-2", "echo two > f.txt", T);
        box.deleteWorkDir("chat-1");
        assertFalse(Files.exists(tmp.resolve("ws/chat-1")));
        assertTrue(Files.exists(tmp.resolve("ws/chat-2/f.txt")));
        assertEquals(0, docker.count("rm"), "the shared container stays");
        assertFalse(Files.exists(base.resolve("chat-1")));
    }

    @Test
    void chatScopeDeleteRemovesTheContainerAndItsBackup() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base, c -> "alice", null, null);
        box.execute("chat-1", "echo x > f.txt", T);
        assertTrue(box.backup("chat-1").startsWith("Backed up"));
        assertTrue(Files.isRegularFile(base.resolve("chat-1/.jllm/workspace-backup.zip")));
        box.deleteWorkDir("chat-1");
        assertFalse(Files.exists(base.resolve("chat-1")));
        assertEquals(1, docker.count("rmi"), "the image of the backup goes too");
        assertTrue(docker.count("rm") >= 1);
    }

    // ── profiles ──────────────────────────────────────────────────────────────

    @Test
    void standardProfileGetsANetworkOfItsOwnPerChat() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base, null, null, null);
        box.execute("c1", "true", T);
        String create = String.join(" ", docker.first("create"));
        assertTrue(create.contains("--network jllm-web-c1"), "not docker's shared default bridge: " + create);
        assertTrue(create.contains("-p 127.0.0.1::8080"), create);
        assertEquals(java.util.Set.of("jllm-web-c1"), docker.networks);
        List<String> made = docker.calls.stream().filter(c -> c.size() > 2 && c.get(1).equals("network")
                && c.get(2).equals("create")).findFirst().orElseThrow();
        assertTrue(String.join(" ", made).contains("--label jllm=1"), made.toString());
        assertTrue(docker.calls.indexOf(made) < docker.calls.indexOf(docker.first("create")), "network before container");

        docker.stopDaemon(); // the fake docker knows one container: forget it so the next chat makes its own
        box.execute("c2", "true", T);
        assertEquals(java.util.Set.of("jllm-web-c1", "jllm-web-c2"), docker.networks, "another chat, another network");

        box.deleteWorkDir("c1");
        assertEquals(java.util.Set.of("jllm-web-c2"), docker.networks, "removed with its container");
    }

    @Test
    void userScopeSharesOneNetworkPerUserAndUsersNeverShareOne() throws Exception {
        config = config.withDockerScope("user");
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base, c -> c.startsWith("a") ? "alice" : "bob", null, null);
        box.execute("a1", "true", T);
        box.execute("a2", "true", T);
        assertEquals(java.util.Set.of("jllm-web-user-alice"), docker.networks, "one network for all of alice's chats");
        docker.stopDaemon(); // the fake docker knows one container: forget it so the next user makes their own
        box.execute("b1", "true", T);
        assertEquals(java.util.Set.of("jllm-web-user-alice", "jllm-web-user-bob"), docker.networks);
    }

    @Test
    void hardenedProfileHasNoNetworkAndTheDaemonIsReachedThroughTheTunnel() throws Exception {
        config = config.withDockerProfile("hardened");
        RemoteExecSandboxExecutor box = web(Files.createDirectories(tmp.resolve("web")), null, null, null);
        ExecutionResult r = box.execute("c1", "echo tunnel; echo data > d.txt", T);
        assertEquals(0, r.exitCode(), r.stderr());
        assertTrue(r.stdout().contains("tunnel"));
        String create = String.join(" ", docker.first("create"));
        for (String flag : List.of("--network none", "--read-only", "--cap-drop ALL", "no-new-privileges",
                "--memory 512m", "--pids-limit 256", "--user 1000:1000", "--tmpfs /tmp:rw,size=64m",
                "--label jllm.profile=hardened")) {
            assertTrue(create.contains(flag), flag + " in " + create);
        }
        assertFalse(create.contains(" -p "), "docker cannot publish a port without a network: " + create);
        assertEquals(0, docker.count("port"));
        assertTrue(docker.count("exec") >= 1, "the daemon is reached with docker exec -i");
        List<String> exec = docker.first("exec");
        assertEquals(List.of("exec", "-i", "jllm-web-c1", "python", "-c"), exec.subList(1, 6));
        assertTrue(exec.get(6).startsWith("import base64;exec(base64.b64decode('"), exec.get(6));
        // the file tools use the same tunnel
        assertEquals("1\tdata\n", run(box, "read_file", "c1", "{\"path\":\"d.txt\"}"));
    }

    @Test
    void anAdminGrantGivesTheHardenedProfileTheDefaultNetwork() throws Exception {
        config = config.withDockerProfile("hardened");
        RemoteExecSandboxExecutor box = web(Files.createDirectories(tmp.resolve("web")), null,
                c -> new SandboxPolicy(null, null, null, null, null, "default", null, null), null);
        assertEquals(0, box.execute("c1", "true", T).exitCode());
        String create = String.join(" ", docker.first("create"));
        assertTrue(create.contains("--network jllm-web-c1"), "granted a network: its own, not the default bridge: " + create);
        assertTrue(create.contains("-p 127.0.0.1::8080"), create);
        assertEquals(0, docker.count("exec"));
    }

    @Test
    void policyLimitsFeedTheContainerAndRecreateItWhenTheyChange() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("web"));
        web(base, null, c -> new SandboxPolicy(null, null, "128m", "0.5", 64, null, null, null), null)
                .execute("c1", "true", T);
        String create = String.join(" ", docker.first("create"));
        assertTrue(create.contains("--memory 128m") && create.contains("--cpus 0.5") && create.contains("--pids-limit 64"), create);
        docker.stopDaemonKeepContainer();
        web(base, null, c -> new SandboxPolicy(null, null, "256m", "0.5", 64, null, null, null), null)
                .execute("c1", "true", T);
        assertEquals(2, docker.count("create"), "other limits: the container is made again");
    }

    // ── backups ───────────────────────────────────────────────────────────────

    @Test
    void closeBacksUpWithImageAndRecordAndTheNextContainerRestoresFromThem() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base, c -> "alice", null, null);
        box.execute("chat-1", "echo kept > keep.txt", T);
        box.close();
        BackupRecord rec = new FileBackupRecordStore(k -> base.resolve("chat-1/.jllm"), base).get("chat/chat-1").orElseThrow();
        assertEquals("jllm-backup/web-chat-chat-1", rec.image());
        assertEquals(base.resolve("chat-1/.jllm/workspace-backup.zip").toAbsolutePath().toString(), rec.tarball());
        assertEquals("default", rec.dockerHost());
        assertEquals("alice", rec.userId());
        assertEquals(0, rec.expiresAt());
        assertEquals(1, docker.count("commit"));
        assertTrue(docker.first("commit").contains("jllm-backup/web-chat-chat-1"));
        docker.stopDaemon();
        assertEquals("kept", web(base, c -> "alice", null, null).execute("chat-1", "cat keep.txt", T).stdout().strip());
        String create = String.join(" ", docker.calls.stream().filter(c -> c.get(1).equals("create")).reduce((a, b) -> b).orElseThrow());
        assertTrue(create.contains("jllm-backup/web-chat-chat-1 python"), "created from the committed image: " + create);
    }

    @Test
    void theImageIsNotUsedOnAnotherDockerHostButTheTarballStillRestores() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base, null, null, null);
        box.execute("chat-1", "echo kept > keep.txt", T);
        box.close();
        docker.stopDaemon();
        RemoteExecSandboxExecutor other = web(base, null, c -> new SandboxPolicy(null, null, null, null, null, null, "tcp://elsewhere:2375", null), null);
        // the record says "default": the override host cannot use that image, but the tarball restores
        Placement p = other.placement("chat-1");
        DockerEndpoint ep = new DockerEndpoint(config, docker, p.dockerHost(), true);
        WorkspaceBackups wb = new WorkspaceBackups(new ChatDirLayout(base), RemoteExecSandboxExecutor.Kind.WEB, base, null);
        assertNull(wb.usableImage(p, ep));
        assertNotNull(wb.initialWorkspace(p, false));
    }

    @Test
    void aWorkspaceOverTheQuotaIsNotBackedUpAndTheContainerIsKept() throws Exception {
        config = withCleanup(cleanup(0, "stop", 0, 10));
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base, null, null, null);
        box.execute("chat-1", "head -c 5000 /dev/zero > big.bin", T);
        String msg = box.backup("chat-1");
        assertTrue(msg.startsWith("Backup refused") && msg.contains("over the limit"), msg);
        assertFalse(Files.exists(base.resolve("chat-1/.jllm/workspace-backup.zip")));
        long removed = docker.count("rm");
        box.close();
        assertEquals(removed, docker.count("rm"), "container kept so the files are not lost");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("was kept")), warnings.toString());
    }

    @Test
    void aBackupBiggerThanTheQuotaIsNeverDownloadedWhole() throws Exception {
        // random data does not compress: the archive is over quota + 1 MiB, so the download stops at the cap
        config = withCleanup(cleanup(0, "stop", 0, 1000));
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base, null, null, null);
        assertEquals(0, box.execute("chat-1", "head -c 3000000 /dev/urandom > big.bin", T).exitCode());
        String msg = box.backup("chat-1");
        assertTrue(msg.startsWith("Backup refused") && msg.contains("over the limit"), msg);
        assertFalse(Files.exists(base.resolve("chat-1/.jllm/workspace-backup.zip")));
    }

    @Test
    void theSweepWarnsAboutAWorkspaceOverTheQuota() throws Exception {
        Assumptions.assumeTrue(has("du", "-sb", "."), "du -sb needed");
        config = withCleanup(cleanup(0, "stop", 0, 10));
        RemoteExecSandboxExecutor box = web(Files.createDirectories(tmp.resolve("web")), null, null, null);
        box.execute("chat-1", "head -c 5000 /dev/zero > big.bin", T);
        box.sweep(System.currentTimeMillis());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("over its limit")), warnings.toString());
    }

    // ── sweep ─────────────────────────────────────────────────────────────────

    @Test
    void idleContainersAreBackedUpAndRemovedThenRestoredOnNextUse() throws Exception {
        config = withCleanup(cleanup(5, "backup-remove", 7, 0));
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base, null, null, null);
        box.execute("chat-1", "echo kept > keep.txt", T);
        long now = System.currentTimeMillis();
        assertTrue(box.sweep(now + 60_000).isEmpty(), "not idle long enough");
        assertEquals(List.of("backed up and removed jllm-web-chat-1"), box.sweep(now + 6 * 60_000));
        assertTrue(Files.isRegularFile(base.resolve("chat-1/.jllm/workspace-backup.zip")));
        BackupRecord rec = new FileBackupRecordStore(k -> base.resolve("chat-1/.jllm"), base).get("chat/chat-1").orElseThrow();
        assertTrue(rec.expiresAt() > now, "retention of 7 days");
        docker.stopDaemon();
        assertEquals("kept", box.execute("chat-1", "cat keep.txt", T).stdout().strip());
        assertEquals(2, docker.count("create"));
    }

    @Test
    void stopActionStopsTheIdleContainerAndTheNextUseStartsIt() throws Exception {
        config = withCleanup(cleanup(1, "stop", 0, 0));
        RemoteExecSandboxExecutor box = web(Files.createDirectories(tmp.resolve("web")), null, null, null);
        box.execute("chat-1", "true", T);
        assertEquals(List.of("stopped jllm-web-chat-1"), box.sweep(System.currentTimeMillis() + 2 * 60_000));
        assertEquals(1, docker.count("stop"));
        assertEquals(0, box.execute("chat-1", "true", T).exitCode());
        assertEquals(1, docker.count("create"), "the stopped container is reused");
    }

    @Test
    void expiredBackupsAreDeletedByTheSweep() throws Exception {
        config = withCleanup(cleanup(0, "stop", 1, 0));
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base, null, null, null);
        box.execute("chat-1", "echo x > f", T);
        assertTrue(box.backup("chat-1").startsWith("Backed up"));
        assertTrue(box.sweep(System.currentTimeMillis()).isEmpty());
        List<String> done = box.sweep(System.currentTimeMillis() + 2 * 86_400_000L);
        assertEquals(List.of("expired backup chat/chat-1"), done);
        assertFalse(Files.exists(base.resolve("chat-1/.jllm/workspace-backup.zip")));
        assertFalse(Files.exists(base.resolve("chat-1/.jllm/backup.json")));
        assertEquals(1, docker.count("rmi"));
    }

    // ── dropped users and orphans ─────────────────────────────────────────────

    @Test
    void droppingAUserRemovesContainersVolumesBackupsAndRecords() throws Exception {
        config = config.withDockerScope("user");
        Path base = Files.createDirectories(tmp.resolve("web"));
        RemoteExecSandboxExecutor box = web(base, c -> "alice", null, null);
        box.execute("chat-1", "echo x > f", T);
        assertTrue(box.backup("chat-1").startsWith("Backed up"));
        Path tar = base.resolve(".sandbox/alice/workspace-backup.zip");
        assertTrue(Files.isRegularFile(tar), "user-scope tarball in a dot directory, not a chat directory");
        assertTrue(Files.isRegularFile(base.resolve(".sandbox/alice/backup.json")));
        docker.listing = "jllm-web-user-alice\n";
        List<String> removed = box.dropUser("alice");
        assertTrue(removed.contains("jllm-web-user-alice") && removed.contains("backup user/alice"), removed.toString());
        assertFalse(Files.exists(tar));
        assertFalse(Files.exists(base.resolve(".sandbox/alice/backup.json")));
        assertTrue(docker.count("rm") >= 1);
        assertEquals(1, docker.count("rmi"));
        assertTrue(docker.calls.stream().anyMatch(c -> c.size() > 3 && c.get(1).equals("volume") && c.get(2).equals("rm")
                && c.get(c.size() - 1).equals("jllm-alice")), "the named volume is removed");
    }

    @Test
    void orphanSweepsTouchOnlyTheirScope() throws Exception {
        RemoteExecSandboxExecutor box = web(Files.createDirectories(tmp.resolve("web")), null, null, null);
        docker.listing = "jllm-web-gone jllm=1,jllm.kind=web,jllm.owner=gone,jllm.scope=chat\n"
                + "jllm-web-user-bob jllm=1,jllm.kind=web,jllm.owner=bob,jllm.scope=user\n";
        assertEquals(List.of("jllm-web-gone"), box.sweepOrphans(c -> false), "user containers are not chats");
        docker.calls.clear();
        assertEquals(List.of("jllm-web-user-bob"), box.sweepOrphanUsers(u -> false));
    }

    // ── status ────────────────────────────────────────────────────────────────

    @Test
    void statusDescribesRunnerProfileScopeAndContainer() throws Exception {
        config = config.withDockerProfile("hardened").withDockerScope("user");
        RemoteExecSandboxExecutor box = web(Files.createDirectories(tmp.resolve("web")), c -> "alice", null, null);
        String s = box.sandboxStatus("chat-1");
        assertTrue(s.contains("container") && s.contains("hardened") && s.contains("no network") && s.contains("scope: user")
                && s.contains("jllm-web-user-alice") && s.contains("/workspace/chat-1") && s.contains("run_js storage: host"), s);
        assertTrue(LocalSandboxExecutor.of(config).sandboxStatus("c").contains("no isolation"));
    }
}
