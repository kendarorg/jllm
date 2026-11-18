package org.kendar.llm.eval.grade;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.llm.Usage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.kendar.llm.eval.grade.CacheChecks.ExpectedBreak;
import static org.kendar.llm.eval.grade.CheckResult.Status.FAIL;
import static org.kendar.llm.eval.grade.CheckResult.Status.NOT_MEASURABLE;
import static org.kendar.llm.eval.grade.CheckResult.Status.PASS;
import static org.kendar.llm.eval.grade.CheckResult.Status.WARN;
import static org.kendar.llm.eval.grade.Traces.msg;
import static org.kendar.llm.eval.grade.Traces.toolResultMsg;
import static org.kendar.llm.eval.grade.Traces.usage;

@Tag("unit")
class CacheStrategyChecksTest {

    @TempDir
    Path ws;

    private static void is(CheckResult.Status s, Check c, TrialContext ctx) {
        CheckResult r = c.check(ctx);
        assertEquals(s, r.status(), r.name() + ": " + r.reason());
        assertTrue(!r.reason().isBlank() && !r.reason().contains("\n"));
    }

    private static final List<String> TOOLS = List.of("read_file", "bash");
    private static final String RECEIPT = "[masked: read_file(a.txt) -> ~900 tokens, recall id=rab12]";

    /** Three requests that only append messages. */
    private static Traces stable(Usage u1, Usage u2, Usage u3) {
        var m1 = msg("user", "hello");
        var m2 = msg("assistant", "hi");
        var m3 = msg("user", "next");
        return Traces.trace()
                .request("sys", TOOLS, u1, m1)
                .request("sys", TOOLS, u2, m1, m2, m3)
                .request("sys", TOOLS, u3, m1, m2, m3, msg("assistant", "ok"), msg("user", "more"));
    }

    @Test
    void ratios() {
        var t = stable(usage(1000, 0, 0), usage(100, 900, 0), usage(50, 950, 0));
        assertEquals(List.of(0.0, 0.9, 0.95), CacheChecks.cachedRatios(t.build()));
        is(PASS, Checks.cachedRatioAtLeast(0.8, false), t.ctx());
        is(FAIL, Checks.cachedRatioAtLeast(0.99, false), t.ctx());
        is(WARN, Checks.cachedRatioAtLeast(0.99, true), t.ctx());
        is(WARN, Checks.cachedRatioAtLeast(0.5, true), t.ctx());
    }

    @Test
    void notMeasurableWithoutCachedTokens() {
        var none = stable(usage(1000, 0, 0), usage(1100, 0, 0), usage(1200, 0, 0));
        is(NOT_MEASURABLE, Checks.cachedRatioAtLeast(0.5, false), none.ctx());
        var noUsage = stable(null, null, null);
        is(NOT_MEASURABLE, Checks.cachedRatioAtLeast(0.5, false), noUsage.ctx());
        // prefix checks still run
        is(PASS, Checks.prefixStable(0.95), none.ctx());
        is(PASS, Checks.breaksAsExpected(ExpectedBreak.none()), none.ctx());
    }

    @Test
    void creationTokensCountAsMeasurable() {
        var t = stable(usage(0, 0, 1000), usage(100, 900, 0), usage(100, 900, 0));
        is(PASS, Checks.cachedRatioAtLeast(0.8, false), t.ctx());
    }

    @Test
    void sharedPrefixLength() {
        var t = stable(null, null, null).build();
        var r = t.requests();
        int len = CacheChecks.sharedPrefixLength(r.get(0), r.get(1));
        assertTrue(len > r.get(0).prefix().length());
        assertEquals(CacheChecks.serialize(r.get(0)).length(), len);
        assertEquals(CacheChecks.serialize(r.get(0)).length(), CacheChecks.sharedPrefixLength(r.get(0), r.get(0)));
    }

    @Test
    void appendOnlyHasNoBreaks() {
        var t = stable(null, null, null);
        assertTrue(CacheChecks.breaks(t.build()).isEmpty());
        is(PASS, Checks.breaksAsExpected(ExpectedBreak.none()), t.ctx());
        is(PASS, Checks.prefixStable(1.0), t.ctx());
        is(FAIL, Checks.breakOccurs(ExpectedBreak.systemOnly()), t.ctx());
    }

