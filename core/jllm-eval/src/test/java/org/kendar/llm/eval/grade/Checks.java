package org.kendar.llm.eval.grade;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Predicate;

/** Entry point for the fluent case API: every deterministic, cache, strategy and retention check. */
public final class Checks {

    private Checks() {
    }

    // trace
    public static Check toolCalled(String tool) { return TraceChecks.toolCalled(tool); }
    public static Check toolNotCalled(String tool) { return TraceChecks.toolNotCalled(tool); }
    public static Check toolCalledWith(String tool, String field, String contains) { return TraceChecks.toolCalledWith(tool, field, contains); }
    public static Check toolCalledWith(String tool, Predicate<JsonNode> test, String description) { return TraceChecks.toolCalledWith(tool, test, description); }
    public static Check toolOrder(String... tools) { return TraceChecks.toolOrder(tools); }
    public static Check approvalRequested() { return TraceChecks.approvalRequested(); }
    public static Check approvalRequested(String text) { return TraceChecks.approvalRequested(text); }
    public static Check approvalNotRequested() { return TraceChecks.approvalNotRequested(); }
    public static Check eventPresent(String type) { return TraceChecks.eventPresent(type); }
    public static Check eventAbsent(String type) { return TraceChecks.eventAbsent(type); }
    public static Check maxToolCalls(int max) { return TraceChecks.maxToolCalls(max); }
    public static Check maxDuplicateCalls(int max) { return TraceChecks.maxDuplicateCalls(max); }
    public static Check noRunError() { return TraceChecks.noRunError(); }
    public static Check answerContains(String text) { return TraceChecks.answerContains(text); }
    public static Check answerNotContains(String text) { return TraceChecks.answerNotContains(text); }
    public static Check answerMatches(String regex) { return TraceChecks.answerMatches(regex); }

    // state
    public static Check fileEquals(String path, String expected) { return StateChecks.fileEquals(path, expected); }
    public static Check fileContains(String path, String expected) { return StateChecks.fileContains(path, expected); }
    public static Check fileMatches(String path, Predicate<String> test, String description) { return StateChecks.fileMatches(path, test, description); }
    public static Check fileMissing(String path) { return StateChecks.fileMissing(path); }
    public static Check filesUnchanged(String... paths) { return StateChecks.filesUnchanged(paths); }
    public static Check workspaceUnchanged() { return StateChecks.workspaceUnchanged(); }
    public static Check noWritesOutsideWorkspace() { return StateChecks.noWritesOutsideWorkspace(); }
    public static Check serverCalled(String extrasKey) { return StateChecks.serverCalled(extrasKey); }
    public static Check serverNotCalled(String extrasKey) { return StateChecks.serverNotCalled(extrasKey); }
    public static Check serverCalledWith(String extrasKey, String text) { return StateChecks.serverCalledWith(extrasKey, text); }
    public static Check noCanaryLeak(String extrasKey) { return StateChecks.noCanaryLeak(extrasKey); }

    // cache
    public static Check cachedRatioAtLeast(double min, boolean sharedServer) { return CacheChecks.cachedRatioAtLeast(min, sharedServer); }
    public static Check breaksAsExpected(CacheChecks.ExpectedBreak expected) { return CacheChecks.breaksAsExpected(expected); }
    public static Check breakOccurs(CacheChecks.ExpectedBreak expected) { return CacheChecks.breakOccurs(expected); }
    public static Check prefixStable(double minFraction) { return CacheChecks.prefixStable(minFraction); }

    // strategies
    public static Check mnemonicLogNeverRewritten() { return StrategyChecks.mnemonicLogNeverRewritten(); }
    public static Check mnemonicRecallFindsMasked(String expectedContent) { return StrategyChecks.mnemonicRecallFindsMasked(expectedContent); }
    public static Check mnemonicGuardBlocksMaskedWrites(boolean requireAttempt) { return StrategyChecks.mnemonicGuardBlocksMaskedWrites(requireAttempt); }
    public static Check scopeOffloadsLargeOutputs(int maxChars) { return StrategyChecks.scopeOffloadsLargeOutputs(maxChars); }
    public static Check scopePinned() { return StrategyChecks.scopePinned(); }
    public static Check compactionSummaryHasSections() { return StrategyChecks.compactionSummaryHasSections(); }

    // retention
    public static Check retained(Similarity similarity, Judge judge, RetentionChecks.Fact... facts) { return RetentionChecks.retained(similarity, judge, facts); }
}
