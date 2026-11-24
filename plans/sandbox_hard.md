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
  `--sandbox-scope chat|user`). The existing `/sandbox` slash command (today `clean` and `backup`, in `SlashCommands`) gets a read-only `status`
  form that shows the approach in effect: runner, scope,
  container key/name, work dir, and whether `run_js` uses the remote storage. The scope cannot be changed mid-session;
  the slash command is read-only.

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
   download, admin-only scope change with warning. CLI: `--sandbox-scope` option and read-only `/sandbox` slash command.
9. **Hardened profile**: per user/per chat configurable limits on `RemoteExecSandboxExecutor`, for temporary users.

## Constraints

- No backward-compat overloads or shims: change signatures and fix callers.
- Must build and pass on Windows, Linux and macOS.
- Overlaps with TODO section 1 (docker sandbox for web chats): do not duplicate; that list stays the pilot-blocker view.

## Status

Analysis and proposal agreed (including `DockerSandboxExecutor` removal, `run_js` in the layer model, remote scope per
chat or user, hardened profile, backup and retention). Nothing implemented. To be planned in detail (scope, order,
tests) in a later session.