    @Test
    void modeSwitchBreaksOnlySystem() {
        var m1 = msg("user", "hello");
        var t = Traces.trace()
                .request("sys A", TOOLS, null, m1)
                .request("sys B", TOOLS, null, m1, msg("assistant", "x"));
        is(PASS, Checks.breaksAsExpected(ExpectedBreak.systemOnly()), t.ctx());
        is(PASS, Checks.breakOccurs(ExpectedBreak.systemOnly()), t.ctx());
        is(FAIL, Checks.breaksAsExpected(ExpectedBreak.toolsOnly()), t.ctx());
        is(FAIL, Checks.breaksAsExpected(ExpectedBreak.none()), t.ctx());
        is(PASS, Checks.breaksAsExpected(ExpectedBreak.toolsOnly().or(ExpectedBreak.systemOnly())), t.ctx());
    }

    @Test
    void toolToggleBreaksOnlyTools() {
        var m1 = msg("user", "hello");
        var t = Traces.trace()
                .request("sys", TOOLS, null, m1)
                .request("sys", List.of("read_file"), null, m1);
        is(PASS, Checks.breaksAsExpected(ExpectedBreak.toolsOnly()), t.ctx());
        is(FAIL, Checks.breaksAsExpected(ExpectedBreak.systemOnly()), t.ctx());
        // a tool toggle that also changes a message is unexpected
        var bad = Traces.trace()
                .request("sys", TOOLS, null, m1)
                .request("sys", List.of("read_file"), null, msg("user", "changed"));
        is(FAIL, Checks.breaksAsExpected(ExpectedBreak.toolsOnly()), bad.ctx());
    }

    @Test
    void mnemonicBreaksOnlyAtNewlyMaskedMessage() {
        var m1 = msg("user", "task");
        var m2 = toolResultMsg("t1", "x".repeat(500));
        var masked = toolResultMsg("t1", RECEIPT);
        var good = Traces.trace()
                .request("sys", TOOLS, null, m1, m2)
                .request("sys", TOOLS, null, m1, masked, msg("assistant", "a"));
        is(PASS, Checks.breaksAsExpected(ExpectedBreak.newlyMasked()), good.ctx());
        var bad = Traces.trace()
                .request("sys", TOOLS, null, m1, m2)
                .request("sys", TOOLS, null, m1, toolResultMsg("t1", "something else"));
        is(FAIL, Checks.breaksAsExpected(ExpectedBreak.newlyMasked()), bad.ctx());
        var shrink = Traces.trace()
                .request("sys", TOOLS, null, m1, m2)
                .request("sys", TOOLS, null, m1);
        is(FAIL, Checks.breaksAsExpected(ExpectedBreak.newlyMasked()), shrink.ctx());
    }

    @Test
    void prefixStableDetectsEarlyChange() {
        var t = Traces.trace()
                .request("sys", TOOLS, null, msg("user", "a".repeat(200)), msg("assistant", "b"))
                .request("sys", TOOLS, null, msg("user", "Z" + "a".repeat(199)), msg("assistant", "b"));
        is(FAIL, Checks.prefixStable(0.9), t.ctx());
        is(NOT_MEASURABLE, Checks.prefixStable(0.9), Traces.trace().request("s", TOOLS, null, msg("user", "a")).ctx());
    }

    // ---- strategies -----------------------------------------------------------------------------------------

    @Test
    void mnemonicLogRewrite() {
        var m1 = msg("user", "task");
        var m2 = toolResultMsg("t1", "x".repeat(500));
        var masked = toolResultMsg("t1", RECEIPT);
        var ok = Traces.trace().request("s", TOOLS, null, m1, m2)
                .request("s", TOOLS, null, m1, masked, msg("assistant", "a"));
        is(PASS, Checks.mnemonicLogNeverRewritten(), ok.ctx());

        var bad = Traces.trace().request("s", TOOLS, null, m1, m2)
                .request("s", TOOLS, null, msg("user", "different"), m2);
        is(FAIL, Checks.mnemonicLogNeverRewritten(), bad.ctx());

        var excused = Traces.trace().request("s", TOOLS, null, m1, m2)
                .request("s", TOOLS, null, msg("user", "[Context segment 2]"))
                .event("compaction", "{\"strategy\":\"mnemonic\",\"kind\":\"segment\"}");
        is(PASS, Checks.mnemonicLogNeverRewritten(), excused.ctx());
        var maskOnlyEvent = Traces.trace().request("s", TOOLS, null, m1, m2)
                .request("s", TOOLS, null, msg("user", "different"), m2)
                .event("compaction", "{\"strategy\":\"mnemonic\",\"kind\":\"mask\"}");
        is(FAIL, Checks.mnemonicLogNeverRewritten(), maskOnlyEvent.ctx());
    }

