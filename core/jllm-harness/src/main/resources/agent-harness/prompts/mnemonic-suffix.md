# System prompt addendum of the mnemonic context strategy (part of the cached prefix: keep it stable).
Context memory: to keep the prompt small, old tool outputs, large tool inputs and older parts of the conversation
are replaced by one-line receipts such as `[masked: read_file(path=src/A.java) -> ~4200 tokens, recall id=r3f2a91c0d4e1]`.
Nothing is lost: call `history_recall` with `{"id": "..."}` to get the original text, with `{"query": "..."}` to search
what was moved out of the prompt, or with `{"file": "path"}` for the reads and edits of a file. Never copy a receipt
into a file or a command: recall the original first.
