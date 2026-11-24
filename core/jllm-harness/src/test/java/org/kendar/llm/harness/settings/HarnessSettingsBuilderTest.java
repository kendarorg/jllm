package org.kendar.llm.harness.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessSettingsBuilderTest {

    private static final String FULL = "{\"mcpServers\":[{\"name\":\"s\",\"url\":\"http://x/mcp\"}],"
            + "\"instructionFiles\":[\"A.md\"],\"execution\":{\"maxParallel\":3,\"useContextStack\":false},"
            + "\"sandbox\":{\"tempDir\":\"/t\",\"timeoutSeconds\":9,\"backend\":\"docker\",\"docker\":{\"image\":\"i\","
            + "\"profile\":\"hardened\",\"scope\":\"user\",\"cleanup\":{\"idleMinutes\":5,\"action\":\"remove\"}},\"outputLogMaxBytes\":7,\"shell\":\"sh\"},"
            + "\"context\":{\"maxTokens\":5000,\"strategy\":\"mnemonic\",\"scope\":{\"previewChars\":4},"
            + "\"mnemonic\":{\"maskTrigger\":1},\"todos\":false},\"output\":{\"toolMaxChars\":11,\"validation\":\"strict\"},"
            + "\"smallModel\":true,\"permissions\":{\"deny\":[\"bash\\\\(rm.*\"]},\"chatRetentionLimit\":4,"
            + "\"plugins\":{\"p\":{\"enabled\":true}},\"storage\":{\"maxBytes\":99},\"whitelistedEnvVars\":[\"PATH\"],"
            + "\"files\":{\"maxReadBytes\":2,\"scopedInstructionsDepth\":1},\"web\":{\"fetchMaxBytes\":3},"
            + "\"loop\":{\"stuck\":{\"repeat\":5},\"verify\":{\"maxReentries\":2}},"
            + "\"hooks\":{\"stop\":[{\"command\":\"make\"}]},\"tokenStore\":{\"type\":\"file\",\"dir\":\"/k\"}}";

    @TempDir
    Path tmp;

    private HarnessSettings full() throws Exception {
        Path f = Files.writeString(tmp.resolve("s.json"), FULL);
        return HarnessSettingsLoader.load(f, k -> null);
    }

    @Test
    void toBuilderBuildRoundTripsEveryComponent() throws Exception {
        HarnessSettings s = full();

        assertEquals(s, s.toBuilder().build());
        assertEquals(HarnessSettings.empty(), HarnessSettings.empty().toBuilder().build());
    }

    @Test
    void settersReplaceOnlyTheirComponent() throws Exception {
        HarnessSettings s = full();

        HarnessSettings t = s.toBuilder().smallModel(false).chatRetentionLimit(8).build();

        assertEquals(false, t.smallModel());
        assertEquals(8, t.chatRetentionLimit());
        assertEquals(s.sandbox(), t.sandbox());
        assertEquals(s.hooks(), t.hooks());
        assertEquals(s.tokenStore(), t.tokenStore());
    }

    @Test
    void defaultsApplyToAnEmptyBuilder() {
        HarnessSettings s = HarnessSettings.builder().build();

        assertEquals(HarnessSettings.DEFAULT_INSTRUCTION_FILES, s.instructionFiles());
        assertEquals(1, s.maxParallel());
        assertEquals(-1, s.storageMaxBytes());
        assertTrue(s.mcpServers().isEmpty());
    }

    @Test
    void commandLineSettersKeepTheRestOfTheirSection() throws Exception {
        HarnessSettings s = full().toBuilder().sandboxBackend("LOCAL").dockerImage("alpine").dockerScope("USER")
                .dockerRemote(true, null).maxTokens(1234).contextStrategy("compaction").build();

        assertEquals("local", s.sandbox().backend());
        assertEquals("alpine", s.sandbox().docker().image());
        assertEquals("user", s.sandbox().docker().scope());
        assertEquals(true, s.sandbox().docker().remote());
        assertEquals("/t", s.sandbox().tempDir());
        assertEquals(7L, s.sandbox().outputLogMaxBytes());
        assertEquals(1234, s.context().maxTokens());
        assertEquals("compaction", s.context().strategy());
        assertEquals(Boolean.FALSE, s.context().todos());
        assertEquals(4, s.context().scope().previewChars());
    }

    @Test
    void invalidCommandLineValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> HarnessSettings.builder().sandboxBackend("vm"));
        assertThrows(IllegalArgumentException.class, () -> HarnessSettings.builder().dockerScope("x"));
        assertThrows(IllegalArgumentException.class, () -> HarnessSettings.builder().contextStrategy("magic"));
    }

    @Test
    void withPluginKeepsTheOriginalAndMergesSettings() throws Exception {
        HarnessSettings s = full();

        HarnessSettings t = s.withPlugin("p", false, null).withPlugin("q", true, null);

        assertTrue(s.plugins().get("p").get("enabled").asBoolean());
        assertEquals(false, t.plugins().get("p").get("enabled").asBoolean());
        assertTrue(t.plugins().get("q").get("enabled").asBoolean());
        assertNotEquals(s, t);
        assertEquals(List.of("make"), t.stopHooks().stream().map(StopHook::command).toList());
    }
}
