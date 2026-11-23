package org.kendar.llm.harness.context.compaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.context.RequestPrefix;
import org.kendar.llm.harness.context.TokenEstimate;
import org.kendar.llm.harness.llm.Usage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenEstimatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void estimateUsesCharsOrTheLargerProviderFigure() {
        ArrayNode m = MAPPER.createArrayNode();
        m.addObject().put("role", "user").put("content", "x".repeat(700));
        long est = TokenEstimate.tokens(m.toString().length());
        assertEquals(est, TokenEstimator.estimateTokens(m, null));
        assertEquals(est, TokenEstimator.estimateTokens(m, new Usage(1, 0, 0, 0)));
        assertEquals(1_000_000, TokenEstimator.estimateTokens(m, new Usage(1_000_000, 0, 0, 0)));
    }

    @Test
    void fixedPartCountsPinnedAndExtraAndToleratesNulls() {
        assertEquals(0, TokenEstimator.fixedChars(null));
        assertEquals(0, TokenEstimator.fixedTokens(null));
        assertEquals(12, TokenEstimator.fixedChars(new RequestPrefix("1234", "12345678", null, null, false)));
        assertEquals(4, TokenEstimator.fixedTokens(new RequestPrefix("1234", "12345678", null, null, false)));
        assertEquals(4, TokenEstimator.fixedChars(new RequestPrefix("1234", null, null, null, false)));
    }

    @Test
    void scaledDividesByRatioOnlyWhenPositive() {
        assertEquals(1000, TokenEstimator.scaled(1000, 1.0));
        assertEquals(500, TokenEstimator.scaled(1000, 2.0));
        assertEquals(2000, TokenEstimator.scaled(1000, 0.5));
        assertEquals(1000, TokenEstimator.scaled(1000, 0));
        assertEquals(1000, TokenEstimator.scaled(1000, -1));
    }
}
