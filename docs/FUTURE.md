# Future work

Features jllm does not have yet, taken from the gaps in [HARNESSES-COMPARE](HARNESSES-COMPARE.md). Nothing here is
scheduled. Each item says what it is and how it could fit the harness.

## Messaging gateway (chat apps)

Talk to the agent from Telegram, Discord, Slack, WhatsApp and similar apps instead of the terminal or the web chat
(Hermes Agent has this).

- The relay is small with the existing API: map a chat-app conversation to a `chatId` (`sessions().create`), call
  `subscribe` and `start`, and send the answer back on `run_finished`. Lines starting with `/` can go through
  `SlashCommands`.
- The work is in the rest: approvals need a chat-side prompt (buttons, or a yes/no reply) that ends in
  `resolveApproval`; only allow-listed users may talk to it, because they run tools on the host; long answers must be
  split to the app's message limit; messages sent during a run go to `steer` or `followUp`.
- Best as a separate module (for example `jllm-gateway`), so the harness keeps no chat-app SDKs.

## Model streaming

Today a model call returns once; only turn-level events reach the front ends (SSE in `jllm-web`). Pi, OpenCode, Qwen
Code and Hermes Agent stream text, thinking and tool-call argument deltas, with abort in mid-stream.

- Add a streaming variant to `ModelClient` for the Anthropic and OpenAI-compatible clients, emitting delta events
  (text, thinking, tool-call arguments) into the existing event sink, then assemble the same final response the loop
  uses now.
- Cancel must be able to stop a call in progress.
- Keep the non-streaming path, as fallback and for the small model used for helper tasks.
- Check the interaction with prompt caching and with thinking blocks (signatures) before changing the history format.

## Provider OAuth logins

Log in to a model provider with a subscription account instead of an API key (Pi, OpenCode, Qwen Code and Hermes Agent
do). jllm has the generic OAuth utilities and MCP OAuth (`OAuthLogin`, `TokenStore`, `/login`) but no provider login.

- Implement `OAuthLogin` per provider, discovered through `ServiceLoader` as now, and let a provider entry in the
  settings use the stored token instead of `apiKey`, refreshing it when it expires.
- Each provider has its own flow (device code or browser redirect) and its own terms of use for third-party clients;
  check both before building one.

## Browser automation

A tool group that drives a real browser: open pages, click, fill forms, read the rendered page, take screenshots
(Hermes Agent has this). Today there are `web_fetch` and `web_search` only, which do not run JavaScript.

- Ship it as an optional tool set (`HarnessToolSet`) backed by a browser driver, for example Playwright, so the core
  keeps its small dependency list.
- It should run inside the sandbox, not on the host browser profile.
- Pages are outside content: the output must go through the existing output sanitizer and taint gating, and
  state-changing actions (click, submit) need approval like other non-read-only tools.
- Screenshots need image input in the model clients, which is also missing.

## Custom prompt templates

User-defined commands such as `/review src/Foo.java`, stored as markdown files with placeholders (`$1`, `$@`), as in
Pi, OpenCode and Qwen Code. Today only Java plugins can add slash commands (`SlashCommands.register`).

- Load `*.md` files from a settings-defined directory (user and project level) and register each as a command with the
  file name as its name and the first line as its description.
- Expand `$1`, `$2`, `$@` from the typed arguments and send the result to the model as the user message.
- Reuse the same lookup rules and name-clash check that skills and plugin commands already use.
