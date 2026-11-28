package org.kendar.llm.harness.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.sandbox.SandboxKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The settings map of a role: caps, keys, unknown features and the derived sandbox. */
class RoleSettingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Role role(Map<String, JsonNode> settings) {
        return new Role("dev", List.of("*"), false, "ask", Map.of(), settings);
    }

    @Test
    void unknownKeysAreKeptAsTheyAre() throws Exception {
        JsonNode idp = MAPPER.readTree("{\"groups\":[\"a\",\"b\"],\"nested\":{\"x\":1}}");
        Map<String, JsonNode> in = new LinkedHashMap<>();
        in.put("idp", idp);
        in.put("x_feature-2", TextNode.valueOf("v"));
        Role r = role(in);
        assertEquals(in, r.settings());
        assertEquals(idp, r.setting("idp"));
        assertNull(r.setting("missing"));
        assertEquals(SandboxKind.NONE, r.sandbox().kind());
    }

    @Test
    void settingsAreCopies() throws Exception {
        ObjectNode idp = (ObjectNode) MAPPER.readTree("{\"a\":1}");
        Role r = role(Map.of("idp", idp));
        idp.put("a", 2);
        assertEquals(1, r.setting("idp").path("a").asInt());
        ((ObjectNode) r.setting("idp")).put("a", 3);
        assertEquals(1, r.setting("idp").path("a").asInt());
        assertThrows(UnsupportedOperationException.class, () -> r.settings().put("b", TextNode.valueOf("x")));
    }

    @Test
    void nullValuesAreDropped() {
        Map<String, JsonNode> in = new LinkedHashMap<>();
        in.put("a", null);
        in.put("b", JsonNodeFactory.instance.nullNode());
        assertTrue(role(in).settings().isEmpty());
        assertTrue(role(null).settings().isEmpty());
    }

    @Test
    void invalidAndReservedKeysAreRejected() {
        for (String k : List.of("", "Upper", "has space", "dot.ted", "x".repeat(65), "name", "tools", "readonly",
                "approval", "toolpermissions", "settings")) {
            Map<String, JsonNode> in = new LinkedHashMap<>();
            in.put(k, TextNode.valueOf("v"));
            assertThrows(IllegalArgumentException.class, () -> role(in), k);
            assertFalse(Role.settingKey(k), k);
        }
        Map<String, JsonNode> nullKey = new LinkedHashMap<>();
        nullKey.put(null, TextNode.valueOf("v"));
        assertThrows(IllegalArgumentException.class, () -> role(nullKey));
        assertTrue(Role.settingKey("x".repeat(64)));
        assertTrue(Role.settingKey(Role.SANDBOX));
    }

    @Test
    void depthIsCapped() {
        assertDoesNotThrow(() -> role(Map.of("deep", nested(Role.MAX_SETTINGS_DEPTH))));
        assertThrows(IllegalArgumentException.class, () -> role(Map.of("deep", nested(Role.MAX_SETTINGS_DEPTH + 1))));
    }

    /** A value {@code levels} deep: a scalar is level 1, each object around it one more. */
    private static JsonNode nested(int levels) {
        JsonNode n = TextNode.valueOf("leaf");
        for (int i = 1; i < levels; i++) {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            o.set("k", n);
            n = o;
        }
        return n;
    }

    @Test
    void sizeIsCapped() {
        String big = "x".repeat(Role.MAX_SETTINGS_BYTES);
        assertThrows(IllegalArgumentException.class, () -> role(Map.of("big", TextNode.valueOf(big))));
        // the cap is on the whole map, not per key
        String half = "x".repeat(Role.MAX_SETTINGS_BYTES / 2);
        assertThrows(IllegalArgumentException.class,
                () -> role(Map.of("a", TextNode.valueOf(half), "b", TextNode.valueOf(half))));
        assertDoesNotThrow(() -> role(Map.of("ok", TextNode.valueOf("x".repeat(Role.MAX_SETTINGS_BYTES - 100)))));
    }

    @Test
    void withSettingReplacesAndRemoves() {
        Role r = role(Map.of("idp", TextNode.valueOf("a")));
        Role sb = r.withSetting(Role.SANDBOX, RoleSandbox.of("docker-isolated", "user", "none", false).toJson());
        assertEquals(List.of("idp", "sandbox"), List.copyOf(sb.settings().keySet()));
        assertEquals(SandboxKind.DOCKER_ISOLATED, sb.sandbox().kind());
        assertEquals("user", sb.sandbox().scope());
        assertEquals("none", sb.sandbox().network());
        assertEquals(List.of("idp"), List.copyOf(r.settings().keySet()), "the original is unchanged");
        Role replaced = sb.withSetting("idp", TextNode.valueOf("b"));
        assertEquals("b", replaced.setting("idp").asText());
        Role removed = replaced.withSetting("idp", null);
        assertEquals(List.of("sandbox"), List.copyOf(removed.settings().keySet()));
        assertEquals(removed.settings(), removed.withSetting("missing", null).settings());
        assertThrows(IllegalArgumentException.class, () -> r.withSetting("Bad", TextNode.valueOf("x")));
        assertEquals(r.name(), removed.name());
        assertEquals(r.tools(), removed.tools());
    }

    @Test
    void sandboxIsLenientOnBadValues() throws Exception {
        for (String bad : List.of("\"local\"", "{\"kind\":\"local\"}", "[1]", "{\"kind\":\"spaceship\"}",
                "{\"kind\":\"docker-open\",\"scope\":\"planet\"}",
                "{\"kind\":\"docker-isolated\",\"network\":\"host\"}",
                "{\"kind\":\"docker-open\",\"localFallback\":\"yes\"}")) {
            Role r = role(Map.of(Role.SANDBOX, MAPPER.readTree(bad)));
            assertEquals(RoleSandbox.NONE, r.sandbox(), bad);
            assertEquals(MAPPER.readTree(bad), r.setting(Role.SANDBOX), "the stored value is kept: " + bad);
            assertThrows(IllegalArgumentException.class, () -> RoleSandbox.parse(MAPPER.readTree(bad)), bad);
        }
    }

    @Test
    void sharedLocalNetworkAndFallbackFields() throws Exception {
        assertFalse(RoleSandbox.of("shared-local", null, null, false).networkGranted());
        assertTrue(RoleSandbox.parse(MAPPER.readTree("{\"kind\":\"shared-local\",\"network\":true}")).networkGranted());
        assertTrue(RoleSandbox.of("shared-local", null, "default", false).networkGranted());
        assertFalse(RoleSandbox.parse(MAPPER.readTree("{\"kind\":\"shared-local\",\"network\":false}")).networkGranted());
        assertFalse(RoleSandbox.of("docker-open", null, "default", false).networkGranted());

        RoleSandbox fb = RoleSandbox.parse(MAPPER.readTree("{\"kind\":\"docker-isolated\",\"localFallback\":true}"));
        assertTrue(fb.localFallback());
        assertEquals(fb, RoleSandbox.parse(fb.toJson()));
        assertFalse(RoleSandbox.of("docker-open", null, null, false).localFallback(), "off by default");
        assertFalse(RoleSandbox.of("shared-local", null, null, true).localFallback(), "only docker kinds fall back");
    }

    @Test
    void sandboxJsonRoundTrip() {
        for (RoleSandbox sb : List.of(RoleSandbox.NONE, RoleSandbox.of("shared-local", null, null, false),
                RoleSandbox.of("docker-open", "chat", null, false), RoleSandbox.of("docker-isolated", "user", "default", false))) {
            assertEquals(sb, RoleSandbox.parse(sb.toJson()));
            assertEquals(sb, role(Map.of(Role.SANDBOX, sb.toJson())).sandbox());
        }
        assertEquals(RoleSandbox.NONE, RoleSandbox.parse(null));
        assertFalse(RoleSandbox.of("docker-open", "chat", null, false).toJson().has("network"));
    }

    @Test
    void adminKeepsEveryToolAndItsSettings() {
        Role a = Role.admin("off", Map.of(), Map.of("idp", TextNode.valueOf("x")));
        assertTrue(a.fixed());
        assertEquals(List.of("*"), a.tools());
        assertEquals("x", a.setting("idp").asText());
        assertTrue(Role.ADMIN.settings().isEmpty());
    }
}
