# Plugin ids and configuration

Status: plan, nothing implemented. This changes the harness plugin system and the two existing plugins. `jllm-js`
depends on it: [JS-PLAN](JS-PLAN.md) needs an id and a settings section for `JsPlugin`.

## Goal

Every plugin has an id and an `enabled` flag, and takes its own settings from one place in the settings file. Only
plugins listed in that place are loaded. Anything else that is found (classpath or plugin directory) is skipped with a warning.

## Today

- `HarnessPlugin` has one method, `install(PluginRegistrar)`. It has no id and no settings.
- `builder.discoverPlugins()` loads every plugin found through `ServiceLoader`. Nothing can turn one off, and nothing
  is logged about it.
- Two plugins exist: `DocsPlugin` (`jllm-docs`, a `read_file` reader for Word) and `PdfPlugin` (`jllm-pdf`, a reader
  for PDF). Both front ends call `discoverPlugins()`: `jllm-cli/.../Main.java` and `jllm-web/.../HarnessConfiguration.java`.
- `jllm-cli` and `jllm-web` depend on `jllm-pdf` and `jllm-docs` in their poms, so both are always on the classpath. This
  plan removes those two dependencies: plugins reach the front ends only through `plugin-dir`.
- `HarnessPlugin` is used as a lambda in `HooksTest` (`HarnessPlugin plugin = r -> r...`). It is a functional interface today.
- Settings are `HarnessSettings`, a record loaded by `loadLayered` (global, project, `--settings`). Its `merge` already
  merges objects per key, so per-plugin layering needs no new merge code. The record has no `plugins` field yet.
- `builder.settings(...)` and `builder.plugin(...)` are independent builder calls in any order; `plugin()` installs at once.

## Decisions

| Topic | Decision | Why |
|---|---|---|
| Id | `HarnessPlugin.id()`, required. It is a default method that throws (see Changes), so a plugin without an id fails at startup. Pattern `[a-z][a-z0-9-]{1,31}`. Two plugins with the same id fail startup, whatever their source (two versions of one plugin in `plugin-dir` included). | A stable key for config and logs. Ids are `pdf`, `docs`, `js`. |
| Allow-list | A plugin found by discovery (classpath or `plugin-dir`) loads only if it is listed under `plugins` with `"enabled": true`. | Nothing runs by accident. |
| Not listed | WARN on the log: `plugin 'x' found (<source>) but not listed under plugins; not loaded`, where `<source>` is `classpath` or the jar path. Skipped. | Matches the request: a warning when missing. |
| Listed, not found | WARN: `plugin 'x' is listed under plugins but was not found on the classpath or in the plugin directory`. | Usually a missing jar or a typo. |
| Disabled | INFO: `plugin 'x' disabled in settings`. Skipped. | Explicit, not a warning. |
| Missing `enabled` | Treated as `false`, with a WARN. | Forces an explicit choice. |
| Explicit `builder.plugin(...)` | Not subject to the allow-list: passing the object in code is the opt-in. Id validation and the duplicate-id check still apply, and `"enabled": false` for that id in settings still skips it. Installation is deferred to `build()`, so it sees settings given by `settings(...)` in any order. | An embedder or a test that instantiates a plugin has already chosen it; forcing a settings entry would break every existing call site for no gain. Discovery is what needs the list. |
| Settings | The plugin gets its own `settings` node (`plugins.<id>.settings`, JSON, empty object if absent) at install time. | Each plugin validates its own keys. |
| Missing `plugins` section | Same as an empty section: nothing discovered is loaded, one WARN per plugin found. | Safe default; see Risks. |
| Layers | Settings files merge per plugin id: a higher layer changes the `enabled` flag or a key of one plugin, not the whole section. Needs no new merge code: `HarnessSettings.merge` already merges objects per key. `HarnessSettings` only gains a raw `plugins` field (`JsonNode`). | Matches the existing layered settings. |
| Startup summary | One INFO line: `plugins loaded: [...], disabled: [...], not listed: [...]`. | One place to check what a process is running. |
| Plugin directory | The CLI and web app load plugin jars from a directory (`--plugin-dir`, `jllm.plugin-dir`), in addition to the classpath. Jars there are discovered by `ServiceLoader` through a child class loader whose parent is the application class loader (parent first), kept open for the life of the process. A directory given explicitly that does not exist is a WARN, not a failure; the default `./plugins` is skipped silently. | Lets a plugin be dropped in without a rebuild of the application. |
| Trust | Jars in `plugin-dir` run with full application rights. The allow-list still applies to them, and each loaded jar path is logged at INFO. | A plugin directory is code execution; the user controls who can write to it. |
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

A plugin has two parts: the jar, and the config entry. Both are needed.

