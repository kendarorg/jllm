# GraalJS set: downloaded by `scripts/setup`, provided in every module

Status: plan, nothing implemented.

## Goal

Remove the `graal-dir` profile. The GraalJS libraries (polyglot, js, regex, isolate) are not in the repository: a one-time
`scripts/setup` downloads them into the git-ignored `.graal/` and runs the first build. From then on every module sees them
as `provided` dependencies, with no profile and no `-Dgraal.dir`.

## Version

- The only source is `<graalvm.version>` in the root pom. Setup, the run scripts and `release/graaljs/resolve.*` read it
  from there; no copy anywhere else.
- The root pom repository URL and the enforcer path interpolate `${graalvm.version}`.

## Layout (`.graal/` stays gitignored)

```
.graal/<graalvm.version>/repo                    Maven layout (jars + poms) with the gate artifact org.kendar.llm.graal:jllm-graal-set:<version>
.graal/<graalvm.version>/graaljs-<edition>.manifest   edition, version, SHA-256 of every jar
```

- One copy only. There is no flat `lib` folder: the run scripts list the `*.jar` files of `repo` themselves.
- The edition is recorded in the manifest, not in the path. Setup replaces the whole `<version>` folder, so editions never mix.
- The gate artifact is a pom listing polyglot, js (pom), regex and the isolate of the chosen edition
  (`js-isolate` commercial, `js-isolate-community` community). Template: `release/graaljs/jllm-graal-set.pom`, filled by
  `resolve.*` (new option `--repo <dir>`; the manifest goes next to it, not into `repo`).

## Build wiring

- Root pom: `<repository id="graal-local" url="file://${maven.multiModuleProjectDirectory}/.graal/${graalvm.version}/repo">`.
- `.mvn/` at the root (a small README file) so `${maven.multiModuleProjectDirectory}` is reliable.
- Root pom, maven-enforcer `requireFilesExist` in `validate`: the gate pom of the current version. Message: GraalJS
  `<version>` is not set up, run `scripts/setup.sh` / `scripts\setup.ps1` (also after a `graalvm.version` change).
- `jllm-js`, `jllm-cli`, `jllm-web`, `jllm-eval`: the four Graal dependencies become one
  `org.kendar.llm.graal:jllm-graal-set:${graalvm.version}` (type pom, scope provided).
- The `graal-dir` profiles in `jllm-js` and `jllm-eval` are deleted. There is one copy of the language on the test class
  path, so nothing needs excluding.
- `GraalProbe` keeps its `jllm.graal.dir` manifest lookup; the run scripts set it to `.graal/<version>` (where the manifest is).

## Scripts

`scripts/setup.sh`, `scripts/setup.ps1`, `scripts/setup.cmd` (wrapper):

1. Read the version from the root pom.
2. Edition: commercial by default. Community only with `--edition community` (never offered in the prompt, so nobody picks
   the weaker isolation by accident).
3. Commercial: show the Oracle GraalVM Free Terms and Conditions URL and ask y/N; `--accept-license` (`-AcceptLicense`)
   accepts silently. Anything but yes aborts before any download. Without a terminal and without the flag it fails.
   Community has no terms; the flag does nothing there.
4. `resolve.* <edition> .graal/<version> --repo .graal/<version>/repo`: download, licence re-check, SHA-256 manifest.
   `resolve.*` no longer writes flat jars in this mode. The gate pom is written last.
5. Delete the other `.graal/<other-version>/` folders (including the old `.graal/<version>/<edition>` layout of
   `graal-fetch`). Open: delete automatically (preferred) or only print a hint.
6. `mvn -DskipTests install` (skipped with `--no-build`).

Rerunning for the same version redownloads and replaces the folder after the same license question. A new version always
asks again, because the terms can differ per version.

## Environment variables

`JLLM_ACCEPT_ORACLE_TERMS` and `JLLM_GRAAL_EDITION` are removed everywhere: `setup.*`, `release/graaljs/resolve.*`,
`release/template/fetch-graaljs.*`, `release/release.*`, docs. Acceptance is the prompt or `--accept-license`; the release
scripts keep an explicit flag.

## Other scripts

- Deleted: `scripts/graal-fetch.*`, `scripts/graal-flags.*`.
- `run-cli`, `run-web`, `run-eval` (`.sh`, `.ps1`): fail with "run scripts/setup" when `.graal/<version>/repo` is missing.
  `run-cli` builds the class path by listing the `*.jar` files of `repo` (`find` / `Get-ChildItem -Recurse`, joined with
  `:` or `;`); `run-web` passes the same list, comma separated, as `loader.path`. `run-eval` becomes `mvn -Peval -pl core/jllm-eval -am` without `-Dgraal.dir`.
- `core/jllm-eval/scripts/run-smoke.*` and `envs/*.env`: the `GRAAL_JS` setting and the graal-fetch step go.
- Release (`release/release.*`, `fetch-graaljs`): unchanged apart from the env var removal. They still ship `lib-ext/`
  from `release/graaljs`.

## Acceptance rule (state it in the docs)

Having the downloaded Graal files in `.graal/` is the evidence that the license was accepted: the project does not ship
them in the repository, so anyone who has them downloaded them with `scripts/setup` and accepted the terms of the chosen
edition. No marker file, no setting. Suggested wording:

> The GraalJS libraries are not in this repository. If they are present in `.graal/`, you downloaded them with
> `scripts/setup` and accepted the license terms of the chosen edition. After a `graalvm.version` change, run
> `scripts/setup` again.

Docs to update: `README.md`, `docs/guides/js.md`, `docs/enterprise/compliance-and-licensing.md`,
`core/jllm-eval/README.md`, `core/jllm-eval/FEATURES.md`, `release/template/README.md`, `docs/getting-started/cli-and-web.md`,
`docs/guides/extending.md`, `NOTICE` if it mentions the fetch.

## Risks

- Maven cannot print a custom message for a missing artifact; the enforcer rule gives the readable one, the README repeats it.
- Transitive artifacts also exist on Central. The folder is a pinned, verified copy; the gate coordinate is the real guard.
- Parent poms of Graal artifacts may still be fetched from Central, so the first build is not fully offline.
- Community isolation is weaker (no hard memory separation): documented, and not offered in the prompt.
- Developers who decline the commercial terms cannot build unless they run `setup --edition community`.
- Test the commercial path only with a person accepting the terms; the community path can be tested without it.
