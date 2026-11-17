package org.kendar.llm.eval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.eval.TestSupport.*;

@Tag("unit")
class SelfGradedTest {

    private static final Map<String, String> KEY = map("ANTHROPIC_API_KEY", "k-1234");

    @AfterEach
    void reset() {
        Secrets.clear();
    }

    @Test
    void anthropicTargetWithAnthropicJudgeIsSelfGraded() {
        assertTrue(load(map(), KEY).selfGraded());
    }

    @Test
    void anthropicTargetWithDifferentJudgeModelIsNot() {
        assertFalse(load(map("eval.judge.anthropic.model", "claude-sonnet-5-5"), KEY).selfGraded());
    }

    @Test
    void remoteTargetWithAnthropicJudgeIsNot() {
        assertFalse(load(map("eval.target", "remote.mac"), KEY).selfGraded());
    }

    @Test
    void remoteTargetAndOpenAiJudgeOnSameServerAndModelIsSelfGraded() {
        assertTrue(load(map("eval.target", "remote.mac", "eval.judge", "openai",
                "eval.judge.openai.baseUrl", "http://127.0.0.1:8080/v1/",
                "eval.judge.openai.model", "qwen3.6-27b"), map()).selfGraded());
    }

    @Test
    void sameServerOtherModelIsNot() {
        assertFalse(load(map("eval.target", "remote.mac", "eval.judge", "openai",
                "eval.judge.openai.baseUrl", "http://localhost:8080/v1",
                "eval.judge.openai.model", "other"), map()).selfGraded());
    }

    @Test
    void localTargetJudgedByItselfIsSelfGraded() {
        assertTrue(load(map("eval.target", "local.strong", "eval.judge", "openai",
                "eval.judge.openai.baseUrl", "http://localhost:8080/v1",
                "eval.judge.openai.model", "qwen3.6-27b"), map()).selfGraded());
    }
}