1. **Jar.** Dropped into the plugin directory (`./plugins` by default, or `--plugin-dir`). The shipped front ends have
   no plugin on their classpath; an application that embeds the harness may still add one as a pom dependency.
   Discovery is `ServiceLoader` in both cases.
2. **Config.** An entry under `plugins`, in the settings file. This is the switch.
3. **Command line (CLI).** `--plugin <id>` (repeatable) enables a plugin for this run, and `--no-plugin <id>` disables
   one. They override the file for that run only, through a `HarnessSettings.withPlugin(id, enabled)` step like the
   other flags in `Main`. An id with no entry in the file gets one with `enabled: true`.
4. **Spring Boot (web).** The same `plugins` section as properties: `jllm.plugins.pdf.enabled=true`,
   `jllm.plugins.js.settings.storage.maxBytes=-1`, mapped into the same settings object, so there is one code path.
   `JllmProperties` gains `pluginDir` and a `plugins` map. There is no comma-list form.

Use 1 and 2 as the normal way, 3 for one-off runs, 4 as a thin mapping.

## Changes

- `HarnessPlugin`: add `default String id()` whose body throws `IllegalStateException` with a long, explicit message
  naming the plugin class: `Plugin <class> does not implement id(). Every plugin MUST return a unique id matching
  [a-z][a-z0-9-]{1,31}, and the same id must be the key under 'plugins' in the settings file. Override id() in <class>.`
  The loader calls `id()` first for every plugin, so the failure happens at startup, before `install`, and stops the
  process: it is not downgraded to a WARN. Add `default void install(PluginRegistrar registrar, JsonNode settings)`
  that calls `install(registrar)`, and make the one-argument `install` a default that does nothing, so a plugin
  overrides whichever it needs. This is a runtime break for existing implementers: they still compile, but fail at
  startup until they add `id()`. The interface is no longer functional. `HooksTest` must replace its lambda with a small class that has an id.
- `PluginLoader` (new, in `jllm-harness`): takes the discovered plugins (classpath and `plugin-dir`) and the `plugins`
  node, applies the rules above, returns the plugins to install with their settings nodes and writes the warnings.
  Used by `discoverPlugins()`. Explicit `plugin(...)` calls are checked at `build()` (id, duplicates, `enabled: false`).
- `AgentHarness.Builder`: `discoverPlugins()` and `plugin(...)` only record; `build()` installs, using `settings`.
  New `pluginDir(Path)`.
- `HarnessSettings`: new `plugins` field and `withPlugin(id, enabled)`.
- Docs: update `docs/EXTENDING.md` section 3 and `docs/TOOLS.md` (document readers) for ids, the `plugins` section and
  `plugin-dir`.
- `jllm-pdf`: `PdfPlugin.id()` returns `pdf`. `jllm-docs`: `DocsPlugin.id()` returns `docs`.
- `jllm-cli`, `jllm-web`: pass the `--plugin` / `--no-plugin` flags or the properties into the settings object before
  `build()`, and `--plugin-dir` / `jllm.plugin-dir` to `pluginDir(...)`. The `jllm-pdf` and `jllm-docs` dependencies are
  removed from both poms; the reactor module order still builds the plugins, and `install` copies them to
  `target/plugins`. No front end has a plugin on its classpath; `jllm-js` likewise reaches them only through `plugin-dir`.
- `jllm-cli` is documented as a runnable fat jar (shade). Without the plugins inside it, `java -jar` needs
  `--plugin-dir`. Update its pom description, `README.md` (line on jllm-pdf / jllm-docs) and `docs/TOOLS.md` (the
  "add the jllm-pdf module" message becomes "put the pdf plugin jar in the plugin directory and enable it").
- A default plugin directory is needed so the common case works: `./plugins` next to the jar when `--plugin-dir` is
  absent, silently skipped if it does not exist.

## Build output

Each plugin module (`jllm-pdf`, `jllm-docs`, `jllm-js`) follows the pattern of the protocol-master plugins:

- **`package`:** `maven-assembly-plugin` with `jar-with-dependencies`, attached with its classifier. The plain jar stays
  the module's main artifact, which is what `jllm-cli` and `jllm-web` use. The jar carries its own libraries (for
  example PDFBox or GraalJS). `jllm-harness` becomes `provided` in each plugin pom, so it is not bundled; its types
  come from the parent loader. A plugin loaded from `plugin-dir` runs in a child class loader and must find its other
  libraries inside the jar.
- **`install`:** first `maven-antrun-plugin` deletes `${project.artifactId}-*-jar-with-dependencies.jar` from
  `${plugins.location}`, then `maven-resources-plugin` `copy-resources` copies the new jar there. "Same plugin" means
  "same artifact id", since the jar name starts with it. `copy-resources` alone does not delete, hence antrun.
  `plugins.location` is `${maven.multiModuleProjectDirectory}/target/plugins`, defined once in the root pom, so it
  also holds for `-pl` builds. A build outside the reactor must pass `-Dplugins.location`.

