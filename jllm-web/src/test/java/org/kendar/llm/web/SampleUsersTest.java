package org.kendar.llm.web;

import org.kendar.llm.web.users.FileUserRepository;
import org.kendar.llm.web.users.FileRoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.web.commons.users.UserCredentialResolver;
import org.kendar.llm.web.commons.users.Permissions;
import org.kendar.llm.harness.identity.UserRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SampleUsersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static SimpleTool tool(String name, ToolKind kind) {
        return new SimpleTool(name, name, new ObjectMapper().createObjectNode(), "g", kind, (c, i) -> "");
    }

    /** The shipped permission files, copied into a temp folder so the test reads what the sample ships. */
    private static Permissions shipped(Path dir) throws Exception {
        Path src = Path.of("..", "runConfigs", "unsafe", "webauth", "permissions");
        if (!Files.isDirectory(src)) src = Path.of("runConfigs", "unsafe", "webauth", "permissions");
        for (String role : List.of("guest", "reader", "poweru", "admin")) {
            Files.copy(src.resolve(role + ".json"), dir.resolve(role + ".json"));
        }
        return new Permissions(new FileRoleRepository(dir, new ObjectMapper()));
    }

    private static void user(Path dir, String id, String role, String credentials) throws Exception {
        Files.writeString(dir.resolve(id + ".json"),
                "{\"name\":\"" + id + "\",\"role\":\"" + role + "\",\"credentials\":" + credentials + "}");
    }

    @Test
    void guestReachesFilesOnlyThroughRunJsAndWeb(@TempDir Path dir) throws Exception {
        UserPolicy guest = shipped(dir).forRole("guest").orElseThrow();
        assertTrue(guest.allows(tool("run_js", ToolKind.OTHER)));
        assertTrue(guest.allows(tool("web_fetch", ToolKind.READ_ONLY)));
        assertFalse(guest.allows(tool("bash", ToolKind.OTHER)));
        assertEquals(org.kendar.llm.harness.permission.ToolPermission.DENY, guest.permissionOf(tool("bash", ToolKind.OTHER)));
        assertFalse(guest.allows(tool("read_file", ToolKind.READ_ONLY)));
        assertFalse(guest.allows(tool("write_file", ToolKind.FILE_EDIT)));
    }

    @Test
    void readerHasReadOnlyFileToolsOnly(@TempDir Path dir) throws Exception {
        UserPolicy reader = shipped(dir).forRole("reader").orElseThrow();
        assertTrue(reader.allows(tool("read_file", ToolKind.READ_ONLY)));
        assertTrue(reader.allows(tool("grep", ToolKind.READ_ONLY)));
        assertFalse(reader.allows(tool("write_file", ToolKind.FILE_EDIT)));
        assertFalse(reader.allows(tool("edit_file", ToolKind.FILE_EDIT)));
        assertTrue(reader.allows(tool("run_js", ToolKind.OTHER)));
        assertFalse(reader.allows(tool("bash", ToolKind.OTHER)));
        assertEquals(org.kendar.llm.harness.permission.ToolPermission.DENY, reader.permissionOf(tool("bash", ToolKind.OTHER)));
        assertFalse(reader.allows(tool("web_fetch", ToolKind.READ_ONLY)));
    }

    @Test
    void powerUserRunsBothJsAndBash(@TempDir Path dir) throws Exception {
        UserPolicy p = shipped(dir).forRole("poweru").orElseThrow();
        assertTrue(p.allows(tool("run_js", ToolKind.OTHER)));
        assertTrue(p.allows(tool("bash", ToolKind.OTHER)));
        assertTrue(p.allows(tool("write_file", ToolKind.FILE_EDIT)));
        assertFalse(p.allows(tool("anything", ToolKind.OTHER)));
    }

    @Test
    void adminHasEveryToolWithoutApprovalPrompts(@TempDir Path dir) throws Exception {
        UserPolicy admin = shipped(dir).forRole("admin").orElseThrow();
        assertTrue(admin.allows(tool("write_file", ToolKind.FILE_EDIT)));
        assertTrue(admin.allows(tool("anything", ToolKind.OTHER)));
        assertFalse(admin.approval().requiresApproval(tool("write_file", ToolKind.FILE_EDIT)));
    }

    @Test
    void userSettingsAreStoredAsAJsonObjectInTheUserFile(@TempDir Path dir) {
        ObjectMapper mapper = new ObjectMapper();
        UserRepository users = new FileUserRepository(dir, mapper, new FileRoleRepository(dir.resolve("permissions"), mapper));
        users.create("amy", "Amy", "admin", null, null);
        assertEquals("{}", users.find("amy").orElseThrow().settings());
        assertEquals("{\"k\":{\"a\":1}}", users.setSettings("amy", "{\"k\": {\"a\": 1}}").settings());
        assertEquals("{\"k\":{\"a\":1}}", users.find("amy").orElseThrow().settings());
        assertThrows(IllegalArgumentException.class, () -> users.setSettings("amy", "\"text\""));
        assertEquals("{}", users.setSettings("amy", "").settings());
    }

    @Test
    void roleSandboxIsStoredInTheRoleFileAndDefaultsToNone(@TempDir Path dir) {
        Permissions perms = new Permissions(new FileRoleRepository(dir, new ObjectMapper()));
        perms.save(new org.kendar.llm.harness.identity.Role("ops", List.of("*"), false, "ask", Map.of(),
                Map.of("sandbox", org.kendar.llm.harness.identity.RoleSandbox.of("docker-isolated", null, "default", false).toJson())));
        var sb = perms.role("ops").orElseThrow().sandbox();
        assertEquals(org.kendar.llm.harness.sandbox.SandboxKind.DOCKER_ISOLATED, sb.kind());
        assertEquals("hardened", sb.profile());
        assertNull(sb.scope());
        assertEquals("default", sb.network());
        perms.save(new org.kendar.llm.harness.identity.Role("plain", List.of("*"), false, "ask", Map.of(), null));
        assertEquals(org.kendar.llm.harness.sandbox.SandboxKind.NONE, perms.role("plain").orElseThrow().sandbox().kind());
        assertEquals(org.kendar.llm.harness.sandbox.SandboxKind.NONE, perms.role("admin").orElseThrow().sandbox().kind());
    }

    @Test
    void roleSettingsAreTopLevelKeysOfTheRoleFileAndUnknownOnesSurviveASave(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ops.json"), "{\"tools\":[\"*\"],\"approval\":\"off\","
                + "\"sandbox\":{\"kind\":\"shared-local\"},\"idp\":{\"groups\":[\"g1\"],\"deep\":{\"x\":1}},\"flag\":true,"
                + "\"Bad Key\":1,\"name\":\"ignored\"}");
        FileRoleRepository repo = new FileRoleRepository(dir, mapper);
        var ops = repo.find("ops").orElseThrow();
        assertEquals(List.of("sandbox", "idp", "flag"), List.copyOf(ops.settings().keySet()),
                "invalid and reserved keys are not settings");
        assertEquals(org.kendar.llm.harness.sandbox.SandboxKind.SHARED_LOCAL, ops.sandbox().kind());
        assertEquals("off", ops.approval());

        repo.save(ops.withSetting("sandbox", org.kendar.llm.harness.identity.RoleSandbox.of("docker-open", "user", null, false).toJson()));
        var saved = repo.find("ops").orElseThrow();
        assertEquals(ops.setting("idp"), saved.setting("idp"));
        assertTrue(saved.setting("flag").asBoolean());
        assertEquals("user", saved.sandbox().scope());
        var file = mapper.readTree(dir.resolve("ops.json").toFile());
        assertEquals("g1", file.path("idp").path("groups").path(0).asText(), "settings stay top-level keys of the file");
        assertEquals("docker-open", file.path("sandbox").path("kind").asText());

        repo.save(saved.withSetting("idp", null));
        assertFalse(mapper.readTree(dir.resolve("ops.json").toFile()).has("idp"), "a removed setting leaves the file");
        assertEquals(List.of("sandbox", "flag"), List.copyOf(repo.find("ops").orElseThrow().settings().keySet()));
    }

    @Test
    void roleFileWithSettingsOverTheCapsKeepsTheRole(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("big.json"), "{\"tools\":[\"grep\"],\"blob\":\""
                + "x".repeat(org.kendar.llm.harness.identity.Role.MAX_SETTINGS_BYTES) + "\"}");
        var big = new FileRoleRepository(dir, mapper).find("big").orElseThrow();
        assertEquals(List.of("grep"), big.tools());
        assertTrue(big.settings().isEmpty());
    }

    @Test
    void toolPermissionsAreStoredAndDecideDenyThenAskThenAllow(@TempDir Path dir) throws Exception {
        Permissions perms = new Permissions(new FileRoleRepository(dir, new ObjectMapper()));
        perms.save(new org.kendar.llm.harness.identity.Role("ops", List.of("*"), false, "ask", Map.of(
                "send_*", org.kendar.llm.harness.permission.ToolPermission.ALLOW,
                "send_secret", org.kendar.llm.harness.permission.ToolPermission.DENY,
                "bash", org.kendar.llm.harness.permission.ToolPermission.ASK),
                Map.of()));
        UserPolicy p = perms.forRole("ops").orElseThrow();
        assertEquals(org.kendar.llm.harness.permission.ToolPermission.ALLOW, p.permissionOf(tool("send_mail", ToolKind.OTHER)));
        assertEquals(org.kendar.llm.harness.permission.ToolPermission.DENY, p.permissionOf(tool("send_secret", ToolKind.OTHER)));
        assertEquals(org.kendar.llm.harness.permission.ToolPermission.ASK, p.permissionOf(tool("bash", ToolKind.OTHER)));
        assertNull(p.permissionOf(tool("read_file", ToolKind.READ_ONLY)));
        assertEquals(3, perms.role("ops").orElseThrow().toolPermissions().size(), "reloaded from the file");
    }

    @Test
    void roleWithoutFileGetsNothing(@TempDir Path dir) throws Exception {
        Permissions perms = shipped(dir);
        assertTrue(perms.forRole("wizard").isEmpty());
        assertFalse(perms.resolver().resolve(HarnessPrincipal.of("x")).allows(tool("read_file", ToolKind.READ_ONLY)));
        Files.writeString(dir.resolve("broken.json"), "{\"tools\": 3}");
        assertTrue(perms.forRole("broken").isEmpty());
    }

    @Test
    void credentialsComeFromEnvironmentReferencesOnly(@TempDir Path dir) throws Exception {
        user(dir, "rita", "guest", "{\"mcp:docs\":{\"Authorization\":\"env:PATH\",\"X-Literal\":\"secret\"},"
                + "\"api:pets\":{\"X-Key\":\"env:JLLM_SAMPLE_UNSET_VARIABLE\"}}");
        UserCredentialResolver r = new UserCredentialResolver(new FileUserRepository(dir, mapper, new FileRoleRepository(dir.resolve("permissions"), mapper)));
        HarnessPrincipal rita = HarnessPrincipal.of("rita");

        Map<String, String> docs = r.headers(rita, "mcp:docs");
        assertEquals(System.getenv("PATH"), docs.get("Authorization"));
        assertFalse(docs.containsKey("X-Literal"));
        assertEquals(Map.of(), r.headers(rita, "api:pets"));
        assertEquals(Map.of(), r.headers(HarnessPrincipal.of("nobody"), "mcp:docs"));
        assertEquals(Map.of(), r.headers(null, "mcp:docs"));
    }

    @Test
    void unknownUserIdsAreRejected(@TempDir Path dir) throws Exception {
        user(dir, "rita", "guest", "{}");
        UserRepository users = new FileUserRepository(dir, mapper, new FileRoleRepository(dir.resolve("permissions"), mapper));
        assertTrue(users.find("rita").isPresent());
        assertTrue(users.find("../rita").isEmpty());
        assertTrue(users.find("Rita").isEmpty());
        assertEquals(1, users.all().size());
    }

    @Test
    void chatWithoutPrincipalGetsNoTools(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("reader.json"), "{\"tools\":[\"*\"]}");
        var resolver = new Permissions(new FileRoleRepository(dir, mapper)).resolver();
        var anyTool = tool("read_file", ToolKind.READ_ONLY);
        assertFalse(resolver.resolve(null).allows(anyTool));
        assertTrue(resolver.resolve(new HarnessPrincipal("u", Set.of("reader"), Map.of())).allows(anyTool));
        var identity = new org.kendar.llm.harness.auth.Identity(null, resolver, null, chat -> null);
        assertFalse(identity.policyOf("c").allows(anyTool));
    }
}
