# Sandboxes

A `SandboxExecutor` decides three things: where a chat works (the work directory), where agents keep temporary files
(the scratch directory), and how the `bash` tool runs commands. The harness ships four implementations. Only
`DockerSandboxExecutor` isolates anything.

| Executor | Used by | Work dir | Scratch dir | Isolation |
|---|---|---|---|---|
| `CwdSandboxExecutor` | CLI | the project directory, for every chat | `<cwd>/.kendhar/scratchpad/<chatId>/<agentId>` | none |
| `ChatDirSandboxExecutor` | web | `<X>/<chatId>` | `<X>/<chatId>/.kendhar/scratchpad/<agentId>` | none |
| `LocalDirSandboxExecutor` | library default | `<sandbox.tempDir>/<chatId>` | `<work dir>/<agentId>` | none |
| `DockerSandboxExecutor` | CLI and web with `sandbox.backend: docker`; library | that of the executor it wraps (project directory or chat directory), mounted in a container | that of the wrapped executor | container |

`<X>` is the project directory given to the web application. `sandbox.tempDir` defaults to
`${java.io.tmpdir}/agent-harness`.

## What every executor guarantees

- **Path jail for file tools.** `read_file`, `write_file`, `edit_file`, `glob` and `grep` run inside the JVM, confined to
  `workDir(chatId)`; `..` and symlink escapes are rejected. The executor does not run them.
- **`chatId` validation.** A chat id must match `[A-Za-z0-9._-]{1,64}` and cannot contain `..`, so it cannot leave the
  base directory.
- **Writable scratch.** Each agent of a chat (main agent and every sub-agent) has its own scratch directory, writable in
  every mode, including plan mode.
- **Timeouts.** `bash` uses `sandbox.timeoutSeconds` (default 120); the tool caps a requested timeout at 600 s. On
  timeout the process and its descendants are killed.
- **Bounded output.** The last 50 KB of output are kept; the full output goes to `bash-N.log` in the scratch directory.
- **Shell description.** `shellEnvironment()` tells the model which OS and shell it has (Windows `cmd /c`, `bash -c`,
  or `sh -c` in the container), so it writes matching syntax.

## `CwdSandboxExecutor`: the project directory

Used by the CLI. The work directory is the real project directory for every chat, so the agent edits your files in
place.

- Scratch: `<cwd>/.kendhar/scratchpad/<chatId>/<agentId>`. If the project directory is not writable (checked by creating
  a probe file), scratch and history fall back to `sandbox.tempDir`, still under the chat id.
- History: `<cwd>/.kendhar/history/<chatId>/<agentId>/`.
- Deleting a chat removes its scratch directories only. The project directory is never touched.
- Commands run as the user of the JVM, with full access to everything that user can reach.

## `ChatDirSandboxExecutor`: one directory per chat

Used by the web application. Each chat is self-contained under a base directory:

```
<X>/<chatId>/              work dir
<X>/<chatId>/.kendhar/     history, scratchpad, checkpoints
```

- Chats cannot see each other through the file tools.
- Deleting a chat deletes `<X>/<chatId>` entirely.
- Commands still run as the JVM user: `bash` can reach any path, only the file tools are jailed.

## `LocalDirSandboxExecutor`: plain subprocess

The default when the builder is given no sandbox. A command runs as `cmd /c` on Windows or `bash -c` elsewhere, with
the chat's directory as current directory. No network, memory, CPU or process limits. A warning is logged at startup.
`CwdSandboxExecutor` and `ChatDirSandboxExecutor` extend it and change only the directory layout.

## `DockerSandboxExecutor`: container isolation

Two modes, chosen by `sandbox.docker.mode`:

- `oneshot` (default): each `bash` command runs in a throwaway container (`docker run --rm`), started through the docker
  CLI. The file tools run in the JVM on the host. Described below.
