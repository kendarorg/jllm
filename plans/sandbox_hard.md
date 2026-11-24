# Sandbox hardening and rework

Note: in this plan only, `jllm-js` is considered always present (part of the layer model, not a separate plugin).

## Goal

Make the sandboxing model simple to reason about and safer by default. Today layout (where a chat works) and isolation
(how commands run) are fused in one class hierarchy, executor selection is duplicated in CLI and web, and the local
mode has no isolation at all. Reference: `docs/guides/sandbox.md`.

## Current model (analysis)

Layer 1, directory layout, no isolation (`core/jllm-harness/.../harness/sandbox/`):

| Class | Used by | Work dir | Scratch / history |
|---|---|---|---|
| `LocalDirSandboxExecutor` | library default, base class | `base/<chatId>` | `base/<chatId>/<agentId>` |
| `CwdSandboxExecutor` | CLI | project dir, shared by all chats | `<cwd>/.jllm/scratchpad`, temp-dir fallback if read-only |
| `ChatDirSandboxExecutor` | web | `X/<chatId>`, deleted with the chat | `X/<chatId>/.jllm/...` |

Layer 2, command isolation:

- `DockerSandboxExecutor`: one throwaway container per command (no network by default, memory/cpu/pids limits,
  read-only rootfs, cap-drop ALL, no-new-privileges, non-root; work dir bind-mounted at `/workspace`).
  **Flagged for removal**, replaced by the hardened profile of `RemoteExecSandboxExecutor`.
- `RemoteExecSandboxExecutor`: one long-running container with an HTTP daemon (`sandbox-agent.py`); bash and file tools
  go through it. `Kind.CLI` mounts the project; `Kind.WEB` has one container per chat on an anonymous volume.
  `RemoteFileBackend` / `ScratchJailBackend` give file-tool access.
- `jllm-js` `run_js` (GraalJS, constrained or isolated profile, tools-only view of the world): always present, in every
  configuration, alongside whichever runner is chosen. It is the no-host-access option of this layer: a role with
  `run_js` and no `bash` never touches the host shell, whatever the runner is. Documented in `sandbox.md` since this plan
  was made.

Selection: `sandbox.backend` (`local`|`docker`) + `docker.mode=daemon`, hand-coded in `jllm-cli/.../Main.java` and
`web/jllm-web-commons/.../HarnessFactory.executor`.

## Problems

1. Executor selection duplicated in CLI and web; rules can drift.
2. Layout and isolation fused: docker executors wrap and delegate to a `LocalDirSandboxExecutor`; the Cwd and ChatDir
   layouts are subclasses of the local runner.
3. `SandboxExecutor` is wide (about 20 default methods: fallback, backup, clean, files, shell, log limit, env
   whitelist).
4. `RemoteExecSandboxExecutor` is 771 lines: container lifecycle, backup, fallback state and daemon client mixed.
5. Local mode is unsafe by default: no network/resource/filesystem restriction; plan mode cannot stop a shell command
   from writing to the work dir (`readOnlyDir` ignored).
6. Naming and docs: `LocalDirSandboxExecutor` means "no sandbox"; interface javadoc says only the local backend exists;
   dead `Process p = null;` in `LocalDirSandboxExecutor.workDir`.

## Target design

### Layers

- **Layout** (work, scratch, history, delete, chat storage): Cwd and ChatDir become layouts, not subclasses of the
  local runner.
- **Runner**: local, or `RemoteExecSandboxExecutor` (daemon). Per-command docker is gone.
- **`run_js`**: always available next to the runner. It may optionally use the remote executor as its storage (can, not
  must): the JS file jail / file access sits on the container workspace, or stays on the host directory. Same key and
  work dir as the container scope below when it opts in.
- **Selection**: one `SandboxFactory.create(settings, config, layout, kind)` used by CLI and web;
  `docker.mode`/`sandbox.backend=docker` collapses onto the daemon runner.

### `RemoteExecSandboxExecutor`: one class, two profiles

`RemoteExecSandboxExecutor` keeps its name and absorbs the role of `DockerSandboxExecutor`. It is the long-running
container with the HTTP daemon (bash and file tools through it, usable as storage by `run_js`), started with a `profile`:

- **standard**: as today.
- **hardened**: what `DockerSandboxExecutor` had: no network by default, memory/cpu/pids limits, read-only rootfs,
  cap-drop ALL, no-new-privileges, non-root. Each setting is configurable per user and per chat (admin sets the defaults
  and the allowed overrides; a looser setting such as network on is an explicit admin grant). Intended for temporary
  users. Writable paths: `/workspace` (per chat) or `/workspace/<chatId>` (per user), plus tmpfs for `/tmp`; nothing
  else.

### Container scope: per chat or per user

A setting (`sandbox.remote.scope` = `chat` | `user`), resolved by `SandboxFactory`, not a new executor class. The
container is identified by a key; the usual per-chat structure is kept inside it.

