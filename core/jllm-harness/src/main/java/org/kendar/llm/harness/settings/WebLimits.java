package org.kendar.llm.harness.settings;

/** @param fetchMaxBytes largest response body {@code web_fetch} reads; null or {@code <= 0} = default (5 MB) */
public record WebLimits(Long fetchMaxBytes) {
}
