# cliSafe

Terminal chat for real use. Start it with `CLI Safe Apple|Cubi|Haiku` (IntelliJ) or `run-cli` with the argument
`cliSafe` (release zip).

| Setting | Value |
|---|---|
| `--mode default` | every write and every shell command asks first (never `auto`) |
| `sandbox.backend` | `docker`: commands run in a container, not on the host |
| `sandbox.localFallback` | `false`: if Docker is missing nothing runs; it never falls back to the host shell |
| `permissions.deny` | writes to `.env` files and `rm -rf` are refused |

No `--trust-project`: never pass it for a folder you have not read.

## Applied when they land

Folder trust (prompt before a project's `.jllm/`, instruction files or MCP servers are loaded) and the Docker
protection of `.git` and `.jllm` are not available yet. They apply to this configuration once released.