| | `chat` | `user` |
|---|---|---|
| Container key | `chatId` | `userId` |
| Volume | one per chat, removed with the chat | one per user, persistent |
| Work dir in container | `/workspace` | `/workspace/<chatId>` (same layout, one level down) |
| Chat delete | remove container and volume | remove `/workspace/<chatId>` only |
| Shared between chats | nothing | installed packages, caches, tools |
| Isolation | chat from chat | user from user; chats of one user share the container |
| Cost | many containers | few containers, idle reaping needed |

A `ContainerRegistry` keyed by scope key (the lifecycle component split out of problem 4) does get-or-start, resource
limits per container, and a periodic sweep that applies the role clean-up policy to idle containers (no separate global
cap; reaping is by role policy only); `RemoteExecSandboxExecutor` asks it for the container and
only deals with the chat's work dir. In `user` scope, chats of one user run commands in parallel in the same container,
each in its own work dir.

### Web

- The user is known from auth. Each user has a set of allowed storages (scopes) granted by the admin(s). If only one is
  allowed it is chosen automatically; if several, the user picks. `user` scope uses a named volume (`jllm-<userId>`),
  not an anonymous one. Chat history/storage layout stays per chat, so `Layout` is unchanged.
- **Docker host**: for now one main `docker_host` in the admin config; each user can have an override (own host). The
  `ContainerRegistry` resolves the host per user, then the container key.
- **Clean-up policy per role**: configurable per role, e.g. after X time of inactivity stop the container and back it
  up, then remove it (restored on next use). Fields: inactivity timeout, action (stop, backup+remove, remove), backup
  retention, and a maximum workspace size (per-role quota on the volume, checked at backup and by the sweep). The
  effective policy for a user is the one of the role they act under. A user with several roles selects
  the role in a combo box in the UI; via API (no selection) the first role in alphabetical order is used.
- Only an admin can change scope or docker host policy of a group, and grant the tarball download permission.

### CLI

- Single OS user, and `Kind.CLI` bind-mounts the project, which is fixed at container creation. So `user` scope cannot
  mean one container for everything; it is keyed by user plus project path. `chat` scope (default) is one container per
  session. A `user` container survives between runs and is reused if running.
- The scope for the current session is chosen with a command line option (overrides the config, e.g.
  `--sandbox-scope chat|user`). No new slash command: the existing `/status` (context usage, cost, chat count, in
  `SlashCommands.status`) also returns the sandbox status: runner, profile, scope, container key/name, work dir, and
  whether `run_js` uses the remote storage. `/sandbox` keeps only its actions (`clean`, `backup`). The scope cannot be
  changed mid-session.

### Backup and retention

Decided: both. A workspace archive (small, portable) plus a `docker commit` image (keeps installed packages, tied to
the docker host that made it). The archive already exists: `RemoteExecSandboxExecutor.backup(chatId)` downloads the
daemon's `GET /backup` as a zip (`WorkspaceArchive`) into `<layout>/<chatId>/.jllm/workspace-backup.zip` (web) or the
project directory (CLI), and the next start restores it. So "tarball" in this plan means that zip; no new format. The
`docker commit` image is the new part.

- The backup record is stored on the owner of the container (the chat in `chat` scope, the user in `user` scope): image
  id (or tag), tarball location, docker host it was made on, timestamp. This needs a field in the chat/user storage (see
  the SQL store project).
- On next use the record is looked up and the image is recycled to start the container. The tarball restores the
  workspace and is the fallback if the image is gone or the user's `docker_host` override changed.
- When the retention of the group expires, both are deleted (tarball removed, image removed from the docker host) and
  the record is cleared.
- **User dropped**: all of their data is dropped (containers, volumes, backups, tarballs, image records). Applies to
  temporary users on expiry and to any deleted user; no backup outlives the user.

### Scope change and tarball download

- When an admin changes the scope of a group (per chat to per user, or the reverse), the UI warns before applying that
  the existing containers will be dropped and only their content (workspace) is kept; installed packages and other
  container state are lost. The workspace is preserved as a tarball and moved into the new layout (`/workspace` of a chat
  becomes `/workspace/<chatId>`, or the reverse).
- Users can download their tarballs (workspace backups) if authorized: a permission on the role, granted only by an
  admin and checked on the download endpoint, so a user only gets their own.

`user` scope is allowed in multi-tenant web; the admin decides per group (scope is admin-only). Docs must say that it
shares one container across a user's chats, so untrusted chats should use `chat` scope.

### Local runner

Decided: the local runner stays unisolated and is meant for testing and trusted single-user use only.

- Plan mode on the local runner is advisory, as in Claude Code: file tools are blocked from writing, but a shell command
  can still write. No OS-level enforcement, no refusal of shell in plan mode. Real read-only plan mode needs the remote
  runner (read-only mount).
- The web does not refuse shell on the local runner. An admin who runs the web on the local runner accepts that bash
  runs on the host as the server user. Docs and the admin UI say it plainly: the local runner is for testing only; use
  `RemoteExecSandboxExecutor` for anything real.

## Proposed work

