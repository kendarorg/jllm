package org.kendar.llm.harness.permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolKind;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionGateTest {

    private final Map<String, Boolean> flagged = new HashMap<>();
    private PermissionGate gate;

    @BeforeEach
    void setUp() {
        gate = new PermissionGate(t -> flagged.getOrDefault(t.name(), false));
    }

    private HarnessTool tool(String name, ToolKind kind, boolean requiresApproval) {
        HarnessTool t = mock(HarnessTool.class);
        when(t.name()).thenReturn(name);
        when(t.kind()).thenReturn(kind);
        flagged.put(name, requiresApproval);
        return t;
    }

    @Test
    void planAllowsFileToolsConfinedToScratchAndAsksForShell() {
        assertEquals(Decision.ALLOW, gate.check(PermissionMode.PLAN, tool("write_file", ToolKind.FILE_EDIT, true)));
        assertEquals(Decision.ASK, gate.check(PermissionMode.PLAN, tool("bash", ToolKind.OTHER, true)));
        assertEquals(Decision.ALLOW, gate.check(PermissionMode.PLAN, tool("read_file", ToolKind.READ_ONLY, false)));
    }

    @Test
    void autoAllowsEverything() {
        assertEquals(Decision.ALLOW, gate.check(PermissionMode.AUTO, tool("bash", ToolKind.OTHER, true)));
    }

    @Test
    void editAllowsFileEditsButStillAsksForOthers() {
        assertEquals(Decision.ALLOW, gate.check(PermissionMode.EDIT, tool("edit_file", ToolKind.FILE_EDIT, true)));
        assertEquals(Decision.ASK, gate.check(PermissionMode.EDIT, tool("bash", ToolKind.OTHER, true)));
    }

    @Test
    void defaultAsksOnlyWhenFlagged() {
        assertEquals(Decision.ASK, gate.check(PermissionMode.DEFAULT, tool("bash", ToolKind.OTHER, true)));
        assertEquals(Decision.ALLOW, gate.check(PermissionMode.DEFAULT, tool("glob", ToolKind.READ_ONLY, false)));
    }

    @Test
    void byKindPolicyAsksForEverythingThatIsNotReadOnly() {
        PermissionGate byKind = new PermissionGate(ToolApprovalPolicy.byKind());
        assertEquals(Decision.ASK, byKind.check(PermissionMode.DEFAULT, tool("bash", ToolKind.OTHER, false)));
        assertEquals(Decision.ALLOW, byKind.check(PermissionMode.DEFAULT, tool("glob", ToolKind.READ_ONLY, false)));
    }
}
