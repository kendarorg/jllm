package org.kendar.llm.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.eval.TestSupport.*;

@Tag("unit")
class EvalConfigTest {

    @Test
    void anthropicDefaultsToHaiku() {
        EvalConfig c = load(map(), map("ANTHROPIC_API_KEY", "sk-test-1"));
        assertEquals(EvalConfig.TargetKind.ANTHROPIC, c.targetKind());
        assertEquals("claude-haiku-5-5", c.target().model());
        assertEquals("anthropic", c.judgeKind());
        assertEquals("claude-sonnet-5-5", c.judge().model());
        assertFalse(c.selfGraded());
    }

    @Test
    void modelNamesDefaultToTheIdsAndCanBeOverridden() {
        EvalConfig d = load(map(), map("ANTHROPIC_API_KEY", "sk-test-1"));
        assertEquals("claude-haiku-5-5", d.targetModelName());
        assertEquals("claude-sonnet-5-5", d.judgeModelName());
        EvalConfig o = load(map("eval.target.modelName", "Qwen3.6-35B"),
                map("ANTHROPIC_API_KEY", "sk-test-1", "EVAL_JUDGE_MODELNAME", "Sonnet"));
        assertEquals("Qwen3.6-35B", o.targetModelName());
        assertEquals("claude-haiku-5-5", o.target().model());
        assertEquals("Sonnet", o.judgeModelName());
        assertEquals("claude-sonnet-5-5", o.judge().model());
        assertFalse(o.selfGraded());
    }

    @Test
    void commandLineBeatsEnvironmentBeatsFile() {
        EvalConfig c = load(map("eval.trials", "7"), map("EVAL_TRIALS", "5", "ANTHROPIC_API_KEY", "k-1234"));
        assertEquals(7, c.trials());
        EvalConfig d = load(map(), map("EVAL_TRIALS", "5", "ANTHROPIC_API_KEY", "k-1234"));
        assertEquals(5, d.trials());
    }

    @Test
    void maxTrialsAndCaseTimeoutHaveDefaultsAndOverrides() {
        EvalConfig d = load(map(), map("ANTHROPIC_API_KEY", "k-1234"));
        assertEquals(10, d.maxTrials());
        assertEquals(0, d.caseTimeoutSeconds());
        EvalConfig o = load(map("eval.maxTrials", "2", "eval.caseTimeoutSeconds", "300"), map("ANTHROPIC_API_KEY", "k-1234"));
        assertEquals(2, o.maxTrials());
        assertEquals(300, o.caseTimeoutSeconds());
        EvalConfig z = load(map("eval.caseTimeoutSeconds", "0"), map("ANTHROPIC_API_KEY", "k-1234"));
        assertEquals(0, z.caseTimeoutSeconds());
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
    void fastDefaultsToTargetAndCanBeSeparate() {
        EvalConfig same = load(map(), map("ANTHROPIC_API_KEY", "k-1234"));
        assertFalse(same.fastSeparate());
        EvalConfig sep = load(map("eval.target", "remote.mac", "eval.fast", "anthropic"),
                map("ANTHROPIC_API_KEY", "k-1234"));
        assertTrue(sep.fastSeparate());
        assertEquals("claude-haiku-5-5", sep.fast().model());
    }

    @Test
    void passKDefaultsToOneAndTheMaximumTrials() {
        assertEquals(java.util.List.of(1, 10), load(map(), map("ANTHROPIC_API_KEY", "k-1234")).passK());
        assertEquals(java.util.List.of(1, 3),
                load(map("eval.maxTrials", "3"), map("ANTHROPIC_API_KEY", "k-1234")).passK());
        assertEquals(java.util.List.of(1), load(map("eval.maxTrials", "1"), map("ANTHROPIC_API_KEY", "k-1234")).passK());
    }

    @Test
    void passKIsDeduplicatedSortedAndClippedToTheMaximumTrials() {
        EvalConfig c = load(map("eval.passK", "5, 2,2, 20,1", "eval.maxTrials", "8"), map("ANTHROPIC_API_KEY", "k-1234"));
        assertEquals(java.util.List.of(1, 2, 5, 8), c.passK());
        assertEquals(java.util.List.of(2), load(map("eval.passK", "2"), map("ANTHROPIC_API_KEY", "k-1234")).passK());
    }

    @Test
    void invalidPassKAndDirtyTreeAreReported() {
        EvalConfigException e = assertThrows(EvalConfigException.class, () -> load(
                map("eval.passK", "1,x", "eval.dirtyTree", "maybe"), map("ANTHROPIC_API_KEY", "k-1234")));
        assertTrue(e.getMessage().contains("eval.passK"), e.getMessage());
        assertTrue(e.getMessage().contains("eval.dirtyTree"), e.getMessage());
        assertThrows(EvalConfigException.class,
                () -> load(map("eval.passK", "0"), map("ANTHROPIC_API_KEY", "k-1234")));
    }

    @Test
    void dirtyTreeDefaultsToWarn() {
        assertEquals(org.kendar.llm.eval.pin.DirtyTreePolicy.WARN, load(map(), map("ANTHROPIC_API_KEY", "k-1234")).dirtyTree());
        assertEquals(org.kendar.llm.eval.pin.DirtyTreePolicy.REFUSE,
                load(map("eval.dirtyTree", "Refuse"), map("ANTHROPIC_API_KEY", "k-1234")).dirtyTree());
        assertEquals(org.kendar.llm.eval.pin.DirtyTreePolicy.ALLOW,
                load(map(), map("ANTHROPIC_API_KEY", "k-1234", "EVAL_DIRTYTREE", "allow")).dirtyTree());
    }

    @Test
    void resolvedKeysNeverHoldASecretOrAResultIrrelevantKey() {
        EvalConfig c = load(map("eval.anthropic.apiKey", "sk-secret-123456", "eval.dirtyTree", "refuse"),
                map("ANTHROPIC_API_KEY", "k-1234"));
        assertTrue(c.resolvedKeys().containsKey("eval.trials"));
        assertTrue(c.resolvedKeys().containsKey("eval.passK"));
        assertFalse(c.resolvedKeys().containsKey("eval.dirtyTree"));
        assertFalse(c.resolvedKeys().keySet().stream().anyMatch(k -> k.endsWith(".apiKey")));
        assertFalse(c.resolvedKeys().values().stream().anyMatch(v -> v.contains("sk-secret-123456")));
    }
}
