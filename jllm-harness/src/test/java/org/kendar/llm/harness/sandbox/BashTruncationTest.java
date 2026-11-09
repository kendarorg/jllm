package org.kendar.llm.harness.sandbox;

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

class BashTruncationTest {

    @TempDir
    Path base;

    @Test
    void hugeOutputKeepsTailWritesFullLogAndReportsExitCode() throws Exception {
        Path gen = base.resolve("Gen.java");
        Files.writeString(gen, "public class Gen { public static void main(String[] a) {\n"
                + "for (int i = 1; i <= 100000; i++) System.out.println(String.format(\"line-%06d \", i) + \"x\".repeat(40));\n"
                + "System.exit(7); } }\n");
        LocalDirSandboxExecutor sandbox = new LocalDirSandboxExecutor(config(base));
        var bash = new ShellAgentTools(sandbox, config(base)).tools().get(0);
        Path scratch = Files.createDirectories(base.resolve("scratch"));
        ToolContext ctx = new ToolContext("c1", sandbox.workDir("c1"), scratch, false, "t1");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString().replace("\\", "/");

        String out = bash.execute(ctx, json("{\"command\":\"\\\"" + java + "\\\" "
                + gen.toString().replace("\\", "/") + "\",\"timeout_seconds\":120}"));

        assertTrue(out.startsWith("exit code: 7"), out.substring(0, Math.min(200, out.length())));
        assertTrue(out.contains("[output truncated:"), out.substring(0, Math.min(400, out.length())));
        assertTrue(out.contains("line-100000"));
        assertFalse(out.contains("line-000001 "));
        assertTrue(out.length() < 60_000);
        Path log = Path.of(out.substring(out.indexOf("full log: ") + 10, out.indexOf(".log]") + 4));
        assertTrue(log.startsWith(scratch.toAbsolutePath()));
        String full = Files.readString(log);
        assertTrue(full.contains("line-000001 "));
        assertTrue(full.contains("line-100000"));
        assertEquals(100000, full.lines().count());
    }

    @Test
    void smallOutputIsUntouched() {
        LocalDirSandboxExecutor sandbox = new LocalDirSandboxExecutor(config(base));
        ExecutionResult r = sandbox.execute("c1", "echo hi", Duration.ofSeconds(10));
        assertNull(r.logFile());
        assertEquals(0, r.omittedLines());
        assertTrue(r.stdout().contains("hi"));
    }
}
