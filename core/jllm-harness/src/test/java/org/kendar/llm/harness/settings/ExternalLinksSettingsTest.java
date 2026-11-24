package org.kendar.llm.harness.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.tools.ExternalLinks;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code files.externalLinks}: the settings key, its default, the builder and the config it maps to. */
class ExternalLinksSettingsTest {

    @TempDir
    Path dir;

    private HarnessSettings load(String json) throws Exception {
        return HarnessSettingsLoader.load(Files.writeString(dir.resolve("s.json"), json));
    }

    @Test
    void defaultsToRead() throws Exception {
        assertEquals(ExternalLinks.READ, HarnessSettings.empty().externalLinks());
        assertEquals(ExternalLinks.READ, load("{}").externalLinks());
        assertEquals(ExternalLinks.READ, load("{\"files\":{\"maxReadBytes\":5}}").externalLinks());
    }

    @Test
    void readsTheThreeValues() throws Exception {
        assertEquals(ExternalLinks.NONE, load("{\"files\":{\"externalLinks\":\"none\"}}").externalLinks());
        assertEquals(ExternalLinks.READ, load("{\"files\":{\"externalLinks\":\"read\"}}").externalLinks());
        assertEquals(ExternalLinks.READ_WRITE, load("{\"files\":{\"externalLinks\":\"readwrite\"}}").externalLinks());
        assertEquals(ExternalLinks.READ_WRITE, load("{\"files\":{\"externalLinks\":\"ReadWrite\"}}").externalLinks());
    }

    @Test
    void anUnknownValueIsRefusedAtLoad() {
        assertThrows(RuntimeException.class, () -> load("{\"files\":{\"externalLinks\":\"write\"}}"));
    }

    @Test
    void theBuilderChangesOnlyThisKey() throws Exception {
        HarnessSettings s = load("{\"files\":{\"maxReadBytes\":2000,\"scopedInstructionsDepth\":3}}");

        HarnessSettings changed = s.toBuilder().externalLinks(ExternalLinks.NONE).build();

        assertEquals(ExternalLinks.NONE, changed.externalLinks());
        assertEquals(2000, changed.filesMaxReadBytes());
        assertEquals(3, changed.scopedInstructionsDepth());
        assertEquals(ExternalLinks.NONE, HarnessSettings.empty().toBuilder().externalLinks(ExternalLinks.NONE).build()
                .externalLinks());
        assertEquals(ExternalLinks.READ_WRITE, changed.toBuilder().externalLinks(ExternalLinks.READ_WRITE).build().externalLinks());
    }

    @Test
    void theConfigCarriesTheMode() throws Exception {
        assertEquals(ExternalLinks.READ, HarnessConfigMapper.apply(HarnessSettings.empty(), HarnessConfig.defaults()).externalLinks());
        HarnessConfig c = HarnessConfigMapper.apply(load("{\"files\":{\"externalLinks\":\"readwrite\"}}"),
                HarnessConfig.defaults());
        assertEquals(ExternalLinks.READ_WRITE, c.externalLinks());
        assertEquals(ExternalLinks.NONE, c.withExternalLinks(ExternalLinks.NONE).externalLinks());
        assertEquals(c.maxTurns(), c.withExternalLinks(ExternalLinks.NONE).maxTurns());
    }
}
