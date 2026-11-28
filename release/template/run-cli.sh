#!/usr/bin/env bash
# Terminal chat. First argument (optional): the configuration, cliSafe (default) or unsafe (development and testing only).
# Other arguments are passed on: ./run-cli.sh [config] --provider anthropic --model <model> --mode edit
cd "$(dirname "$0")" || exit 1
cfg=cliSafe
case ${1:-} in -*|"") ;; *) cfg=$1; shift;; esac
[ -f "configs/$cfg/settings.json" ] || { echo "unknown configuration '$cfg' (see configs/: cliSafe, unsafe)" >&2; exit 1; }
[ "$cfg" = unsafe ] && echo "WARNING: unsafe configuration: no sandbox, the model can read, change or destroy anything you can. Development and testing only." >&2
v=$(java -version 2>&1 | sed -n '1s/[^"]*"\([0-9]*\).*/\1/p')
[ "${v:-0}" -ge 21 ] || { echo "Java 21 or newer is required (found: ${v:-none})" >&2; exit 1; }
extra=()
[ "$cfg" = cliSafe ] && extra=(--mode default --sandbox docker)
# GraalJS (run_js) comes from lib-ext; an empty lib-ext starts normally and run_js reports why it is off
exec java -cp "jllm-cli.jar:lib-ext/*" org.kendar.llm.cli.Main --plugin-dir ./plugins --settings "./configs/$cfg/settings.json" "${extra[@]}" "$@"
