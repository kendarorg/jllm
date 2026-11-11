# Plugin ids and configuration

Status: plan, nothing implemented. This changes the harness plugin system and the two existing plugins. `jllm-js`
depends on it: [JS-PLAN](JS-PLAN.md) needs an id and a settings section for `JsPlugin`.

## Goal

Every plugin has an id and an `enabled` flag, and takes its own settings from one place in the settings file. Only
plugins listed in that place are loaded. Anything else on the classpath is skipped with a warning.

## Today

- `HarnessPlugin` has one method, `install(PluginRegistrar)`. It has no id and no settings.
- `builder.discoverPlugins()` loads every plugin found through `ServiceLoader`. Nothing can turn one off, and nothing
  is logged about it.
- Two plugins exist: `DocsPlugin` (`jllm-docs`, a `read_file` reader for Word) and `PdfPlugin` (`jllm-pdf`, a reader
  for PDF). Both front ends call `discoverPlugins()`: `jllm-cli/.../Main.java` and `jllm-web/.../HarnessConfiguration.java`.

## Decisions

| Topic | Decision | Why |
|---|---|---|
| Id | `HarnessPlugin.id()`, required. Pattern `[a-z][a-z0-9-]{1,31}`. Unique across the classpath; a clash fails startup. | A stable key for config and logs. Ids are `pdf`, `docs`, `js`. |
| Allow-list | A plugin loads only if it is listed under `plugins` with `"enabled": true`. | Nothing runs by accident. |
| Not listed | WARN on the log: `plugin 'x' found on the classpath but not listed under plugins; not loaded`. Skipped. | Matches the request: a warning when missing. |
| Listed, not found | WARN: `plugin 'x' is listed under plugins but not on the classpath`. | Usually a missing dependency or a typo. |
| Disabled | INFO: `plugin 'x' disabled in settings`. Skipped. | Explicit, not a warning. |
| Missing `enabled` | Treated as `false`, with a WARN. | Forces an explicit choice. |
| Explicit `builder.plugin(...)` | Goes through the same check. | One place decides what runs. Tests list their plugins too. |
| Settings | The plugin gets its own `settings` node (JSON, empty object if absent) at install time. | Each plugin validates its own keys. |
| Layers | Settings files merge per plugin id: a higher layer changes the `enabled` flag or a key of one plugin, not the whole section. | Matches the existing layered settings. |
| Startup summary | One INFO line: `plugins loaded: [...], disabled: [...], not listed: [...]`. | One place to check what a process is running. |
| Plugin directory | The CLI and web app load plugin jars from a directory (`--plugin-dir`, `jllm.plugin-dir`), in addition to the classpath. Jars there are discovered by `ServiceLoader` through a child class loader. | Lets a plugin be dropped in without a rebuild of the application. |
| Build output | Each plugin module builds a jar-with-dependencies into its own `target/`, and copies it at `install` into the shared root `target/plugins/`. | Same pattern as the protocol-master project. `target/` is already ignored by git, so no new ignore entry. |

## Settings shape

```json
"plugins": {
  "pdf":  { "enabled": true },
  "docs": { "enabled": false },
  "js":   { "enabled": true, "settings": { "storage": { "maxBytes": -1 } } }
}
```

`js.storage.maxBytes` in [JS-PLAN](JS-PLAN.md) moves to `plugins.js.settings.storage.maxBytes`.

## Ways to add a plugin at startup

A plugin has two parts: the jar on the classpath, and the config entry. Both are needed.

1. **Classpath.** A dependency in the application's pom, or a jar dropped into the CLI's lib directory. Discovery is
   still `ServiceLoader`.
2. **Config.** An entry under `plugins`, in the settings file. This is the switch.
3. **Command line (CLI).** `--plugin <id>` enables a plugin for this run, and `--no-plugin <id>` disables one. They
   override the file for that run only. The CLI already takes a settings file; these flags are added next to it.
4. **Spring Boot (web).** The same `plugins` section, given as application properties and mapped into the same
   settings object, so there is one code path.

Recommendation: 1 and 2 as the normal way, 3 for one-off runs, 4 as a thin mapping. Confirm before implementing.

## Changes

- `HarnessPlugin`: add `String id()`, and `install(PluginRegistrar registrar, JsonNode settings)`. The old one-argument
  `install` stays as a default method for one release, so external plugins keep compiling.
- `PluginLoader` (new, in `jllm-harness`): reads `plugins`, applies the rules above, returns the plugins to install and
  writes the warnings. Used by `discoverPlugins()` and by `plugin(...)`.
