package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FileSettingsSinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void roundTripAndRemove() throws Exception {
        Path file = tmp.resolve("sub/stored-settings.json");
        FileSettingsSink sink = new FileSettingsSink(file);
        assertTrue(sink.sections().isEmpty());
        assertTrue(sink.layer().isEmpty());
        sink.put("roleMapping", json("{\"enabled\":true,\"claim\":\"groups\"}"));
        sink.put("docker_host", json("\"tcp://h:2376\""));
        assertEquals(json("{\"enabled\":true,\"claim\":\"groups\"}"), sink.get("roleMapping").orElseThrow());
        assertEquals(List.of("docker_host", "roleMapping"), List.copyOf(sink.sections().keySet()));
        // a fresh instance reads the same from the file
        FileSettingsSink again = new FileSettingsSink(file);
        assertEquals(sink.sections(), again.sections());
        assertTrue(again.remove("docker_host"));
        assertFalse(again.remove("docker_host"));
        assertTrue(again.get("docker_host").isEmpty());
        assertEquals(List.of("roleMapping"), List.copyOf(new FileSettingsSink(file).sections().keySet()));
    }

    @Test
    void invalidInputIsRefused() throws Exception {
        FileSettingsSink sink = new FileSettingsSink(tmp.resolve("s.json"));
        for (String bad : new String[]{null, "", " a", "../x", "a/b", "-a", "x".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> sink.put(bad, json("1")), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> sink.put("a", null));
        assertThrows(IllegalArgumentException.class, () -> sink.put("a", json("null")));
        String big = "\"" + "x".repeat(AbstractSettingsSink.MAX_SECTION_BYTES) + "\"";
        assertThrows(IllegalArgumentException.class, () -> sink.put("a", json(big)));
        assertFalse(Files.exists(tmp.resolve("s.json")));
    }

    @Test
    void replaceIsAtomicAndLeavesTheOtherSectionsAndNoTemporaryFile() throws Exception {
        Path file = tmp.resolve("s.json");
        FileSettingsSink sink = new FileSettingsSink(file);
        sink.put("a", json("{\"x\":1,\"y\":2}"));
        sink.put("b", json("[1,2]"));
        sink.put("a", json("{\"z\":3}"));
        // the whole section is replaced, not merged
        assertEquals(json("{\"z\":3}"), sink.get("a").orElseThrow());
        assertEquals(json("{\"a\":{\"z\":3},\"b\":[1,2]}"), MAPPER.readTree(Files.readString(file)));
        try (Stream<Path> files = Files.list(tmp)) {
            assertEquals(List.of(file), files.toList());
        }
    }

    @Test
    void unknownSectionsAreKeptAsTheyAre() throws Exception {
        Path file = tmp.resolve("s.json");
        String unknown = "{\"deep\":[1,{\"b\":null,\"c\":\"env:SECRET\"}],\"n\":1.5}";
        Files.writeString(file, "{\"future\":" + unknown + ",\"Odd Name\":true}");
        FileSettingsSink sink = new FileSettingsSink(file);
        sink.put("known", json("1"));
        JsonNode stored = MAPPER.readTree(Files.readString(file));
        assertEquals(json(unknown), stored.get("future"));
        assertTrue(stored.get("Odd Name").asBoolean());
        assertEquals(json(unknown), sink.get("future").orElseThrow());
    }

    @Test
    void readersGetCopiesAndSeeWritesWithoutRestart() throws Exception {
        Path file = tmp.resolve("s.json");
        FileSettingsSink sink = new FileSettingsSink(file);
        sink.put("a", json("{\"v\":1}"));
        Map<String, JsonNode> held = sink.sections();
        ((com.fasterxml.jackson.databind.node.ObjectNode) sink.get("a").orElseThrow()).put("v", 99);
        assertEquals(1, sink.get("a").orElseThrow().get("v").asInt());
        List<Map<String, JsonNode>> seen = new ArrayList<>();
        sink.onChange(seen::add);
        sink.put("a", json("{\"v\":2}"));
        assertEquals(2, sink.get("a").orElseThrow().get("v").asInt());
        assertEquals(1, held.get("a").get("v").asInt()); // what a reader already holds does not change
        assertEquals(1, seen.size());
        assertEquals(2, seen.get(0).get("a").get("v").asInt());
        // a write through another instance (another node, a hand edit) is seen after reload
        new FileSettingsSink(file).put("a", json("{\"v\":3}"));
        assertEquals(2, sink.get("a").orElseThrow().get("v").asInt());
        sink.reload();
        assertEquals(3, sink.get("a").orElseThrow().get("v").asInt());
        assertEquals(2, seen.size());
        sink.reload(); // nothing changed: no call
        assertEquals(2, seen.size());
        // a failing listener does not fail the write nor stop the others
        AtomicInteger after = new AtomicInteger();
        sink.onChange(m -> {
            throw new IllegalStateException("boom");
        });
        sink.onChange(m -> after.incrementAndGet());
        sink.put("b", json("1"));
        assertEquals(1, after.get());
    }

    @Test
    void storedLayerWinsOverTheReadOnlyLayers() throws Exception {
        JsonNode global = json("{\"smallModel\":false,\"context\":{\"maxTokens\":1000,\"keepRecentTurns\":3},"
                + "\"permissions\":{\"deny\":[\"bash(rm)\"]}}");
        JsonNode explicit = json("{\"context\":{\"maxTokens\":2000}}");
        SettingsSource base = () -> List.of(new SettingsLayer(SettingsLayer.Kind.GLOBAL, "g", global),
                new SettingsLayer(SettingsLayer.Kind.EXPLICIT, "e", explicit));
        FileSettingsSink sink = new FileSettingsSink(tmp.resolve("s.json"));
        SettingsSource source = sink.over(base);
        assertEquals(2000, load(source).context().maxTokens());
        sink.put("context", json("{\"maxTokens\":5000}"));
        sink.put("smallModel", json("true"));
        sink.put("permissions", json("{\"deny\":[\"web_fetch\"]}"));
        List<SettingsLayer> layers = source.layers();
        assertEquals(SettingsLayer.Kind.STORED, layers.get(layers.size() - 1).kind());
        assertEquals(sink.origin(), layers.get(layers.size() - 1).origin());
        HarnessSettings s = load(source);
        assertEquals(5000, s.context().maxTokens());
        assertEquals(3, s.context().keepRecentTurns()); // merged per key like any layer
        assertTrue(s.isSmallModel());
        assertEquals(List.of("bash(rm)", "web_fetch"), s.permissions().deny()); // a layer only adds denials
        sink.remove("context");
        assertEquals(2000, load(source).context().maxTokens());
    }

    @Test
    void hostSectionsStayOutOfTheHarnessLayer() throws Exception {
        FileSettingsSink sink = new FileSettingsSink(tmp.resolve("s.json"));
        sink.put("web.roleMapping", json("{\"enabled\":true}"));
        assertTrue(SettingsSink.hostSection("web.roleMapping"));
        assertTrue(sink.layer().isEmpty());
        assertTrue(sink.get("web.roleMapping").isPresent());
        List<String> warnings = new ArrayList<>();
        sink.put("smallModel", json("true"));
        HarnessSettings s = HarnessSettingsLoader.loadLayered(sink.over(List::of), null, k -> null, null, null,
                warnings::add, new org.kendar.llm.harness.safety.ContentWarnings());
        assertTrue(s.isSmallModel());
        assertEquals(List.of(), warnings); // no "Unknown setting"
        assertFalse(sink.layer().orElseThrow().tree().has("web.roleMapping"));
    }

    @Test
    void concurrentWritersDoNotCorruptTheFile() throws Exception {
        Path file = tmp.resolve("s.json");
        int threads = 8;
        int rounds = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                // two instances on the same file, as two beans would be
                FileSettingsSink sink = new FileSettingsSink(t % 2 == 0 ? file : tmp.resolve(".").resolve("s.json"));
                done.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        sink.put("own" + id, json("{\"i\":" + i + "}"));
                        sink.put("shared", json("{\"by\":" + id + ",\"i\":" + i + "}"));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        JsonNode stored = MAPPER.readTree(Files.readString(file));
        for (int t = 0; t < threads; t++) assertEquals(rounds - 1, stored.get("own" + t).get("i").asInt());
        assertEquals(rounds - 1, stored.get("shared").get("i").asInt());
        assertEquals(threads + 1, stored.size());
        assertEquals(threads + 1, new FileSettingsSink(file).sections().size());
    }

    private static HarnessSettings load(SettingsSource source) {
        return HarnessSettingsLoader.loadLayered(source, null, k -> null, null, null, w -> { },
                new org.kendar.llm.harness.safety.ContentWarnings());
    }
}