- `daemon`: one long-running container with a small HTTP daemon inside; `bash` **and** the file tools run through it.
  See [Daemon mode](#daemon-mode-a-long-running-container).

### Selecting and configuring it

Settings file (unset keys keep the defaults):

```json
"sandbox": {
  "backend": "docker",
  "localFallback": true,
  "docker": {
    "binary": "docker", "image": "maven:3-eclipse-temurin-21", "network": "none",
    "memory": "512m", "cpus": "1.0", "pidsLimit": 256, "user": "1000:1000", "mode": "oneshot"
  }
}
```

| Key | Default | Effect |
|---|---|---|
| `sandbox.backend` | `local` | `local` (no isolation) or `docker` |
| `sandbox.localFallback` | `true` | run locally, without isolation, when docker is unavailable; `false` returns an error instead |
| `sandbox.docker.binary` | `docker` | CLI to call |
| `sandbox.docker.image` | `python:3.12-slim` | image the command runs in |
| `sandbox.docker.network` | `none` | docker network; `none` = no network access |
| `sandbox.docker.memory` | `512m` | memory limit |
| `sandbox.docker.cpus` | `1.0` | CPU limit |
| `sandbox.docker.pidsLimit` | `256` | process limit |
| `sandbox.docker.user` | `1000:1000` | user in the container |
| `sandbox.docker.mode` | `oneshot` | `oneshot` or `daemon` (see below) |
| `sandbox.docker.remote` | `false` | daemon mode: the docker daemon is on another machine; upload and back up the workspace |
| `sandbox.docker.remoteHost` | from `DOCKER_HOST` | address where the remote host's published ports are reached |

Command line, winning over the settings file. The CLI and the web application take the same flags
(`--flag value` or `--flag=value`):

| Flag | Meaning |
|---|---|
| `--sandbox local\|docker` | backend |
| `--docker-image IMAGE` | image |
| `--docker-mode oneshot\|daemon` | one container per command, or one long-running container |
| `--docker-remote`, `--docker-remote-host ADDRESS` | daemon mode with a docker daemon on another machine ([remote docker](#remote-docker)) |
| `--sandbox-clean` | CLI only: remove the container of the startup directory, print what was removed, exit |
| `--sandbox-clean-all` | CLI only: remove every CLI sandbox container, print what was removed, exit |

The directory layout is unchanged: with the CLI the project directory is mounted at `/workspace`, with the web
application the chat directory is.

### The image

The repository builds no image and has no Dockerfile. Docker pulls the configured image the first time a command runs
(the pull needs network on the host, whatever `network` says). The default `python:3.12-slim` has Python and a minimal
Debian, with no bash, git, compiler or Maven. Choose an image with the tools your agent needs, for example
`maven:3-eclipse-temurin-21` for Java builds. The image must provide `sh`.

Always applied: read-only root filesystem, `/tmp` as a 64 MB tmpfs, `HOME=/tmp`, all capabilities dropped,
`no-new-privileges`. Only the chat's work directory is mounted, at `/workspace`, which is also the current directory.
The command runs with `sh -c` (POSIX sh, not bash), on Linux.

- **Plan mode.** The agent works in its scratch directory (mounted at `/workspace`), and the project directory is mounted
  read-only at `/project`. This is the only executor that can enforce a read-only working directory. The local
  executors only start `bash` in the scratch directory; they cannot stop a command writing elsewhere.
- **Availability.** Docker is checked at first use with `docker version`; success is cached, failure is retried. A
  remote daemon (`DOCKER_HOST` not local) is refused, because a bind mount would use the wrong files.
- **Fallback.** When Docker is unavailable and `sandbox.localFallback` is `true` (the default), commands run
  without isolation in the wrapped local executor. Set it to `false` to fail instead; the error tells the model
  that Docker is missing.
- **Timeouts.** The container is killed with `docker kill` on timeout or cancellation.
- **Limits.** In `oneshot` mode the file tools still run in the JVM against the host work directory, not in the
  container. Use `daemon` mode to run them inside as well.

When embedding the library, pass it to the builder. The optional third argument is the executor that supplies the
directory layout (default: `LocalDirSandboxExecutor`):

```java
AgentHarness.builder()
    .sandbox(new DockerSandboxExecutor(config, new ProcessDockerCommandRunner(), new ChatDirSandboxExecutor(config, base)))
    …
```

`settings.applyTo(config)` copies the `sandbox.docker.*` keys into `HarnessConfig.Docker`. For daemon mode use
`new RemoteExecSandboxExecutor(config, new ProcessDockerCommandRunner(), layout, Kind.CLI | Kind.WEB, projectDir)`.

## Daemon mode: a long-running container

`sandbox.docker.mode: daemon` (`--docker-mode daemon`). Instead of one `docker run --rm` per command, a container stays
up and runs a small HTTP daemon, `sandbox-agent.py` (Python standard library only). Every tool goes through it:
`bash`, `read_file`, `write_file`, `edit_file`, `glob`, `grep`. Tool names, schemas and results do not change; only
where they run does. Files, installed packages and background processes survive between calls and a command no longer
pays container startup. Each command is still a fresh `sh -c`: `cd` and exported variables do not carry over.

```sh
java -jar jllm-cli.jar --sandbox docker --docker-mode daemon --docker-image maven:3-eclipse-temurin-21
java -jar jllm-web.jar --sandbox docker --docker-mode daemon
```

The image must provide `python` (version 3) and `sh`; the default `python:3.12-slim` has both. For another image
without Python, install it or build an image on top.

### Layout

| | CLI | Web |
|---|---|---|
| Container | one per startup directory, named `jllm-cli-<hash of the directory>` | one per chat, named `jllm-web-<chatId>` |
| `/workspace` | bind mount of the project directory | anonymous volume inside the container, not on the host |
| Scratchpad | `/workspace/.kendhar/scratchpad/<agentId>` | `/workspace/.kendhar/scratchpad/<agentId>` |
| History, sessions, settings | `<project>/.kendhar/history` on the host (visible through the mount) | `<X>/<chatId>/.kendhar/history` on the host, outside the container |
| Deleting | the container is kept; see [cleaning](#cleaning) | the container is removed with `docker rm -f -v`: files and scratchpad go with the chat |

- The docker daemon must be local, unless you allow [remote docker](#remote-docker): a remote `DOCKER_HOST` is refused
  otherwise, since the published port and the bind mount would not reach it.
- **Docker missing.** The agent does not silently run on the host and does not just fail: it asks you
  ("Docker is unavailable: run without isolation?", an approval dialog in the CLI and the web UI). If you agree, that
  chat uses the host directories and plain processes from then on (the project directory, as with `--sandbox local`) and
  each tool result says so; if you decline, the tool returns the error. The question is not asked when
  `sandbox.localFallback` is `false` or the run is not interactive: the tool then fails with the error. This differs
  from `oneshot` mode, which falls back silently when `sandbox.localFallback` is `true`.
- Flags on every container: `--memory`, `--cpus`, `--pids-limit`, `--read-only`, `/tmp` as a 64 MB tmpfs,
  `--cap-drop ALL`, `no-new-privileges`, non-root `--user`. Writable: `/workspace` and `/tmp` only. The settings
  `memory`, `cpus`, `pidsLimit`, `user` apply as in `oneshot` mode.
- **Network**: docker's default (the published port needs a network). `sandbox.docker.network` is ignored in daemon
  mode; there is no `--network` flag.
- Labels: `jllm=1`, `jllm.kind=cli|web`, `jllm.owner=<chatId or hash of the startup directory>`,
  `jllm.config=<hash of image, limits, mode and daemon script>`.
- Plan mode stays a convention for `bash` (it runs in the scratch directory); the container cannot stop a command writing
  to `/workspace`. The file tools do refuse writes outside the scratch directory.
- Sub-agents share the chat's container; each has its own scratch directory.
- The daemon script is written to `~/.jllm/sandbox-agent/<version>/` and mounted read-only at `/agent`.

### Remote docker

A docker daemon on another machine cannot bind-mount your project or be reached on `127.0.0.1`. Allow it explicitly:

```json
"docker": { "mode": "daemon", "remote": true, "remoteHost": "192.168.1.45" }
```

or `--docker-remote --docker-remote-host 192.168.1.45` (CLI and web). `remoteHost` is the address at which this machine
reaches the ports the remote host publishes; blank, it is taken from `DOCKER_HOST` (`tcp://host:2375`), else `127.0.0.1`.
The docker CLI itself still talks to the remote daemon through `DOCKER_HOST` or a docker context.

What changes:

- `/workspace` is a volume in the container in both front ends (nothing is mounted), and the daemon script is sent in the
  environment instead of being mounted.
- **Setup.** When a container is created, the workspace is uploaded: the CLI sends the project directory, the web sends
  the chat's last backup (`<X>/<chatId>/.kendhar/workspace-backup.zip`) if there is one. Reusing a running container
  uploads nothing.
- **Backup.** The container's workspace is downloaded into the project directory (CLI; files are overwritten, files
  deleted in the container are not deleted locally) or into the chat's backup file (web). It happens when the session ends
  (CLI exit, web shutdown), on `/sandbox backup`, and before `/sandbox clean`. Between backups the only copy is in the
  container: if the remote host loses it, those changes are lost. `--sandbox-clean` does not back up first.
- **The scratchpad is lost**: `.kendhar/scratchpad` is neither uploaded nor backed up. The history is not lost: it is
  always on this machine (never in the container), so it is simply not part of the archive. Checkpoints
  (`.kendhar/checkpoints`) travel with the workspace.
- Symbolic links are not copied. Executable bits are kept (by a `.jllm-exec` list in the archive).
- Large projects (`node_modules`, build output, `.git`) are uploaded whole: start the agent from a smaller directory or
  clean first.
- The port is published on all interfaces of the remote host (`0.0.0.0`) and the traffic is plain HTTP: the token is the
  only protection, and it and the files can be read by anyone on the path. Use it on a trusted network or tunnel it.

The daemon's `GET /backup` (zip of the workspace) and `POST /setup` (zip to extract, overwriting; names that leave the
workspace are refused) do the transfer.

### Security

The daemon listens on the container's port 8080, published on `127.0.0.1` only (`-p 127.0.0.1::8080`, random host
port). Every request, `/health` included, must carry a per-container token:

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

- The container starts on the first tool call and the harness waits for `/health`.
- **CLI, keep and reuse.** The container is left running on exit. Next run: running, reuse it; stopped, `docker start`;
  missing, create. If its `jllm.config` label differs from the current settings (image, limits, mode, daemon script),
  it is recreated and a warning is printed: background processes and tools installed in it are lost, project files are
  not (they are in the bind mount).
- **Web.** The container is removed on chat delete and on shutdown. At startup, `jllm.kind=web` containers whose chat no
  longer exists are removed. CLI containers are never touched by that sweep. A new web volume is handed to the container
  user with a one-off `chown` before the daemon starts.
- A command that is cancelled or times out has its process group killed (`DELETE /exec/{id}`); the container stays up.

### Cleaning

CLI only. Project files and `.kendhar` are never removed.

| | |
|---|---|
| `--sandbox-clean` | remove the container of the startup directory, print what was removed, exit |
| `--sandbox-clean-all` | remove every `jllm.kind=cli` container |
| `/sandbox clean` | remove the container of the current session's directory; the next command makes a new one (remote docker: backs up first) |
| `/sandbox backup` | remote docker only: download the container's workspace into the project directory now |

### Differences from `oneshot`

- `grep` uses the daemon's regular expressions (Python `re`), not Java's; they agree on ordinary patterns.
- Checkpoints (`/undo`) work, but are kept inside the container under `/workspace/.kendhar/checkpoints`, so they live
  and die with it. In the web, deleting the chat removes them.
- Paths shown to the model are container paths (`/workspace/...`); `/where` shows them too.
- Full command output beyond 50 KB goes to `bash-N.log` in the agent's scratch directory inside the container.

### The daemon

All endpoints require the token. Paths are jailed to the root (symlinks resolved); `root=workspace` (default) or
`root=scratch` selects `/workspace` or the scratchpad.

| Endpoint | Does |
|---|---|
| `GET /health` | readiness |
| `POST /exec` `{command, cwd, timeoutSeconds, logDir}` | runs `sh -c` in its own process group, returns `{id}` |
| `GET /exec/{id}?wait=N` | `{running:true}`, or once finished `{stdout (last 50 KB), exitCode, timedOut, omittedLines, logPath}` |
| `DELETE /exec/{id}` | kills the process group |
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
| `GET /backup` | zip of the whole workspace, without `.kendhar/scratchpad` (and `.kendhar/history`, which is never in the container) |
| `POST /setup` | extracts the zip in the body into the workspace, overwriting |

A real-container integration test, `DockerWslIntegrationTest`, runs only on request, e.g. against the docker of a WSL
distribution: `mvn test -DargLine="-Djllm.it.wsl=Ubuntu"`. It uses remote mode (Windows paths cannot be bind-mounted into
WSL's docker) and removes the containers it creates.

The Python tests (`python -m unittest jllm-harness/src/test/python/test_sandbox_agent.py`) cover the token, the
environment, execution, the jail and the endpoints; they need `python` and `sh`.

### Web application: file explorer

The existing `/api/chat/{chatId}/fs` endpoints work in both modes; in daemon mode they proxy to the daemon, because the
files are not on the host.

| Endpoint | Notes |
|---|---|
| `GET /fs` | list a directory; `root=workspace\|scratch` (scratch: daemon mode only) |
| `GET /fs/content`, `GET /fs/download` | read; download of a directory is a zip |
| `GET /fs/glob?pattern=` | new, daemon mode only |
| `GET /fs/tree?depth=` | new, daemon mode only |
| `PUT /fs/content`, `POST /fs`, `POST /fs/upload`, `POST /fs/rename`, `DELETE /fs` | writes, executed in the container |

`.kendhar` stays hidden in the workspace root.

## Which one to use

- Your own project, trusted model and input: `CwdSandboxExecutor` (CLI default).
- Many chats that should not mix files: `ChatDirSandboxExecutor` (web default).
- Untrusted input, shared hosts or unattended `auto` mode: the docker backend, or your own `SandboxExecutor`.
- Many commands, installed tools that must persist, builds: docker in `daemon` mode.

Permission rules (`deny`, `ask`, `allow` on `bash(...)`) are a guard rail, not a boundary: quoting, variables or
`python -c` bypass a regex. Only a container limits what a command can do.

See [EXTENDING §9](EXTENDING.md#9-sandbox) for the interface and how to write a custom executor.