- `jllm-pdf`: `PdfPlugin.id()` returns `pdf`. `jllm-docs`: `DocsPlugin.id()` returns `docs`.
- `jllm-cli`, `jllm-web`: pass the `--plugin` / `--no-plugin` flags or the properties into the settings object before
  `build()`.

## Build output

Each plugin module (`jllm-pdf`, `jllm-docs`, `jllm-js`) follows the pattern of the protocol-master plugins:

- **`package`:** `maven-assembly-plugin` with `jar-with-dependencies`, so the jar carries its own libraries (for example
  PDFBox or GraalJS). The host (`jllm-harness`, Jackson, and the rest) is `provided`, not bundled, so it is not
  duplicated in the plugin. This matters: a plugin loaded from `plugin-dir` runs in a child class loader and must find
  its libraries inside the jar.
- **`install`:** `maven-resources-plugin` `copy-resources` copies the jar into `${plugins.location}`, set to
  `../target/plugins` (the root `target/`, as in the example, since the jllm modules sit one level below the root).
  Before writing, the copy step deletes any older jar of the same plugin id from that directory, so only one version
  is present per id.

PF4J is not used. Its manifest entries, annotation processor and dependency are left out, and no PF4J type appears in
any jllm module. Plugins are found through `META-INF/services` and `ServiceLoader`.

The workflow is a full build with `./mvnw install`, which runs the copy. The run configurations need no extra step. A
single module can be rebuilt with `./mvnw install -pl <module>`. Make in IntelliJ compiles only, so run `install` first
after a plugin change.

## IntelliJ run configurations

The six run configurations in `.run/` (`CLI Haiku`, `CLI Apple`, `CLI Cubi`, `Web Haiku`, `Web Apple`, `Web Cubi`) get
the plugin options added to `PROGRAM_PARAMETERS`:

```
--plugin-dir $PROJECT_DIR$/target/plugins --plugin pdf --plugin docs --plugin js
```

for the CLI. The web app gets the matching `jllm.plugin-dir` and `jllm.plugins` properties, in the run configuration's
VM options or program arguments, whichever the module uses. No before-launch step is added: the plugin jars come from
`./mvnw install`, which is run by hand.

## Tests

1. Id validation: bad pattern rejected; duplicate id across two plugins fails startup.
2. Allow-list: a plugin on the classpath and not listed is not installed, and a WARN is logged with its id.
3. Listed but absent: WARN logged, no failure.
4. Disabled: not installed, INFO logged, no WARN.
5. Missing `enabled`: not installed, WARN logged.
6. Settings: the plugin receives its own `settings` node; an absent node gives an empty object.
7. Layering: a higher layer changes only the named plugin's `enabled` or a key; other plugins keep their values.
8. CLI flags override the file for one run and do not write the file.
9. The startup summary lists loaded, disabled and not listed ids.
10. A jar in `--plugin-dir` is discovered and listed in the startup summary; a jar outside it is not.
11. The copy step writes the jar to the module `target/` and `target/plugins/`, and removes an older jar of the same plugin id from
    `target/plugins/` first (checked in a build run, not a unit test).

## Milestones

| # | Work | Done when |
|---|---|---|
| 1 | `HarnessPlugin.id()`, settings-based install, `PluginLoader`, warnings | harness tests for 1 to 7 green |
| 2 | Ids on `PdfPlugin` and `DocsPlugin`; the two modules' tests updated | both modules build; the plugins load only when listed |
| 3 | CLI flags and Spring properties mapped to `plugins`; `--plugin-dir` and `jllm.plugin-dir`; the copy step in each plugin module | flags, properties and copy step tested (8 to 11) |
| 3a | Run configurations in `.run/` updated (plugin options only, no extra build step) | the six run configs start with the plugins listed in the startup summary, after `./mvnw install` |
| 4 | `JsPlugin` uses id `js` and reads its settings from this section | JS-PLAN milestone 2 done |

## Risks

- **Existing setups break.** Anyone who relies on discovery alone will see both plugins stop loading. The first release
  logs a WARN for each, and the docs say to add the `plugins` section. Decide whether a missing `plugins` section means
  "load everything, with warnings" for one release, or "load nothing". The plan assumes "load nothing", which is the
  safer default, but it changes current behaviour.
- **Explicit plugins in tests.** Tests that call `builder.plugin(...)` must now list the plugin. Mechanical, but many
  call sites may exist; check with a search before milestone 1.
