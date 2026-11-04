package org.kendar.llm.harness;

import org.kendar.llm.harness.llm.Usage;

import java.time.Duration;

/** Instrumentation hook of the harness. Metric names are neutral ({@code harness.*}). */
public interface HarnessMetrics {

    void modelCall(Duration d, String outcome);

    void tokens(Usage u);

    void toolCall(String tool, String outcome, Duration d);

    void turn();

    void compaction(int tier);

    /** A context-strategy step other than compaction tiers, e.g. {@code mask} or {@code segment}. */
    default void context(String kind) {
    }

    void approval(String kind, boolean approved);

    static HarnessMetrics noop() {
        return new HarnessMetrics() {
            @Override public void modelCall(Duration d, String outcome) { }
            @Override public void tokens(Usage u) { }
            @Override public void toolCall(String tool, String outcome, Duration d) { }
            @Override public void turn() { }
            @Override public void compaction(int tier) { }
            @Override public void approval(String kind, boolean approved) { }
        };
    }
}
