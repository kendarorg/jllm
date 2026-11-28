# jllm-js: `run_js`

An optional plugin that adds one tool, `run_js`. The model writes JavaScript; the script runs in GraalJS (`org.graalvm.polyglot:polyglot` and `:js`, plain JDK 21, no GraalVM install) with no host access. Its only
view of the world is the harness tools, which the script sees as a generated library: `tools.read_file({path})`,
`tools.web_fetch({url})`, `tools.mcp__docs__search({...})`.

It replaces running scripts through `bash`: scraping and data-transforming logic runs without host access, and file
and web operations go through the standard Files and Web tools.

## Enabling

**Neither the plugin jar nor the front-end jars contain GraalJS.** The libraries (polyglot, js, the regex language
`org.graalvm.regex:regex`, and the isolate add-ons of the four platforms) are put on the class path next to the jar.
Without them (or without the regex jar) the plugin logs one WARN naming the missing piece and registers nothing: the
harness starts, `run_js` is absent. The `jllm-cli` and `jllm-web` Maven modules declare them `provided`, so their tests
and an IDE run (tick "include dependencies with provided scope") see the same set the release ships.

- **Release zip** (layout below): the zip ships an empty `lib-ext/`. `setup.sh` / `setup.ps1`, released next to the zip, unzip it (Linux, macOS, Windows), ask the edition (commercial, with the Oracle terms to accept, or community) and run `fetch-graaljs` for you. `run-cli` and `run-web` add that folder to the class path (CLI) or the
  loader path (web, `-Dloader.path=lib-ext`; the web jar uses the ZIP layout, so `java -jar jllm-web.jar` keeps
  working). An empty `lib-ext/` starts normally with `run_js` off. To swap or restore the set run `fetch-graaljs`
  (`.sh`, `.cmd`, `.ps1`) with `community` or `oracle`: it empties `lib-ext/*.jar`, downloads from Maven Central,
  verifies the SHA-256 of each jar against `lib-ext/manifests/` and deletes the file and fails on a mismatch.
- **Source checkout**: run `scripts/setup` once (`.sh`, `.ps1`, `.cmd`). It reads `graalvm.version` from the root pom,
  downloads the set into the git-ignored `.downloaded-libs/graal/<version>/repo` (a Maven layout holding the gate artifact
  `org.kendar.llm.graal:jllm-graal-set`) with a manifest of SHA-256 sums next to it, deletes the folders of other
  versions and runs `mvn -DskipTests install` (`--no-build` skips it). The default edition is commercial: setup shows the
  Oracle GraalVM Free Terms and Conditions URL and asks y/N before any download (`--accept-license` / `-AcceptLicense`
  accepts silently; without a terminal and without the flag it fails). `--edition community` needs no terms and is never
  offered in the prompt. Every module then sees the set as a `provided` dependency, with no profile and no flag; the
  build stops at `validate` with a "not set up" message when the set of the current version is missing, so run setup
  again after a `graalvm.version` change. `scripts/run-cli`, `scripts/run-web` and `scripts/run-eval` use it too.