1. **`SandboxFactory`**: one `create(settings, config, layout, kind)` used by CLI and web; selection rules in one place.
2. **Split layout from runner**: `Layout` and `Runner` (local, daemon). Cwd and ChatDir become layouts. Remove
   `DockerSandboxExecutor`; `docker.mode`/`sandbox.backend=docker` collapses onto the daemon runner. Expose the remote
   executor's storage so `run_js` can optionally use it.
3. **Narrow the interface**: `Layout`, `Exec`, `Files`, `Lifecycle` (fallback, backup, clean, close).
4. **Break up `RemoteExecSandboxExecutor`** (stays the single remote class, with a hardened or standard profile):
   container lifecycle (`ContainerRegistry`), backup/fallback, daemon client.
5. **Local mode**: no new enforcement. Document that plan mode is advisory on the local runner and that the local
   runner is for testing only (docs, and a clear note in the web admin UI).
6. **Cleanup**: dead code, rename the local executor, refresh javadoc and `docs/guides/sandbox.md`
   (and `extending.md` section 9).
7. **JS in the layer model**: `run_js` is an always-available member of the isolation layer. The factory/docs describe
   it with every runner (role with `run_js` and no `bash` as the no-host profile); check the `fileJail` roots against
   the new `Layout`; allow, not require, the remote executor as its storage.
8. **Remote scope (chat or user)**: `sandbox.remote.scope` and the `ContainerRegistry`, resolved in `SandboxFactory`.
   Web: allowed storages per user, per-user docker host override, per-role clean-up policy, backup records, tarball
   download, admin-only scope change with warning. CLI: `--sandbox-scope` option; sandbox status added to the existing `/status`.
9. **Hardened profile**: per user/per chat configurable limits on `RemoteExecSandboxExecutor`, for temporary users.

## Constraints

- No backward-compat overloads or shims: change signatures and fix callers.
- Must build and pass on Windows, Linux and macOS.
- Overlaps with TODO section 1 (docker sandbox for web chats): do not duplicate; that list stays the pilot-blocker view.

## Status

Implemented (core, CLI, web wiring), see `docs/guides/sandbox.md`:

- `Layout` (`DirLayout`, `CwdLayout`, `ChatDirLayout`) split from the runners; `LocalSandboxExecutor` and
  `RemoteExecSandboxExecutor`; `DockerSandboxExecutor` and `docker.mode`/`oneshot`/`--docker-mode` removed,
  `sandbox.backend=docker` means the daemon runner.
- Interface narrowed to `Layout`, `Exec`, `SandboxFiles`, `Lifecycle`.
- `SandboxFactory.create(settings, config, layout, kind)` used by CLI and web.
- `RemoteExecSandboxExecutor` broken up: `ContainerRegistry` (lifecycle, idle sweep by clean-up policy), `WorkspaceBackups`,
  `BackupRecordStore` (zip plus `docker commit` image, record per owner, retention expiry, user-dropped clean-up),
  `DaemonClient`.
- Profiles `standard` and `hardened` (`sandbox.docker.profile`; hardened without network through an exec tunnel, because
  docker cannot publish a port on `--network none`), per user/per chat overrides through `SandboxPolicy`.
- Scope `chat` | `user` (`sandbox.docker.scope`, CLI `--sandbox-scope`, `--sandbox-profile`), daemon header `X-Jllm-Chat`,
  named volume `jllm-<userId>`, `/workspace/<chatId>` work dir; CLI chat scope = container per session.
- `/status` shows the sandbox; local runner documented as testing-only with advisory plan mode; `run_js` described as
  always present beside the runner.
- Web wiring: factory call, `userOf`, `SandboxPolicy` seam, orphan sweep.
- Web, sandbox per role (`RoutedSandboxExecutor`): each role has a sandbox kind, `none` (default: no file tools and no
  `bash`, every attempt is a tool error and the server keeps running; `DisabledSandboxExecutor`), `local`,
  `docker-isolated` (hardened profile, network optional) or `docker-open` (standard profile), plus the container scope
  (`chat` | `user`). Stored in `roles.settings` (free-text JSON, SQL) or the role file; `users.settings` exists for
  later per-user settings. A chat without a user, an unreadable role or a failing lookup resolves to `none`. On the web
  `jllm.sandbox` is ignored (a warning is logged); `jllm.docker-*` give the defaults of the containers.
- Admin UI: Roles, Sandbox section (kind, scope, network, plain warning for `local`).

Deviation: the scope key is `sandbox.docker.scope`, not `sandbox.remote.scope` (`sandbox.docker.remote` already means a
docker daemon on another machine).

Not yet:

- Per-user allowed storages, per-user docker host override and per-role clean-up policy persisted in the SQL store
  (a role stores only kind, scope and network today).
- Admin UI: clean-up policy editing and the scope-change warning (kind, scope, network and the local-runner warning are
  done).
- A list of allowed container images per role, each with its mode (isolated or exposed): see TODO section 1.
- Tarball download endpoint and its role permission.
- Scope-change migration of existing workspaces.
