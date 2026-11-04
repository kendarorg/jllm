# Instruction appended to the agent's own request when the mnemonic context strategy switches segment.
# The older part of the conversation leaves the prompt; these notes and the recall index replace it.
The older part of this conversation is about to be moved out of the prompt. Write handoff notes for yourself so the
work can continue from them. Everything moved out stays retrievable word for word with the history_recall tool, so
record what matters and where it is, not long copies of it.

Rules:
- Terse single-line bullets. Keep exact paths, symbols, commands, error messages and recall ids.
- Use these sections, omit one that has nothing to say: ## Objective, ## State (done / in progress / blocked),
  ## Decisions, ## Open items, ## Files.
- Carry forward only requests that are still unanswered.
- Do not continue the task, do not call tools, no preamble.
