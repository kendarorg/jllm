package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;

import java.util.List;

/** 4 Context strategies: the modes session under compaction, mnemonic and scope. */
class StrategyModeSwitchesSessionTest extends StrategySessionsBase {

    @Override
    public List<EvalCase> cases() {
        return List.of(modes());
    }
}