The SQL drivers (MySQL, PostgreSQL; SQLite is bundled) follow the same pattern in the same folders: `scripts/setup` also
fetches them (`--sql mysql|postgres|all|none`, default all) into `.downloaded-libs/<db>/<version>/`, and the release
`setup` / `fetch-sql-driver` put them into `lib-ext/` with their manifests in `lib-ext/manifests/`; each fetch leaves the
other set alone. MySQL asks to accept its GPL terms. See the
[SQL starter README](../../web/jllm-sql-spring-starter/README.md#jdbc-drivers).

Layouts. The release:

```
Release files (next to each other)         Inside the zip: jllm-<version>/
jllm-<version>.zip                         ├── jllm-cli.jar, jllm-web.jar
SHA256SUMS                                 ├── run-cli.sh/.cmd, run-web.sh/.cmd
setup.sh, setup.ps1                        ├── fetch-graaljs.sh/.cmd/.ps1, fetch-sql-driver.sh/.cmd/.ps1
                                           ├── lib-ext/            empty until setup / fetch-graaljs / fetch-sql-driver; sql/ = the SQL store jars (shipped)
                                           │   └── manifests/      graaljs-community / graaljs-oracle
                                           ├── plugins/            js, pdf, docs, workflows, workflow-web
                                           ├── settings/settings.json
                                           ├── webauth/            users/, permissions/
                                           ├── examples/flows/
                                           └── application.properties, README.md, LICENSE, NOTICE
```

A source checkout:

```
Source checkout (after scripts/setup)
.downloaded-libs/graal/                                    git-ignored
└── <graalvm.version>/
    ├── graaljs-<edition>.manifest         edition, version, SHA-256 of every jar
    └── repo/                              Maven layout: jars + poms
        └── org/kendar/llm/graal/jllm-graal-set/<version>/   gate artifact, written last
```

- **Embedding the harness**: add `org.graalvm.polyglot:polyglot`, `org.graalvm.polyglot:js` (type `pom`),
  `org.graalvm.regex:regex` and `org.graalvm.polyglot:js-isolate-community` (type `pom`) at the version of the root
  pom (`graalvm.version`). Where the host is a fat jar, keep `META-INF/services` entries merged (shade
  `ServicesResourceTransformer`) and the `Multi-Release: true` manifest attribute: Truffle will not start without them.

The edition is read, for diagnostics only, from a manifest file `graaljs*community*` or `graaljs*oracle*` in
`-Djllm.graal.dir` or `./lib-ext` (the release and the scripts write one), then from Maven metadata and the engine's
implementation name.

## Licensing

jllm is Apache 2.0 and does not redistribute GraalJS inside its jars. The release zip does not carry it: `setup` / `fetch-graaljs` download it into `lib-ext/` under
the licence each pom declares, listed in `NOTICE` and the manifests. From GraalJS 25.1 the central `js` pom declares
UPL and MIT and `js-community` is a deprecated alias of it; earlier `js` poms pulled an enterprise artifact under
Oracle terms. The isolate add-on exists in two flavours: `js-isolate-community` (UPL, MIT) and `js-isolate`, whose pom
declares the GraalVM Free Terms and Conditions. `fetch-graaljs oracle` refuses to run until the operator passes
`--accept-oracle-terms`; `scripts/setup` asks for them (or takes `--accept-license`) in the default commercial
edition: accepting is the operator's decision, not jllm's. The community isolate is weaker (no hard memory separation).

Having the downloaded Graal files in `.downloaded-libs/graal/` is the evidence that the license was accepted: the project does not ship
them in the repository, so anyone who has them downloaded them with `scripts/setup` and accepted the terms of the chosen
edition. No marker file, no setting.

The GraalJS libraries are not in this repository. If they are present in `.downloaded-libs/graal/`, you downloaded them with
`scripts/setup` and accepted the license terms of the chosen edition. After a `graalvm.version` change, run
`scripts/setup` again.
 The release scripts fail on any resolved pom that declares a licence other than UPL,
MIT, Apache, BSD or Unicode/ICU (the shaded ICU4J), and on a front-end jar that contains GraalJS.

Put the jar (`jllm-js-*.jar`, a thin jar copied to the plugin directory by `mvn install`) in the plugin
directory and enable it in the settings file:

```json
{ "plugins": { "js": { "enabled": true, "settings": { "engine": "isolated", "maxTimeoutSeconds": 120,
                                                      "maxWallSeconds": 300, "maxInnerCalls": 200 } } } }
```

or in code: `new AgentHarnessBuilder().plugin(new JsPlugin())`.

| Setting | Default | Meaning |
|---|---|---|
| `engine` | `isolated` | `isolated`: the GraalJS isolate (`SandboxPolicy.ISOLATED`); without one the tool is disabled with a message naming the artifact of the installed edition (Community `js-isolate-community`, Oracle `js-isolate`): fail closed, never a silent downgrade. `constrained`: explicit opt-in for trusted single-user setups, logs a WARN, no security boundary. Both editions work; the release fetches the matching isolate |
| `policy` | `isolated` | sandbox policy of the isolated profile: `isolated` or `untrusted` (the web application defaults to `untrusted`) |
| `statementLimit` | 1000000 | statements per run (constrained profile; the isolate relies on the CPU limit) |
| `maxTimeoutSeconds` | 120 | ceiling of the per-call `timeoutSeconds` (default 30); the clock stands still during tool calls |
| `maxWallSeconds` | 300 | total time of a script, tool calls included; never paused |
| `gracefulSeconds` | 10 | after a user stop, how long the script has to finish before it is killed |
| `maxInnerCalls` | 200 | tool calls one script may make |
| `maxConcurrentRuns` | 4 | scripts running at once (0 = unlimited); a script waits briefly for a slot, then fails with "Too many scripts are running" |
| `maxHeapMemory` | 256MB | script heap (isolated profile, or constrained where the runtime counts heap) |
| `maxIsolateMemory` | 1GB | the whole isolate |
| `maxStackFrames` | 2000 | script call depth |
| `maxAstDepth` | 1000 | nesting depth of the parsed script |
| `maxRunAllocatedBytes` | 1GB | allocation budget of one run (memory watchdog of the constrained profile) |
| `heapKillRatio` | 0.85 | share of the JVM old generation, after a collection, above which the watchdog kills the biggest allocator |
| `fileJail` | `true` | root of the file access of scripts, see below |

Sizes are a number of bytes or text with a unit (`256MB`, `1GB`). An unknown value of `engine` or `policy`, or a
number that is not positive, fails the plugin install with a message naming the key (`plugins.js.settings.<key>`).
The turn budget is set by `loop.innerCallsPerTurn` (default 10), see Limits.

### Profiles

The sandbox policy of the engine does the locking down; no `allow*` option is set by hand. A capability probe, run
once, reports what the classpath can do: GraalJS, the js language, the regex language, the sandbox limits, and the
isolate (verified by building an isolated engine and evaluating a trivial expression, not assumed from a jar). The
edition (community, oracle, unknown) is read from Maven metadata, the engine's implementation name and the release
manifest, and is logged for diagnostics only: nothing behaves differently by edition. At startup one INFO line says
the profile in use.

- **constrained**: constrained policy, engine and heap shared with the host, statement limit. Where the runtime supports
  the sandbox limits, the sandbox heap, CPU and stack-frame limits apply; where it does not (a plain JDK runs
  interpreter-only and has no heap limit), a memory watchdog does: a per-run allocation budget
  (`maxRunAllocatedBytes`), read from the thread allocation counter on each clock tick with the tool-call time
  excluded, and a process-wide guard on the old generation that kills the script that allocated the most when a
  collection leaves the heap above `heapKillRatio`.
- **isolated**: isolated (or `untrusted`) policy, the engine in a native isolate with its own heap: isolate memory,
  heap, CPU time, stack frames, AST depth and console stream sizes are limits of the engine. The isolate does not
  count statements, so a runaway loop ends at the CPU limit (or the script timeout). Isolates exist for Linux amd64
  and aarch64, macOS aarch64 and Windows amd64 (the `js-isolate-community` or `js-isolate` artifact, see Licensing). After a failure
  that may have damaged the isolate the engine is rebuilt, so one crash does not disable the tool.

Scripts run on a platform thread of their own, because an isolate does not work on a virtual thread; the caller waits.

## Honest scope

**The plugin does not remove `bash`.** Which of `run_js` and `bash` a user gets is decided by their role (web) or the
harness permissions: a role may have either or both. A host shell is a second, unrestricted way out (a helper script
written with `write_file` and run with `bash python3 x.py` has the whole network and filesystem), so only a role that
lists `bash` (for example `poweru`, or `admin`) has it. Scripts never see `bash` either way. A `deny` rule on `bash` is
not a substitute for leaving it out of the role: `python -c` and quoting bypass a regex. On the web a role also needs a
sandbox for `bash`: with the default `none` it has neither files nor a shell, while `run_js` still runs (it does not use
the sandbox), and the file calls of its scripts fail as the file tools do.

The guarantee "no host access except tools" holds only for a user whose role gives no tool with host access. Outside the
sandbox and out of its reach:

- MCP stdio servers start host processes from the settings file. That is configuration, not a model action.
- Anything that needs a build tool needs a different profile, without `run_js`.
- The path jail of the file tools is race-free on Linux and macOS. On Windows a process on the host that swaps a
  directory for a junction and back between the check and the open can still win the race (see the tools guide, path
  jail); a script itself cannot create links.
- The `docker` shell backend is not part of this profile.

## What a script can do

- Globals: the ECMAScript built-ins, `console` and `tools`. No `require`, `fetch`, `XMLHttpRequest`, `Java`,
  `Packages`, `Polyglot`, `Graal`, `load`, `print`, no environment, no processes, no threads. `tools` is frozen. Of
  the language options only `js.print`, `js.load` and `js.graal-builtin` are set (to false): the forbidden-globals
  test showed those three globals survive the sandbox policies; `Java`, `Polyglot`, `require` and the package globals
  are undefined without any option.
- `tools.<name>(args)`: one function per tool of the calling agent, the name with `-` replaced by `_`. It takes one
  object and returns the result, parsed when it is JSON, else a string. Calls are synchronous: no `await`, no
  promises (`await` outside an async function is a `SyntaxError`). A failed call throws
  an `Error` with the message of the tool (denied by the user, by a rule or a hook, a tool error, bad arguments); an
  uncaught one ends the run with `ok: false`.
- The library is built for each run from the tools the agent can call at that moment, so a tool disabled with
  `/tools` or a source disabled with `/mcp` is absent from the next run. `run_js` itself, `bash`, `task` and
  `request_tool_group` are never in it. A tool name that collides after the `-`/`_` mapping is rejected when the tool
  is registered.
- Tool results are raw (no sanitizing) so parsing sees the real content. They are taint-marked: if any inner call
  returned untrusted data, the chat is tainted, whatever the script did with it. The `run_js` result (value and
  console) is sanitized before it reaches the model.
- Regular expressions work (`/a/g`, `RegExp`, `match`, `replace`, `split`). GraalJS runs them in the internal `regex`
  language, so the constrained engine and its contexts are built with `"js", "regex"` (the isolate carries regex
  inside, and is built with `js`); allowing only `js` in-process makes every regex fail with `No language for id regex
  found`. The regex jar is mandatory for the tool; a regex literal test runs on every profile. `wasm` is deliberately not enabled:
  `WebAssembly` is unavailable.
- Nothing survives a run: each call gets a fresh `Context`, closed at the end. State between calls goes in files.

Result: `ok`, `value` (the `return` value as JSON), `console` (`console.log` and `console.error`, capped at 50 KB,
with a note when cut), `error` (`<message> at line N`) and `fileRoot`.

## Permissions

- The `run_js` approval covers the script; the preview is the full code.
- Calls from a script to the built-in file tools (`read_file`, `write_file`, `edit_file`, `glob`, `ls`, `grep`,
  `grep_code`) are "free": no gate and no prompt. The read-only ones are free always. The others (`write_file`,
  `edit_file`) are free only while the chat holds no untrusted output. Inner results taint the chat at once, so the
  first write after an untrusted read (a `web_fetch` in the same script, say) goes through the normal gate and asks
  the user; a non-interactive run (a workflow, an eval, a sub-agent without an approver) denies it, and the denial
  reaches the script as an `Error`. A script that scrapes and then writes therefore gets a prompt or a denial; a
  relaxation for the private scratch directory is a possible future decision. A tool is free only if it is one of the
  objects the harness created for the built-in file tools: an MCP or REST tool whose group is set to `Files` is not
  free. `deny` rules, `PRE_TOOL` hook denials and plan-mode confinement still apply.
- Every other call goes through the standard gating, exactly as a call from the model: mode, permission rules,
  grants, taint, the AUTO classifier, a refusal when nobody can answer. The prompt names `run_js` as the parent.
- `web_fetch` and `web_download` also ask once per host, see [per-host approval](tools.md#per-host-web-approval).
- Hooks and events see each inner call: `HookContext.parent()` is `null` for a call from the model and
  `run_js:<toolUseId>` for an inner call, and `tool_call` / `tool_result` events carry `parent`, so front ends can
  show them under the `run_js` call.

## File jail

With `fileJail: true` (default) the file tools called from a script work in a root of their own:

- web app: the chat's work directory (already per chat);
- CLI: the scratchpad of the calling agent, `<project>/.jllm/scratchpad/<chatId>/<agentId>/`. The script cannot see
  project files, the history or the scratchpads of other agents.

With the docker daemon or remote backend the same jail applies through the file backend (`ScratchJailBackend`): in the CLI layout the script sees only `/workspace/.jllm/scratchpad/<agentId>`.

`fileRoot` in the result tells the model where that is, as a path its own file tools accept. With `false`, scripts get
the same root as the file tools of the model (in the CLI the whole project directory). `storage.maxBytes` caps the
chat storage, see [TOOLS §6](tools.md#6-output-limits).

## Limits

- Time: 30 s per call by default (`timeoutSeconds`), at most `maxTimeoutSeconds`. The script clock stands still while a
  tool call is running (an approval prompt, a slow download). A second clock, `maxWallSeconds` (default 300), never
  stands still: after it expires no new tool call starts, the script is stopped, and the error says the total time was
  exceeded, tool calls included. A script cannot run forever by sleeping in tool calls.
- Tool calls: at most `maxInnerCalls` (200) per script; the next call throws a catchable `Error`.
- Turn budget: tool calls made by a script count against the run's turn budget. The budget is the model turns plus
  the inner calls divided by `loop.innerCallsPerTurn` (default 10; zero or less turns the counting off) and is compared
  with `maxTurns`. When it is used up the next inner call throws a catchable `Error` telling the script to return what
  it has, and at the next turn boundary the loop asks whether to continue, reporting both counts. A sub-agent has its
  own budget. Both limits are kept, and the numbers are meant to be tuned with eval data.
- Statements: `statementLimit` (default 1000000), per run, constrained profile.
- Memory: see Profiles. A limit that is hit is an ordinary failure (`ok: false`, `Limit reached: ...` naming the kind
  of limit, or `Memory limit: ...` from the watchdog), never an exception of the tool.
- Concurrency: `maxConcurrentRuns` scripts at a time.
- Cancel: when the user stops the turn, the next tool call of the script throws a catchable `Error` ("cancelled by
  the user"), so it can clean up and return partial output; if it is still running after `gracefulSeconds` it is
  killed (`close(true)`: no `finally` runs). Both front ends ask which way when a script is running (CLI: `k` or `g`; web: Kill now / Let it clean up); no answer in 30 s kills it.
- Errors map by state, in a fixed order: cancelled by the user, wall or script timeout, memory watchdog kill, resource
  exhausted (per kind), cancelled, internal error.
- Speed: on a plain JDK GraalJS runs interpreter-only, roughly 10 to 100 times slower than with a JIT. A heavy script
  can hit the timeout or the statement limit. The interpreter-only warning and engine diagnostics are logged only at
  DEBUG level for `org.kendar.llm.js`.
