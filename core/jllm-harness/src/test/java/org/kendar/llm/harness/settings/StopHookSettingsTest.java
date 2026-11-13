package org.kendar.llm.harness.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** {@code loop} and {@code hooks.stop} settings; project stop hooks need the user's trust. */
class StopHookSettingsTest {

    @TempDir
    Path tmp;
    private Path home;
    private Path project;
    private ProjectMcpTrust trust;
    private final List<String> warnings = new ArrayList<>();

    private static final String HOOK = "{\"hooks\":{\"stop\":[{\"command\":\"mvn -q test\",\"timeoutSeconds\":60}]}}";

    @BeforeEach
    void setUp() {
        home = tmp.resolve("home");
        project = tmp.resolve("proj");
        System.setProperty("jllm.home", home.toString());
        trust = new FileProjectMcpTrust(home.resolve(".jllm/trusted-mcp.json"));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("jllm.home");
    }

    private void write(Path f, String s) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, s);
    }

    private HarnessSettings load(ProjectMcpTrust.Prompt prompt) {
        return HarnessSettings.loadLayered(new FileSettingsSource(null, project), project, k -> null, prompt, trust, warnings::add);
    }

    @Test
    void userStopHooksAreLoadedWithDefaults() throws Exception {
        write(home.resolve(".jllm/settings.json"), HOOK);
        HarnessSettings s = load((d, n, l) -> { throw new AssertionError("not asked"); });
        assertEquals(1, s.stopHooks().size());
        HarnessSettings.StopHook h = s.stopHooks().get(0);
        assertEquals(60, h.timeout());
        assertEquals(List.of("main"), h.agents());
        assertTrue(h.changedOnly());
        assertEquals(8000, h.maxChars());
    }

    @Test
    void aProjectStopHookWithoutTrustIsRefused() throws Exception {
        write(project.resolve(".jllm/settings.json"), HOOK);
        assertTrue(load(ProjectMcpTrust.Prompt.NONE).stopHooks().isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Stop hook 'mvn -q test'")), warnings.toString());
        assertTrue(load((d, n, l) -> false).stopHooks().isEmpty());
    }

    @Test
    void aTrustedProjectStopHookIsRememberedPerDefinition() throws Exception {
        write(project.resolve(".jllm/settings.json"), HOOK);
        AtomicInteger asked = new AtomicInteger();
        assertEquals(1, load((d, n, l) -> {
            asked.incrementAndGet();
            assertTrue(n.startsWith(HarnessSettings.STOP_HOOK_TRUST_PREFIX));
            assertEquals("mvn -q test", l);
            return true;
        }).stopHooks().size());
        assertEquals(1, load(ProjectMcpTrust.Prompt.NONE).stopHooks().size());
        assertEquals(1, asked.get());
        write(project.resolve(".jllm/settings.json"), HOOK.replace("60", "61"));
        assertTrue(load(ProjectMcpTrust.Prompt.NONE).stopHooks().isEmpty());
    }

    @Test
    void loopSettingsOverrideTheDefaults() throws Exception {
        write(home.resolve(".jllm/settings.json"),
                "{\"loop\":{\"stuck\":{\"enabled\":false,\"repeat\":5},\"verify\":{\"maxReentries\":1}}}");
        HarnessConfig c = load(ProjectMcpTrust.Prompt.NONE).applyTo(HarnessConfig.defaults());
        assertFalse(c.loop().stuck().enabled());
        assertEquals(5, c.loop().stuck().repeat());
        assertEquals(3, c.loop().stuck().alternationCycles());
        assertEquals(1, c.loop().verify().maxReentries());
        assertEquals(HarnessConfig.Loop.defaults(), HarnessSettings.empty().applyTo(HarnessConfig.defaults()).loop());
    }
}
