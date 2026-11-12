# jllm-browser plan

Status: plan only, nothing implemented.

## Goal

Let the harness fetch web content through the user's browser (their network, session, optionally incognito), from both
the web UI and the CLI, with a fallback to a plain host HTTP fetch.

## Decisions

- One tool: `web_fetch`, always registered. `GET`/`HEAD` only.
- Order (config `fetch.order`, default `[bridge, host]`): try the bridge if it advertises `http.fetch`, else host HTTP.
- Fall back on bridge unavailability, timeout, network/CORS failure, extension denial. Do not fall back on a real HTTP
  response (404/401/403); return it.
- Harness stays browser-agnostic: it only knows the `ClientBridge` SPI.
- CLI transport: loopback WebSocket server in the CLI process.
- Web transport: existing SSE stream plus a POST for the reply.
- Extension: Manifest V3, Chrome/Edge and Firefox (one codebase, per-browser manifest).
- Loopback port: default `47813` (configurable); random free port if taken.
- No headless browser backend. Only the user's real browser (normal or incognito/private window).
- Installation: `/browser install` slash command (see Installation).
- Incognito is a setting: `off | on | prefer`.

## Modules

```
jllm-harness   ClientBridge SPI, web_fetch tool, policy check, host HTTP fetch, fallback chain, sanitizer hookup
jllm-browser   LocalBrowserBridge (CLI), launcher, shared message model, the extension (extension/ directory)
jllm-web       WebClientBridge (SSE event + POST endpoint), UI handler registry, extension page-bridge glue
```

`jllm-browser` depends on `jllm-harness`. `jllm-web` depends on both.

## Harness SPI (jllm-harness)

```java
interface ClientBridge {
    Set<String> capabilities(String chatId);
    CompletableFuture<ClientResponse> request(String chatId, ClientRequest req);
}
record ClientRequest(String kind, Map<String,Object> params, Duration timeout, long maxBytes) {}
record ClientResponse(int status, Map<String,String> meta, InputStream body, String error) {}
```

- Builder: `.clientBridge(bridge)`. Default `ClientBridge.NONE` (empty capabilities).
- `web_fetch` steps: policy check, bridge attempt (short timeout), host fallback, `ToolOutputSanitizer`, large bodies
  written to the workspace and returned as path plus summary.
- Result metadata: `source: bridge|host`, `session: incognito|normal` (bridge only), status, final URL, size.
- Policy (harness side, applies to every backend): scheme `http`/`https` only, allowlist/denylist, size cap, block
  private/loopback/link-local targets unless enabled, redirect re-check.
- Pending requests complete exceptionally on cancel, shutdown, and client disconnect.

## Message format (shared)

Request: `{ v, id, kind: "http.fetch", url, method, headers, maxBytes, incognito: off|on|prefer }`

Response: `{ v, id, ok, status, finalUrl, headers, session, error, bodyRef | bodyBase64 }`

- Bodies up to a small threshold travel inline. Larger bodies are chunked (`chunk` frames with `id`, `seq`, `last`).
- Error codes: `no-client`, `timeout`, `denied`, `incognito-unavailable`, `too-large`, `network`, `blocked-scheme`.

## CLI transport (jllm-browser)

- `LocalBrowserBridge` starts a WebSocket server on `127.0.0.1:<browser.cli.port>`, default `47813`. If the port
  is taken (another CLI session), that session binds a random free port instead. The extension tries the stored
  port, then the default; a session on a random port is reached through its pairing URL (the port is in the URL,
  and the extension stores it). Several CLI sessions can run at once, each with its own token.
- Per-session token on every connection; `Origin` must be the extension origin; loopback bind only; server closes with
  the CLI session.
- Pairing: CLI opens `http://127.0.0.1:<port>/pair#token=...` in the default browser (or a configured binary, with
  `--incognito` when requested). The extension's content script on that origin hands port and token to the extension.
  The token is stored; later sessions reconnect to the configured port.
- Launch the browser only when a request needs it and no client is connected. Wait `connectTimeout` (default 5 s),
  then fall back to host HTTP.

## Web transport (jllm-web)

- `WebClientBridge.request()`: creates an id, stores the future in `ConcurrentHashMap<id, Pending(chatId)>`, emits a
  `client-request` SSE event.
- `POST /api/chats/{chatId}/client-response/{id}`: checks the chat matches and the id is unused, completes and removes.
- `capabilities(chatId)` is empty when no UI tab is attached or the extension did not answer `hello`.
- UI: `store.js` handler registry `kind -> async handler`. Handler `http.fetch` posts to the extension page-bridge
  (`window.postMessage`), then POSTs the reply.

## Extension (jllm-browser/extension)

