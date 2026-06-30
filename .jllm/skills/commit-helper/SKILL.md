---
name: commit-helper
description: Write clean Conventional Commit messages and stage the right changes. Use when the user wants to commit work or needs help phrasing a commit message.
allowed-tools: run_shell_command,read_file
---

# Commit Helper

Guide the user to a well-formed commit using the Conventional Commits standard.

## 1. Inspect what will be committed

Always look at the actual changes before writing a message.

- See what is already staged: `git diff --staged`
- See unstaged changes: `git diff`
- See the file-level overview and branch state: `git status`

If nothing is staged yet, help the user stage the relevant files
(`git add <path>`). Do not blindly `git add -A`; stage only the changes that
belong in this commit, and split unrelated work into separate commits.

## 2. Message format

Use the Conventional Commits format:

```
<type>(<optional scope>): <short summary>

<optional body — what and why, wrapped at ~72 cols>

<optional footer — BREAKING CHANGE:, issue refs>
```

Common types:

- `feat` — a new feature
- `fix` — a bug fix
- `docs` — documentation only
- `style` — formatting, no code-meaning change
- `refactor` — code change that neither fixes a bug nor adds a feature
- `perf` — performance improvement
- `test` — adding or fixing tests
- `build` / `ci` — build system or CI changes
- `chore` — maintenance, tooling, deps

## 3. Writing rules

- Summary in the imperative mood, lower case, no trailing period, <= 50 chars.
- The body explains *why* the change was made and any context a reviewer needs,
  not a restatement of the diff.
- Mark incompatible changes with a `BREAKING CHANGE:` footer (or `!` after the
  type/scope).
- One logical change per commit.

## 4. Commit

Once the message is agreed and the right files are staged, create the commit.
Show the user the final message for confirmation before committing.
