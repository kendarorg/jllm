package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.fixture.Fixtures;
import org.kendar.llm.eval.fixture.SampleTools;
import org.kendar.llm.eval.grade.CacheChecks.ExpectedBreak;
import org.kendar.llm.harness.permission.PermissionMode;

import java.util.List;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/** 3 Prompt-cache layout: prefix stability and the breaks the layout allows. */
class PromptCacheCasesTest extends SuiteBase {

    private static final String LONG = filler(14000, "inventory");

    @Override
    public List<EvalCase> cases() {
        Fixtures toggle = Fixtures.create().sampleTools(new SampleTools());
        return List.of(
                smoke(CACHE, "prefix-stable-over-tool-turns")
                        .setup(files("a.txt", "one\n", "b.txt", "two\n", "c.txt", "three\n"))
                        .user("Read a.txt and tell me what it says.")
                        .user("Read b.txt and tell me what it says.")
                        .user("Read c.txt and tell me what it says.")
                        .check(prefixStable(0.9)).check(noRunError()).build(),
                full(CACHE, "cached-ratio-on-long-context")
                        .setup(files("long.txt", LONG))
                        .user("Read long.txt and tell me in one sentence what it is about.")
                        .user("Without reading again, what word does each paragraph start with, alternating?")
                        .user("Summarize it in five words.")
                        .check(noRunError()).check(cachedRatioAtLeast(0.5, false)).build(),
                full(CACHE, "mode-switch-breaks-system-only")
                        .user("Reply with the single word ONE.")
                        .modeSwitch(PermissionMode.EDIT)
                        .user("Reply with the single word TWO.")
                        .check(noRunError()).check(answerContains("TWO")).check(breakOccurs(ExpectedBreak.systemOnly())).check(breaksAsExpected(ExpectedBreak.systemOnly()))
                        .build(),
                full(CACHE, "edge-tool-toggle-breaks-tools-only").harness(toggle.harness()).setup(toggle.setup())
                        .user("Reply with the single word ONE.")
                        .action("/tools disable send_email", (h, chat) -> h.commands().execute(chat, "/tools disable send_email"))
                        .user("Reply with the single word TWO.")
                        .check(noRunError()).check(answerContains("TWO")).check(breakOccurs(ExpectedBreak.toolsOnly())).check(breaksAsExpected(ExpectedBreak.toolsOnly()))
                        .build(),
                full(CACHE, "edge-no-change-no-break")
                        .user("Reply with the single word ONE.")
                        .user("Reply with the single word TWO.")
                        .user("Reply with the single word THREE.")
                        .check(noRunError()).check(answerContains("THREE")).check(breaksAsExpected(ExpectedBreak.none())).build(),
                full(CACHE, "risk-no-unexpected-break-in-long-chat")
                        .setup(files("a.txt", "one\n", "b.txt", "two\n", "c.txt", "three\n", "d.txt", "four\n"))
                        .user("Read a.txt and tell me what it says.")
                        .user("Read b.txt and tell me what it says.")
                        .user("Read c.txt and tell me what it says.")
                        .user("Read d.txt and tell me what it says.")
                        .user("List the four contents in order.")
                        .check(noRunError()).check(answerContains("four")).check(breaksAsExpected(ExpectedBreak.none())).check(prefixStable(1.0)).risk().build());
    }
}