```
manifest.json
background.js        service worker: socket client (CLI mode), request execution, allowlist, incognito handling
content-bridge.js    injected on the jllm web UI origin only: postMessage <-> chrome.runtime relay
content-pair.js      injected on the loopback /pair page only: receives port and token
options.html/js      allowlist, caps, incognito mode, pairing status, request log
```

- Permissions: `scripting`, `tabs`, `storage`; `optional_host_permissions: ["<all_urls>"]`, granted per origin.
  No `externally_connectable`.
- `http.fetch` normal session: `fetch(url, {credentials: 'include'})` from the service worker.
- Incognito: window-based path. Open a tab in an incognito window and fetch from it; `credentials: 'omit'` is
  anonymous but is not an incognito session. `on` fails with `incognito-unavailable` if "Allow in Incognito" is off
  (detect with `isAllowedIncognitoAccess`); `prefer` falls back to normal and reports `session: normal`.
- Safety: own allowlist, size cap, timeout, concurrent request cap, block `file:`/`chrome:`/`data:`, private targets
  off by default, request log, badge when active.
- Web mode pairing: per-install secret shared with the UI at first connect, sent on every message.
- CLI mode: WebSocket client with keepalive pings so the MV3 worker stays connected; reconnect with backoff.

## Firefox specifics

- Same source, two manifests (`manifest.chrome.json`, `manifest.firefox.json`) generated at build time.
- Firefox MV3 uses an event page (`background.scripts`) instead of a service worker; keep the WebSocket alive with
  pings and reconnect on wake.
- Namespace: `browser.*` with a small shim over `chrome.*`.
- Private windows: the user must enable "Run in Private Windows" for the add-on; detect with
  `browser.extension.isAllowedIncognitoAccess()`. Same `off|on|prefer` semantics.
- Needs `browser_specific_settings.gecko.id`. Release builds must be signed (AMO unlisted signing is enough).

## Installation

Browsers do not allow a program to silently install an extension, so the slash command prepares and guides it.
Behavior of the browsers changes between versions; verify each path against current versions before relying on it.

`/browser install [chrome|edge|firefox]`:
1. Extracts the bundled extension (from the `jllm-browser` jar) to `~/.jllm/browser-extension/<browser>/`.
2. Chrome/Edge: opens `chrome://extensions` / `edge://extensions` and prints: enable Developer mode, "Load
   unpacked", select the printed folder. Launching with `--load-extension` is not relied on (recent branded Chrome
   builds ignore it).
3. Firefox: serves the signed `.xpi` from the loopback server and opens its URL, so Firefox shows the normal install
   prompt. Unsigned builds load only as temporary add-ons (`about:debugging`) or in Developer/Nightly.
4. Waits for the extension to connect (pairing) and reports the result; `/browser status` shows connection, browser,
   incognito availability.

`/browser uninstall` removes the extracted folder and the stored token. Machine-wide forced install via enterprise
policy (registry `ExtensionInstallForcelist`, Firefox `policies.json`) is possible but needs admin rights and a
hosted/signed package; out of scope for the command, documented in `docs/BROWSER.md`.

Distribution: the extension is bundled in the `jllm-browser` jar. No Chrome Web Store listing (decided). Firefox
uses a self-signed (AMO unlisted) `.xpi` served locally.

## Config

```
fetch.order                 [bridge, host]
fetch.bridge.timeout        5s
fetch.bridge.connectTimeout 5s     (CLI launch wait)
fetch.bridge.incognito      off | on | prefer
fetch.maxBytes              10MB
fetch.allow / fetch.deny    host patterns
fetch.allowPrivate          false
browser.cli.port            47813 (default; random free port if taken)
browser.cli.binary          (default browser)
```

Extension `incognito` is the authority; the request hint can only tighten it.

## Phases

1. Harness: SPI, `ClientBridge.NONE`, `web_fetch` with policy, host fetch, sanitizer, workspace spill, tests with a
   `FakeClientBridge`.
2. `jllm-browser` Java: message model, `LocalBrowserBridge`, launcher, pairing server, tests with a fake WebSocket
   client.
3. Extension: manifest, background socket client, `http.fetch`, options page, allowlist, caps.
4. Web: `WebClientBridge`, endpoint, UI handler registry, page-bridge, `hello` capability handshake.
5. Incognito/private mode (window-based path, detection, `prefer` fallback).
6. Firefox build (manifest generation, event-page background, signing) and `/browser install|status|uninstall`.
7. Hardening: chunked bodies, redirect re-check, reconnect, request log, docs (`docs/BROWSER.md`).

## Testing

- Unit: policy, fallback rules (which errors fall back), pending-map lifecycle, token and `Origin` checks.
- Integration: fake WebSocket client against `LocalBrowserBridge`; SSE plus POST round trip in `jllm-web`.
- Manual: extension in Chrome, normal and incognito, CLI and web, bridge-down fallback.
