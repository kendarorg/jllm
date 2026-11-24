package org.kendar.llm.js;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class JsSettingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsSettings parse(String json) throws Exception {
        return JsSettings.from(MAPPER.readTree(json));
    }

    @Test
    void defaultsAreThePlansValues() {
        JsSettings d = JsSettings.defaults();

        assertEquals(1_000_000L, d.statementLimit());
        assertEquals(120, d.maxTimeoutSeconds());
        assertEquals(10, d.gracefulSeconds());
        assertTrue(d.fileJail());
        assertEquals(300, d.maxWallSeconds());
        assertEquals(200, d.maxInnerCalls());
        assertEquals(4, d.maxConcurrentRuns());
        assertEquals(JsSettings.EngineChoice.AUTO, d.engine());
        assertEquals(JsSettings.Policy.ISOLATED, d.policy());
        assertEquals(256L * 1024 * 1024, d.maxHeapMemory());
        assertEquals(1024L * 1024 * 1024, d.maxIsolateMemory());
        assertEquals(2000, d.maxStackFrames());
        assertEquals(1000, d.maxAstDepth());
        assertEquals(1024L * 1024 * 1024, d.maxRunAllocatedBytes());
        assertEquals(0.85, d.heapKillRatio());
    }

    @Test
    void anAbsentOrEmptySectionGivesTheDefaults() throws Exception {
        assertEquals(JsSettings.defaults(), JsSettings.from(null));
        assertEquals(JsSettings.defaults(), parse("{}"));
        assertEquals(JsSettings.defaults(), parse("[]"));
    }

    @Test
    void everyKeyIsRead() throws Exception {
        JsSettings s = parse("{\"statementLimit\":5,\"maxTimeoutSeconds\":6,\"gracefulSeconds\":7,\"fileJail\":false,"
                + "\"maxWallSeconds\":8,\"maxInnerCalls\":9,\"maxConcurrentRuns\":0,\"engine\":\"Constrained\","
                + "\"policy\":\"untrusted\",\"maxHeapMemory\":\"64MB\",\"maxIsolateMemory\":\"2GB\","
                + "\"maxStackFrames\":10,\"maxAstDepth\":11,\"maxRunAllocatedBytes\":4096,\"heapKillRatio\":0.5}");

        assertEquals(new JsSettings(5, 6, 7, false, 8, 9, 0, JsSettings.EngineChoice.CONSTRAINED,
                JsSettings.Policy.UNTRUSTED, 64L << 20, 2L << 30, 10, 11, 4096, 0.5), s);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "statementLimit|0", "statementLimit|-1", "statementLimit|\"many\"", "maxTimeoutSeconds|0",
            "gracefulSeconds|-5", "maxWallSeconds|0", "maxInnerCalls|0", "maxConcurrentRuns|-1",
            "engine|\"fast\"", "engine|3", "policy|\"loose\"", "maxHeapMemory|0", "maxHeapMemory|\"lots\"",
            "maxIsolateMemory|-4", "maxStackFrames|0", "maxAstDepth|-1", "maxRunAllocatedBytes|0",
            "heapKillRatio|0", "heapKillRatio|1.5", "heapKillRatio|\"high\"", "fileJail|\"yes\""})
    void invalidValuesFailWithAMessageNamingTheKey(String key, String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse("{\"" + key + "\":" + value + "}"));

        assertTrue(e.getMessage().contains("plugins.js.settings." + key), e.getMessage());
    }

    @Test
    void sizesTakeUnitsAndPlainBytes() throws Exception {
        assertEquals(512L * 1024, parse("{\"maxHeapMemory\":\"512KB\"}").maxHeapMemory());
        assertEquals(3L << 30, parse("{\"maxHeapMemory\":\"3gb\"}").maxHeapMemory());
        assertEquals(1000, parse("{\"maxHeapMemory\":1000}").maxHeapMemory());
        assertEquals(1000, parse("{\"maxHeapMemory\":\"1000\"}").maxHeapMemory());
    }

    @Test
    void theErrorListsTheAllowedEngineValues() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parse("{\"engine\":\"x\"}"));

        assertTrue(e.getMessage().contains("auto") && e.getMessage().contains("constrained")
                && e.getMessage().contains("isolated"), e.getMessage());
    }
}
