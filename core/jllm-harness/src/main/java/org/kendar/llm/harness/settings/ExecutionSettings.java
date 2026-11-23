package org.kendar.llm.harness.settings;

import org.kendar.llm.harness.HarnessConfig;

/**
 * @param maxParallel maximum model calls in flight, and chat runs / sub-agents running at once. Default {@code 1}:
 *                    one model call at a time, depth-first (made for a local LLM). {@code -1} = unlimited;
 *                    {@code 0} is treated as {@code 1}
 * @param useContextStack sub-agents continue the parent's request prefix (see
 *                    {@link HarnessConfig#withUseContextStack}); default {@code true}
 */
public record ExecutionSettings(Integer maxParallel, Boolean useContextStack) {
}
