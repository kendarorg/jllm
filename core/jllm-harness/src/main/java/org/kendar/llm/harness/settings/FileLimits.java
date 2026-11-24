package org.kendar.llm.harness.settings;

import org.kendar.llm.harness.tools.ExternalLinks;

/**
 * @param maxReadBytes            largest file the file tools read whole; null or {@code <= 0} = default (1 000 000)
 * @param scopedInstructions      a file tool call inside the work dir adds the instruction files of the directories
 *                                down to its path; default true
 * @param scopedInstructionsDepth directory levels below the work dir looked at for them; null or negative = default
 *                                ({@code ScopedInstructions.DEFAULT_MAX_DEPTH})
 * @param externalLinks           {@code none}, {@code read} or {@code readwrite}: what the file tools do with a link
 *                                inside the work dir whose target is outside it; null = {@code read}. A web host
 *                                ignores it and always uses {@code none}
 */
public record FileLimits(Long maxReadBytes, Boolean scopedInstructions, Integer scopedInstructionsDepth,
                         ExternalLinks externalLinks) {
}
