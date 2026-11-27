# webSafe

Web application for real use. Start it with `Web Safe Apple|Cubi|Haiku` (IntelliJ) or `run-web` with the argument
`webSafe` (release zip).

| Setting | Value |
|---|---|
| `server.address` | `127.0.0.1` (loopback only) |
| `jllm.web.use-real-password` | `true`: no default passwords. Each user without a password gets a random one at start. Only the administrators' passwords are logged, once (WARN line `Generated password for administrator ...`); copy it, then change it |
| `JLLM_TOKEN_KEY` | environment variable, required (real passwords make the server "exposed"): base64 of 32 random bytes (`openssl rand -base64 32`). Without it the start is refused; there is no clear-text override here |
| `jllm.store` | `file`: users, roles and stored settings under `webauth/`, the configuration under `config/` |
| `jllm.chats-dir` | `tmp/chats` |
| Roles | `admin` and `user`, both with a `docker-isolated` sandbox (no network, container per user) and approval `ask`. Docker must be installed: without it a chat reports the error and runs nothing locally |

`webauth/users/` ships one user, `admin`, without a password. The generated hash is written into that file at first
start. Create other users in Administration; they get a random password that an administrator can reset.

`tmp/` is the working directory (the chats are in `tmp/chats`) and is ignored by git.

Never expose it beyond loopback without a TLS reverse proxy.

## Virtual configuration

The configuration is not read from any folder the chats can reach. `config/` holds it as JSON files
(`RemoteFileConfigProvider`): `settings.json`, `instructions/`, `agents/`, `plugin-data/`. Administrators edit it in the
web application (`/api/admin/config/**`), the model and the users' commands never see it. `jllm.chats-dir`
(`tmp/chats`) is where the chats live: users write there, so `webauth/`, `config/` and the plugin folder must stay
outside it (the start fails otherwise).

Settings that name a file (`promptFile` of an agent, the `path` of a skill, `instructionFiles`) are refused. Import an
existing file configuration once with `jllm-web --import-config <dir>`.

Not available yet, so not configured here: the `shared-local` sandbox. It is added to this configuration when it is
released.
