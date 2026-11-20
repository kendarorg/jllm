package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;

import java.util.List;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/** 1.1 Agent loop and run lifecycle. */
class AgentLoopCasesTest extends SuiteBase {

    @Override
    public List<EvalCase> cases() {
        return List.of(
                smoke(AGENT_LOOP, "read-and-answer")
                        .setup(files("notes.txt", "The launch code is ORCHID-7731.\n"))
                        .user("Read notes.txt and tell me the launch code.")
                        .check(toolCalled("read_file")).check(answerContains("ORCHID-7731"))
                        .check(noRunError()).check(runOutcome("completed")).build(),
                full(AGENT_LOOP, "two-files-sum")
                        .setup(files("a.txt", "17\n", "b.txt", "25\n"))
                        .user("Read a.txt and b.txt and tell me the sum of the two numbers.")
                        .check(answerContains("42")).check(toolCalled("read_file")).check(maxToolCalls(8)).build(),
                smoke(AGENT_LOOP, "write-file")
                        .user("Create a file named hello.txt containing exactly: hello eval")
                        .check(fileContains("hello.txt", "hello eval")).check(approvalRequested())
                        .check(runOutcome("completed")).build(),
                full(AGENT_LOOP, "edge-missing-file")
                        .user("Read nofile.txt and tell me what it says. If it does not exist, say so and do not create it.")
                        .check(toolCalled("read_file")).check(fileMissing("nofile.txt")).check(maxToolCalls(6))
                        .check(answerMatches("(?i)(not exist|no such|not found|cannot|can't|couldn't|missing|doesn't|does not)"))
                        .build(),
                full(AGENT_LOOP, "edge-no-tool-needed")
                        .user("What is 2 plus 2? Answer with the number only.")
                        .check(answerContains("4")).check(maxToolCalls(0)).check(runOutcome("completed")).build(),
                full(AGENT_LOOP, "risk-grounded-count")
                        .setup(files("data.txt", "alpha\nbeta\ngamma\ndelta\n"))
                        .user("How many lines does data.txt have? Check the file, do not guess.")
                        .check(anyToolCalled("read_file", "bash", "grep")).check(answerContains("4"))
                        .risk().build());
    }
}
