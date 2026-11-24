package org.kendar.llm.harness.settings;

/**
 * @param maxTokens       context window; the compaction ceiling is derived from it
 * @param keepRecentTurns whole recent turns kept verbatim when compacting
 * @param auto            compact automatically (default true)
 * @param keepTokens      newest tokens kept verbatim when compacting
 * @param buffer          tokens kept free below maxTokens; default max(15%, 16k)
 * @param strategy        {@code compaction} (default) or {@code mnemonic}
 * @param mnemonic        settings of the mnemonic strategy
 * @param noiseCommands   remove shell commands that add no information (mkdir, rm, cd, ...) from the context
 *                        after their turn, see {@code NoiseCommandPruner} (default true)
 * @param deferCompactionOnThinkingOverflow Anthropic: do not compact while the turn in progress holds thinking
 *                        blocks (default false: compact and switch thinking off for the rest of the turn). true keeps
 *                        thinking but the prompt may exceed the budget until the turn ends: can increase charges
 * @param todos           offer {@code todo_write} and {@code todo_read} with any strategy (default true; false hides
 *                        them, except with the scope strategy, which always offers them)
 */
public record ContextSettings(Integer maxTokens, Integer keepRecentTurns, Boolean auto, Integer keepTokens,
                              Integer buffer, String strategy, MnemonicSettings mnemonic, ScopeSettings scope,
                              Boolean noiseCommands, Boolean deferCompactionOnThinkingOverflow, Boolean todos) {
}
