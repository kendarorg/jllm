package org.kendar.llm.eval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.eval.TestSupport.*;

@Tag("unit")
class EvalConfigTest {

    @AfterEach
    void reset() {
        Secrets.clear();
    }

    @Test
    void anthropicDefaultsToHaiku() {
        EvalConfig c = load(map(), map("ANTHROPIC_API_KEY", "sk-test-1"));
        assertEquals(EvalConfig.TargetKind.ANTHROPIC, c.targetKind());
        assertEquals("claude-haiku-5-5", c.target().model());
        assertEquals("anthropic", c.judgeKind());
        assertTrue(c.selfGraded());
    }

    @Test
    void commandLineBeatsEnvironmentBeatsFile() {
        EvalConfig c = load(map("eval.trials", "7"), map("EVAL_TRIALS", "5", "ANTHROPIC_API_KEY", "k-1234"));
        assertEquals(7, c.trials());
        EvalConfig d = load(map(), map("EVAL_TRIALS", "5", "ANTHROPIC_API_KEY", "k-1234"));
        assertEquals(5, d.trials());
    }

    @Test
    void reportsEveryInvalidKeyAtOnce() {
        EvalConfigException e = assertThrows(EvalConfigException.class, () -> load(
                map("eval.target", "remote.mac", "eval.remote.mac.baseUrl", "nonsense",
                        "eval.remote.mac.contextTokens", "abc", "eval.trials", "0", "eval.judge", "both"), map()));
        String m = e.getMessage();
        assertTrue(m.contains("eval.remote.mac.baseUrl"), m);
        assertTrue(m.contains("eval.remote.mac.contextTokens"), m);
        assertTrue(m.contains("eval.trials"), m);
        assertTrue(m.contains("eval.judge"), m);
        assertTrue(e.errors().size() >= 4);
    }

    @Test
    void missingAnthropicKeyIsReported() {
        EvalConfigException e = assertThrows(EvalConfigException.class, () -> load(map(), map()));
        assertTrue(e.getMessage().contains("apiKey"), e.getMessage());
    }

    @Test
    void secretInPropertiesFileIsRejected() {
        Properties p = file();
        p.setProperty("eval.anthropic.apiKey", "sk-in-file");
        EvalConfigException e = assertThrows(EvalConfigException.class,
                () -> EvalConfig.load(p, map(), map("ANTHROPIC_API_KEY", "k-1234")));
        assertTrue(e.getMessage().contains("eval.anthropic.apiKey"));
        assertFalse(e.getMessage().contains("sk-in-file"));
    }

    @Test
    void localTargetUsesAliasWithoutQuantizationAndConfiguredContext() {
        EvalConfig c = load(map("eval.target", "local.strong", "eval.local.model.strong.contextTokens", "49152",
                "eval.judge", "openai"), map());
        assertEquals("qwen3.6-27b", c.target().model());
        assertEquals(49152, c.target().contextTokens());
        assertEquals(49152, c.local().model().contextTokens());
        assertEquals("Q4_K_M", c.local().model().quantization());
        assertEquals(49152, c.target().toProviderSettings().contextTokens());
        assertFalse(c.local().model().alias().toLowerCase().contains("q4"));
    }

    @Test
    void aliasWithQuantizationIsRejected() {
        EvalConfigException e = assertThrows(EvalConfigException.class, () -> load(
                map("eval.target", "local.strong", "eval.local.model.strong.alias", "qwen3.6-27b-Q4_K_M",
                        "eval.judge", "openai"), map()));
        assertTrue(e.getMessage().contains("alias"));
    }

    @Test
    void contextAboveModelMaximumIsRejected() {
        EvalConfigException e = assertThrows(EvalConfigException.class, () -> load(
                map("eval.target", "local.alt", "eval.local.model.alt.maxContextTokens", "8192",
                        "eval.judge", "openai"), map()));
        assertTrue(e.getMessage().contains("maxContextTokens"));
    }

    @Test
    void unpinnedImageIsRejected() {
        EvalConfigException e = assertThrows(EvalConfigException.class, () -> load(
                map("eval.target", "local.alt", "eval.local.image", "ghcr.io/ggml-org/llama.cpp:latest",
                        "eval.judge", "openai"), map()));
        assertTrue(e.getMessage().contains("eval.local.image"));
    }

    @Test
    void fastDefaultsToTargetAndCanBeSeparate() {
        EvalConfig same = load(map(), map("ANTHROPIC_API_KEY", "k-1234"));
        assertFalse(same.fastSeparate());
        EvalConfig sep = load(map("eval.target", "remote.mac", "eval.fast", "anthropic"),
                map("ANTHROPIC_API_KEY", "k-1234"));
        assertTrue(sep.fastSeparate());
        assertEquals("claude-haiku-5-5", sep.fast().model());
    }
}
