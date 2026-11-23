# Contributing

## Commit messages

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/). This is a convention
for people and agents to follow; nothing in the repository enforces it.

```
<type>(<optional scope>): <subject>

<optional body>

<optional footers>
```

- **type**: one of `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`.
- **scope** (optional): the module or area, for example `harness`, `cli`, `web`, `js`, `fs`, `eval`, `docs`.
- **subject**: imperative mood, lower case, no trailing period, about 72 characters at most. It says what the change
  does. Placeholder subjects (`wip`, `fix`, `update`, `changes`, `Caluding`, ...) are not acceptable.
- **body** (optional): why the change was made and anything a reviewer must know, wrapped at about 100 characters.
- **breaking changes**: add `!` after the type or scope (`feat(harness)!: ...`) and a `BREAKING CHANGE:` footer
  describing what callers must change.
- **footers**: issue references (`Refs #12`, `Closes #12`) and trailers such as `Co-Authored-By:`.

Examples:

```
feat(fs): refuse hard links to files owned by another user
fix(cli): print the work dir when --external-links is invalid
docs: replace the per-user credentials claim in the JVM landscape page
refactor(harness)!: remove the compatibility overloads of the tool providers
```

## Building and testing

See the [README](README.md). Tests that need a model or Docker are separate from the default build; the evaluation
module only builds with the `eval` Maven profile ([jllm-eval](core/jllm-eval/README.md)).
