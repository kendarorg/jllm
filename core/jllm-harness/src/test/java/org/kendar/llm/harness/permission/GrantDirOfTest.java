package org.kendar.llm.harness.permission;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GrantDirOfTest {
    private final Path root = Path.of("work").toAbsolutePath();

    @Test
    void acceptsFoldersUnderTheWorkDir() {
        assertEquals("a/b", ApprovalService.grantDirOf(root, "a/b/"));
        assertEquals("a/b", ApprovalService.grantDirOf(root, "a\\b"));
        assertEquals("a", ApprovalService.grantDirOf(root, "a/./"));
    }

    @Test
    void rejectsEscapesAbsoluteAndGit() {
        assertNull(ApprovalService.grantDirOf(root, ".."));
        assertNull(ApprovalService.grantDirOf(root, "a/../../x"));
        assertNull(ApprovalService.grantDirOf(root, "a/.."));
        assertNull(ApprovalService.grantDirOf(root, "/etc"));
        assertNull(ApprovalService.grantDirOf(root, "C:\\x"));
        assertNull(ApprovalService.grantDirOf(root, "a/.git/hooks"));
        assertNull(ApprovalService.grantDirOf(root, "  "));
    }
}
