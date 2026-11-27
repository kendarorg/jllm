package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectMountsTest {

    @TempDir
    Path tmp;

    private static List<String> args(Path p) {
        return ProjectMounts.args(p, "/workspace", DockerHosts::mountSource);
    }

    private static String src(Path p) {
        return DockerHosts.mountSource(p);
    }

    @Test
    void gitAndJllmAreReadOnlyWithScratchpadWritableOnTop() throws Exception {
        Path p = Files.createDirectories(tmp.resolve("p"));
        Files.createDirectories(p.resolve(".git/hooks"));
        Files.writeString(p.resolve(".git/config"), "[core]");
        Files.createDirectories(p.resolve(".jllm/scratchpad"));
        assertEquals(List.of(
                "--mount", "type=bind,src=" + src(p.resolve(".git/hooks")) + ",dst=/workspace/.git/hooks,readonly",
                "--mount", "type=bind,src=" + src(p.resolve(".git/config")) + ",dst=/workspace/.git/config,readonly",
                "--mount", "type=bind,src=" + src(p.resolve(".jllm")) + ",dst=/workspace/.jllm,readonly",
                "--mount", "type=bind,src=" + src(p.resolve(".jllm/scratchpad"))
                        + ",dst=/workspace/.jllm/scratchpad"), args(p));
    }

    @Test
    void submoduleHooksAndConfigAreMountedToo() throws Exception {
        Path p = Files.createDirectories(tmp.resolve("p"));
        Files.createDirectories(p.resolve(".git/hooks"));
        Files.writeString(p.resolve(".git/config"), "x");
        Path sub = Files.createDirectories(p.resolve(".git/modules/sub"));
        Files.writeString(sub.resolve("HEAD"), "ref: refs/heads/main");
        Files.createDirectories(sub.resolve("hooks"));
        Files.writeString(sub.resolve("config"), "x");
        Files.createDirectories(sub.resolve("objects/aa"));
        assertEquals(List.of(".git/modules/sub"), ProjectMounts.submoduleGitDirs(p));
        String all = String.join(" ", args(p));
        assertTrue(all.contains("dst=/workspace/.git/modules/sub/hooks,readonly"), all);
        assertTrue(all.contains("dst=/workspace/.git/modules/sub/config,readonly"), all);
    }

    @Test
    void missingDirectoriesBecomeReadOnlyTmpfs() throws Exception {
        Path p = Files.createDirectories(tmp.resolve("p"));
        Files.createDirectories(p.resolve(".git"));
        assertEquals(List.of("--tmpfs", "/workspace/.git/hooks:ro", "--tmpfs", "/workspace/.jllm:ro"), args(p));
    }

    @Test
    void noGitMeansOnlyJllmIsCovered() throws Exception {
        Path p = Files.createDirectories(tmp.resolve("p"));
        assertEquals(List.of("--tmpfs", "/workspace/.jllm:ro"), args(p));
    }

    @Test
    void gitFileOfAWorktreeIsNotMounted() throws Exception {
        Path p = Files.createDirectories(tmp.resolve("p"));
        Files.writeString(p.resolve(".git"), "gitdir: /elsewhere");
        assertFalse(String.join(" ", args(p)).contains(".git"));
    }

    @Test
    void commaInAPathIsCsvQuoted() throws Exception {
        Path p = Files.createDirectories(tmp.resolve("a,b"));
        Files.createDirectories(p.resolve(".jllm"));
        String all = String.join(" ", args(p));
        assertTrue(all.contains("\"src="), all);
    }
}
