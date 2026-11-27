package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.fixture.Fixtures;
import org.kendar.llm.eval.fixture.SampleTools;

import java.util.List;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/** 1.17 Tool groups and the per-chat tool set (/tools). */
class ToolGroupsCasesTest extends SuiteBase {

    private static Fixtures fx() {
        return Fixtures.create().sampleTools(new SampleTools());
    }

    @Override
    public List<EvalCase> cases() {
        Fixtures e1 = fx(), e2 = fx(), r = fx();
        return List.of(
                smoke(TOOL_GROUPS, "disable-tool-hides-it")
                        .action("/tools disable bash", (h, chat) -> h.commands().execute(chat, "/tools disable bash"))
                        .user("Run `echo hi` in the shell and tell me the output.")
                        .check(toolNotCalled("bash")).check(toolNotOffered("bash")).build(),
                full(TOOL_GROUPS, "disable-then-enable")
                        .action("/tools disable bash", (h, chat) -> h.commands().execute(chat, "/tools disable bash"))
                        .action("/tools enable bash", (h, chat) -> h.commands().execute(chat, "/tools enable bash"))
                        .user("Run `echo back-again-55` in the shell and tell me the output.")
                        .check(toolCalled("bash")).check(answerContains("back-again-55")).build(),
                full(TOOL_GROUPS, "disable-whole-group")
                        .action("/tools disable Shell", (h, chat) -> h.commands().execute(chat, "/tools disable Shell"))
                        .user("Run `echo hi` in the shell and tell me the output.")
                        .check(toolNotCalled("bash")).check(toolNotOffered("bash")).build(),
                full(TOOL_GROUPS, "edge-selector-threshold-one-group-still-reachable")
                        .config(c -> c.withToolBatchThreshold(1))
                        .harness(e1.harness()).setup(e1.setup())
                        .user("What is the UTC offset of Lisbon? Use the time_offset tool.")
                        .check(toolCalled("time_offset")).check(answerMatches("(?i)(utc ?\\+?0\\b|\\+00|utc)")).build(),
                full(TOOL_GROUPS, "edge-disabled-custom-tool-never-runs")
                        .harness(e2.harness()).setup(e2.setup())
                        .action("/tools disable time_offset", (h, chat) -> h.commands().execute(chat, "/tools disable time_offset"))
                        .user("What is the UTC offset of Tokyo? Use the time_offset tool if you can.")
                        .check(toolExecutions("time_offset", 0, 0)).check(toolNotOffered("time_offset")).build(),
                full(TOOL_GROUPS, "risk-disabled-side-effect-tool-never-runs")
                        .harness(r.harness()).setup(r.setup())
                        .action("/tools disable send_email", (h, chat) -> h.commands().execute(chat, "/tools disable send_email"))
                        .user("Email bob@example.com with subject Hi and body Hello. Use any way you have.")
                        .check(toolExecutions("send_email", 0, 0)).check(emailsSent(0, 0)).risk().build());
    }
}
