package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectGuardTest {

    @TempDir
    Path tmp;

    @Test
    void changedDeletedAndCreatedProtectedFilesAreRestored() throws Exception {
        Path p = tmp;
        Files.createDirectories(p.resolve(".git/hooks"));
        Files.writeString(p.resolve(".git/config"), "[core]");
        Files.writeString(p.resolve(".git/hooks/pre-commit"), "ok");
        Files.createDirectories(p.resolve(".jllm/scratchpad"));
        Files.writeString(p.resolve(".jllm/settings.json"), "{}");
        ProjectGuard g = ProjectGuard.snapshot(p);
        Files.writeString(p.resolve(".git/config"), "[core]\nfsmonitor=evil");
        Files.delete(p.resolve(".git/hooks/pre-commit"));
        Files.writeString(p.resolve(".git/hooks/post-merge"), "evil");
        Files.writeString(p.resolve(".jllm/settings.json"), "{\"x\":1}");
        Files.writeString(p.resolve(".jllm/new.md"), "evil");
        Files.writeString(p.resolve(".jllm/scratchpad/ok.txt"), "scratch is free");
        Files.writeString(p.resolve("src.txt"), "project files are free");
        List<String> changed = g.restore();
        assertEquals(5, changed.size(), changed.toString());
        assertEquals("[core]", Files.readString(p.resolve(".git/config")));
        assertEquals("ok", Files.readString(p.resolve(".git/hooks/pre-commit")));
        assertFalse(Files.exists(p.resolve(".git/hooks/post-merge")));
        assertEquals("{}", Files.readString(p.resolve(".jllm/settings.json")));
        assertFalse(Files.exists(p.resolve(".jllm/new.md")));
        assertTrue(Files.exists(p.resolve(".jllm/scratchpad/ok.txt")));
        assertTrue(Files.exists(p.resolve("src.txt")));
        assertTrue(g.restore().isEmpty());
    }

    @Test
    void directoriesCreatedWhereThereWasNothingAreRemoved() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        ProjectGuard g = ProjectGuard.snapshot(tmp);
        Files.createDirectories(tmp.resolve(".git/hooks"));
        Files.writeString(tmp.resolve(".git/hooks/pre-commit"), "evil");
        Files.createDirectories(tmp.resolve(".jllm"));
        Files.writeString(tmp.resolve(".jllm/settings.json"), "{}");
        assertFalse(g.restore().isEmpty());
        assertFalse(Files.exists(tmp.resolve(".git/hooks")));
        assertFalse(Files.exists(tmp.resolve(".jllm")));
    }
}
