package org.kendar.llm.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.grade.Check;
import org.kendar.llm.eval.grade.CheckResult;
import org.kendar.llm.eval.pin.Fingerprint;
import org.kendar.llm.harness.context.ContextStrategyKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.kendar.llm.eval.TestSupport.load;
import static org.kendar.llm.eval.TestSupport.map;

@Tag("unit")
class FingerprintTest {

    private static final String KEY = "sk-secret-value-1234567890";

    @TempDir
    Path tmp;

    private static Fingerprint.Settings settings(Map<String, String> sys) {
        return Fingerprint.settings(load(sys, map("ANTHROPIC_API_KEY", KEY)));
    }

    @Test
    void theOrderTheSettingsAreGivenInDoesNotMatter() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("eval.trials", "4");
        a.put("eval.maxTrials", "6");
        a.put("eval.caseTimeoutSeconds", "120");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("eval.caseTimeoutSeconds", "120");
        b.put("eval.maxTrials", "6");
        b.put("eval.trials", "4");
        assertEquals(settings(a).hash(), settings(b).hash());
        assertEquals(settings(a).canonicalJson(), settings(b).canonicalJson());
        assertEquals(64, settings(a).hash().length());
    }

    @Test
    void canonicalJsonSortsTheKeysAtEveryLevel() throws Exception {
        ObjectMapper m = new ObjectMapper();
        String x = m.writeValueAsString(Fingerprint.canonical(m.readTree("{\"b\":{\"z\":1,\"a\":2},\"a\":[{\"y\":1,\"x\":2}]}")));
        String y = m.writeValueAsString(Fingerprint.canonical(m.readTree("{\"a\":[{\"x\":2,\"y\":1}],\"b\":{\"a\":2,\"z\":1}}")));
        assertEquals(x, y);
        assertEquals("{\"a\":[{\"x\":2,\"y\":1}],\"b\":{\"a\":2,\"z\":1}}", x);
    }

    @Test
    void anyResultRelevantSettingChangesTheHash() {
        String base = settings(map()).hash();
        assertNotEquals(base, settings(map("eval.trials", "5")).hash());
        assertNotEquals(base, settings(map("eval.maxTrials", "4")).hash());
        assertNotEquals(base, settings(map("eval.passK", "1,2")).hash());
        assertNotEquals(base, settings(map("eval.caseTimeoutSeconds", "30")).hash());
        assertNotEquals(base, settings(map("eval.systemPrompt.extra", "call tools as JSON")).hash());
        assertNotEquals(base, settings(map("eval.anthropic.model", "claude-sonnet-5-5")).hash());
        assertNotEquals(base, settings(map("eval.compaction.contextTokens", "32768")).hash());
    }

    @Test
    void settingsThatOnlySayWhereResultsGoOrWhatToDoWithThemDoNotChangeTheHash() {
        String base = settings(map()).hash();
        assertEquals(base, settings(map("eval.dirtyTree", "refuse")).hash());
        assertEquals(base, settings(map("eval.outputDir", "elsewhere")).hash());
        assertEquals(base, settings(map("eval.updateBaseline", "true")).hash());
    }

    @Test
    void anExplicitDefaultEqualsTheImplicitOne() {
        assertEquals(settings(map()).hash(), settings(map("eval.maxTrials", "10", "eval.passK", "1,10")).hash());
        // above the maximum is clipped to it, a duplicate removed
        assertEquals(settings(map()).hash(), settings(map("eval.passK", "10,1,1,99")).hash());
    }

    @Test
    void noApiKeyValueIsHashedOrWritten() throws Exception {
        Fingerprint.Settings s = settings(map());
        assertFalse(s.canonicalJson().contains(KEY));
        assertFalse(s.canonicalJson().contains("apiKey"), s.canonicalJson());
        // another key gives the same fingerprint: the key is not an input
        EvalConfig other = load(map(), map("ANTHROPIC_API_KEY", "sk-another-value-0987654321"));
        assertEquals(s.hash(), Fingerprint.settings(other).hash());
        assertFalse(Fingerprint.settings(other).canonicalJson().contains("0987654321"));
        // the saved copy is masked even when a secret sits inside a hashed free-text setting
        EvalConfig c = load(map("eval.systemPrompt.extra", "note " + KEY), map("ANTHROPIC_API_KEY", KEY));
        Path file = Fingerprint.settings(c).write(tmp, c.secrets());
        String saved = Files.readString(file);
        assertFalse(saved.contains(KEY), saved);
        assertTrue(saved.contains("***"));
        assertEquals("settings.json", file.getFileName().toString());
    }

    @Test
    void theHarnessConfigurationIsPartOfTheFingerprint() {
        String json = settings(map()).canonicalJson();
        assertTrue(json.contains("\"harness\""), json);
        assertTrue(json.contains("\"agentId\" : \"jllm\""), json);
        assertTrue(json.contains("<workspace>"), "no machine specific path: " + json);
        assertFalse(json.contains(System.getProperty("java.io.tmpdir").replace("\\", "\\\\")), json);
    }

    @Test
    void theHarnessPromptsHaveAStableHash() {
        String a = Fingerprint.prompts();
        assertEquals(64, a.length(), a);
        assertEquals(a, Fingerprint.prompts());
    }

    private static EvalCase.Builder base() {
        return EvalCase.named("c").user("hello").check(Check.of("is-ok", Check.Tier.TRACE,
                ctx -> CheckResult.pass("is-ok", "")));
    }

    @Test
    void aCaseHashFollowsTheDescriptionAndTheAppliedConfiguration() {
        ContextStrategyKind s = ContextStrategyKind.COMPACTION;
        String h = Fingerprint.caseHash(base().build(), s);
        assertEquals(64, h.length());
        assertEquals(h, Fingerprint.caseHash(base().build(), s));
        assertNotEquals(h, Fingerprint.caseHash(base().build(), ContextStrategyKind.SCOPE));
        assertNotEquals(h, Fingerprint.caseHash(EvalCase.named("c").user("hello again").check(Check.of("is-ok",
                Check.Tier.TRACE, ctx -> CheckResult.pass("is-ok", ""))).build(), s));
        assertNotEquals(h, Fingerprint.caseHash(base().check(Check.of("more", Check.Tier.TRACE,
                ctx -> CheckResult.pass("more", ""))).build(), s));
        assertNotEquals(h, Fingerprint.caseHash(base().risk().build(), s));
        assertNotEquals(h, Fingerprint.caseHash(base().config(c -> c.withMaxTokens(1234)).build(), s));
        assertEquals(h, Fingerprint.caseHash(base().config(c -> c).build(), s));
    }
}