    @Test
    void mnemonicRecall() {
        var masked = toolResultMsg("t1", RECEIPT);
        var base = Traces.trace().request("s", TOOLS, null, msg("user", "task"), masked);
        is(FAIL, Checks.mnemonicRecallFindsMasked("secret"), base.ctx());

        var good = Traces.trace().request("s", TOOLS, null, msg("user", "task"), masked)
                .call("9", "history_recall", "{\"id\":\"rab12\"}")
                .result("9", "history_recall", false, "the secret value 7731");
        is(PASS, Checks.mnemonicRecallFindsMasked("secret value"), good.ctx());
        is(PASS, Checks.mnemonicRecallFindsMasked(null), good.ctx());
        is(FAIL, Checks.mnemonicRecallFindsMasked("other"), good.ctx());

        var failed = Traces.trace().request("s", TOOLS, null, msg("user", "task"), masked)
                .call("9", "history_recall", "{\"id\":\"rab12\"}")
                .result("9", "history_recall", true, "unknown id");
        is(FAIL, Checks.mnemonicRecallFindsMasked(null), failed.ctx());

        var noMask = Traces.trace().request("s", TOOLS, null, msg("user", "task"))
                .call("9", "history_recall", "{}").result("9", "history_recall", false, "x");
        is(FAIL, Checks.mnemonicRecallFindsMasked(null), noMask.ctx());
    }

    @Test
    void mnemonicGuard() throws Exception {
        String bad = "{\"path\":\"a.txt\",\"content\":\"" + RECEIPT + "\"}";
        var blocked = Traces.trace().call("1", "write_file", bad).result("1", "write_file", true, "denied")
                .ctx(ws, Map.of(), Map.of());
        is(PASS, Checks.mnemonicGuardBlocksMaskedWrites(true), blocked);
        var allowed = Traces.trace().call("1", "write_file", bad).result("1", "write_file", false, "ok")
                .ctx(ws, Map.of(), Map.of());
        is(FAIL, Checks.mnemonicGuardBlocksMaskedWrites(false), allowed);
        var none = Traces.trace().call("1", "write_file", "{\"path\":\"a\",\"content\":\"real\"}")
                .result("1", "write_file", false, "ok").ctx(ws, Map.of(), Map.of());
        is(PASS, Checks.mnemonicGuardBlocksMaskedWrites(false), none);
        is(FAIL, Checks.mnemonicGuardBlocksMaskedWrites(true), none);
        Files.writeString(ws.resolve("leak.txt"), RECEIPT);
        is(FAIL, Checks.mnemonicGuardBlocksMaskedWrites(false), none);
    }

    @Test
    void scopeOffload() {
        String big = "y".repeat(5000);
        var m1 = msg("user", "task");
        var pointer = toolResultMsg("t1", "[Output offloaded to /w/offload/t1.txt (5000 chars); read it with read_file if you need more.]\nyy");
        var ok = Traces.trace()
                .request("s", TOOLS, null, m1, toolResultMsg("t1", big))
                .request("s", TOOLS, null, m1, pointer, msg("assistant", "a"), msg("user", "q"));
        is(PASS, Checks.scopeOffloadsLargeOutputs(2000), ok.ctx());

        var never = Traces.trace().request("s", TOOLS, null, m1, toolResultMsg("t1", big));
        is(FAIL, Checks.scopeOffloadsLargeOutputs(2000), never.ctx());

        var stillBig = Traces.trace()
                .request("s", TOOLS, null, m1, pointer)
                .request("s", TOOLS, null, m1, pointer, toolResultMsg("t2", big), msg("assistant", "a"), msg("user", "q"));
        is(FAIL, Checks.scopeOffloadsLargeOutputs(2000), stillBig.ctx());
    }