PF4J is not used. Its manifest entries, annotation processor and dependency are left out, and no PF4J type appears in
any jllm module. Plugins are found through `META-INF/services` and `ServiceLoader`.

The workflow is a full build with `./mvnw install`, which runs the copy. The run configurations need no extra step. A
single module can be rebuilt with `./mvnw install -pl <module>`. Make in IntelliJ compiles only, so run `install` first
after a plugin change.

## IntelliJ run configurations

The six run configurations in `.run/` (`CLI Haiku`, `CLI Apple`, `CLI Cubi`, `Web Haiku`, `Web Apple`, `Web Cubi`) get
the plugin options appended to `PROGRAM_PARAMETERS`. The working directory is `$PROJECT_DIR$/tmp`, so the path uses
`$PROJECT_DIR$`.

CLI:

```
--plugin-dir $PROJECT_DIR$/target/plugins --plugin pdf --plugin docs --plugin js
```

Web (the web run configs already pass `--jllm.*` program arguments):

```
--jllm.plugin-dir=$PROJECT_DIR$/target/plugins --jllm.plugins.pdf.enabled=true --jllm.plugins.docs.enabled=true --jllm.plugins.js.enabled=true
```

No before-launch step is added: the plugin jars come from `./mvnw install`, which is run by hand. The `js` entry loads
only once `jllm-js` exists; until then it logs the "listed but not on the classpath" WARN, which is expected.

## Tests

1. Id validation: bad pattern rejected; a plugin that does not override `id()` fails startup with the long message
   naming its class; two plugins with one id fail startup.
2. Allow-list: a plugin on the classpath and not listed is not installed, and a WARN is logged with its id.
3. Listed but absent: WARN logged, no failure.
4. Disabled: not installed, INFO logged, no WARN.
5. Missing `enabled`: not installed, WARN logged.
6. Settings: the plugin receives its own `settings` node; an absent node gives an empty object. Holds for a plugin
   passed to `plugin(...)` before and after `settings(...)`.
7. Layering: a higher layer changes only the named plugin's `enabled` or a key; other plugins keep their values.
8. CLI flags override the file for one run and do not write the file; `--plugin` on an unlisted id enables it;
   `--no-plugin` wins over `enabled: true`. The web properties map to the same section.
9. The startup summary lists loaded, disabled and not listed ids. A missing `plugins` section loads nothing. An
   explicit `plugin(...)` installs without a list entry and is skipped when its entry has `enabled: false`.
10. With no plugin on the classpath, a jar in `--plugin-dir` is discovered and listed in the startup summary; a jar outside it is not.
11. The copy step writes the jar to the module `target/` and `target/plugins/`, and removes an older jar of the same plugin id from
    `target/plugins/` first (checked in a build run, not a unit test).

## Milestones

| # | Work | Done when |
|---|---|---|
| 1 | `HarnessPlugin.id()`, settings-based install, `PluginLoader`, deferred install at `build()`, `HarnessSettings.plugins`, warnings; `HooksTest` lambda replaced | harness tests for 1 to 7 and 9 green |
| 2 | Ids on `PdfPlugin` and `DocsPlugin`; the two modules' tests updated; `plugins` section added to the project's own settings files; docs updated | both modules build; the plugins load only when listed |
| 3 | CLI flags and Spring properties mapped to `plugins`; `--plugin-dir` and `jllm.plugin-dir`; the copy step in each plugin module | flags, properties and copy step tested (8 to 11) |
| 3a | Run configurations in `.run/` updated (plugin options only, no extra build step) | the six run configs start with the plugins listed in the startup summary, after `./mvnw install` |
| 4 | `JsPlugin` uses id `js` and reads its settings from this section | JS-PLAN milestone 2 done |

## Risks

- **Existing setups break.** Anyone who relies on discovery alone will see both plugins stop loading. The first release
  logs a WARN for each, and the docs say to add the `plugins` section. Decided: a missing `plugins` section loads
  nothing, and every plugin found on the classpath gets the WARN `plugin 'x' found on the classpath but not listed
  under plugins; not loaded`. This changes current behaviour.
- **Startup break.** `id()` throws by default, so external `HarnessPlugin` implementations fail at startup until they add an id (they still compile; lambdas no longer fit the interface). Only
  `HooksTest` is affected in this repository (checked by search). Explicit `plugin(...)` calls need no settings entry,
  so no other call site changes.
- **Plugin dir is code execution.** See the Trust decision. The directory must not be writable by untrusted users.
