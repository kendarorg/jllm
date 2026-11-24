package org.kendar.llm.harness.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WebPluginHostTest {
    private static final ObjectMapper M = new ObjectMapper();
    @TempDir
    Path dir;

    /** A trivial plugin: echoes the user and a path parameter, stores a document, pushes an event. */
    static final class Echo implements WebPlugin {
        final String id;
        final List<String> requires;
        boolean closed;

        Echo(String id, String... requires) {
            this.id = id;
            this.requires = List.of(requires);
        }

        public String id() {
            return id;
        }

        public List<String> requires() {
            return requires;
        }

        public void install(WebContext c) {
            c.route("GET", "/hello/{name}", r -> WebResponse.ok(M.createObjectNode()
                    .put("hello", r.param("name")).put("user", r.user())));
            c.route("POST", "/doc", r -> {
                c.storage().put(r.user(), "docs", "d1", r.body());
                c.events().push(r.user(), "saved", r.body());
                return WebResponse.ok(c.storage().get(r.user(), "docs", "d1").orElseThrow());
            });
        }

        public void close() {
            closed = true;
        }
    }

    private static WebRequest get(String path, String user) {
        return new WebRequest("GET", path, Map.of(), Map.of(), null, user, false, Set.of());
    }

    private static JsonNode settings(String json) throws Exception {
        return M.readTree(json);
    }

    private WebPluginHost host(String settings) throws Exception {
        return new WebPluginHost(new ServiceRegistry(), id -> new MemoryPluginStorage(), settings(settings));
    }

    @Test
    void enabledPluginAnswersAnEndpointUnderItsOwnId() throws Exception {
        WebPluginHost h = host("{'a1':{'enabled':true}}".replace('\'', '"'));
        Echo echo = new Echo("a1");
        h.load(List.of(echo));
        assertEquals(List.of("a1"), h.loaded());
        WebResponse r = h.dispatch("a1", get("/hello/ann", "u1"));
        assertEquals(200, r.status());
        assertEquals("ann", r.body().path("hello").asText());
        assertEquals("u1", r.body().path("user").asText());
        assertEquals(404, h.dispatch("a1", get("/nope", "u1")).status());
        assertEquals(405, h.dispatch("a1", new WebRequest("DELETE", "/hello/x", Map.of(), Map.of(), null, "u", false,
                Set.of())).status());
        assertEquals(404, h.dispatch("other", get("/hello/ann", "u1")).status(), "no way into a plugin that is not loaded");
        h.close();
        assertTrue(echo.closed);
        assertEquals(404, h.dispatch("a1", get("/hello/ann", "u1")).status(), "unloaded cleanly");
    }

    @Test
    void notListedOrDisabledPluginsAreInvisible() throws Exception {
        WebPluginHost h = host("{'off':{'enabled':false}}".replace('\'', '"'));
        h.load(List.of(new Echo("off"), new Echo("unlisted")));
        assertTrue(h.loaded().isEmpty());
        assertEquals(2, h.warnings().size());
        assertEquals(404, h.dispatch("off", get("/hello/x", "u")).status());
    }

    @Test
    void warningsNameThePluginAndTheReason() throws Exception {
        WebPluginHost h = host("{'off':{'enabled':false}}".replace('\'', '"'));
        h.load(List.of(new Echo("off"), new Echo("unlisted")));
        assertTrue(h.warnings().stream().anyMatch(w -> w.contains("off") && w.contains("disabled")));
        assertTrue(h.warnings().stream().anyMatch(w -> w.contains("unlisted") && w.contains("not listed")));
    }

    @Test
    void pluginNeedingAMissingPluginIsSkippedAndOrderFollowsDependencies() throws Exception {
        WebPluginHost h = host("{'a1':{'enabled':true},'b1':{'enabled':true},'c1':{'enabled':true}}".replace('\'', '"'));
        h.load(List.of(new Echo("b1", "a1"), new Echo("a1"), new Echo("c1", "zzz")));
        assertEquals(List.of("a1", "b1"), h.loaded());
        assertTrue(h.warnings().stream().anyMatch(w -> w.contains("c1") && w.contains("zzz")));
    }

    @Test
    void pluginThatFailsToStartIsSkipped() throws Exception {
        WebPluginHost h = host("{'bad1':{'enabled':true}}".replace('\'', '"'));
        h.load(List.of(new WebPlugin() {
            public String id() {
                return "bad1";
            }

            public void install(WebContext c) {
                throw new IllegalStateException("needs something");
            }
        }));
        assertTrue(h.loaded().isEmpty());
        assertTrue(h.warnings().get(0).contains("needs something"));
    }

    @Test
    void storageIsKeptApartPerPluginAndEventsPerUser() throws Exception {
        var storages = new FilePluginStorage(dir, M);
        WebPluginHost h = new WebPluginHost(new ServiceRegistry(), storages,
                settings("{'a1':{'enabled':true},'b1':{'enabled':true}}".replace('\'', '"')));
        h.load(List.of(new Echo("a1"), new Echo("b1")));
        List<String> seen = new ArrayList<>();
        h.events("a1").subscribe("u1", e -> seen.add(e.type()));
        var body = M.createObjectNode().put("x", 1);
        h.dispatch("a1", new WebRequest("POST", "/doc", Map.of(), Map.of(), body, "u1", false, Set.of()));
        h.dispatch("a1", new WebRequest("POST", "/doc", Map.of(), Map.of(), body, "u2", false, Set.of()));
        assertEquals(List.of("saved"), seen, "only u1 hears u1's event");
        assertEquals(List.of("d1"), storages.forPlugin("a1").ids("u1", "docs"));
        assertTrue(storages.forPlugin("b1").ids("u1", "docs").isEmpty(), "apart by plugin id");
        assertEquals(List.of("u1", "u2"), storages.forPlugin("a1").owners("docs"));
        storages.forPlugin("a1").put("../evil", "c/../d", "x y", body);
        assertEquals(List.of("x y"), storages.forPlugin("a1").ids("../evil", "c/../d"), "names are encoded, never paths");
    }
}
