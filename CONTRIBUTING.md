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
module only builds with the `eval` Maven profile ([jllm-eval](eval/jllm-eval/README.md)).

## Dependency scan (OWASP dependency-check)

The Maven profile `owasp` is not active by default and there is no CI for it: run it locally.

```
export NVD_API_KEY=<your key>     # PowerShell: $env:NVD_API_KEY = "<your key>"
mvn -Powasp verify -DskipTests
```

- The key comes from the environment variable `NVD_API_KEY` (free, request one at https://nvd.nist.gov/developers/request-an-api-key).
  Without it the first database download is very slow and can be rate limited.
- The scan always writes `target/dependency-check-report.json`; `scripts/OwaspReport.java` (JDK 21 source launcher, run
  by `exec-maven-plugin`) converts it to [docs/OWASP_DC.md](docs/OWASP_DC.md), which is committed.
- The build fails when a vulnerability has CVSS 7.0 or higher. The Markdown file is written before that check, so it is
  up to date even when the build fails.
- Convert a report by hand: `java scripts/OwaspReport.java target/dependency-check-report.json docs/OWASP_DC.md`.
