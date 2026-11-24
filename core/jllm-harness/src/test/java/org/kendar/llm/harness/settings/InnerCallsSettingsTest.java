package org.kendar.llm.harness.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code loop.innerCallsPerTurn}: default 10, set by the key, zero passes through (counting off). */
class InnerCallsSettingsTest {

    @TempDir
    Path dir;

    private HarnessConfig config(String json) throws Exception {
        HarnessSettings s = HarnessSettingsLoader.load(Files.writeString(dir.resolve("s.json"), json));
        return HarnessConfigMapper.apply(s, HarnessConfig.defaults());
    }

    @Test
    void defaultsToTen() throws Exception {
        assertEquals(10, HarnessConfig.defaults().loop().innerCallsPerTurn());
        assertEquals(10, config("{}").loop().innerCallsPerTurn());
        assertEquals(10, config("{\"loop\":{\"stuck\":{\"repeat\":4}}}").loop().innerCallsPerTurn());
    }

    @Test
    void theKeySetsIt() throws Exception {
        assertEquals(25, config("{\"loop\":{\"innerCallsPerTurn\":25}}").loop().innerCallsPerTurn());
    }

    @Test
    void zeroOrLessKeepsItAsTheOffSwitchAndTheOtherLoopKeysSurvive() throws Exception {
        HarnessConfig c = config("{\"loop\":{\"innerCallsPerTurn\":0,\"verify\":{\"maxReentries\":1}}}");

        assertEquals(0, c.loop().innerCallsPerTurn());
        assertEquals(1, c.loop().verify().maxReentries());
        assertEquals(-3, config("{\"loop\":{\"innerCallsPerTurn\":-3}}").loop().innerCallsPerTurn());
    }
}
