# hardening4

Source: review of `hardening3` at b248e51 (23 Nov 2026) plus the maintainer's additions.
Rules: no compat shims (change signatures, fix callers); fix code not tests; builds must pass on Windows, Linux, macOS; no git hooks; nobody but the user commits or runs LLM evals.

## Status

| Step | State |
|---|---|
| 1 SSRF guard | done, built, tests pass (AddressPolicyTest, WebAgentToolsTest incl. blocked first URL and redirect, WebHostApprovalTest, SettingsLimitsTest); docs and release template done; DNS pinning not implemented (documented gap) |
| 2 Real passwords: `jllm.web.use-real-password` | done: `RealPasswordInitializer` (jllm-web), `RealPasswordInitializerTest`, README and deployment checklist; jllm-web tests pass |
| 4 Release defaults | done: `server.address=127.0.0.1` in template `application.properties`, `approval` dropped from template admin permissions (users/admin.json had none), template README exposure steps |
| 5 Default-password enforcement | done: `ApiInterceptor` (403 `password_change_required`; allows `/api/me`, `/api/me/password`, `/api/login`, `/api/logout`, `/api/auth/*`), `jllm.web.enforce-password-change-on-loopback` (default false = loopback exempt; forwarding headers disable the exemption), `DefaultPasswordEnforcementTest`, jllm-web README |
| 6 authorize pure function | done: `ToolExecutor.decide(AuthorizeInput)` -> `AuthorizeOutcome`; `ToolExecutorAuthorizeTest` compares it with a verbatim copy of the old logic over the whole input space |
| 7 Taint | done: `TaintTracker.prune` keeps cleared results' taint until next user message; shared `ClearedResult.TEXT`; `TaintTrackerPruneTest` |
| 8 Symlink-safe paths | done: `PermissionRules.normalizePath` resolves real paths (existing ancestor) on every OS; symlink test skips where unsupported |
| 3 Blocking I/O under locks | done: `AgentService` uses `ReentrantLock` + `Condition` (no sleep poll), `run.events` is an `ArrayDeque`, per-subscriber bounded queue (`Subscriber`, own virtual thread, overflow -> `subscriber_dropped` event, `ChatApi.events` closes the stream); tests in `AgentServiceEventsTest` |
| Everything else | not started |

## Phase 1 - before any shared deployment

### 1. SSRF in web_fetch / web_download (review #1)
- Settings file, section `web`: `blockLocalHost` (default false), `blockExtraAddresses` (default `[]`: IP, CIDR, host, `*.suffix`).
- `blockLocalHost=true` refuses loopback, link-local (169.254.169.254), site-local, any-local, IPv6 fc00::/7, IPv4-mapped IPv6.
- Checked on the first URL and every redirect hop, before the host-approval question.
- Known gap: name is resolved by the guard and again by `HttpClient` (DNS rebinding window). Close it by connecting to the checked address (custom `HttpClient` socket/proxy selector or a pinned-IP request with SNI kept). Decide after a spike; document until then.
- To do: build + run `AddressPolicyTest`, `WebAgentToolsTest`, `WebHostApprovalTest`; add a `WebAgentTools` test (blocked first URL, blocked redirect target); extend `HarnessSettingsLoader` test for the two keys; document in `docs/guides/tools.md` and `docs/reference/usage.md`.
- Release template `settings.json`: web template sets `blockLocalHost=true`; CLI/local stays default false.

### 2. Real passwords (maintainer addition)
- `jllm.web.use-real-password=true` (default false; local play unchanged).
- At startup, `ApplicationRunner` in `WebAuthConfiguration`: for each user with `usesDefaultPassword()`, generate a 24-char `SecureRandom` password, `PasswordHasher.hash`, `users.setPassword`.
- Log only the admin password, once, at WARN, portainer style. Other users: log only that a password was generated; admin resets them via AdminApi.
- Not default afterwards, so it is never regenerated; the UI "change default" banner no longer applies.
- Tests: flag off = nothing changes; flag on = defaults replaced, already-set passwords untouched, external users skipped, admin password appears in log and verifies, others never logged.
- Docs: `jllm-web/README.md` property table, `docs/enterprise/deployment.md` checklist.

