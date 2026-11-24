#!/usr/bin/env bash
# Terminal chat. Extra arguments are passed on: ./run-cli.sh --provider anthropic --model <model> --mode edit
cd "$(dirname "$0")" || exit 1
v=$(java -version 2>&1 | sed -n '1s/[^"]*"\([0-9]*\).*/\1/p')
[ "${v:-0}" -ge 21 ] || { echo "Java 21 or newer is required (found: ${v:-none})" >&2; exit 1; }
# GraalJS (run_js) comes from lib-ext; an empty lib-ext starts normally and run_js reports why it is off
exec java -cp "jllm-cli.jar:lib-ext/*" org.kendar.llm.cli.Main --plugin-dir ./plugins --settings ./settings/settings.json "$@"
