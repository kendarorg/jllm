package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.web.WebauthFixture.json;
import static org.kendar.llm.web.WebauthFixture.tool;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminControllerTest {

    @TempDir
    Path dir;

    @Test
    void onlyAdminsReachTheAdminApi() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie rita = f.signIn("rita");
        for (String path : List.of("/api/admin/users", "/api/admin/roles", "/api/admin/catalog", "/api/admin/skills")) {
            f.mvc.perform(get(path)).andExpect(status().isUnauthorized());
            f.get(rita, path).andExpect(status().isForbidden());
            f.get(f.signIn("admin"), path).andExpect(status().isOk());
        }
        f.send(rita, "POST", "/api/admin/users", Map.of("id", "eve", "role", "admin")).andExpect(status().isForbidden());
        assertFalse(f.users.exists("eve"));
    }

    @Test
    void emailsAreUnique() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie admin = f.signIn("admin");
        f.send(admin, "PUT", "/api/admin/users/rita", Map.of("email", "rita@example.com")).andExpect(status().isOk());
        f.send(admin, "PUT", "/api/admin/users/rita", Map.of("email", "Rita@Example.com")).andExpect(status().isOk());
        f.send(admin, "PUT", "/api/admin/users/gus", Map.of("email", "RITA@example.com")).andExpect(status().isConflict());
        f.send(admin, "POST", "/api/admin/users", Map.of("id", "eve", "role", "guest", "email", "rita@example.com"))
                .andExpect(status().isConflict());
        assertFalse(f.users.exists("eve"));
        assertNull(f.users.find("gus").orElseThrow().email());
        f.send(admin, "PUT", "/api/admin/users/rita", Map.of("email", "")).andExpect(status().isOk());
        f.send(admin, "PUT", "/api/admin/users/gus", Map.of("email", "rita@example.com")).andExpect(status().isOk());
    }

    @Test
    void usersAreCreatedEditedResetAndDeleted() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie admin = f.signIn("admin");
        f.send(admin, "POST", "/api/admin/users", Map.of("id", "nora", "name", "Nora", "role", "guest"))
                .andExpect(status().isCreated());
        f.signIn("nora"); // default password
        f.send(admin, "POST", "/api/admin/users", Map.of("id", "nora", "role", "guest")).andExpect(status().isConflict());
        f.send(admin, "POST", "/api/admin/users", Map.of("id", "x", "role", "wizard")).andExpect(status().isBadRequest());
        f.send(admin, "POST", "/api/admin/users", Map.of("id", "../x", "role", "guest")).andExpect(status().isBadRequest());

        // credentials and own settings survive an edit
        Files.writeString(dir.resolve("users/nora.json"), "{\"name\":\"Nora\",\"role\":\"guest\",\"credentials\":"
                + "{\"mcp:docs\":{\"Authorization\":\"env:X\"}},\"disabledTools\":[\"web_fetch\"]}");
        f.send(admin, "PUT", "/api/admin/users/nora", Map.of("role", "reader", "name", "N")).andExpect(status().isOk());
        var nora = f.users.find("nora").orElseThrow();
        assertEquals("reader", nora.role());
        assertEquals("N", nora.name());
        assertEquals("env:X", nora.credentials().get("mcp:docs").get("Authorization"));
        assertEquals(List.of("web_fetch"), nora.disabledTools());

        Cookie noraSession = f.signIn("nora");
        f.send(admin, "POST", "/api/admin/users/nora/password", Map.of("password", "set by admin"))
                .andExpect(status().isNoContent());
        f.get(noraSession, "/api/chat").andExpect(status().isUnauthorized());
        f.signIn("nora", "set by admin");
        f.send(admin, "POST", "/api/admin/users/nora/password", Map.of()).andExpect(status().isNoContent());
        f.signIn("nora");

        f.skills.writeOwn("nora", "mine", "", "m");
        f.send(admin, "DELETE", "/api/admin/users/nora", null).andExpect(status().isNoContent());
        assertFalse(f.users.exists("nora"));
        assertTrue(f.skills.own("nora").isEmpty(), "own skills are removed with the user");
    }

    @Test
    void theLastAdminAndTheCallerAreProtected() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie admin = f.signIn("admin");
        f.send(admin, "DELETE", "/api/admin/users/admin", null).andExpect(status().isConflict());
        f.send(admin, "PUT", "/api/admin/users/admin", Map.of("role", "guest")).andExpect(status().isConflict());

        f.user("boss", "admin");
        f.send(admin, "PUT", "/api/admin/users/boss", Map.of("role", "guest")).andExpect(status().isOk());
        f.user("boss", "admin");
        f.send(admin, "DELETE", "/api/admin/users/admin", null).andExpect(status().isConflict()); // self
        f.send(f.signIn("boss"), "DELETE", "/api/admin/users/admin", null).andExpect(status().isNoContent());
    }

    @Test
    void rolesAreManagedAndTheAdminRoleIsFixed() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie admin = f.signIn("admin");
        JsonNode roles = json(f.get(admin, "/api/admin/roles"));
        assertEquals("admin", roles.get(0).path("name").asText());
        assertTrue(roles.get(0).path("fixed").asBoolean());

        f.send(admin, "POST", "/api/admin/roles", Map.of("name", "admin", "tools", List.of())).andExpect(status().isConflict());
        // every tool stays whatever is sent; only the approval is stored
        f.send(admin, "PUT", "/api/admin/roles/admin", Map.of("tools", List.of(), "approval", "off")).andExpect(status().isOk());
        assertEquals("off", f.permissions.role("admin").orElseThrow().approval());
        assertTrue(f.permissions.role("admin").orElseThrow().allowsName("anything"));
        f.send(admin, "PUT", "/api/admin/roles/admin", Map.of("approval", "ask")).andExpect(status().isOk());
        f.send(admin, "DELETE", "/api/admin/roles/admin", null).andExpect(status().isForbidden());

        f.send(admin, "POST", "/api/admin/roles", Map.of("name", "docs", "tools", List.of("mcp__docs__*", "grep"),
                "approval", "off")).andExpect(status().isCreated());
        f.send(admin, "POST", "/api/admin/roles", Map.of("name", "docs", "tools", List.of())).andExpect(status().isConflict());
        f.send(admin, "POST", "/api/admin/roles", Map.of("name", "bad", "tools", List.of(), "approval", "maybe"))
                .andExpect(status().isBadRequest());
        var docs = f.permissions.role("docs").orElseThrow();
        assertTrue(docs.allows(tool("mcp__docs__search", ToolKind.OTHER)));
        assertFalse(docs.allows(tool("mcp__other__x", ToolKind.OTHER)));
        assertTrue(docs.allows(tool("grep", ToolKind.READ_ONLY)));

        f.send(admin, "PUT", "/api/admin/roles/docs", Map.of("tools", List.of("grep"), "readOnly", true))
                .andExpect(status().isOk());
        assertFalse(f.permissions.role("docs").orElseThrow().allows(tool("mcp__docs__search", ToolKind.OTHER)));

        f.user("dora", "docs");
        String refusal = f.send(admin, "DELETE", "/api/admin/roles/docs", null).andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        assertTrue(refusal.contains("dora"), "the message names the users: " + refusal);
        f.send(admin, "POST", "/api/admin/users", Map.of("id", "ghosty", "role", "nosuchrole")).andExpect(status().isBadRequest());
        f.send(admin, "PUT", "/api/admin/users/dora", Map.of("role", "nosuchrole")).andExpect(status().isBadRequest());
        assertThrows(org.kendar.llm.harness.identity.UnknownRoleException.class,
                () -> f.users.create("ghosty", "G", "nosuchrole", null, null));
        f.users.delete("dora");
        f.send(admin, "DELETE", "/api/admin/roles/docs", null).andExpect(status().isNoContent());
        assertTrue(f.permissions.role("docs").isEmpty());
    }

    @Test
    void roleSettingsAreManagedPerFeatureAndUnknownOnesAreKept() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie admin = f.signIn("admin");
        f.send(admin, "POST", "/api/admin/roles", Map.of("name", "ops", "tools", List.of("*"),
                "settings", Map.of("idp", Map.of("groups", List.of("g1"))),
                "sandbox", Map.of("kind", "DOCKER-ISOLATED", "network", "None"))).andExpect(status().isCreated());
        JsonNode s = json(f.get(admin, "/api/admin/roles/ops/settings").andExpect(status().isOk()));
        assertEquals("g1", s.path("idp").path("groups").path(0).asText());
        assertEquals("docker-isolated", s.path("sandbox").path("kind").asText(), "the sandbox is stored normalized");
        assertEquals("none", s.path("sandbox").path("network").asText());

        // a save without settings keeps them; a save with a sandbox only replaces that key
        f.send(admin, "PUT", "/api/admin/roles/ops", Map.of("tools", List.of("grep"))).andExpect(status().isOk());
        f.send(admin, "PUT", "/api/admin/roles/ops", Map.of("tools", List.of("grep"), "sandbox", Map.of("kind", "local")))
                .andExpect(status().isOk());
        var ops = f.permissions.role("ops").orElseThrow();
        assertEquals("g1", ops.setting("idp").path("groups").path(0).asText());
        assertEquals(org.kendar.llm.harness.sandbox.SandboxKind.LOCAL, ops.sandbox().kind());
        JsonNode row = null;
        for (JsonNode r : json(f.get(admin, "/api/admin/roles"))) if ("ops".equals(r.path("name").asText())) row = r;
        assertNotNull(row);
        assertEquals("g1", row.path("settings").path("idp").path("groups").path(0).asText());
        assertEquals("local", row.path("sandbox").path("kind").asText());

        // one feature at a time
        s = json(f.send(admin, "PUT", "/api/admin/roles/ops/settings/future", Map.of("a", List.of(1, 2)))
                .andExpect(status().isOk()));
        assertEquals(2, s.path("future").path("a").path(1).asInt());
        assertEquals("g1", s.path("idp").path("groups").path(0).asText());
        assertEquals("local", s.path("sandbox").path("kind").asText());
        f.send(admin, "PUT", "/api/admin/roles/ops/settings/sandbox", Map.of("kind", "spaceship"))
                .andExpect(status().isBadRequest());
        f.send(admin, "PUT", "/api/admin/roles/ops/settings/sandbox", List.of(1)).andExpect(status().isBadRequest());
        f.send(admin, "PUT", "/api/admin/roles/ops", Map.of("tools", List.of("grep"), "sandbox", Map.of("kind", "x")))
                .andExpect(status().isBadRequest());
        f.send(admin, "PUT", "/api/admin/roles/ops", Map.of("tools", List.of("grep"), "settings", List.of()))
                .andExpect(status().isBadRequest());
        f.send(admin, "PUT", "/api/admin/roles/ops/settings/tools", Map.of()).andExpect(status().isBadRequest());
        f.send(admin, "PUT", "/api/admin/roles/ops/settings/Bad", Map.of()).andExpect(status().isBadRequest());
        assertEquals(org.kendar.llm.harness.sandbox.SandboxKind.LOCAL, f.permissions.role("ops").orElseThrow().sandbox().kind(),
                "a refused change stores nothing");

        s = json(f.send(admin, "DELETE", "/api/admin/roles/ops/settings/idp", null).andExpect(status().isOk()));
        assertFalse(s.has("idp"));
        assertTrue(s.has("future"));
        f.send(admin, "DELETE", "/api/admin/roles/ops/settings/idp", null).andExpect(status().isNotFound());
        f.get(admin, "/api/admin/roles/ghost/settings").andExpect(status().isNotFound());
        f.send(admin, "PUT", "/api/admin/roles/ghost/settings/idp", Map.of()).andExpect(status().isNotFound());
        f.send(admin, "DELETE", "/api/admin/roles/ghost/settings/idp", null).andExpect(status().isNotFound());

        // the admin role keeps its settings too
        f.send(admin, "PUT", "/api/admin/roles/admin/settings/idp", Map.of("groups", List.of("ops"), "priority", 1))
                .andExpect(status().isOk());
        assertEquals(1, f.permissions.role("admin").orElseThrow().setting("idp").path("priority").asInt());
        assertTrue(f.permissions.role("admin").orElseThrow().allowsName("anything"));
        assertEquals(1, json(f.get(admin, "/api/admin/roles")).get(0).path("settings").path("idp").path("priority").asInt());
    }

    @Test
    void onlyAdminsReachTheRoleSettings() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie rita = f.signIn("rita");
        f.mvc.perform(get("/api/admin/roles/admin/settings")).andExpect(status().isUnauthorized());
        f.get(rita, "/api/admin/roles/admin/settings").andExpect(status().isForbidden());
        f.send(rita, "PUT", "/api/admin/roles/admin/settings/idp", Map.of("x", 1)).andExpect(status().isForbidden());
        f.send(rita, "DELETE", "/api/admin/roles/admin/settings/idp", null).andExpect(status().isForbidden());
        assertNull(f.permissions.role("admin").orElseThrow().setting("idp"));
    }

    @Test
    void adminRoleAlwaysHasEveryToolWhateverItsFile() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Files.writeString(dir.resolve("permissions/admin.json"), "{\"tools\":[]}");
        var p = f.permissions.forRole("admin").orElseThrow();
        assertTrue(p.allows(tool("write_file", ToolKind.FILE_EDIT)));
        assertNull(p.approval(), "admin is asked for approvals unless its file says off");
        Files.writeString(dir.resolve("permissions/admin.json"), "{\"tools\":[],\"approval\":\"off\"}");
        assertFalse(f.permissions.forRole("admin").orElseThrow().approval()
                .requiresApproval(tool("write_file", ToolKind.FILE_EDIT)));
    }

    @Test
    void catalogListsToolsAndMcpServersEvenDisabledOnes() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        JsonNode cat = json(f.get(f.signIn("admin"), "/api/admin/catalog"));
        List<String> names = new ArrayList<>();
        cat.path("tools").forEach(t -> names.add(t.path("name").asText()));
        assertTrue(names.containsAll(List.of("read_file", "grep", "write_file", "web_fetch", "skill")), names.toString());
        JsonNode docs = cat.path("mcp").get(0);
        assertEquals("docs", docs.path("name").asText());
        assertFalse(docs.path("enabled").asBoolean());
        assertEquals("mcp__docs__*", docs.path("pattern").asText());
    }

    @Test
    void theCatalogEntryOfRunJsCarriesTheEngineStatusAndOthersHaveNone() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        JsonNode cat = json(f.get(f.signIn("admin"), "/api/admin/catalog"));
        JsonNode runJs = null;
        JsonNode readFile = null;
        for (JsonNode t : cat.path("tools")) {
            if (t.path("name").asText().equals("run_js")) runJs = t;
            if (t.path("name").asText().equals("read_file")) readFile = t;
        }

        assertNotNull(runJs);
        assertEquals("edition community, profile isolated, isolate available", runJs.path("status").asText());
        assertNotNull(readFile);
        assertTrue(readFile.path("status").isNull(), "a tool with nothing to report has no status");
    }

    @Test
    void globalSkillsReachEveryUserAndShadowOwnOnes() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie admin = f.signIn("admin");
        f.skills.writeOwn("rita", "style", "", "rita's style");

        f.send(admin, "POST", "/api/admin/skills", Map.of("name", "pdf", "content", "x")).andExpect(status().isConflict());
        JsonNode created = json(f.send(admin, "POST", "/api/admin/skills",
                Map.of("name", "style", "description", "House style", "content", "Use British spelling."))
                .andExpect(status().isCreated()));
        assertEquals(List.of("rita"), List.of(created.path("shadows").get(0).asText()));

        var rita = f.permissions.resolver(f.users, f.skills).resolve(f.users.find("rita").orElseThrow().principal());
        assertEquals(1, rita.skills().size());
        assertEquals("Use British spelling.", rita.skills().get(0).text().read(), "the global one wins");
        JsonNode list = json(f.get(f.signIn("rita"), "/api/me/skills")).path("skills");
        boolean shadowed = false;
        for (JsonNode s : list) if (s.path("source").asText().equals("own")) shadowed = s.path("shadowed").asBoolean();
        assertTrue(shadowed);

        f.send(admin, "PUT", "/api/admin/skills/style", Map.of("description", "d", "content", "Changed."))
                .andExpect(status().isOk());
        assertEquals("Changed.", json(f.get(admin, "/api/admin/skills/style")).path("content").asText());
        f.send(admin, "PUT", "/api/admin/skills/pdf", Map.of("content", "x")).andExpect(status().isForbidden());
        f.send(admin, "DELETE", "/api/admin/skills/pdf", null).andExpect(status().isForbidden());
        f.send(admin, "DELETE", "/api/admin/skills/style", null).andExpect(status().isNoContent());
        assertTrue(f.skills.global().isEmpty());
    }
}
