package org.kendar.llm.harness.context;

import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.tools.HarnessTool;

import java.util.List;

/**
 * The request the agent sends every turn, minus its messages. A summary request that is this same request followed by
 * an instruction hits the provider's prompt cache.
 */
public record RequestPrefix(String pinned, String extra, List<HarnessTool> tools, ModelClient client,
                            boolean disableParallelToolUse) {
}
