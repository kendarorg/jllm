package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.fixture.FakeLlmServer;

import java.util.List;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/**
 * 1.2 Cut-off responses and local-server quirks. A scripted server cuts the responses off, so these cases grade the
 * harness's handling and are independent of the model under test; one trial is enough.
 */
class CutOffCasesTest extends SuiteBase {

    @Override
    public List<EvalCase> cases() {
        FakeLlmServer continued = new FakeLlmServer().cutText("The meeting is on ").text("Tuesday at noon.");
        FakeLlmServer xml = new FakeLlmServer()
                .cutText("<tool_call>\n<function=write_file>\n<parameter=path>\nq.txt\n</parameter>\n"
                        + "<parameter=content>\nhel")
                .text("Sorry, I will write the file again.");
        FakeLlmServer json = new FakeLlmServer()
                .cutToolCall("write_file", "{\"path\": \"a.txt\", \"content\": \"abc")
                .text("The write was cut off.");
        FakeLlmServer twice = new FakeLlmServer()
                .cutToolCall("write_file", "{\"path\": \"b.txt\", \"content\": \"abc")
                .cutToolCall("write_file", "{\"path\": \"b.txt\", \"content\": \"abc");
        return List.of(
                smoke(CUTOFF, "cut-off-text-is-continued").model(continued.client())
                        .user("When is the meeting?").trials(1)
                        .check(requestsContain("[harness: response cut off]"))
                        .check(answerContains("Tuesday")).build(),
                full(CUTOFF, "qwen-xml-call-cut-off-not-executed").model(xml.client())
                        .user("Write hello into q.txt.").trials(1)
                        .check(fileMissing("q.txt"))
                        .check(requestsContain("Not executed: your response was cut off"))
                        .check(runOutcome("completed")).build(),
                full(CUTOFF, "edge-broken-json-call-not-executed").model(json.client())
                        .user("Write abc into a.txt.").trials(1)
                        .check(fileMissing("a.txt"))
                        .check(requestsContain("Not executed: your response was cut off"))
                        .check(runOutcome("completed")).build(),
                full(CUTOFF, "risk-cut-call-twice-ends-run").model(twice.client())
                        .user("Write abc into b.txt.").trials(1)
                        .check(fileMissing("b.txt")).check(runOutcome("truncated")).risk().build());
    }
}
