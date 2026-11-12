# jllm-js: `run_js`

An optional plugin that adds one tool, `run_js`. The model writes JavaScript; the script runs in GraalJS (community
edition, `org.graalvm.polyglot:polyglot` and `:js`, plain JDK 21, no GraalVM install) with no host access. Its only
view of the world is the harness tools, which the script sees as a generated library: `tools.read_file({path})`,
`tools.web_fetch({url})`, `tools.mcp__docs__search({...})`.

It replaces running scripts through `bash`: scraping and data-transforming logic runs without host access, and file
and web operations go through the standard Files and Web tools.

## Enabling

Put the jar (`jllm-js-*-jar-with-dependencies.jar`, copied to the plugin directory by `mvn install`) in the plugin
directory and enable it in the settings file:

```json
{ "plugins": { "js": { "enabled": true, "settings": { "statementLimit": 10000000, "maxTimeoutSeconds": 120,
                                                      "gracefulSeconds": 10, "fileJail": true } } } }
```

or in code: `AgentHarness.builder().plugin(new JsPlugin())`.

| Setting | Default | Meaning |
|---|---|---|
| `statementLimit` | 10000000 | statements per run; stops tight loops |
| `maxTimeoutSeconds` | 120 | ceiling of the per-call `timeoutSeconds` (default 30) |
| `gracefulSeconds` | 10 | after a user stop, how long the script has to finish before it is killed |
| `fileJail` | `true` | root of the file access of scripts, see below |

## Honest scope

**Installing the plugin removes the `bash` tool.** A host shell would be a second, unrestricted way out (a helper
script written with `write_file` and run with `bash python3 x.py` has the whole network and filesystem). The plugin
declares `replaces("bash")`: the tool is absent, not hidden, and a `bash` tool added later through MCP or REST is
refused. Sub-agents inherit the tool set, so they have no `bash` either. A `deny` rule on `bash` is not a substitute:
`python -c` and quoting bypass a regex.

The guarantee "no host access except tools" holds only in a profile where no tool gives host access. Outside the
sandbox and out of its reach:

- MCP stdio servers start host processes from the settings file. That is configuration, not a model action.
- Anything that needs a build tool needs a different profile, without `run_js`.
- The `docker` shell backend is not part of this profile.

## What a script can do

- Globals: the ECMAScript built-ins, `console` and `tools`. No `require`, `fetch`, `XMLHttpRequest`, `Java`,
  `Packages`, `Polyglot`, `Graal`, `load`, `print`, no environment, no processes, no threads. `tools` is frozen.
- `tools.<name>(args)`: one function per tool of the calling agent, the name with `-` replaced by `_`. It takes one
  object and returns the result, parsed when it is JSON, else a string. Calls are synchronous. A failed call throws
  an `Error` with the message of the tool (denied by the user, by a rule or a hook, a tool error, bad arguments); an
  uncaught one ends the run with `ok: false`.
- The library is built for each run from the tools the agent can call at that moment, so a tool disabled with
  `/tools` or a source disabled with `/mcp` is absent from the next run. `run_js` itself, `bash`, `sub_agent` and
  `request_tool_group` are never in it. A tool name that collides after the `-`/`_` mapping is rejected when the tool
  is registered.
- Tool results are raw (no sanitizing) so parsing sees the real content. They are taint-marked: if any inner call
  returned untrusted data, the chat is tainted, whatever the script did with it. The `run_js` result (value and
  console) is sanitized before it reaches the model.
- Nothing survives a run: each call gets a fresh `Context`, closed at the end. State between calls goes in files.

Result: `ok`, `value` (the `return` value as JSON), `console` (`console.log` and `console.error`, capped at 50 KB,
with a note when cut), `error` (`<message> at line N`) and `fileRoot`.

## Permissions

- The `run_js` approval covers the script; the preview is the full code.
- Calls from a script to the built-in file tools (`read_file`, `write_file`, `edit_file`, `glob`, `ls`, `grep`,
  `grep_code`) need no prompt, in any mode and in a tainted run. A tool is free only if it is one of the objects the
  harness created for the built-in file tools: an MCP or REST tool whose group is set to `Files` is not free.
  `deny` rules, `PRE_TOOL` hook denials and plan-mode confinement still apply.
- Every other call goes through the standard gating, exactly as a call from the model: mode, permission rules,
  grants, taint, the AUTO classifier, a refusal when nobody can answer. The prompt names `run_js` as the parent.
- `web_fetch` and `web_download` also ask once per host, see [per-host approval](TOOLS.md#per-host-web-approval).
- Hooks and events see each inner call: `HookContext.parent()` is `null` for a call from the model and
  `run_js:<toolUseId>` for an inner call, and `tool_call` / `tool_result` events carry `parent`, so front ends can
  show them under the `run_js` call.

## File jail

With `fileJail: true` (default) the file tools called from a script work in a root of their own:

- web app: the chat's work directory (already per chat);
- CLI: the scratchpad of the calling agent, `<project>/.kendhar/scratchpad/<chatId>/<agentId>/`. The script cannot see
  project files, the history or the scratchpads of other agents.

With the docker daemon or remote backend the same jail applies through the file backend (`ScratchJailBackend`): in the CLI layout the script sees only `/workspace/.kendhar/scratchpad/<agentId>`.

`fileRoot` in the result tells the model where that is, as a path its own file tools accept. With `false`, scripts get
the same root as the file tools of the model (in the CLI the whole project directory). `storage.maxBytes` caps the
chat storage, see [TOOLS §6](TOOLS.md#6-output-limits).

## Limits

- Time: 30 s per call by default (`timeoutSeconds`), at most `maxTimeoutSeconds`. The clock stands still while a
  tool call is running (an approval prompt, a slow download); the statement limit still bounds a script that loops
  over tool calls. On expiry the run ends with `ok: false` and a `Timeout` error; it does not throw.
- Statements: `statementLimit`, per run.
- Cancel: when the user stops the turn, the next tool call of the script throws a catchable `Error` ("cancelled by
  the user"), so it can clean up and return partial output; if it is still running after `gracefulSeconds` it is
  killed (`close(true)`: no `finally` runs). Both front ends ask which way when a script is running (CLI: `k` or `g`; web: Kill now / Let it clean up); no answer in 30 s kills it.
- Memory: GraalJS community has no heap limit; none is enforced.
- Speed: on a plain JDK GraalJS runs interpreter-only, roughly 10 to 100 times slower than with a JIT. A heavy script
  can hit the timeout or the statement limit. The interpreter-only warning and engine diagnostics are logged only at
  DEBUG level for `org.kendar.llm.js`.
