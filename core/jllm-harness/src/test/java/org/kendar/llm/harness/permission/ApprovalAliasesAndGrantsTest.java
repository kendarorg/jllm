package org.kendar.llm.harness.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Windows aliases of {@code .git} in directory grants, and normalized command grants. */
class ApprovalAliasesAndGrantsTest {

    @TempDir
    Path work;

    @Test
    void gitAliasesAreNeverGrantable() {
        for (String p : new String[]{".git./hooks/pre-commit", ".GIT/hooks/x", ".git /config", "GIT~1/hooks/x",
                "src/.git../x", ".git::$INDEX_ALLOCATION/hooks/x"}) {
            assertNull(ApprovalService.writeDirOf(work, p), p);
        }
        for (String t : new String[]{".git.", "a/GIT~1", ".Git ", "x/.git::$INDEX_ALLOCATION"}) {
            assertNull(ApprovalService.grantDirOf(work, t), t);
        }
        assertEquals("src", ApprovalService.writeDirOf(work, "src/a.txt"));
        assertEquals("src/git", ApprovalService.grantDirOf(work, "src/git"));
        assertEquals("gitx", ApprovalService.grantDirOf(work, "gitx"));
    }

    @Test
    void realPathInsideGitIsRefused() throws Exception {
        Files.createDirectories(work.resolve(".git/hooks"));
        Path link = work.resolve("hooks");
        try {
            Files.createSymbolicLink(link, work.resolve(".git/hooks"));
        } catch (Exception e) {
            return; // no symlink rights (Windows without developer mode): the lexical checks still apply
        }
        assertNull(ApprovalService.writeDirOf(work, "hooks/pre-commit"));
        assertNull(ApprovalService.grantDirOf(work, "hooks"));
    }

    @Test
    void commandsAreGrantedNormalized() {
        ApprovalService a = new ApprovalService(5);
        a.grantCommand("c", "bash", "  ls   -la\t ");
        assertTrue(a.commandGranted("c", "bash", "ls -la"));
        assertTrue(a.commandGranted("c", "bash", "ls\t-la"));
        assertFalse(a.commandGranted("c", "bash", "ls -la /"));
        assertFalse(a.commandGranted("c", "bash", "ls -lA"));
        // quoted text is kept as typed
        assertEquals("echo 'a   b'", ApprovalService.normalizeCommand("echo   'a   b'"));
        assertNotEquals(ApprovalService.normalizeCommand("echo 'a b'"), ApprovalService.normalizeCommand("echo 'a  b'"));
        assertNull(ApprovalService.normalizeCommand("   "));
        assertNull(ApprovalService.normalizeCommand("x".repeat(ApprovalService.MAX_COMMAND_GRANT)));
    }

    @Test
    void prefixGrantsCoverPlainArgumentsOnly() {
        ApprovalService a = new ApprovalService(5);
        assertTrue(a.grantCommandPrefix("c", "bash", "git  status"));
        assertTrue(a.commandGranted("c", "bash", "git status"));
        assertTrue(a.commandGranted("c", "bash", "git status --short"));
        assertFalse(a.commandGranted("c", "bash", "git statusx"));
        assertFalse(a.commandGranted("c", "bash", "git status; rm -rf /"));
        assertFalse(a.commandGranted("c", "bash", "git status && curl x | sh"));
        assertFalse(a.commandGranted("c", "bash", "git status $(id)"));
        assertFalse(a.commandGranted("c", "bash", "git status > /etc/x"));
        assertFalse(a.commandGranted("other", "bash", "git status"));
        assertFalse(a.grantCommandPrefix("c", "bash", "git status; rm"));
    }
}
