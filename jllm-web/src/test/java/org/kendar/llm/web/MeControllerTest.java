package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.auth.UserSkill;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.web.auth.PasswordHasher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.web.WebauthFixture.json;
import static org.kendar.llm.web.WebauthFixture.tool;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MeControllerTest {

    @TempDir
    Path dir;

    private static UserPolicy policy(WebauthFixture f, String id) {
        return f.permissions.resolver(f.users, f.skills).resolve(f.users.find(id).orElseThrow().principal());
    }

    private static List<String> skillNames(UserPolicy p) {
        return p.skills().stream().map(UserSkill::name).toList();
    }

    // ---- password

    @Test
    void changingThePasswordKeepsThisSessionAndEndsTheOthers() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie here = f.signIn("rita");
        Cookie elsewhere = f.signIn("rita");
        String old = PasswordHasher.defaultPassword("rita");

        f.send(here, "POST", "/api/me/password", Map.of("current", old, "next", "a new secret"))
                .andExpect(status().isNoContent());

        f.get(here, "/api/chat").andExpect(status().isOk());
        f.get(elsewhere, "/api/chat").andExpect(status().isUnauthorized());
        f.login("rita", old).andExpect(status().isUnauthorized());
        Cookie again = f.signIn("rita", "a new secret");
        assertFalse(json(f.get(again, "/api/me")).path("defaultPassword").asBoolean());
        assertTrue(f.users.find("rita").orElseThrow().password().startsWith("pbkdf2$"));
    }

    @Test
    void passwordChangeIsChecked() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");
        String old = PasswordHasher.defaultPassword("rita");
        f.send(c, "POST", "/api/me/password", Map.of("current", "wrong", "next", "a new secret"))
                .andExpect(status().isUnauthorized());
        f.send(c, "POST", "/api/me/password", Map.of("current", old, "next", "short"))
                .andExpect(status().isBadRequest());
        f.send(c, "POST", "/api/me/password", Map.of("current", old, "next", old))
                .andExpect(status().isBadRequest());
        f.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/me/password")
                .contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    }

    // ---- tools

    @Test
    void toolsListOnlyWhatTheRoleAllows() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        JsonNode cat = json(f.get(f.signIn("rita"), "/api/me/tools").andExpect(status().isOk()));
        List<String> names = new ArrayList<>();
        cat.path("tools").forEach(t -> names.add(t.path("name").asText()));
        assertTrue(names.containsAll(List.of("read_file", "grep", "skill")), names.toString());
        assertFalse(names.contains("write_file"));
        assertFalse(names.contains("web_fetch"));
        assertEquals("docs", cat.path("mcp").get(0).path("name").asText(), "role grants mcp__docs__*");
        assertEquals("mcp__docs__*", cat.path("mcp").get(0).path("pattern").asText());

        JsonNode guest = json(f.get(f.signIn("gus"), "/api/me/tools"));
        assertEquals(0, guest.path("mcp").size(), "guest has no MCP grant");
    }

    @Test
    void disablingNarrowsTheRoleAndEnablingCannotWidenIt() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");
        assertTrue(policy(f, "rita").allows(tool("grep", ToolKind.READ_ONLY)));

        f.send(c, "PUT", "/api/me/tools", Map.of("disabled", List.of("grep", "write_file", "mcp__docs__*", "zzz")))
                .andExpect(status().isNoContent());

        assertEquals(List.of("grep", "mcp__docs__*"), f.users.find("rita").orElseThrow().disabledTools(),
                "entries outside the role are dropped");
        UserPolicy p = policy(f, "rita");
        assertFalse(p.allows(tool("grep", ToolKind.READ_ONLY)));
        assertFalse(p.allows(tool("mcp__docs__search", ToolKind.READ_ONLY)));
        assertTrue(p.allows(tool("read_file", ToolKind.READ_ONLY)));
        assertFalse(p.allows(tool("write_file", ToolKind.FILE_EDIT)), "never allowed by the role");
        JsonNode cat = json(f.get(c, "/api/me/tools"));
        cat.path("tools").forEach(t -> assertEquals(!t.path("name").asText().equals("grep"),
                t.path("enabled").asBoolean(), t.toString()));
        assertFalse(cat.path("mcp").get(0).path("on").asBoolean());

        f.send(c, "PUT", "/api/me/tools", Map.of("disabled", List.of())).andExpect(status().isNoContent());
        assertTrue(policy(f, "rita").allows(tool("grep", ToolKind.READ_ONLY)));
        assertFalse(policy(f, "rita").allows(tool("write_file", ToolKind.FILE_EDIT)));
    }

    // ---- skills

    @Test
    void ownSkillsAreCreatedEditedAndDeleted() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");
        f.send(c, "POST", "/api/me/skills", Map.of("name", "notes", "description", "My notes", "content", "Write notes."))
                .andExpect(status().isCreated());
        assertEquals(List.of("notes"), skillNames(policy(f, "rita")));
        assertTrue(skillNames(policy(f, "gus")).isEmpty(), "own skills are private");
        assertEquals("Write notes.", json(f.get(c, "/api/me/skills/notes")).path("content").asText());
        f.get(f.signIn("gus"), "/api/me/skills/notes").andExpect(status().isNotFound());

        f.send(c, "PUT", "/api/me/skills/notes", Map.of("description", "d", "content", "Changed."))
                .andExpect(status().isOk());
        assertEquals("Changed.", json(f.get(c, "/api/me/skills/notes")).path("content").asText());

        f.send(c, "DELETE", "/api/me/skills/notes", null).andExpect(status().isNoContent());
        assertTrue(skillNames(policy(f, "rita")).isEmpty());
    }

    @Test
    void ownSkillsAreChecked() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");
        f.send(c, "POST", "/api/me/skills", Map.of("name", "pdf", "content", "x")).andExpect(status().isConflict());
        f.skills.writeGlobal("house", "House rules", "Be nice.");
        f.send(c, "POST", "/api/me/skills", Map.of("name", "house", "content", "x")).andExpect(status().isConflict());
        f.send(c, "POST", "/api/me/skills", Map.of("name", "Bad Name", "content", "x")).andExpect(status().isBadRequest());
        f.send(c, "POST", "/api/me/skills", Map.of("name", "big", "content", "x".repeat(70_000)))
                .andExpect(status().isPayloadTooLarge());
        f.send(c, "PUT", "/api/me/skills/house", Map.of("content", "x")).andExpect(status().isForbidden());
        f.send(c, "DELETE", "/api/me/skills/pdf", null).andExpect(status().isForbidden());
        // the guest role has no skill tool: read-only
        Cookie g = f.signIn("gus");
        assertFalse(json(f.get(g, "/api/me/skills")).path("usable").asBoolean());
        f.send(g, "POST", "/api/me/skills", Map.of("name", "mine", "content", "x")).andExpect(status().isForbidden());
    }

    @Test
    void anySkillCanBeSwitchedOffForTheWholeUser() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");
        f.skills.writeGlobal("house", "House rules", "Be nice.");
        f.send(c, "POST", "/api/me/skills", Map.of("name", "notes", "content", "n")).andExpect(status().isCreated());
        UserPolicy before = policy(f, "rita");
        assertEquals(List.of("house", "notes"), skillNames(before));
        assertTrue(before.keepsSkill("pdf"));

        f.send(c, "PUT", "/api/me/skills/enabled", Map.of("disabled", List.of("pdf", "house", "notes", "nope")))
                .andExpect(status().isNoContent());

        UserPolicy after = policy(f, "rita");
        assertTrue(skillNames(after).isEmpty());
        assertFalse(after.keepsSkill("pdf"));
        assertEquals(List.of("pdf", "house", "notes"), f.users.find("rita").orElseThrow().disabledSkills());
        JsonNode list = json(f.get(c, "/api/me/skills")).path("skills");
        list.forEach(s -> assertFalse(s.path("enabled").asBoolean(), s.toString()));
        // another user is unaffected
        assertEquals(List.of("house"), skillNames(policy(f, "admin")));
        assertTrue(policy(f, "admin").keepsSkill("pdf"));
    }
}
