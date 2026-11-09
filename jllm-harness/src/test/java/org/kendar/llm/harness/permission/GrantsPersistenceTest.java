package org.kendar.llm.harness.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.state.FileStateStore;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GrantsPersistenceTest {

    @Test
    void grantsSurviveANewService(@TempDir Path dir) {
        ApprovalService a = new ApprovalService(5, new FileStateStore(dir));
        a.grant("c1", "bash");
        a.grant("c1", "write_file");

        ApprovalService b = new ApprovalService(5, new FileStateStore(dir));
        assertTrue(b.granted("c1", "bash"));
        assertTrue(b.granted("c1", "write_file"));
        assertFalse(b.granted("c1", "other"));
        assertFalse(b.granted("c2", "bash"));
    }

    @Test
    void commandGrantsAreExactAndLimitedTo511Chars(@TempDir Path dir) {
        ApprovalService a = new ApprovalService(5, new FileStateStore(dir));
        a.grantCommand("c1", "bash", "mkdir -p tmp/results");
        String longCmd = "x".repeat(ApprovalService.MAX_COMMAND_GRANT);
        a.grantCommand("c1", "bash", longCmd);

        ApprovalService b = new ApprovalService(5, new FileStateStore(dir));
        assertTrue(b.commandGranted("c1", "bash", "mkdir -p tmp/results"));
        assertFalse(b.commandGranted("c1", "bash", "mkdir -p tmp/other"));
        assertFalse(b.commandGranted("c1", "bash", longCmd));
        assertFalse(b.granted("c1", "bash"));
    }

    @Test
    void dirGrantsCoverSubdirsOnlyAndPersist(@TempDir Path dir) {
        Path work = dir.resolve("work");
        assertEquals("src/a", ApprovalService.writeDirOf(work, "src/a/F.java"));
        assertEquals(".", ApprovalService.writeDirOf(work, "F.java"));
        assertNull(ApprovalService.writeDirOf(work, "../x/F.java"));
        assertNull(ApprovalService.writeDirOf(work, "src/../../x/F.java"));
        assertNull(ApprovalService.writeDirOf(work, ".git/hooks/pre-commit"));

        ApprovalService a = new ApprovalService(5, new FileStateStore(dir));
        a.grantDir("c1", "src/a");
        ApprovalService b = new ApprovalService(5, new FileStateStore(dir));
        assertTrue(b.dirGranted("c1", "src/a"));
        assertTrue(b.dirGranted("c1", "src/a/deep/er"));
        assertFalse(b.dirGranted("c1", "src/ab"));
        assertFalse(b.dirGranted("c1", "src"));
        assertFalse(b.dirGranted("c2", "src/a"));
        assertFalse(b.dirGranted("c1", null));
    }

    @Test
    void forgetRemovesThePersistedGrants(@TempDir Path dir) {
        ApprovalService a = new ApprovalService(5, new FileStateStore(dir));
        a.grant("c1", "bash");
        a.forget("c1");
        assertFalse(new ApprovalService(5, new FileStateStore(dir)).granted("c1", "bash"));
    }
}
