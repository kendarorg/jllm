package org.kendar.llm.eval.grade;

import java.util.function.Function;

/** One expectation on a finished trial. Checks run cheapest tier first; after a failure only the paid tiers (embedding, judge) are skipped. */
public interface Check {

    /** Cost order of graders: event trace, then workspace and server state, then cache, embeddings, the LLM judge. */
    enum Tier {TRACE, STATE, CACHE, EMBEDDING, JUDGE}

    String name();

    Tier tier();

    CheckResult check(TrialContext ctx);

    /** A check from a lambda. */
    static Check of(String name, Tier tier, Function<TrialContext, CheckResult> body) {
        return new Check() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Tier tier() {
                return tier;
            }

            @Override
            public CheckResult check(TrialContext ctx) {
                return body.apply(ctx);
            }
        };
    }
}
