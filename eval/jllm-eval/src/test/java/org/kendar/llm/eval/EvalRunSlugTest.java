package org.kendar.llm.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class EvalRunSlugTest {

    @Test
    void modelBecomesFolderName() {
        assertEquals("claude-haiku-5-5", EvalRun.slug("claude-haiku-5-5"));
        assertEquals("qwen3.6-27b", EvalRun.slug("Qwen3.6-27B"));
        assertEquals("org-model-v1", EvalRun.slug("org/model:v1"));
        assertEquals("model", EvalRun.slug("///"));
    }
}
