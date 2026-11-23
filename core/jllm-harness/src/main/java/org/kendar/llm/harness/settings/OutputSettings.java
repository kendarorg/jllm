package org.kendar.llm.harness.settings;

import java.util.List;

/**
 * @param toolMaxChars  tool output kept in the context (default 30000)
 * @param eventMaxChars tool output carried by the {@code tool_result} event (default 2000)
 * @param validation    tool-call argument check: {@code off}, {@code lenient} (default) or {@code strict}
 */
public record OutputSettings(Integer toolMaxChars, Integer eventMaxChars, String sanitize, Boolean taintGating,
                             List<String> extraPatterns, String validation) {
}
