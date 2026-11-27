package org.kendar.llm.harness.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProtectedPathTest {

    @TempDir
    Path tmp;

    @Test
    void configurationPathsAreProtected() {
        for (String s : new String[]{".jllm/settings.json", ".JLLM/x", ".git/hooks/pre-commit", "sub/.git/config",
                "AGENTS.md", "CLAUDE.md", "KENDAR.md", "SYSTEM.md", "docs/agents.md", "a/../.git/config",
                "./.jllm/../.jllm/y"}) {
            assertTrue(ApprovalService.isProtectedPath(tmp, s), s);
        }
        for (String s : new String[]{"src/Main.java", "README.md", "jllm/x", ".gitignore", ".github/ci.yml"}) {
            assertFalse(ApprovalService.isProtectedPath(tmp, s), s);
        }
    }

    @Test
    void aLinkToGitHooksIsProtectedAfterCanonicalisation() throws Exception {
        Files.createDirectories(tmp.resolve(".git/hooks"));
        try {
            Files.createSymbolicLink(tmp.resolve("innocent"), tmp.resolve(".git/hooks"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here");
        }
        assertTrue(ApprovalService.isProtectedPath(tmp, "innocent/pre-commit"));
    }
}
