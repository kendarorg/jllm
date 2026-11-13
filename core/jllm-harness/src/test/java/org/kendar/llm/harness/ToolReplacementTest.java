package org.kendar.llm.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.PluginRegistrar;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class ToolReplacementTest {

    @TempDir
    Path base;

    private AgentHarness.Builder builder() {
        return AgentHarness.builder().modelClient(new ScriptedModel()).config(config(base));
    }

    private static HarnessPlugin plugin(java.util.function.Consumer<PluginRegistrar> body) {
        return new HarnessPlugin() {
            @Override
            public String id() {
                return "test-plugin";
            }

            @Override
            public void install(PluginRegistrar r) {
                body.accept(r);
            }
        };
    }

    private static List<String> names(AgentHarness h) {
        return h.tools().every().stream().map(HarnessTool::name).toList();
    }

    @Test
    void withoutToolsRemovesOnlyTheNamedTools() {
        AgentHarness h = builder().withoutTools(List.of("bash")).build();

        assertFalse(names(h).contains("bash"));
        assertNull(h.tools().get("bash"));
        assertEquals(11, names(h).size());
    }

    @Test
    void replacesRemovesTheToolAndRefusesItLater() {
        AgentHarness h = builder().plugin(plugin(r -> r.replaces("bash"))).build();

        assertFalse(names(h).contains("bash"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> h.tools().setSource("mcp",
                List.of(tool("bash", "Mine", ToolKind.OTHER, (c, i) -> ""))));
        assertTrue(e.getMessage().contains("bash"));
        assertFalse(names(h).contains("bash"));
    }

    @Test
    void replacedToolCanBeProvidedByThePluginItself() {
        AgentHarness h = builder().plugin(plugin(r -> r.replaces("bash")
                .tool(tool("run_x", "Mine", ToolKind.OTHER, (c, i) -> "")))).build();

        assertTrue(names(h).contains("run_x"));
        assertFalse(names(h).contains("bash"));
    }

    @Test
    void nameCheckRunsAtBuildAndOnEverySourceChange() {
        java.util.List<java.util.Collection<String>> seen = new java.util.ArrayList<>();
        AgentHarness h = builder().plugin(plugin(r -> r.toolNameCheck(seen::add))).build();
        assertEquals(1, seen.size());

        h.tools().setSource("s", List.of(tool("a_b", "G", ToolKind.OTHER, (c, i) -> "")));
        assertEquals(2, seen.size());
        assertTrue(seen.get(1).contains("a_b"));
    }

    @Test
    void failingNameCheckLeavesTheRegistryUnchanged() {
        AgentHarness h = builder().plugin(plugin(r -> r.toolNameCheck(n -> {
            if (n.contains("bad")) throw new IllegalArgumentException("collision");
        }))).build();
        int before = names(h).size();

        assertThrows(IllegalArgumentException.class, () -> h.tools().setSource("s",
                List.of(tool("bad", "G", ToolKind.OTHER, (c, i) -> ""))));
        assertEquals(before, names(h).size());
        assertNull(h.tools().get("bad"));
    }

    @Test
    void failingNameCheckStopsTheBuild() {
        AgentHarness.Builder b = builder().plugin(plugin(r -> r.toolNameCheck(n -> {
            throw new IllegalArgumentException("collision");
        })));

        assertThrows(IllegalArgumentException.class, b::build);
    }
}
