package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;

import java.util.List;

/** 4 Context strategies: the refactoring session under compaction, mnemonic and scope. */
class StrategyRefactoringSessionTest extends StrategySessionsBase {

    @Override
    public List<EvalCase> cases() {
        return List.of(refactoring());
    }
}
