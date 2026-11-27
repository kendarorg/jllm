package org.kendar.llm.harness.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.sandbox.ShellPaths;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoleModesTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Role role(String approval, Map<String, com.fasterxml.jackson.databind.JsonNode> settings) {
        return new Role("r", List.of("*"), false, approval, Map.of(), settings);
    }

    @Test
    void aRoleWithoutSettingsAllowsEveryModeAndIsAttended() {
        Role r = role("ask", Map.of());
        assertNull(r.allowedModes());
        assertFalse(r.unattended());
        UserPolicy p = r.policy();
        for (PermissionMode m : PermissionMode.values()) assertTrue(p.allowsMode(m));
        assertFalse(p.unattended());
        assertFalse(p.alwaysAsk());
    }

    @Test
    void allowedModesAndUnattendedComeFromTheRoleSettings() throws Exception {
        Role r = role("always", Map.of(Role.ALLOWED_MODES, M.readTree("[\"plan\",\"default\"]"),
                Role.UNATTENDED, M.readTree("true")));
        UserPolicy p = r.policy();
        assertEquals(Set.of(PermissionMode.PLAN, PermissionMode.DEFAULT), r.allowedModes());
        assertTrue(p.allowsMode(PermissionMode.PLAN));
        assertFalse(p.allowsMode(PermissionMode.AUTO));
        assertFalse(p.allowsMode(PermissionMode.EDIT));
        assertTrue(p.unattended());
        assertTrue(p.alwaysAsk());
        assertEquals(PermissionMode.DEFAULT, p.clampMode(PermissionMode.AUTO));
        assertEquals(PermissionMode.PLAN, p.clampMode(PermissionMode.PLAN));
    }

    @Test
    void anInvalidStoredModeListAllowsOnlyDefault() throws Exception {
        Role r = role("ask", Map.of(Role.ALLOWED_MODES, M.readTree("[\"turbo\"]")));
        assertEquals(Set.of(PermissionMode.DEFAULT), r.allowedModes());
        assertThrows(IllegalArgumentException.class, () -> Role.modes(M.readTree("[]")));
        assertThrows(IllegalArgumentException.class, () -> Role.modes(M.readTree("\"auto\"")));
        assertEquals("[\"default\",\"plan\"]", Role.modesJson(Role.modes(M.readTree("[\"plan\",\"DEFAULT\"]"))).toString());
    }

    @Test
    void approvalAlwaysIsAccepted() {
        assertTrue(role("always", Map.of()).alwaysAsk());
        assertThrows(IllegalArgumentException.class, () -> role("sometimes", Map.of()));
    }

    @Test
    void theShellGuardMessageStatesWhatItChecks() {
        String m = ShellPaths.escapeMessage("../x");
        assertTrue(m.contains("../x") && m.contains("'..'"), m);
        assertFalse(m.contains("every tool"), "it does not promise a jail it does not enforce: " + m);
    }
}