    @Test
    void scopePinnedFirstMessageAndTodos() {
        var m1 = msg("user", "the task");
        var withTodo = msg("user", "next <todo_list>\n- [ ] a\n</todo_list>");
        var ok = Traces.trace()
                .call("1", "todo_write", "{\"todos\":[]}")
                .request("s", TOOLS, null, m1)
                .request("s", TOOLS, null, m1, msg("assistant", "a"), withTodo);
        is(PASS, Checks.scopePinned(), ok.ctx());

        var moved = Traces.trace()
                .call("1", "todo_write", "{}")
                .request("s", TOOLS, null, m1)
                .request("s", TOOLS, null, msg("user", "[Summary]"), withTodo);
        is(FAIL, Checks.scopePinned(), moved.ctx());

        var noTodoTool = Traces.trace().request("s", TOOLS, null, m1).request("s", TOOLS, null, m1, msg("assistant", "a"), withTodo);
        is(FAIL, Checks.scopePinned(), noTodoTool.ctx());

        var noList = Traces.trace().call("1", "todo_write", "{}")
                .request("s", TOOLS, null, m1).request("s", TOOLS, null, m1, msg("assistant", "a"), msg("user", "plain"));
        is(FAIL, Checks.scopePinned(), noList.ctx());
    }

    @Test
    void compactionSections() {
        String full = StrategyChecks.SUMMARY_PREFIX + "\n" + String.join("\n\n", StrategyChecks.SUMMARY_SECTIONS);
        String partial = StrategyChecks.SUMMARY_PREFIX + "\n## Objective\nx\n## Requirements\ny";
        var ok = Traces.trace().request("s", TOOLS, null, msg("user", full));
        is(PASS, Checks.compactionSummaryHasSections(), ok.ctx());
        var bad = Traces.trace().request("s", TOOLS, null, msg("user", partial));
        is(FAIL, Checks.compactionSummaryHasSections(), bad.ctx());
        var none = Traces.trace().request("s", TOOLS, null, msg("user", "plain"));
        is(FAIL, Checks.compactionSummaryHasSections(), none.ctx());
    }

    // ---- retention ------------------------------------------------------------------------------------------

    private static final RetentionChecks.Fact FACT = new RetentionChecks.Fact("What is the port?", "8421");

    @Test
    void retentionExactFirst() {
        Similarity boom = (a, b) -> {
            throw new AssertionError("must not be called");
        };
        Judge boomJudge = (q, e, a) -> {
            throw new AssertionError("must not be called");
        };
        is(PASS, Checks.retained(boom, boomJudge, FACT), Traces.trace().answer("The port is 8421.").ctx());
        is(PASS, Checks.retained(boom, boomJudge, FACT), Traces.trace().answer("port=8421").ctx());
    }

    @Test
    void retentionExactIsWordBounded() {
        is(FAIL, Checks.retained(null, null, FACT), Traces.trace().answer("The port is 184213.").ctx());
        is(FAIL, Checks.retained(null, null, FACT), Traces.trace().answer("").ctx());
    }

    @Test
    void retentionEscalates() {
        var ctx = Traces.trace().answer("It listens on port eight thousand four hundred twenty-one").ctx();
        int[] judged = {0};
        Judge yes = (q, e, a) -> {
            judged[0]++;
            return new Judge.Verdict(true, "same number");
        };
        Judge no = (q, e, a) -> new Judge.Verdict(false, "different\nnumber");
        // clearly similar: no judge call
        is(PASS, Checks.retained((a, b) -> 0.95, yes, FACT), ctx);
        assertEquals(0, judged[0]);
        // clearly different: no judge call
        is(FAIL, Checks.retained((a, b) -> 0.1, yes, FACT), ctx);
        assertEquals(0, judged[0]);
        // unclear: judge decides
        is(PASS, Checks.retained((a, b) -> 0.7, yes, FACT), ctx);
        assertEquals(1, judged[0]);
        is(FAIL, Checks.retained((a, b) -> 0.7, no, FACT), ctx);
        // unclear without judge fails; without similarity the judge decides
        is(FAIL, Checks.retained((a, b) -> 0.7, null, FACT), ctx);
        is(PASS, Checks.retained(null, yes, FACT), ctx);
    }

    @Test
    void retentionAllFactsRequired() {
        var facts = new RetentionChecks.Fact[]{FACT, new RetentionChecks.Fact("Who owns it?", "Marta")};
        is(PASS, Checks.retained(null, null, facts), Traces.trace().answer("Marta runs port 8421").ctx());
        is(FAIL, Checks.retained(null, null, facts), Traces.trace().answer("port 8421").ctx());
        assertEquals(Check.Tier.JUDGE, Checks.retained(null, (q, e, a) -> null, facts).tier());
        assertEquals(Check.Tier.EMBEDDING, Checks.retained((a, b) -> 1, null, facts).tier());
    }
}
