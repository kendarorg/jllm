package org.kendar.llm.harness.settings;

import org.kendar.llm.harness.context.scope.ScopeStrategy;

/**
 * Thresholds of the {@code scope} context strategy; unset values keep the defaults. {@code reserveCap}: room for
 * the answer when it should be less than {@code maxTokens}; 0 (default) = {@code maxTokens}.
 */
public record ScopeSettings(Integer offloadChars, Integer overflowOffloadChars, Integer previewChars,
                            Integer safetyPercent, Integer reserveCap) {

    public ScopeStrategy.Options over(ScopeStrategy.Options d) {
        return new ScopeStrategy.Options(offloadChars != null ? offloadChars : d.offloadChars(),
                overflowOffloadChars != null ? overflowOffloadChars : d.overflowOffloadChars(),
                previewChars != null ? previewChars : d.previewChars(),
                safetyPercent != null ? safetyPercent : d.safetyPercent(),
                reserveCap != null ? reserveCap : d.reserveCap());
    }
}
