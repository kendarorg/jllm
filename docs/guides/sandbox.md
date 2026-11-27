# Sandboxes

A sandbox decides three things: where a chat works (the work directory), where agents keep temporary files (the scratch
directory) and how the `bash` tool runs commands. They are two independent layers, put together by `SandboxFactory`:

- **Layout**: where the chat lives on the host (work, scratch, history, delete). `CwdLayout` (CLI), `ChatDirLayout`
  (web), `DirLayout` (library default).
- **Runner**: how commands run. `LocalSandboxExecutor` (plain processes, no isolation) or `RemoteExecSandboxExecutor`
  (a long-running container with an HTTP daemon; the only isolated runner).

Commands are not the only thing a harness can run. The `jllm-js` plugin runs model-written JavaScript in a second,
independent sandbox ([The JavaScript sandbox](#the-javascript-sandbox-run_js)). It is not a runner: it is always present
next to whichever runner is chosen.

| Layout | Used by | Work dir | Scratch dir |
|---|---|---|---|
| `CwdLayout` | CLI | the project directory, for every chat | `<cwd>/.jllm/scratchpad/<chatId>/<agentId>` |
| `ChatDirLayout` | web | `<X>/<chatId>` | `<X>/<chatId>/.jllm/scratchpad/<agentId>` |
| `DirLayout` | library default | `<sandbox.tempDir>/<chatId>` | `<work dir>/<agentId>` |

| Runner | Selected by | Isolation |
|---|---|---|
| `LocalSandboxExecutor` | `sandbox.backend: local` (default) | none: commands run as the JVM user. For testing and trusted single-user use only |
| `RemoteExecSandboxExecutor` | `sandbox.backend: docker` | container, `standard` or `hardened` [profile](#profiles) |

`<X>` is the project directory given to the web application. `sandbox.tempDir` defaults to
`${java.io.tmpdir}/agent-harness`. There is no per-command docker runner any more: `sandbox.backend: docker` always means
the daemon runner, and the old `sandbox.docker.mode` key and `--docker-mode` flag are gone.

## The web: a sandbox per role

In the CLI the runner is one choice for the whole process (`sandbox.backend`). In the web application it is chosen
**per role** by an administrator, and the default is **none**: a role without a sandbox gets no file tools and no
`bash`. `RoutedSandboxExecutor` holds one runner per kind and picks the runner of each call from the chat's user and
their role. `jllm.sandbox` is ignored on the web (one warning is logged); `jllm.docker-*` and `sandbox.docker.*` stay
the defaults of the containers.

| Kind | Runner | What the role's users get |
|---|---|---|
| `none` (default) | `DisabledSandboxExecutor` | nothing: every file tool and `bash` fails with an error that the model sees |
| `local` | `LocalSandboxExecutor` | files in the chat directory and `bash` on the server as the server user; **no isolation**, trusted users only |
| `docker-isolated` | `RemoteExecSandboxExecutor`, `hardened` profile | a container with no network unless the role grants it, and resource limits; files and `bash` inside it |
| `docker-open` | `RemoteExecSandboxExecutor`, `standard` profile | a container with docker's default network; files and `bash` inside it |

Docker kinds also take the container scope (`chat` or `user`) and, for `docker-isolated`, the network (`none` or
`default`). Everything else (image, limits, remote host, clean-up) comes from the settings.

- **A refusal is a tool error, not a failure.** `SandboxDisabledException` reaches the model as the result of that call;
  it never offers to run locally (unlike docker being unavailable, see [Docker missing](#docker-missing)). The chat, its
  history and its directories keep working.
- **Safe defaults.** A chat without a user, a user or role that cannot be read, a stored value that does not parse, a
  failing lookup and a kind without a runner all resolve to `none`. The answer is cached for two seconds.
- **docker needs docker only when used.** Building the container runner does not touch docker, and the orphan sweep at
  start skips a missing docker. A role set to docker on a host without it affects only its own chats.
- **Where it is stored.** The `sandbox` key of the role settings: `roles.settings` (SQL) or the `sandbox` object of
  the role file: `{"sandbox": {"kind": "docker-isolated", "scope": "user", "network": "none"}}`. An admin changes it in
  Administration, Roles, Sandbox, with `PUT /api/admin/roles/{name}` or `PUT /api/admin/roles/{name}/settings/sandbox`
  (an invalid value is refused with 400).
- **`run_js`** does not depend on the kind: it runs in the server's engine. Only the file calls a script makes follow the
  sandbox: refused for `none`, the chat directory for `local`, the container for the docker kinds.
- **Files tab.** For a role without a sandbox the file explorer answers 403.

## The interface

`SandboxExecutor` is four narrow parts: `Layout` (`location`, `workDir`, `scratchDir`, `chatStorage`, `sharedWorkDir`,
`deleteWorkDir`), `Exec` (`execute`, `environmentWhitelist`, `outputLogLimit`, `shell`, `displayPath`,
`shellDescription`), `SandboxFiles` (`files`: file access when the work dir is not on the host) and `Lifecycle`
(`allowLocalFallback`, `localFallbackActive`, `backup`, `clean`, `close`). See [EXTENDING §9](extending.md#9-sandbox).

## What every runner guarantees

- **Path jail for file tools.** `read_file`, `write_file`, `edit_file`, `glob` and `grep` are confined to
  `workDir(chatId)`; `..` and symlink escapes are rejected. With the local runner they run in the JVM, with the container
  runner inside the container.
- **`chatId` validation.** A chat id must match `[A-Za-z0-9._-]{1,64}` and cannot contain `..`, so it cannot leave the
  base directory.
- **Writable scratch.** Each agent of a chat (main agent and every sub-agent) has its own scratch directory, writable in
  every mode, including plan mode.
- **Timeouts.** `bash` uses `sandbox.timeoutSeconds` (default 120); the tool caps a requested timeout at 600 s. On
  timeout the process and its descendants are killed.
- **Bounded output.** The last 50 KB of output are kept; beyond that the output also goes to `bash-N.log` in the
  scratch directory. The log itself is capped (`outputLogLimit`, default 50 KB, `0` unlimited): it then
  holds the start of the output and ends with `[log truncated at N bytes]`, while the result holds the end.
- **Background children.** A command returns when its shell exits, with the shell's exit code, even if a background
  child (`cmd &`) still holds the output pipe: output is read for 2 more seconds, later output is discarded. The local
  runner leaves such a child running; the daemon kills it (see [Lifecycle](#lifecycle)).
- **Links.** Deleting a work or scratch directory removes symbolic links and Windows junctions without following them.
- **Shell description.** `shellDescription()` is appended to the `bash` tool description and names the shell
  (`sandbox.shell` locally, default `cmd /c` on Windows, `zsh -c` on macOS, `bash -c` elsewhere; `sh -c` in the
  container), so the model writes matching syntax. A chat without the tool
  (policy, chat toggle, deny rule) is never told about a shell.

## Layouts

### `CwdLayout`: the project directory

Used by the CLI. The work directory is the real project directory for every chat, so the agent edits your files in
place.

- Scratch: `<cwd>/.jllm/scratchpad/<chatId>/<agentId>`. If the project directory is not writable (checked by creating
  a probe file), scratch and history fall back to `sandbox.tempDir`, still under the chat id.
- History: `<cwd>/.jllm/history/<chatId>/<agentId>/`.
- Deleting a chat removes its scratch directories only. The project directory is never touched.

### `ChatDirLayout`: one directory per chat

Used by the web application. Each chat is self-contained under a base directory:

```
<X>/<chatId>/              work dir
<X>/<chatId>/.jllm/     history, scratchpad, checkpoints
```

- Chats cannot see each other through the file tools.
- Deleting a chat deletes `<X>/<chatId>` entirely.

### `DirLayout`

The default when the builder is given no sandbox (`LocalSandboxExecutor.of(config)`): `<sandbox.tempDir>/<chatId>`.

## `LocalSandboxExecutor`: plain subprocess

A command runs as `cmd /c` on Windows or `bash -c` elsewhere, with the chat's directory as current directory. No
network, memory, CPU or process limits: commands can reach everything the JVM user can reach, and only the file tools are
jailed. A warning is logged at the first command.

**The local runner is for testing and trusted single-user use only.**

- **Plan mode is advisory**, as in Claude Code: the file tools refuse to write outside the scratch directory, but a shell
  command can still write anywhere. There is no OS-level enforcement and no refusal of shell in plan mode. A real
  read-only plan mode needs the container runner.
- The web does not refuse shell on the local runner. An admin who runs the web on it accepts that `bash` runs on the host
  as the server user; the admin UI says so. Use `RemoteExecSandboxExecutor` for anything real.

The command inherits the whole environment of the harness process (API keys included) unless the settings key
`whitelistedEnvVars` narrows it, see [USAGE](../reference/usage.md#5-settings-file).

## `RemoteExecSandboxExecutor`: a long-running container

Selected by `sandbox.backend: docker` (`--sandbox docker`). A container stays up and runs a small HTTP daemon,
`sandbox-agent.py` (Python standard library only). Every tool goes through it: `bash`, `read_file`, `write_file`,
`edit_file`, `glob`, `grep`. Files and installed packages survive between calls and a command does not pay container
startup. Each command is still a fresh `sh -c`: `cd` and exported variables do not carry over, and background jobs it
starts (`cmd &`) are killed when it ends (see [Lifecycle](#lifecycle)).

```sh
java -jar jllm-cli.jar --sandbox docker --docker-image maven:3-eclipse-temurin-21
```

The web does not take `--sandbox`: an administrator gives each role `docker-isolated` or `docker-open` (see
[The web: a sandbox per role](#the-web-a-sandbox-per-role)); `--docker-image` and the other `--docker-*` flags set the
container defaults.

The image must provide `python` (version 3) and `sh`; the default `python:3.12-slim` has both. For another image
without Python, install it or build an image on top. The repository builds no image; Docker pulls the configured one the
first time it is needed (the pull needs network on the host). The default image has no bash, git, compiler or Maven:
choose an image with the tools your agent needs, for example `maven:3-eclipse-temurin-21` for Java builds.

### Selecting and configuring it

Settings file (unset keys keep the defaults):

```json
"sandbox": {
  "backend": "docker",
  "localFallback": true,
  "docker": {
    "binary": "docker", "image": "maven:3-eclipse-temurin-21", "profile": "standard", "scope": "chat",
    "memory": "512m", "cpus": "1.0", "pidsLimit": 256, "user": "1000:1000", "network": "none"
  }
}
```

| Key | Default | Effect |
|---|---|---|
| `sandbox.backend` | `local` | CLI and library: `local` (no isolation) or `docker` (the container runner). The web ignores it: the kind is per role |
| `sandbox.localFallback` | `true` | allow running locally, without isolation, when docker is unavailable (asked per chat, see [Docker missing](#docker-missing)); `false` returns an error |
| `sandbox.docker.binary` | `docker` | CLI to call |
| `sandbox.docker.image` | `python:3.12-slim` | image of the container |
| `sandbox.docker.profile` | `standard` | `standard` or `hardened` ([Profiles](#profiles)) |
| `sandbox.docker.scope` | `chat` | `chat` or `user` ([Scope](#scope-one-container-per-chat-or-per-user)) |
| `sandbox.docker.network` | `none` | `hardened` only: `none` (no network) or `default` (docker's default network) |
| `sandbox.docker.memory` | `512m` | memory limit |
| `sandbox.docker.cpus` | `1.0` | CPU limit |
| `sandbox.docker.pidsLimit` | `256` | process limit |
| `sandbox.docker.user` | `1000:1000` | user in the container |
| `sandbox.docker.remote` | `false` | the docker daemon is on another machine; upload and back up the workspace |
| `sandbox.docker.remoteHost` | from `DOCKER_HOST` | address where the remote host's published ports are reached; `https://` prefix = TLS |
| `sandbox.docker.allowInsecure` | `true` | accept plain http to a non-loopback remote host (WARN logged); `false` refuses it, recommended for shared deployments |
| `sandbox.docker.trustStore`, `trustStorePassword` | default JDK trust store | PKCS12/JKS file trusted for https daemon URLs |
| `sandbox.docker.cleanup` | none | idle clean-up: `idleMinutes`, `action` (`stop`, `backup-remove`, `remove`), `retentionDays`, `maxWorkspaceBytes`; see [Clean-up policy](#clean-up-policy-and-backups) |

The plan names the scope key `sandbox.remote.scope`; it is `sandbox.docker.scope` because `sandbox.docker.remote` already
means "docker daemon on another machine".

Command line, winning over the settings file. The CLI and the web application take the same flags
(`--flag value` or `--flag=value`):

| Flag | Meaning |
|---|---|
| `--sandbox local\|docker\|none` | backend; `none` (CLI only, `--mode auto` only): the local runner without the file and shell path jail, for use inside a benchmark container that is itself the sandbox |
| `--docker-image IMAGE` | image |
| `--sandbox-profile standard\|hardened` | profile |
| `--sandbox-scope chat\|user` | scope; fixed for the session |
| `--docker-remote`, `--docker-remote-host ADDRESS` | docker daemon on another machine ([remote docker](#remote-docker)) |
| `--sandbox-clean` | CLI only: remove the container of the startup directory, print what was removed, exit |
| `--sandbox-clean-all` | CLI only: remove every CLI sandbox container, print what was removed, exit |

`SandboxFactory` is the one place that turns settings into a runner; CLI and web both call
`SandboxFactory.create(settings, config, layout, kind)`, so the selection rules cannot drift. `kind` is
`RemoteExecSandboxExecutor.Kind.CLI` or `WEB`.

### Profiles

Set by `sandbox.docker.profile`, per container.

| | `standard` | `hardened` |
|---|---|---|
| Network | docker's default | none (see below) |
| Memory, CPU, pids | limits from the settings | limits from the settings, per user and per chat |
| Root filesystem | read-only | read-only |
| Capabilities | all dropped (`--cap-drop ALL`), `no-new-privileges` | same |
| User | non-root `--user` | same |
| Writable | `/workspace`, `/tmp` (64 MB tmpfs) | same: nothing else |

`hardened` is meant for temporary or untrusted users. Docker cannot publish a port on a `--network none` container, so
the harness reaches the daemon of a no-network container through an exec tunnel: a loopback listener on the host that
relays each connection through `docker exec -i <container>` to the daemon's loopback port inside. The container has no
network beyond loopback. `network: default` gives a hardened container docker's default network and the published port
instead; that is a looser setting.

Every setting of a container (profile, memory, cpus, pids, network) can differ per user and per chat. The embedder
supplies a `SandboxPolicy` (chat id to settings); the web builds it from the role's sandbox (kind gives the profile,
plus scope and network), and a looser setting such as network on is an explicit admin grant. A reused container whose `jllm.config` label no longer matches is
recreated.

### Scope: one container per chat or per user

`sandbox.docker.scope`, resolved by `SandboxFactory`; the executor class is the same. The container is identified by a
key and keeps the usual per-chat structure inside.

| | `chat` | `user` |
|---|---|---|
| Container key | `chatId` | `userId` |
| Volume | one per chat, removed with the chat | one per user, persistent (named `jllm-<userId>`) |
| Work dir in container | `/workspace` | `/workspace/<chatId>` (same layout, one level down) |
| Chat delete | remove container and volume | remove `/workspace/<chatId>` only |
| Shared between chats | nothing | installed packages, caches, tools |
| Isolation | chat from chat | user from user; chats of one user share the container |
| Cost | many containers | few containers, idle reaping needed |

In `user` scope the chats of one user run commands in parallel in the same container, each in its own work dir. The
daemon takes the chat from the request header `X-Jllm-Chat` and roots the file tools at `/workspace/<chatId>` and its
scratchpad.

**`user` scope shares one container across a user's chats: untrusted chats should use `chat` scope.** In the web the
scope is admin-only; the admin decides per group.

CLI: there is a single OS user and the project is bind-mounted at `/workspace` when the container is created, so `user`
scope is keyed by user plus project path (the work dir stays `/workspace`). `chat` scope (default) is one container per
session, removed when the session ends. A `user` container survives between runs and is reused if running. The scope
is chosen with `--sandbox-scope` and cannot change mid-session. `/status` shows the sandbox: runner, profile, scope,
container key and name, work dir, and whether `run_js` uses the container storage.

### Plan mode, availability

- **Plan mode.** The agent runs `bash` in its scratch directory and the file tools refuse writes outside it, but the
  container cannot stop a command writing to `/workspace`: plan mode stays advisory for `bash`.
- **Availability.** Docker is checked at first use with `docker version`; success is cached, failure is retried.
- **Timeouts.** A command is killed in the container on timeout or cancellation.

When embedding the library:

```java
SandboxExecutor sandbox = new SandboxFactory().runner(new ProcessDockerCommandRunner())
        .create(settings, config, new ChatDirLayout(base), RemoteExecSandboxExecutor.Kind.WEB);
new AgentHarnessBuilder().sandbox(sandbox) …
```

`SandboxFactory` also takes `userOf` (chat id to user id, for `user` scope), `policy` (the `SandboxPolicy`),
`backupRecords` (the `BackupRecordStore`) and `onWarning`. `settings.applyTo(config)` copies the `sandbox.docker.*` keys
into `HarnessConfig.Docker`.

### Layout of the container

| | CLI | Web |
|---|---|---|
| Container | `jllm-cli-<hash of the directory>[-<chatId>]` | `jllm-web-<chatId>` (chat scope), `jllm-web-<userId>` (user scope) |
| `/workspace` | bind mount of the project directory | `chat`: anonymous volume; `user`: named volume `jllm-<userId>`; not on the host |
| Scratchpad | `/workspace/.jllm/scratchpad/<agentId>` | `chat`: `/workspace/.jllm/scratchpad/<agentId>`; `user`: `/workspace/<chatId>/.jllm/scratchpad/<agentId>` |
| History, sessions, settings | `<project>/.jllm/history` on the host (visible through the mount) | `<X>/<chatId>/.jllm/history` on the host, outside the container |
| Deleting | `chat`: removed at session end; `user`: kept, see [cleaning](#cleaning) | `chat`: container and volume removed with the chat; `user`: `/workspace/<chatId>` removed |

- The docker daemon must be local, unless you allow [remote docker](#remote-docker): a remote `DOCKER_HOST` (or docker
  context endpoint) is refused otherwise, since the published port and the bind mount would not reach it.
- Labels: `jllm=1`, `jllm.kind=cli|web`, `jllm.owner=<container key>`, `jllm.config=<hash of image, profile, limits and
  daemon script>`.
- Sub-agents share the chat's container; each has its own scratch directory.
- The daemon script is written to `~/.jllm/sandbox-agent/<version>/` and mounted read-only at `/agent`.

### Docker missing

The agent does not silently run on the host and does not just fail: it asks you ("Docker is unavailable: run without
isolation?", an approval dialog in the CLI and the web UI). If you agree, that chat uses the host directories and plain
processes from then on (through the local runner) and each tool result says so; if you decline, the tool returns the
error. The question is not asked when `sandbox.localFallback` is `false` or the run is not interactive: the tool then
fails with the error.

### Remote docker

A docker daemon on another machine cannot bind-mount your project or be reached on `127.0.0.1`. Allow it explicitly:

```json
"docker": { "remote": true, "remoteHost": "192.168.1.45" }
```

or `--docker-remote --docker-remote-host 192.168.1.45` (CLI and web). `remoteHost` is the address at which this machine
reaches the ports the remote host publishes; blank, it is taken from `DOCKER_HOST` (`tcp://host:2375`) or the current
docker context's endpoint, else `127.0.0.1`.
The docker CLI itself still talks to the remote daemon through `DOCKER_HOST` or a docker context.

What changes:

- `/workspace` is a volume in the container in both front ends (nothing is mounted), and the daemon script is sent in the
  environment instead of being mounted.
- **Setup.** When a container is created, the workspace is uploaded: the CLI sends the project directory, the web sends
  the owner's last backup (see [Backup](#clean-up-policy-and-backups)) if there is one. Reusing a running container
  uploads nothing.
- **Backup.** The container's workspace is downloaded into the project directory (CLI; files are overwritten, files
  deleted in the container are not deleted locally) or into the owner's backup file (web). It happens when the session ends
  (CLI exit, web shutdown), on `/sandbox backup`, and before `/sandbox clean`. Between backups the only copy is in the
  container: if the remote host loses it, those changes are lost. `--sandbox-clean` does not back up first. When the
  backup fails (e.g. the host has a symlink or junction where the archive has a directory: writing through it is
  refused), `/sandbox clean` and web shutdown keep the container and say how to recover (`docker cp`, or fix and retry).
- **The scratchpad is lost**: `.jllm/scratchpad` is neither uploaded nor backed up. The history is not lost: it is
  always on this machine (never in the container), so it is simply not part of the archive. Checkpoints
  (`.jllm/checkpoints`) travel with the workspace.
- Symbolic links and Windows junctions are not copied, and the backup never writes through one on the host.
  Executable bits are kept (by a `.jllm-exec` list in the archive).
- Large projects (`node_modules`, build output, `.git`) are uploaded whole: start the agent from a smaller directory or
  clean first.
- The port is published on all interfaces of the remote host (`0.0.0.0`). The daemon itself speaks plain HTTP (native TLS in the daemon is a TODO), so unless
  something in front of it terminates TLS the token, the commands and the files can be read by anyone on the path.
  - `remoteHost` may carry a scheme: `"https://docker.example.org"` makes the harness use TLS (JDK default trust store, or
    `sandbox.docker.trustStore` / `trustStorePassword`: a PKCS12 or `.jks` file). This needs a TLS-terminating proxy that
    forwards to the published port; the daemon does not do TLS.
  - `sandbox.docker.allowInsecure` (default `true`): plain http to a non-loopback host is accepted and one WARN is logged.
    Set it to `false` on shared deployments: the harness then refuses such a host at start with a message naming the
    alternatives. Loopback (`127.0.0.1`, `localhost`, `::1`) http is always allowed.
  - Safe alternatives to https: an SSH tunnel (`ssh -L <port>:127.0.0.1:<port> host`, then `remoteHost` = `127.0.0.1`) or a
    trusted/private network.

The daemon's `GET /backup` (zip of the workspace) and `POST /setup` (zip to extract, overwriting; names that leave the
workspace are refused) do the transfer.

### Security

The daemon listens on the container's port 8080, published on `127.0.0.1` only (`-p 127.0.0.1::8080`, random host
port); a hardened no-network container publishes nothing and is reached through the exec tunnel. Every request,
`/health` included, must carry a per-container token:

1. Before starting a container the harness hashes a random UUID with SHA-256; the hex digest is the token.
2. It is passed as the environment variable `JLLM_TOKEN` and sent by the harness in the header `X-Jllm-Token`.
3. The daemon compares it in constant time. A missing or wrong token gets an empty `401`; nothing is executed and the
   token is never logged.
4. The daemon removes every `JLLM_*` variable from the environment of the commands it starts, so `env` in `bash` does not
   show the token.
5. The token is not stored in a file. A reused container's token is read back from `docker inspect`; a recreated
   container gets a new one.

This keeps other local processes and other users of the machine from driving the daemon. It does not protect against code
running inside the container, which already has the sandbox's access. There is no TLS (plain HTTP on localhost) and no
user accounts.

### Lifecycle

Containers are held by the `ContainerRegistry` (get-or-start per container key, resource limits, idle sweep);
`RemoteExecSandboxExecutor` asks it for the container and deals only with the chat's work dir.

- The container starts on the first tool call and the harness waits for `/health`.
- **CLI.** `chat` scope: the container is removed when the session ends (after a backup in remote mode; a failed backup
  keeps it). `user` scope: left running on exit. Next run: running, reuse it; stopped, `docker start`; missing,
  create. If its `jllm.config` label differs from the current settings (image, profile, limits, daemon script), it is
  recreated and a warning is printed: background processes and tools installed in it are lost, project files are not
  (they are in the bind mount).
- **Web.** `chat` scope: the container is removed on chat delete and on shutdown. `user` scope: only `/workspace/<chatId>`
  goes with the chat; the container and volume stay until the clean-up policy removes them or the user is dropped. At
  startup, `jllm.kind=web` containers whose chat (or user) no longer exists are removed. CLI containers are never touched
  by that sweep. A new volume is handed to the container user with a one-off `chown` before the daemon starts.
- A command runs in its own process group. It finishes when its shell exits, not when its output pipe closes: a
  background job that inherited the output does not keep it running (its output after the shell exited is discarded).
  Once the result is read the harness releases the command (`DELETE /exec/{id}`), which kills the whole process group:
  background jobs do not outlive the command that started them, whether it finished, timed out or was cancelled. Only
  a process that leaves the group (`setsid`) survives. The container stays up.

### Clean-up policy and backups

Web. The registry runs a periodic sweep over idle containers and applies the clean-up policy of the role the user acts
under. There is no global cap: reaping is by role policy only.

| Field | Meaning |
|---|---|
| `idleMinutes` | inactivity before the action; `0` = never |
| `action` | `stop`, `backup-remove` (back up, then remove; restored on next use) or `remove` |
| `retentionDays` | how long a backup is kept |
| `maxWorkspaceBytes` | quota of the workspace volume, checked at backup and by the sweep; a backup over it is refused and the container kept |

A user with several roles selects the role in the UI; through the API (no selection) the first role in alphabetical
order is used.

Backup is both: a **workspace archive** (the daemon's `GET /backup` zip, small and portable, in
`<layout>/<chatId>/.jllm/workspace-backup.zip` for `chat` scope, in a per-user location for `user` scope) and a
**`docker commit` image** (keeps installed packages, tied to the docker host that made it). A `BackupRecord` (image,
tarball location, docker host, timestamp) is stored per owner (the chat in `chat` scope, the user in `user` scope) in a
`BackupRecordStore`; the default store is a JSON file next to the tarball, the web can supply a SQL one.

- On next use the record is looked up and the image is recycled to start the container; the archive restores the
  workspace (a volume is not part of the image) and is the fallback when the image is gone or the docker host changed.
- When the retention expires both are deleted (archive removed, image removed from its docker host) and the record is
  cleared.
- When a user is dropped, all their data goes: containers, volumes, backups, archives and records. No backup outlives
  the user.
- Users with the download permission (granted by an admin on the role) can download their own archive.
- When an admin changes the scope of a group, the UI warns first that existing containers are dropped and only the
  workspace is kept (installed packages and other container state are lost); the workspace moves into the new layout
  (`/workspace` of a chat becomes `/workspace/<chatId>`, or the reverse).

Per-user docker host: one main `docker_host` in the admin configuration, and each user can have an override; the registry
resolves the host per user, then the container key.

### Status of the web features

| Part | State |
|---|---|
| Layout/runner split, `SandboxFactory`, profiles, scope, `ContainerRegistry`, backups with `docker commit`, `BackupRecordStore`, clean-up sweep, `SandboxPolicy`, CLI flags and `/status` | implemented (core, CLI) |
| Web wiring: `userOf`, `SandboxPolicy` seam, orphan sweep | implemented |
| Sandbox per role (`none`, `local`, `docker-isolated`, `docker-open`, scope, network), stored in `roles.settings`; role editor with the local-runner warning | implemented |
| A list of allowed container images per role with their mode (isolated or exposed); per-user allowed storages, per-user docker host and per-role clean-up policy stored in the SQL store; clean-up and scope-change warning in the admin UI; tarball download endpoint and permission; scope-change migration | not yet: those come from settings and the embedder |

### Cleaning

CLI. Project files and `.jllm` are never removed.

| | |
|---|---|
| `--sandbox-clean` | remove the container of the startup directory, print what was removed, exit |
| `--sandbox-clean-all` | remove every `jllm.kind=cli` container |
| `/sandbox clean` | remove the container of the current session's directory; the next command makes a new one (remote docker: backs up first) |
| `/sandbox backup` | download the container's workspace into the project directory now (remote docker) |

### Notes

- `grep` uses the daemon's regular expressions (Python `re`), not Java's; they agree on ordinary patterns.
- Checkpoints (`/undo`) work, but are kept inside the container under `/workspace/.jllm/checkpoints`, so they live
  and die with it. In the web, deleting the chat removes them.
- Paths shown to the model are container paths (`/workspace/...`); `/where` shows them too.
- Command output beyond 50 KB goes to `bash-N.log` in the agent's scratch directory inside the container, capped like
  the local log (the request's `logMaxBytes`, else `JLLM_LOG_MAX_BYTES`, default 50 KB).

### The daemon

All endpoints require the token; the body of a request without it is never read (empty `401`, connection closed), and
a body over 256 MB is refused with `413` before it is read. Paths are jailed to the root (symlinks resolved); `root=workspace` (default) or
`root=scratch` selects `/workspace` or the scratchpad. With the request header `X-Jllm-Chat: <chatId>` both roots are the
chat's: `/workspace/<chatId>` and its `.jllm/scratchpad` (user-scope containers). The id must match `[A-Za-z0-9._-]{1,64}`.
The daemon gets `JLLM_EXTERNAL_LINKS` (`none`, `read` or `readwrite`, default `none`) at container creation: what a link that leaves the workspace does, as `files.externalLinks` does on the host (the CLI passes its setting; the web app always passes `none`). `read` lets reads, `glob` and `grep` follow it and refuses writes, moves and deletes through it with a message naming `files.externalLinks`; `readwrite` follows it for both. A recursive delete never follows a link. A change recreates the container.

| Endpoint | Does |
|---|---|
| `GET /health` | readiness |
| `POST /exec` `{command, cwd, timeoutSeconds, logDir, logMaxBytes}` | runs `sh -c` in its own process group, returns `{id}` |
| `GET /exec/{id}?wait=N` | `{running:true}`, or once the shell exited `{stdout (last 50 KB), exitCode, timedOut, omittedLines, logPath}` |
| `DELETE /exec/{id}` | kills the process group (background jobs included) and forgets the command |
| `GET /file?path&offset&limit` | raw bytes |
| `PUT /file?path&append` | writes the body, creating parent directories |
| `DELETE /file?path` | deletes a file or a tree (not the root) |
| `POST /mkdir?path`, `POST /move?from&to` | create a directory, rename (409 if the target exists) |
| `GET /stat?path` | `{exists, type, size, mtime, mode}` |
| `GET /glob?pattern` | matching files, one per line |
| `POST /grep` `{pattern, path, glob}` | raw `path:line:text` |
| `GET /list?path&long&all&format=json` | like `ls -p`: sorted, trailing `/` on directories; `long=1` is the `ls -l` form; `all=1` includes dotfiles; capped at 1000 entries with a `... N more` line |
| `GET /tree?path&depth` | capped recursive tree, symlinks not followed |
| `GET /download?path` | raw bytes |
| `GET /backup` | zip of the whole workspace, without `.jllm/scratchpad` (and `.jllm/history`, which is never in the container) |
| `POST /setup` | extracts the zip in the body into the workspace, overwriting |

A real-container integration test, `DockerWslIntegrationTest`, runs only on request, e.g. against the docker of a WSL
distribution: `mvn test -DargLine="-Djllm.it.wsl=Ubuntu"`. It uses remote mode (Windows paths cannot be bind-mounted into
WSL's docker) and removes the containers it creates.

The Python tests (`python -m unittest core/jllm-harness/src/test/python/test_sandbox_agent.py`) cover the token, the
environment, execution, the jail and the endpoints; they need `python` and `sh`.

### Web application: file explorer

The existing `/api/chat/{chatId}/fs` endpoints work with both runners; with the container runner they proxy to the daemon,
because the files are not on the host.

| Endpoint | Notes |
|---|---|
| `GET /fs` | list a directory; `root=workspace\|scratch` (scratch: container runner only) |
| `GET /fs/content`, `GET /fs/download` | read; download of a directory is a zip |
| `GET /fs/glob?pattern=` | container runner only |
| `GET /fs/tree?depth=` | container runner only |
| `PUT /fs/content`, `POST /fs`, `POST /fs/upload`, `POST /fs/rename`, `DELETE /fs` | writes, executed in the container |

`.jllm` stays hidden in the workspace root.

## The JavaScript sandbox: `run_js`

Provided by the optional `jllm-js` plugin ([JS guide](js.md)). It is always present, with every runner and layout: it is
not a runner and not an alternative way to run `bash`. The script is not a process and has no shell, file system or
network of its own. It runs in an embedded GraalJS engine and its only view of the world is the harness tools, exposed as
`tools.<name>({...})` and gated exactly like a call from the model.

| Profile | Isolation | Limits enforced by |
|---|---|---|
| `constrained` | GraalJS in the JVM, constrained sandbox policy, heap shared with the host | engine limits where the runtime has them, else a statement limit and a memory watchdog |
| `isolated` / `untrusted` | GraalJS in a native isolate with its own heap (Linux amd64/aarch64, macOS aarch64, Windows amd64) | the engine: isolate memory, heap, CPU time, stack frames, AST depth |

- `engine: auto` picks the isolate when the runtime has one, else `constrained`. `isolated` never downgrades silently:
  without an isolate the tool is disabled with a reason. The web application defaults to the `untrusted` policy.
- No `require`, `fetch`, `Java`, processes, threads or environment. A fresh context per run; nothing survives it.
- File tools called from a script use a jail of their own, taken from the layout: the chat work dir (web), or the agent's
  scratchpad (CLI, also with the container runner through `ScratchJailBackend`). `fileJail: false` gives scripts the
  model's own root.
- It may optionally use the container runner as its storage (can, not must): the jail then sits on the container
  workspace, with the same key and work dir as the container scope above; otherwise it stays on the host directory. `/status`
  reports which.
- Limits: per-call and total wall time, tool calls per script, concurrent runs, heap, isolate memory, allocation budget.
- GraalJS is not shipped in the jars; it is fetched by `scripts/setup` / `fetch-graaljs` into `lib-ext/`. Without it the
  plugin registers nothing and `run_js` is absent.

It does not remove `bash`: a role that has `bash` still has the host (local runner) or the container. A role with `run_js`
and no `bash` never touches the host shell, whatever the runner is: that is the no-host profile, for untrusted input where
the model only needs to scrape or transform data. The host shell is a way out of the JS sandbox's guarantees.

## Which one to use

- Your own project, trusted model and input: `CwdLayout` with the local runner (CLI default).
- Many chats that should not mix files: `ChatDirLayout` (web default).
- Untrusted input, shared hosts or unattended `auto` mode: the container runner (`sandbox.backend: docker` in the CLI; on
  the web `docker-isolated` for the role), `hardened` profile for temporary users.
- A web role that should only chat, search and run `run_js`: leave its sandbox at `none`.
- Installed tools that must persist across a user's chats: `user` scope (trusted chats only); otherwise `chat` scope.
- Untrusted input where the model only needs to scrape or transform data: `run_js` without `bash` (no host access at
  all, no container needed).

Permission rules (`deny`, `ask`, `allow` on `bash(...)`) are a guard rail, not a boundary: quoting, variables or
`python -c` bypass a regex. Only a container limits what a command can do.

See [EXTENDING §9](extending.md#9-sandbox) for the interface and how to write a custom executor.
