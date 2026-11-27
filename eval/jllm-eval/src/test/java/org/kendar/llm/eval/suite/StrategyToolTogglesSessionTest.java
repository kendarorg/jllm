package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;

import java.util.List;

/** 4 Context strategies: the toggles session under compaction, mnemonic and scope. */
class StrategyToolTogglesSessionTest extends StrategySessionsBase {

    @Override
    public List<EvalCase> cases() {
        return List.of(toggles());
    }
}
