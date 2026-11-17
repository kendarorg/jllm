jllm - ready to run
===================

Requirements: Java 21 or newer (java -version).

Start
-----
  Terminal : ./run-cli.sh   (Windows: run-cli.cmd)
  Browser  : ./run-web.sh   (Windows: run-web.cmd)  ->  http://localhost:8080

Sign in to the web app with admin/admin, poweru/poweru or guest/guest. The password of each user is its login until
the user picks another one: change it before exposing the app beyond localhost.
  admin  - every tool, no approvals, manages global flows and users
  poweru - files, shell, web and scripts
  guest  - scripts and web only

Choose the model (read this first)
----------------------------------
Nothing is preconfigured: you must pick a provider and a model, and a hosted provider charges you per token.
  * Key: set ANTHROPIC_API_KEY, OPENAI_API_KEY, GROK_API_KEY or QWEN_API_KEY (or --api-key). A local Ollama or
    llama.cpp needs no key and costs nothing.
  * CLI: ./run-cli.sh --provider anthropic --model <model>
         ./run-cli.sh --provider ollama --model qwen3:8b --small-model
  * Web: ./run-web.sh --jllm.provider=anthropic --jllm.model=<model>
  * Tokens and cost: --context-tokens (the model's window, default 100000), --max-tokens (output limit of one answer),
    --max-turns (model calls per request), --pricing.input-per-million / --pricing.output-per-million (USD, for the
    cost estimate). Long chats and agent loops use many tokens: start with a small, cheap model and watch the usage.
  * Local small models: add --small-model, and lower --context-tokens to the real window of the model.
  * Global settings, for every project and every start: ~/.jllm/settings.json (provider, model, permissions, hooks,
    MCP servers). A project can add its own .jllm/settings.json. ./settings/settings.json (this folder) is merged on
    top of both and only enables the plugins.
  Details: https://github.com/kendarorg/jllm/blob/main/docs/reference/usage.md

Contents
--------
  jllm-cli.jar, jllm-web.jar   the two front ends (the GraalJS libraries for run_js are inside)
  plugins/                     js (run_js), pdf and docs (document readers), jllm-workflows, jllm-workflow-web
  settings/settings.json       enables the plugins above; remove an entry to disable one
  webauth/                     web users and roles (users/<id>.json, permissions/<role>.json); edit freely, no restart
  examples/flows/writing-helper.json  a first workflow with choices: it asks the subject (text), the kind of text and
                               the style (single choice), writes it, then lets you pick: keep, shorter or Italian.
                               Paste it in the JSON view of the Flows screen of the web app, or see
                               docs/guides/workflow.md on GitHub. Start it from chat: /flow-start writing-helper

Security
--------
The plugins directory runs with full application rights: do not let untrusted users write there. The CLI has no
isolation by default (shell commands run as you); read the security model before using it on anything important:
https://github.com/kendarorg/jllm/blob/main/docs/enterprise/security-model.md
