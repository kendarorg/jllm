# Rules of the compaction summary, placed before the template. Must end with the "Template:" line.
Rules:
- Use exactly this template; omit a section that has nothing to say.
- Terse single-line bullets. Keep exact paths, symbols, commands, error messages and ticket keys.
- Carry forward only requests that are still unanswered.
- Keep workflow state (what is committed, pushed, merged, deployed).
- Keep what tool calls found that answers or narrows the request: what was searched or read and what it returned, negative results included, so no lookup is done twice.
- Keep every value the user stated or a tool returned (codes, ids, names, numbers, hosts, dates) word for word: the summary replaces the conversation, so a value written as "already given" is lost.
- User-Stated Facts: one bullet per fact the user gave in their own messages ("the audit window code is WIN-208"), word for word, also when it was only acknowledged. Values that tools returned go elsewhere.
- Requirements are the user's requests only: never list this summarization request, its template or these rules.
- Relevant Files: at most 15, excluding the working directory itself.
- Leave out instruction files (KENDAR.md) and environment details: they are sent again separately.
- Do not mention the compaction, do not continue the task, do not call tools, no preamble.

Template:
