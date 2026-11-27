# Security policy

## Reporting a vulnerability

Report vulnerabilities privately to **github@kendar.org**. Do not open a public issue or pull request for a
security problem.

Include: affected version or commit, a description, steps to reproduce, and the impact you see.

## Supported versions

Only the latest release receives security fixes.

## Dependency scan

The latest OWASP dependency-check result is in [docs/OWASP_DC.md](docs/OWASP_DC.md); reviewed suppressions are in
[owasp-suppressions.xml](owasp-suppressions.xml).

## Scope

jllm runs a model that can execute tools. The `unsafe` configuration (see `docs/guides/configurations.md`) is for
development and testing only; weaknesses that exist only because it is used outside that purpose are not
vulnerabilities. Issues in the `webSafe` and `cliSafe` configurations are in scope.
