package org.kendar.llm.harness.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PluginLoaderTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final class P implements HarnessPlugin {
        private final String id;
        JsonNode got;

        P(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void install(PluginRegistrar r, JsonNode settings) {
            got = settings;
        }
    }

    private static final class NoId implements HarnessPlugin {
    }

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    private static PluginLoader.Candidate cp(HarnessPlugin p) {
        return new PluginLoader.Candidate(p, "classpath");
    }

    private static PluginLoader.Candidate code(HarnessPlugin p) {
        return new PluginLoader.Candidate(p, "code");
    }

    @Test
    void badIdAndMissingIdFailStartup() {
        assertThrows(IllegalStateException.class,
                () -> PluginLoader.resolve(List.of(cp(new P("Bad_Id"))), List.of(), null));
        var e = assertThrows(IllegalStateException.class,
                () -> PluginLoader.resolve(List.of(cp(new NoId())), List.of(), null));
        assertTrue(e.getMessage().contains(NoId.class.getName()));
        assertTrue(e.getMessage().contains("does not implement id()"));
    }

    @Test
    void duplicateIdFails() {
        assertThrows(IllegalStateException.class,
                () -> PluginLoader.resolve(List.of(cp(new P("pdf"))), List.of(code(new P("pdf"))), null));
    }

    @Test
    void unlistedIsSkippedWithWarning() {
        var r = PluginLoader.resolve(List.of(cp(new P("pdf"))), List.of(), null);
        assertTrue(r.install().isEmpty());
        assertEquals(List.of("pdf"), r.notListed());
        assertTrue(r.warnings().get(0).contains("plugin 'pdf' found (classpath) but not listed under plugins"));
    }

    @Test
    void listedButAbsentWarns() throws Exception {
        var r = PluginLoader.resolve(List.of(), List.of(), json("{\"x\":{\"enabled\":true}}"));
        assertTrue(r.install().isEmpty());
        assertTrue(r.warnings().get(0).contains("plugin 'x' is listed under plugins but was not found"));
    }

    @Test
    void disabledIsInfoNotWarning() throws Exception {
        var r = PluginLoader.resolve(List.of(cp(new P("pdf"))), List.of(), json("{\"pdf\":{\"enabled\":false}}"));
        assertTrue(r.install().isEmpty());
        assertTrue(r.warnings().isEmpty());
        assertEquals(List.of("plugin 'pdf' disabled in settings"), r.infos());
        assertEquals(List.of("pdf"), r.disabled());
    }

    @Test
    void missingEnabledIsFalseWithWarning() throws Exception {
        var r = PluginLoader.resolve(List.of(cp(new P("pdf"))), List.of(), json("{\"pdf\":{}}"));
        assertTrue(r.install().isEmpty());
        assertEquals(1, r.warnings().size());
    }

    @Test
    void enabledPluginGetsItsSettingsOrEmptyObject() throws Exception {
        var r = PluginLoader.resolve(List.of(cp(new P("js")), cp(new P("pdf"))), List.of(),
                json("{\"js\":{\"enabled\":true,\"settings\":{\"a\":1}},\"pdf\":{\"enabled\":true}}"));
        assertEquals(2, r.install().size());
        assertEquals(1, r.install().get(0).settings().get("a").asInt());
        assertTrue(r.install().get(1).settings().isObject());
        assertEquals(0, r.install().get(1).settings().size());
        assertEquals(List.of("js", "pdf"), r.loaded());
        assertTrue(r.summary().startsWith("plugins loaded: [js, pdf]"));
    }

    @Test
    void explicitPluginNeedsNoEntryButHonoursEnabledFalse() throws Exception {
        var r = PluginLoader.resolve(List.of(), List.of(code(new P("mine"))), null);
        assertEquals(1, r.install().size());
        var off = PluginLoader.resolve(List.of(), List.of(code(new P("mine"))), json("{\"mine\":{\"enabled\":false}}"));
        assertTrue(off.install().isEmpty());
        assertTrue(off.warnings().isEmpty());
    }

    @Test
    void layersMergePerPluginAndCliFlagOverrides() throws Exception {
        JsonNode base = json("{\"a\":{\"enabled\":true,\"settings\":{\"k\":1}},\"b\":{\"enabled\":true}}");
        JsonNode over = json("{\"b\":{\"enabled\":false},\"a\":{\"settings\":{\"j\":2}}}");
        // HarnessSettings.merge is package-private: go through the files
        var dir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "plugcfg-" + System.nanoTime());
        try {
            java.nio.file.Files.createDirectories(dir);
            var f1 = dir.resolve("1.json");
            var f2 = dir.resolve("2.json");
            java.nio.file.Files.writeString(f1, "{\"plugins\":" + base + "}");
            java.nio.file.Files.writeString(f2, "{\"plugins\":" + over + "}");
            java.nio.file.Files.createDirectories(dir.resolve(".jllm"));
            java.nio.file.Files.writeString(dir.resolve(".jllm").resolve("settings.json"), "{\"plugins\":" + base + "}");
            System.setProperty("jllm.home", dir.toString());
            var s = HarnessSettings.loadLayered(new org.kendar.llm.harness.settings.FileSettingsSource(f2, null), null,
                    k -> null, null,
                    new org.kendar.llm.harness.settings.FileProjectMcpTrust(dir.resolve("trust.json")), w -> { });
            System.clearProperty("jllm.home");
            assertTrue(s.plugins().get("a").get("enabled").asBoolean());
            assertEquals(1, s.plugins().get("a").get("settings").get("k").asInt());
            assertEquals(2, s.plugins().get("a").get("settings").get("j").asInt());
            assertFalse(s.plugins().get("b").get("enabled").asBoolean());

            var flagged = s.withPlugin("b", true).withPlugin("c", true);
            assertTrue(flagged.plugins().get("b").get("enabled").asBoolean());
            assertTrue(flagged.plugins().get("c").get("enabled").asBoolean());
            assertFalse(s.plugins().get("b").get("enabled").asBoolean()); // original untouched
        } finally {
            System.clearProperty("jllm.home");
        }
    }

    @Test
    void discoveryLoadsOnlyFromPluginDir() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("plugdir");
        List<String> warnings = new ArrayList<>();
        assertTrue(PluginLoader.discover(dir, true, warnings).isEmpty()); // no jar, nothing on the test classpath
        assertTrue(warnings.isEmpty());
        PluginLoader.discover(dir.resolve("nope"), true, warnings);
        assertEquals(1, warnings.size());
        PluginLoader.discover(dir.resolve("nope"), false, warnings);
        assertEquals(1, warnings.size());
    }

    @Test
    void commandLineOptionsMapToThePluginsSection() {
        var s = HarnessSettings.empty().withPluginOption("pdf.enabled", "true")
                .withPluginOption("js.settings.storage.maxBytes", "-1")
                .withPluginOption("js.settings.name", "x")
                .withPluginOption("docs.enabled", "false");
        assertTrue(s.plugins().get("pdf").get("enabled").asBoolean());
        assertFalse(s.plugins().get("docs").get("enabled").asBoolean());
        assertEquals(-1, s.plugins().get("js").get("settings").get("storage").get("maxBytes").asInt());
        assertEquals("x", s.plugins().get("js").get("settings").get("name").asText());
        assertThrows(IllegalArgumentException.class, () -> s.withPluginOption("pdf.bogus", "1"));
    }
}
