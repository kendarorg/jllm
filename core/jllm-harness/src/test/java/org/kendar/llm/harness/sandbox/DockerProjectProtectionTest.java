package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Needs a docker daemon (tag {@code docker}, skipped by default; run with
 * {@code mvn test -DexcludedGroups=none -Dgroups=docker}). A container with the project mounted as the CLI does:
 * writes to {@code .jllm} and {@code .git/hooks} must not reach the host, the scratchpad and other files must.
 */
@Tag("docker")
class DockerProjectProtectionTest {

    @TempDir
    Path tmp;

    private int docker(List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker"));
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("docker timed out");
        }
        return p.exitValue();
    }

    @Test
    void containerCannotWriteJllmOrGitHooksToTheHost() throws Exception {
        try {
            Assumptions.assumeTrue(docker(List.of("version")) == 0, "no docker daemon");
        } catch (java.io.IOException e) {
            Assumptions.abort("no docker binary");
        }
        Path p = Files.createDirectories(tmp.resolve("p"));
        Files.createDirectories(p.resolve(".git/hooks"));
        Files.writeString(p.resolve(".git/config"), "[core]\n");
        Files.createDirectories(p.resolve(".jllm/scratchpad"));
        Files.writeString(p.resolve(".jllm/settings.json"), "{}");
        List<String> a = new ArrayList<>(List.of("run", "--rm", "-v", DockerHosts.mountSource(p) + ":/workspace"));
        a.addAll(ProjectMounts.args(p, "/workspace", DockerHosts::mountSource));
        a.addAll(List.of("alpine", "sh", "-c", "echo evil > /workspace/.git/hooks/pre-commit; "
                + "echo evil >> /workspace/.git/config; echo evil > /workspace/.jllm/settings.json; "
                + "echo evil > /workspace/.jllm/new.json; echo ok > /workspace/.jllm/scratchpad/s.txt; "
                + "echo ok > /workspace/file.txt; exit 0"));
        docker(a);
        assertFalse(Files.exists(p.resolve(".git/hooks/pre-commit")));
        assertEquals("[core]\n", Files.readString(p.resolve(".git/config")));
        assertEquals("{}", Files.readString(p.resolve(".jllm/settings.json")));
        assertFalse(Files.exists(p.resolve(".jllm/new.json")));
        assertTrue(Files.exists(p.resolve(".jllm/scratchpad/s.txt")));
        assertTrue(Files.exists(p.resolve("file.txt")));
    }
}
