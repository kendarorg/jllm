---
name: pr-description
description: Draft a clear pull request description from the branch's commits and diff. Use when the user is opening a PR or needs to summarize a branch's changes.
allowed-tools: run_shell_command,read_file
---

# PR Description

Produce a reviewer-friendly pull request description grounded in what the branch
actually changed.

## 1. Gather the facts

Determine the base branch (usually `main`) and inspect the branch against it.

- List the commits on the branch: `git log --oneline main..HEAD`
- See the full diff: `git diff main...HEAD`
- See changed files at a glance: `git diff --stat main...HEAD`

Read any files whose intent is unclear so the summary is accurate. Base every
statement on the diff, not on assumptions.

## 2. Structure

Draft the description with these sections:

### Summary
One short paragraph: what this PR does and why. Lead with the user- or
system-visible outcome.

### Changes
A bulleted list of the notable changes, grouped logically (e.g. by component).
Mention new files, removed behaviour, and any breaking or migration-relevant
changes explicitly.

### Testing
How the change was verified: tests added or updated, commands run and their
result, and any manual checks. If something was not tested, say so.

### Notes (optional)
Follow-ups, known limitations, related issues, or screenshots.

## 3. Style

- Keep it concise; reviewers skim. Use bullets over prose where possible.
- Link related issues (e.g. `Closes #123`).
- Call out anything risky or that needs special review attention.
- Do not claim tests pass unless you have evidence from running them.