### 3. Blocking I/O under locks on virtual threads (review #2)
- Replace `synchronized (run)` / `OrderedListener.accept` with `ReentrantLock` where a block can reach I/O.
- Per-subscriber bounded queue drained on its own virtual thread; `SseSink` sends from there. Subscriber that overflows is dropped (SSE client reconnects and replays from `run.events`).
- `enqueue()` retry: replace `Thread.sleep(10)` poll with `Condition`.
- `run.events` -> `ArrayDeque` (review #14).
- Tests: slow subscriber does not delay the run or other subscribers; overflow disconnects only that subscriber; event order preserved per subscriber.

## Phase 2 - defaults match the docs

### 4. Release defaults (review #3)
- `release/template/application.properties`: `server.address=127.0.0.1`.
- `release/template/webauth/users/admin.json` (and permissions `admin.json`): drop `"approval": "off"`.
- README: say how to expose (`server.address=0.0.0.0` + `jllm.web.use-real-password=true` + TLS proxy).

### 5. Server-side default-password enforcement (review #4)
- `ApiInterceptor`: while `usesDefaultPassword()`, allow only password change, logout, `/api/auth/*`; else 403 `password_change_required`.
- Skip for loopback requests (config `jllm.web.enforce-password-change-on-loopback=false` default).
- Tests: blocked, allowed paths, loopback exemption, after change.

## Phase 3 - permission core

### 6. `ToolExecutor.authorize` as a pure function (review #5)
- Record `AuthorizeInput` (free, adminAllowed, mode, matched rule, grants, taint, classifier verdict), `Decision` result, no side effects.
- Exhaustive table test over the input space; first make a characterization test of current behaviour, then refactor, diff must be empty.

### 7. Taint (review #6)
- `TaintTracker.prune` keeps taints from compacted results until the next user message.
- One shared `CLEARED` constant used by `ContextManager`, `ScopeStrategy`, `TaintTracker`.

### 8. Symlink-safe path rules (review #7)
- `PermissionRules.normalizePath` resolves real paths on every OS. Linux test with symlink into a denied dir (skip on Windows without privilege).

## Phase 4 - low priority

| # | Item | Action |
|---|---|---|
| 9 | PBKDF2 timing leak, default-password path | `burn(password)` on that path; test timing class equality loosely (no flaky timing assert; assert burn is called) |
| 10 | Unbounded stored iteration count | reject > 10_000_000 |
| 11 | DONE: Remote sandbox over HTTP | `sandbox.docker.allowInsecure` (default `true` per decision), `https://` `remoteHost` supported in `DaemonClient` (default trust store or `trustStore`/`trustStorePassword`), non-loopback http logs one WARN, refused when `allowInsecure=false`; docs updated |
| 12 | DONE: No CSP | `WebConfig` header `default-src 'self'`; verify frontend still loads (Vite assets, inline styles) |
| 13 | README link to missing `eval-results/history/INDEX.md` | commit file or drop link (user decides; `eval-results/` is untracked) |
| 14 | Model client duplication (~1,760 lines) | extract SSE parsing and retry into `StreamingCall`/base class, one client at a time, each behind its existing tests. **DONE**: SSE parsing + streamed retry were already in `StreamingCall`; added `StreamingCall.send` (non-streamed retry/backoff/401-refresh) and removed the three duplicated loops, `parseSeconds`, `truncate` from the clients |
| 15 | Large units (`FlowEngine`, `ContextManager`, `PathJail`) | split when next touched, no standalone task |
| 16 | Commit messages | convention in `CONTRIBUTING.md` only; no hooks |

## Phase 5 - operations

Each is its own design note before code.

- Per-user rate limits and spend caps (web only): per user token/cost per day, 429 with reset time; stored in the user store.
- Append-only audit log of tool calls, approvals, denials: separate file/table from the chat store, JSON lines, no deletion API.
- Backups with a tested restore: documented procedure for `webauth/`, chats, tokens; restore test in CI against a fixture.
- Health and metrics:
  - web: `GET /api/health` (liveness, readiness: store reachable, model configured).
  - harness: new optional module `jllm-metrics` on Micrometer, embeddable without Spring; counters `approval_unavailable`, `sandbox_fallback`, `classifier_verdict`, `stuck_event`, tool calls by outcome. Alerts documented, not shipped.
- Pinned dependencies and vulnerability scanning: Maven `dependencyManagement` pins, `versions` plugin check, OWASP dependency-check or OSV scanner in CI.

## Phase 6 - benchmarks (`plans/harborswe.md`)

Prerequisites before any run:
- `Main.java:94` honour exit code; `CliArgs`: `--json-result`, `--timeout`.
- `--json-result` includes counts of `approval_unavailable`, classifier verdicts (UNSURE), stuck nudges/stops, taint-forced asks.
- Headless mode: an explicit mode where the container is the only boundary (no `ShellPaths.escapingPath` refusal, no ask->deny from taint false positives); AUTO classifier off for benchmarks.
- Then Harbor adapter, SWE-bench runner.
- Add AgentDojo-style prompt-injection run: task success and attack success rate, taint gating on and off.

## Order

1. Finish and build steps 1-2 (user runs evals; builds without LLM/Docker are fine).
2. 3, 4, 5.
3. 6-8.
4. Phase 4 as time allows, phase 5 designs, phase 6.

## Open questions

- DNS pinning for the SSRF guard: accept the rebinding window until the spike, or block on it?
- Property name: `jllm.web.use-real-password` (assumed from "userRealPassword").
- Where `blockLocalHost` should also be exposable as a `jllm.*` property for the web app (currently settings file only).
