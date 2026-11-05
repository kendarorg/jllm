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
    void forgetRemovesThePersistedGrants(@TempDir Path dir) {
        ApprovalService a = new ApprovalService(5, new FileStateStore(dir));
        a.grant("c1", "bash");
        a.forget("c1");
        assertFalse(new ApprovalService(5, new FileStateStore(dir)).granted("c1", "bash"));
    }
}
