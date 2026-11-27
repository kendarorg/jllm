package org.kendar.llm.web.commons;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.AgentHarnessBuilder;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.harness.identity.Role;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.sandbox.ChatDirLayout;
import org.kendar.llm.harness.sandbox.LocalSandboxExecutor;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.kendar.llm.web.commons.users.Permissions;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A role's allowed modes limit what a user can select through the chat API, and who may own unattended flows. */
class RoleModesWebTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Fixtures.User ANN = new Fixtures.User("ann", false);

    @TempDir
    Path dir;

    private static Role role(String name, Map<String, JsonNode> settings) {
        return new Role(name, List.of("*"), false, "ask", Map.of(), settings);
    }

    private AgentHarness harness(Role role) {
        ModelClient model = new ModelClient() {
            @Override
            public ModelResponse complete(ModelRequest request) {
                var content = MAPPER.createArrayNode();
                content.addObject().put("type", "text").put("text", "ok");
                return new ModelResponse(content, "end_turn", new Usage(1, 1, 0, 0));
            }

            @Override
            public String simpleComplete(String system, String user, int maxTokens) {
                return "";
            }
        };
        return new AgentHarnessBuilder().modelClient(model)
                .sandbox(new LocalSandboxExecutor(new ChatDirLayout(dir)))
                .sessionStore(new InMemoryChatSessionStore()).objectMapper(MAPPER)
                .userPolicyResolver(p -> role.policy())
                .principalDirectory(id -> new org.kendar.llm.harness.auth.HarnessPrincipal(id, Set.of(role.name()), Map.of()))
                .build();
    }

    @Test
    void aNonAdminCannotSwitchToAModeTheirRoleForbids() throws Exception {
        Role limited = role("limited", Map.of(Role.ALLOWED_MODES, MAPPER.readTree("[\"default\",\"plan\"]")));
        AgentHarness harness = harness(limited);
        ChatApi chat = Fixtures.chatApi(harness);
        String id = (String) chat.create(Map.of(), ANN).get("chatId");

        assertEquals("PLAN", chat.mode(id, Map.of("mode", "plan")).get("mode"));
        var e = assertThrows(ApiException.class, () -> chat.mode(id, Map.of("mode", "auto")));
        assertEquals(403, e.status());
        assertThrows(ApiException.class, () -> chat.mode(id, Map.of("mode", "edit")));
        assertEquals("PLAN", harness.sessions().get(id).mode(), "unchanged");
        assertTrue(harness.commands().execute(id, "/auto").output().contains("not allowed"));
        assertEquals("PLAN", harness.sessions().get(id).mode());
    }

    @Test
    void aRoleWithoutTheListAllowsEveryMode() {
        AgentHarness harness = harness(role("free", Map.of()));
        ChatApi chat = Fixtures.chatApi(harness);
        String id = (String) chat.create(Map.of(), ANN).get("chatId");
        assertEquals("AUTO", chat.mode(id, Map.of("mode", "auto")).get("mode"));
    }

    @Test
    void thePolicyOfAUserCarriesTheRoleLimits() throws Exception {
        Role r = role("night", Map.of(Role.UNATTENDED, MAPPER.readTree("true"),
                Role.ALLOWED_MODES, MAPPER.readTree("[\"default\"]")));
        var user = new org.kendar.llm.harness.identity.User("ann", "Ann", "night", Map.of());
        var p = Permissions.policyOf(r, user, null, org.kendar.llm.harness.settings.InstructionRepository.NONE);
        assertTrue(p.unattended());
        assertFalse(p.allowsMode(org.kendar.llm.harness.permission.PermissionMode.AUTO));
    }

    @Test
    void onlyRolesWithTheFlagMayOwnUnattendedFlows() throws Exception {
        var roles = new java.util.concurrent.ConcurrentHashMap<String, Role>();
        roles.put("night", role("night", Map.of(Role.UNATTENDED, MAPPER.readTree("true"))));
        roles.put("day", role("day", Map.of()));
        var repo = new org.kendar.llm.harness.identity.RoleRepository() {
            public java.util.Optional<Role> find(String n) {
                return java.util.Optional.ofNullable(roles.get(n));
            }

            public List<Role> all() {
                return List.copyOf(roles.values());
            }

            public void save(Role r) {
                roles.put(r.name(), r);
            }

            public boolean delete(String n) {
                return roles.remove(n) != null;
            }
        };
        Permissions permissions = new Permissions(repo);
        assertTrue(permissions.mayRunUnattended(Set.of("night")));
        assertFalse(permissions.mayRunUnattended(Set.of("day")));
        assertFalse(permissions.mayRunUnattended(Set.of()));
        assertFalse(permissions.mayRunUnattended(Set.of("unknown")));
    }

    @Test
    void theWebHasNoWayToSkipPermissions() {
        for (var c : JllmProperties.class.getRecordComponents()) {
            assertFalse(c.getName().toLowerCase().contains("skip"), c.getName());
        }
        for (var c : JllmProperties.Web.class.getRecordComponents()) {
            assertFalse(c.getName().toLowerCase().contains("skip"), c.getName());
        }
        for (var m : HarnessFactory.class.getDeclaredMethods()) {
            assertFalse(m.getName().toLowerCase().contains("skip"), m.getName());
        }
        for (var c : org.kendar.llm.harness.identity.Role.class.getRecordComponents()) {
            assertFalse(c.getName().toLowerCase().contains("skip"), c.getName());
        }
        assertEquals(0, java.util.Arrays.stream(org.kendar.llm.harness.identity.HarnessIdentity.class.getMethods())
                .filter(m -> m.getName().toLowerCase().contains("skip")).count());
    }
}
