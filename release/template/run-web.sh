#!/usr/bin/env bash
# Web app on http://localhost:8080. Extra arguments are passed on: ./run-web.sh --jllm.provider=anthropic --jllm.model=<model>
cd "$(dirname "$0")" || exit 1
v=$(java -version 2>&1 | sed -n '1s/[^"]*"\([0-9]*\).*/\1/p')
[ "${v:-0}" -ge 21 ] || { echo "Java 21 or newer is required (found: ${v:-none})" >&2; exit 1; }
echo "Open http://localhost:8080  (admin/admin, poweru/poweru, guest/guest)"
# GraalJS (run_js) comes from lib-ext through the loader path; an empty lib-ext starts normally
exec java -Dloader.path=lib-ext -jar jllm-web.jar --jllm.plugin-dir=./plugins --jllm.settings=./settings/settings.json \
  --jllm.webauth-dir=./webauth "$@"
