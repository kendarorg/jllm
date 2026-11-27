package org.kendar.llm.web.commons.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.identity.Role;
import org.kendar.llm.harness.settings.FileSettingsSink;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The pieces of the identity provider role mapping, without a provider. */
class RoleMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private static Role role(String name, String idp) throws Exception {
        Role r = name.equals("admin") ? Role.ADMIN : new Role(name, List.of("*"), false, "ask", Map.of(), Map.of());
        return idp == null ? r : r.withSetting(IdpSetting.KEY, MAPPER.readTree(idp));
    }

    private static RoleMappingOptions options(boolean allowAdmin) {
        return new RoleMappingOptions(true, null, RoleMappingOptions.OnNoMatch.DEFAULT, allowAdmin, null);
    }

    @Test
    void exactAndGlobMatchesAreCaseSensitive() {
        assertTrue(IdpSetting.glob("eng", "eng"));
        assertFalse(IdpSetting.glob("eng", "Eng"));
        assertFalse(IdpSetting.glob("eng", "engineering"));
        assertTrue(IdpSetting.glob("team-*", "team-a"));
        assertTrue(IdpSetting.glob("team-*", "team-"));
        assertFalse(IdpSetting.glob("team-*", "Team-a"));
        assertTrue(IdpSetting.glob("*-admins", "cloud-admins"));
        assertTrue(IdpSetting.glob("a*b*c", "axxbyyc"));
        assertFalse(IdpSetting.glob("a*b*c", "axxbyy"));
        assertTrue(IdpSetting.glob("*", "anything"));
    }

    @Test
    void lowestPriorityWinsAndTiesGoToTheRoleName() throws Exception {
        List<Role> roles = List.of(role("admin", null), role("zeta", "{\"groups\":[\"eng\"],\"priority\":5}"),
                role("alpha", "{\"groups\":[\"team-*\"],\"priority\":5}"),
                role("beta", "{\"groups\":[\"eng\"],\"priority\":1}"), role("none", null));
        RoleMapper.Decision d = RoleMapper.decide(options(false), roles, List.of("team-x", "eng"));
        assertEquals("beta", d.role());
        assertEquals("eng", d.group());
        assertEquals(List.of("beta", "alpha", "zeta"), d.matches().stream().map(RoleMapper.Match::role).toList());

        d = RoleMapper.decide(options(false), roles.subList(0, 3), List.of("team-x", "eng"));
        assertEquals("alpha", d.role()); // same priority: by name
        assertEquals("team-*", d.pattern());

        d = RoleMapper.decide(options(false), roles, List.of("other"));
        assertFalse(d.matched());
        assertTrue(d.matches().isEmpty());
    }

    @Test
    void adminIsOnlyPickedWithAllowAdmin() throws Exception {
        List<Role> roles = List.of(role("admin", "{\"groups\":[\"ops\"],\"priority\":0}"),
                role("dev", "{\"groups\":[\"ops\"],\"priority\":9}"));
        RoleMapper.Decision d = RoleMapper.decide(options(false), roles, List.of("ops"));
        assertEquals("dev", d.role());
        assertNotNull(d.matches().get(0).skipped());
        assertEquals("admin", RoleMapper.decide(options(true), roles, List.of("ops")).role());
        assertFalse(RoleMapper.decide(options(false), roles.subList(0, 1), List.of("ops")).matched());
    }

    @Test
    void unreadableIdpSettingIsSkippedWithANote() throws Exception {
        List<Role> roles = List.of(role("dev", "{\"groups\":\"ops\"}"), role("ops", "{\"groups\":[\"ops\"]}"));
        RoleMapper.Decision d = RoleMapper.decide(options(false), roles, List.of("ops"));
        assertEquals("ops", d.role());
        assertEquals(1, d.notes().size());
    }

    @Test
    void claimsAreReadAsArraysStringsAndDottedPaths() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "x");
        claims.put("groups", List.of("a", "b", "a"));
        claims.put("spaced", "a b,c ,, d");
        claims.put("realm_access", Map.of("roles", List.of("r1")));
        claims.put("https://example.com/groups", List.of("ns"));
        claims.put("number", 3);
        claims.put("mixed", List.of("a", 1));

        assertEquals(List.of("a", "b"), OidcClient.groups(claims, "groups").values());
        assertEquals(List.of("a", "b", "c", "d"), OidcClient.groups(claims, "spaced").values());
        assertEquals(List.of("r1"), OidcClient.groups(claims, "realm_access.roles").values());
        assertEquals(List.of("ns"), OidcClient.groups(claims, "https://example.com/groups").values());
        assertEquals(OidcClient.Groups.Status.INVALID, OidcClient.groups(claims, "number").status());
        assertEquals(OidcClient.Groups.Status.INVALID, OidcClient.groups(claims, "mixed").status());
        OidcClient.Groups missing = OidcClient.groups(claims, "realm_access.nothing");
        assertEquals(OidcClient.Groups.Status.MISSING, missing.status());
        assertTrue(missing.claimKeys().contains("groups"));
    }

    @Test
    void oversizedAndOverageClaimsAreNotUsed() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= OidcClient.MAX_GROUPS; i++) many.add("g" + i);
        assertEquals(OidcClient.Groups.Status.OVERSIZED, OidcClient.groups(Map.of("groups", many), "groups").status());
        assertEquals(OidcClient.Groups.Status.PRESENT,
                OidcClient.groups(Map.of("groups", many.subList(0, OidcClient.MAX_GROUPS)), "groups").status());
        assertEquals(OidcClient.Groups.Status.OVERSIZED,
                OidcClient.groups(Map.of("groups", List.of("x".repeat(257))), "groups").status());
        OidcClient.Groups overage = OidcClient.groups(Map.of("_claim_names", Map.of("groups", "src1"),
                "_claim_sources", Map.of("src1", Map.of())), "groups");
        assertEquals(OidcClient.Groups.Status.OVERAGE, overage.status());
        assertTrue(overage.values().isEmpty());
    }

    @Test
    void optionsParseStrictlyAndKeepAbsentKeys() throws Exception {
        RoleMappingOptions base = RoleMappingOptions.of(new RoleMappingProperties(true, "roles", "keep", false,
                Duration.ofHours(8)));
        assertEquals("8h", base.toJson().path("maxAge").asText());
        RoleMappingOptions next = RoleMappingOptions.parse(MAPPER.readTree("{\"onNoMatch\":\"REFUSE\"}"), base);
        assertEquals(RoleMappingOptions.OnNoMatch.REFUSE, next.onNoMatch());
        assertEquals("roles", next.claim());
        assertEquals(Duration.ofHours(8), next.maxAge());
        assertNull(RoleMappingOptions.parse(MAPPER.readTree("{\"maxAge\":null}"), base).maxAge());
        assertEquals(Duration.ofMinutes(90), RoleMappingOptions.parse(MAPPER.readTree("{\"maxAge\":\"PT90M\"}"), base)
                .maxAge());
        assertEquals("90m", RoleMappingOptions.parse(MAPPER.readTree("{\"maxAge\":\"90m\"}"), base).toJson()
                .path("maxAge").asText());
        assertEquals(next, RoleMappingOptions.parse(next.toJson(), RoleMappingOptions.of(null)));
        for (String bad : List.of("[]", "{\"x\":1}", "{\"enabled\":1}", "{\"claim\":\"\\u0001\"}",
                "{\"maxAge\":\"31d\"}", "{\"onNoMatch\":\"never\"}")) {
            assertThrows(IllegalArgumentException.class, () -> RoleMappingOptions.parse(MAPPER.readTree(bad), base), bad);
        }
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> RoleMappingOptions.of(
                new RoleMappingProperties(true, null, "sometimes", null, null)));
        assertTrue(e.getMessage().contains("jllm.auth.oidc.role-mapping.on-no-match"));
        e = assertThrows(IllegalStateException.class, () -> RoleMappingOptions.of(
                new RoleMappingProperties(true, null, null, null, Duration.ofSeconds(10))));
        assertTrue(e.getMessage().contains("jllm.auth.oidc.role-mapping.max-age"));
    }

    @Test
    void storedOptionsWinOverThePropertiesAndStayOutOfTheHarnessLayer() throws Exception {
        FileSettingsSink sink = new FileSettingsSink(dir.resolve("stored.json"));
        RoleMappingSettings s = new RoleMappingSettings(new RoleMappingProperties(false, "groups", null, null, null),
                sink, "reader");
        assertFalse(s.current().enabled());
        s.put(MAPPER.readTree("{\"enabled\":true,\"claim\":\"roles\"}"));
        assertTrue(s.current().enabled());
        assertEquals("roles", s.current().claim());
        assertTrue(sink.layer().isEmpty());
        // read again from the file, as another instance would
        RoleMappingSettings again = new RoleMappingSettings(RoleMappingProperties.NONE,
                new FileSettingsSink(dir.resolve("stored.json")), "reader");
        assertEquals("roles", again.current().claim());
        assertTrue(s.reset());
        assertFalse(s.current().enabled());

        RoleMappingSettings noDefault = new RoleMappingSettings(RoleMappingProperties.NONE, sink, null);
        assertThrows(IllegalArgumentException.class, () -> noDefault.put(MAPPER.readTree("{\"enabled\":true}")));
        noDefault.put(MAPPER.readTree("{\"enabled\":true,\"onNoMatch\":\"refuse\"}"));
        assertThrows(IllegalStateException.class, () -> new RoleMappingSettings(
                new RoleMappingProperties(true, null, null, null, null), null, null));
        RoleMappingSettings readOnly = new RoleMappingSettings(RoleMappingProperties.NONE, null, null);
        assertFalse(readOnly.editable());
        assertThrows(IllegalStateException.class, () -> readOnly.put(MAPPER.readTree("{}")));
    }
}
