package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.grade.judge.Rubrics;
import org.kendar.llm.harness.AgentHarnessBuilder;
import org.kendar.llm.js.JsPlugin;

import java.util.List;
import java.util.function.Consumer;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/** 2.3 js plugin (run_js). */
class JsCasesTest extends SuiteBase {

    private static Consumer<AgentHarnessBuilder> js(int statementLimit) {
        return b -> {
            b.settings(settings(root -> root.putObject("plugins").putObject("js").put("enabled", true)
                    .putObject("settings").put("statementLimit", statementLimit)));
            b.plugin(new JsPlugin());
        };
    }

    @Override
    public List<EvalCase> cases() {
        return List.of(
                smoke(JS, "computes-with-script")
                        .harness(js(10_000_000))
                        .user("Use the run_js tool to compute the sum of the integers from 1 to 100 and tell me the result.")
                        .check(toolCalled("run_js")).check(answerContains("5050")).build(),
                full(JS, "script-calls-file-tool")
                        .harness(js(10_000_000))
                        .setup(files("data.txt", "alpha,beta,gamma,delta"))
                        .user("Use one run_js call that reads data.txt through tools.read_file, splits it by comma and returns the number of items. Tell me the number.")
                        .check(toolCalled("run_js")).check(innerCallFromScript()).check(answerContains("4")).build(),
                judged(full(JS, "failure-runaway-loop-reported")
                        .harness(js(100_000))
                        .user("Use run_js to run `while(true){}` and tell me exactly what happened.")
                        .check(toolCalled("run_js")).check(anyToolResultMatches("\"?ok\"?\\s*:\\s*false")), Rubrics.NO_FALSE_CLAIM).build());
    }
}
