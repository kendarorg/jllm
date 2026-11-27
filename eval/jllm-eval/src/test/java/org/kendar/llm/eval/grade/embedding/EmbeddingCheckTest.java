package org.kendar.llm.eval.grade.embedding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.kendar.llm.eval.EvalConfig;
import org.kendar.llm.eval.grade.CheckResult;
import org.kendar.llm.eval.grade.TrialContext;
import org.kendar.llm.eval.run.TrialTrace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("unit")
class EmbeddingCheckTest {

    private static final Embedder FAKE = text -> switch (text) {
        case "same" -> new float[]{1, 0};
        case "near" -> new float[]{0.8f, 0.6f};
        case "far" -> new float[]{0, 1};
        default -> new float[]{-1, 0};
    };

    private static TrialContext ctx(String answer) {
        return new TrialContext("c1", new TrialTrace(List.of(), List.of(), answer, null, 0), Path.of("."), Map.of(),
                List.of("q"), Map.of());
    }

    @Test
    void passesAtOrAboveThresholdUsingBestReference() {
        EmbeddingCheck c = new EmbeddingCheck(FAKE, List.of("far", "same"), 0.75, false);
        assertEquals(CheckResult.Status.PASS, c.check(ctx("near")).status());
    }

    @Test
    void failsBelowThreshold() {
        EmbeddingCheck c = new EmbeddingCheck(FAKE, List.of("same"), 0.9, false);
        assertEquals(CheckResult.Status.FAIL, c.check(ctx("far")).status());
    }

    @Test
    void riskCaseOnlyWarns() {
        EmbeddingCheck c = new EmbeddingCheck(FAKE, List.of("same"), 0.9, true);
        assertEquals(CheckResult.Status.WARN, c.check(ctx("far")).status());
        assertEquals(CheckResult.Status.WARN, c.check(ctx(" ")).status());
    }

    @Test
    void keepsAnswerVectorsPerCase() {
        EmbeddingCheck c = new EmbeddingCheck(FAKE, List.of("same"), 0.5, false);
        c.check(ctx("near"));
        c.check(ctx("same"));
        assertEquals(2, c.answerVectors().get("c1").size());
        assertEquals(Check_TIER, c.tier());
    }

    private static final org.kendar.llm.eval.grade.Check.Tier Check_TIER = org.kendar.llm.eval.grade.Check.Tier.EMBEDDING;

    @Test
    void loaderRefusesWhenHashUnset() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> OnnxEmbedder.open(Path.of("nowhere"), null, Path.of("target/x")));
        assertTrue(e.getMessage().contains("eval.embedding.sha256"));
    }

    @Test
    void modelEmbedsAndRanksParaphraseAboveUnrelated() throws Exception {
        EvalConfig cfg = EvalConfig.load(propsFile(), java.util.Map.of(), java.util.Map.of("ANTHROPIC_API_KEY", "k-test-1"));
        Path dir = cfg.embeddingModelDir();
        assumeTrue(OnnxEmbedder.filesPresent(dir), "embedding model files not present in " + dir);
        try (OnnxEmbedder e = OnnxEmbedder.open(dir, cfg.embeddingSha256(), Files.createTempDirectory("embcache"))) {
            float[] v = e.embed("The file has 42 lines.");
            assertEquals(384, v.length);
            assertEquals(1.0, Embedder.dot(v, v), 1e-3);
            double close = e.similarity("The file has 42 lines.", "It contains forty-two lines.");
            double far = e.similarity("The file has 42 lines.", "Bananas are rich in potassium.");
            assertTrue(close > far, close + " vs " + far);
            assertArrayEquals(v, e.embed("The file has 42 lines."));
        }
    }

    private static java.util.Properties propsFile() throws java.io.IOException {
        java.util.Properties p = new java.util.Properties();
        try (var in = EmbeddingCheckTest.class.getResourceAsStream("/eval.properties")) {
            p.load(in);
        }
        return p;
    }
}
