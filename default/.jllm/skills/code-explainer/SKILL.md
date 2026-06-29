---
name: code-explainer
description: Produce a structured, accurate explanation of a file or module. Use when the user wants to understand what a piece of code does, how it works, or how it fits in.
allowed-tools: read_file,search_file_content,glob
---

# Code Explainer

Explain a file or module clearly enough that a new contributor could work with
it. Stay accurate: describe only what the code actually does.

## 1. Read it properly

- Read the whole target file with `read_file`.
- Use `glob` to find related files (same package, interfaces it implements,
  tests that exercise it).
- Use `search_file_content` to find where the file's types and functions are
  used elsewhere, so you can describe its role in the system.

## 2. What to cover

Organize the explanation into these sections:

### Purpose
One or two sentences: what this code is for and the problem it solves.

### Key types and members
The main classes, functions, or exports and what each is responsible for.
Note important fields, parameters, and return values.

### Control / data flow
Walk through the primary path of execution: entry points, the main steps, how
data is transformed, and where it goes. Note significant branches and error
handling.

### Dependencies and interactions
What this code depends on (libraries, other modules) and what depends on it.
Mention side effects: I/O, state mutation, network, persistence.

### Gotchas
Non-obvious behaviour, assumptions, invariants, concurrency concerns, or
anything that could surprise a maintainer.

## 3. Style

- Reference concrete symbols and line numbers so the reader can follow along.
- Prefer a short narrative plus targeted snippets over dumping the whole file.
- If something is genuinely unclear or looks buggy, say so explicitly.
