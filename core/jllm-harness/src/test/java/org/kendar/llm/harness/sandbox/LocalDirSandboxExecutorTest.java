package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.tools.ShellAgentTools;
import org.kendar.llm.harness.tools.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.config;
import static org.kendar.llm.harness.HarnessTestSupport.json;

class LocalDirSandboxExecutorTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    @TempDir
    Path base;

    LocalDirSandboxExecutor sandbox;

    @BeforeEach
    void setUp() {
        sandbox = new LocalDirSandboxExecutor(config(base));
    }

    private static String sleepCommand(int seconds) {
        return WINDOWS ? "ping -n " + (seconds + 1) + " 127.0.0.1 > nul" : "sleep " + seconds;
    }

    @Test
    void locationIsTheAbsoluteBaseDirHoldingTheChatDirs() {
        assertEquals(base.toAbsolutePath().normalize(), sandbox.location());
        assertEquals(sandbox.location(), sandbox.workDir("c1").getParent());
    }

    @Test
    void capturesStdoutStderrAndExitCode() {
        ExecutionResult ok = sandbox.execute("c1", "echo hello", Duration.ofSeconds(10));
        assertEquals(0, ok.exitCode());
        assertFalse(ok.timedOut());
        assertTrue(ok.stdout().contains("hello"));

        ExecutionResult bad = sandbox.execute("c1", WINDOWS ? "echo oops 1>&2 & exit 3" : "echo oops >&2; exit 3",
                Duration.ofSeconds(10));
        assertEquals(3, bad.exitCode());
        assertTrue(bad.stdout().contains("oops"), "stderr is merged into stdout");
    }

    @Test
    void timeoutKillsTheProcessAndReportsIt() {
        long t0 = System.currentTimeMillis();

        ExecutionResult r = sandbox.execute("c1", sleepCommand(30), Duration.ofSeconds(1));

        long took = System.currentTimeMillis() - t0;
        assertTrue(r.timedOut());
        assertEquals(-1, r.exitCode());
        assertTrue(took < 15_000, "must not wait for the 30s command, took " + took);
    }

    @Test
    void commandsRunInPerChatWorkDirAndAreIsolated() throws Exception {
        sandbox.execute("chatA", WINDOWS ? "echo A> a.txt" : "echo A > a.txt", Duration.ofSeconds(10));
        sandbox.execute("chatB", WINDOWS ? "echo B> b.txt" : "echo B > b.txt", Duration.ofSeconds(10));

        Path a = sandbox.workDir("chatA");
        Path b = sandbox.workDir("chatB");

        assertNotEquals(a, b);
        assertEquals(base.toAbsolutePath().normalize().resolve("chatA"), a);
        assertTrue(Files.exists(a.resolve("a.txt")));
        assertFalse(Files.exists(a.resolve("b.txt")));
        assertTrue(Files.exists(b.resolve("b.txt")));
        assertFalse(Files.exists(b.resolve("a.txt")));
        ExecutionResult ls = sandbox.execute("chatB", WINDOWS ? "dir /b" : "ls", Duration.ofSeconds(10));
        assertTrue(ls.stdout().contains("b.txt"));
        assertFalse(ls.stdout().contains("a.txt"));
    }

    @Test
    void cwdIsTheChatWorkDir() {
        ExecutionResult r = sandbox.execute("chatZ", WINDOWS ? "cd" : "pwd", Duration.ofSeconds(10));

        assertTrue(r.stdout().trim().replace('\\', '/').endsWith("/chatZ"), r.stdout());
    }

    @Test
    void rejectsChatIdsThatCouldEscapeTheBaseDir() {
        for (String bad : new String[]{null, "", "..", "../x", "a/b", "a\\b", "x..y/../z", "/abs", "a b", "a".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> sandbox.workDir(bad), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class,
                    () -> sandbox.execute(bad, "echo x", Duration.ofSeconds(1)), String.valueOf(bad));
        }
        assertDoesNotThrow(() -> sandbox.workDir("0b6f7a0e-1111-4222-8333-444455556666"));
    }

    @Test
    void bashToolFormatsResultAndTimeout() throws Exception {
        ShellAgentTools shell = new ShellAgentTools(sandbox, config(base));
        var bash = shell.tools().get(0);
        ToolContext ctx = new ToolContext("c1", sandbox.workDir("c1"));

        String ok = bash.execute(ctx, json("{'command':'echo hi'}"));
        assertTrue(ok.startsWith("exit code: 0"));
        assertTrue(ok.contains("[stdout]"));

        String timedOut = bash.execute(ctx, json("{'command':'" + sleepCommand(30) + "','timeout_seconds':1}"));
        assertTrue(timedOut.contains("(timed out)"), timedOut);
    }
}
