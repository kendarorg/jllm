package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.web.commons.auth.RoleMappingSettings;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.web.WebauthFixture.json;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin API of the identity provider role mapping: the idp role setting, the global options, the preview. */
class RoleMappingAdminTest {

    @TempDir
    Path dir;

    @Test
    void idpSettingIsValidatedAndNormalized() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie admin = f.signIn("admin");
        String path = "/api/admin/roles/guest/settings/idp";
        for (Object bad : List.of("eng", List.of("eng"), Map.of("groups", "eng"), Map.of("groups", List.of(1)),
                Map.of("groups", List.of(" ")), Map.of("groups", List.of("x".repeat(257))),
                Map.of("groups", List.of("a"), "priority", "high"), Map.of("groups", List.of("a"), "priority", 1.5),
                Map.of("groups", List.of("a"), "extra", true))) {
            f.send(admin, "PUT", path, bad).andExpect(status().isBadRequest());
        }
        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i <= 100; i++) tooMany.add("g" + i);
        f.send(admin, "PUT", path, Map.of("groups", tooMany)).andExpect(status().isBadRequest());
        // through the role save too
        f.send(admin, "PUT", "/api/admin/roles/guest", Map.of("tools", List.of("web_fetch"),
                "settings", Map.of("idp", Map.of("groups", "eng")))).andExpect(status().isBadRequest());
        assertNull(f.permissions.role("guest").orElseThrow().setting("idp"));

        JsonNode saved = json(f.send(admin, "PUT", path, Map.of("groups", List.of(" eng ", "team-*", "eng"))));
        assertEquals("[\"eng\",\"team-*\"]", saved.path("idp").path("groups").toString());
        assertEquals(100, saved.path("idp").path("priority").asInt()); // the default
        // round trip through the store and the role list
        f.send(admin, "PUT", path, Map.of("groups", List.of("eng"), "priority", 7)).andExpect(status().isOk());
        JsonNode roles = json(f.get(admin, "/api/admin/roles"));
        JsonNode guest = null;
        for (JsonNode r : roles) if (r.path("name").asText().equals("guest")) guest = r;
        assertNotNull(guest);
        assertEquals(7, guest.path("settings").path("idp").path("priority").asInt());
        assertEquals(7, f.permissions.role("guest").orElseThrow().setting("idp").path("priority").asInt());
    }

    @Test
    void globalOptionsAreStoredResetAndValidated() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie admin = f.signIn("admin");
        f.get(f.signIn("rita"), "/api/admin/auth/role-mapping").andExpect(status().isForbidden());

        JsonNode initial = json(f.get(admin, "/api/admin/auth/role-mapping"));
        assertFalse(initial.path("enabled").asBoolean());
        assertEquals("groups", initial.path("claim").asText());
        assertEquals("default", initial.path("onNoMatch").asText());
        assertFalse(initial.path("allowAdmin").asBoolean());
        assertTrue(initial.path("maxAge").isNull());
        assertFalse(initial.path("stored").asBoolean());
        assertTrue(initial.path("editable").asBoolean());
        assertEquals("reader", initial.path("defaultRole").asText());

        JsonNode put = json(f.send(admin, "PUT", "/api/admin/auth/role-mapping", Map.of("enabled", true,
                "claim", "realm_access.roles", "onNoMatch", "refuse", "allowAdmin", true, "maxAge", "480m")));
        assertTrue(put.path("enabled").asBoolean());
        assertEquals("realm_access.roles", put.path("claim").asText());
        assertEquals("refuse", put.path("onNoMatch").asText());
        assertTrue(put.path("allowAdmin").asBoolean());
        assertEquals("8h", put.path("maxAge").asText());
        assertTrue(put.path("stored").asBoolean());
        assertFalse(put.path("properties").path("enabled").asBoolean());
        // stored in the settings sink, as a host section
        assertEquals("refuse", f.settingsSink.get(RoleMappingSettings.SECTION).orElseThrow().path("onNoMatch").asText());
        assertTrue(f.settingsSink.layer().isEmpty());
        // a partial update keeps the rest
        put = json(f.send(admin, "PUT", "/api/admin/auth/role-mapping", Map.of("onNoMatch", "keep")));
        assertTrue(put.path("enabled").asBoolean());
        assertEquals("keep", put.path("onNoMatch").asText());

        for (Map<String, Object> bad : List.of(Map.<String, Object>of("enabled", "yes"),
                Map.<String, Object>of("onNoMatch", "maybe"), Map.<String, Object>of("claim", "has space"),
                Map.<String, Object>of("maxAge", "1m"), Map.<String, Object>of("maxAge", "forever"),
                Map.<String, Object>of("maxAge", 60), Map.<String, Object>of("unknown", 1))) {
            f.send(admin, "PUT", "/api/admin/auth/role-mapping", bad).andExpect(status().isBadRequest());
        }
        assertEquals("keep", json(f.get(admin, "/api/admin/auth/role-mapping")).path("onNoMatch").asText());

        JsonNode reset = json(f.send(admin, "DELETE", "/api/admin/auth/role-mapping", null));
        assertFalse(reset.path("enabled").asBoolean());
        assertFalse(reset.path("stored").asBoolean());
        assertTrue(f.settingsSink.get(RoleMappingSettings.SECTION).isEmpty());
    }

    @Test
    void previewWorksWhileTheMappingIsDisabled() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie admin = f.signIn("admin");
        f.send(admin, "PUT", "/api/admin/roles/guest/settings/idp", Map.of("groups", List.of("team-*"), "priority", 5))
                .andExpect(status().isOk());
        f.send(admin, "PUT", "/api/admin/roles/reader/settings/idp", Map.of("groups", List.of("team-a"), "priority", 5))
                .andExpect(status().isOk());
        f.send(admin, "PUT", "/api/admin/roles/admin/settings/idp", Map.of("groups", List.of("ops"), "priority", 1))
                .andExpect(status().isOk());

        JsonNode p = json(f.send(admin, "POST", "/api/admin/auth/role-mapping/preview",
                Map.of("groups", List.of("team-a", "ops"))));
        assertFalse(p.path("enabled").asBoolean());
        assertTrue(p.path("note").asText().contains("disabled"));
        assertEquals("guest", p.path("role").asText()); // a tie on priority: the role name decides
        assertEquals("team-a", p.path("group").asText());
        assertEquals("team-*", p.path("pattern").asText());
        assertEquals(3, p.path("matches").size());
        assertEquals("admin", p.path("matches").get(0).path("role").asText());
        assertTrue(p.path("matches").get(0).path("skipped").asText().contains("allowAdmin"));
        assertTrue(p.path("rule").asText().contains("One role per user"));

        p = json(f.send(admin, "POST", "/api/admin/auth/role-mapping/preview", Map.of("groups", "nothing, other")));
        assertTrue(p.path("role").isNull());
        assertTrue(p.path("outcome").asText().contains("default role reader"));
        f.send(admin, "PUT", "/api/admin/auth/role-mapping", Map.of("onNoMatch", "refuse")).andExpect(status().isOk());
        p = json(f.send(admin, "POST", "/api/admin/auth/role-mapping/preview", Map.of("groups", List.of())));
        assertTrue(p.path("outcome").asText().contains("no_role"));

        f.send(admin, "POST", "/api/admin/auth/role-mapping/preview", Map.of("groups", 3))
                .andExpect(status().isBadRequest());
        f.send(admin, "POST", "/api/admin/auth/role-mapping/preview", Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    void usersShowWhenTheirRoleIsManagedByTheProvider() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie admin = f.signIn("admin");
        f.users.setExternalId("gus", "https://idp#gus");
        assertFalse(managed(f, admin, "gus"));
        f.send(admin, "PUT", "/api/admin/auth/role-mapping", Map.of("enabled", true)).andExpect(status().isOk());
        assertTrue(managed(f, admin, "gus"));
        assertFalse(managed(f, admin, "rita")); // not linked
        assertFalse(managed(f, admin, "admin"));
        // linked with a password of its own: managed too, the next provider sign-in sets the role
        f.users.setPassword("rita", org.kendar.llm.web.commons.auth.PasswordHasher.hash("A-long-pass-phrase-1"));
        f.users.setExternalId("rita", "https://idp#rita");
        assertFalse(f.users.find("rita").orElseThrow().passwordless());
        assertTrue(managed(f, admin, "rita"));
        f.send(admin, "PUT", "/api/admin/auth/role-mapping", Map.of("enabled", false)).andExpect(status().isOk());
        assertFalse(managed(f, admin, "rita"));
        assertFalse(managed(f, admin, "gus"));
    }

    private static boolean managed(WebauthFixture f, Cookie admin, String id) throws Exception {
        for (JsonNode u : json(f.get(admin, "/api/admin/users"))) {
            if (u.path("id").asText().equals(id)) return u.path("roleManagedByIdp").asBoolean();
        }
        throw new AssertionError("no user " + id);
    }
}
