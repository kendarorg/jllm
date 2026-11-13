package org.kendar.llm.harness.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code files.maxReadBytes}, {@code web.fetchMaxBytes} and {@code sandbox.outputLogMaxBytes}. */
class SettingsLimitsTest {

    @TempDir
    Path dir;

    private HarnessSettings load(String json) throws Exception {
        return HarnessSettings.load(Files.writeString(dir.resolve("s.json"), json));
    }

    @Test
    void defaultsWhenAbsent() throws Exception {
        HarnessSettings s = load("{}");
        assertEquals(1_000_000, s.filesMaxReadBytes());
        assertEquals(5L * 1024 * 1024, s.webFetchMaxBytes());
        assertEquals(51_200, s.sandboxOutputLogMaxBytes());
        assertEquals(51_200, HarnessSettings.empty().sandboxOutputLogMaxBytes());
    }

    @Test
    void valuesAreRead() throws Exception {
        HarnessSettings s = load("{\"files\":{\"maxReadBytes\":2000},\"web\":{\"fetchMaxBytes\":3000},"
                + "\"sandbox\":{\"outputLogMaxBytes\":4000}}");
        assertEquals(2000, s.filesMaxReadBytes());
        assertEquals(3000, s.webFetchMaxBytes());
        assertEquals(4000, s.sandboxOutputLogMaxBytes());
    }

    @Test
    void zeroAndNegativeValues() throws Exception {
        HarnessSettings s = load("{\"files\":{\"maxReadBytes\":0},\"web\":{\"fetchMaxBytes\":-1},"
                + "\"sandbox\":{\"outputLogMaxBytes\":0}}");
        assertEquals(1_000_000, s.filesMaxReadBytes(), "<= 0: default");
        assertEquals(5L * 1024 * 1024, s.webFetchMaxBytes(), "<= 0: default");
        assertEquals(0, s.sandboxOutputLogMaxBytes(), "0: unlimited");
        assertEquals(51_200, load("{\"sandbox\":{\"outputLogMaxBytes\":-5}}").sandboxOutputLogMaxBytes());
    }

    @Test
    void commandLineOverridesKeepTheLimits() throws Exception {
        HarnessSettings s = load("{\"files\":{\"maxReadBytes\":2000},\"web\":{\"fetchMaxBytes\":3000},"
                + "\"sandbox\":{\"outputLogMaxBytes\":4000}}")
                .withSandboxBackend("docker").withDockerImage("img").withDockerMode("daemon")
                .withDockerRemote(true, "h").withMaxTokens(1000).withContextStrategy("compaction")
                .withSmallModel(true).withChatRetentionLimit(3).withPlugin("x", true);
        assertEquals(2000, s.filesMaxReadBytes());
        assertEquals(3000, s.webFetchMaxBytes());
        assertEquals(4000, s.sandboxOutputLogMaxBytes());
    }
}
