#!/usr/bin/env bash
# Web app on http://localhost:8080. First argument (optional): the configuration, webSafe (default) or unsafe
# (development and testing only). Other arguments are passed on: ./run-web.sh [config] --jllm.provider=anthropic --jllm.model=<model>
cd "$(dirname "$0")" || exit 1
cfg=webSafe
case ${1:-} in -*|"") ;; *) cfg=$1; shift;; esac
[ -f "configs/$cfg/config/settings.json" ] && [ -d "configs/$cfg/webauth" ] || { echo "unknown configuration '$cfg' (see configs/: webSafe, unsafe)" >&2; exit 1; }
v=$(java -version 2>&1 | sed -n '1s/[^"]*"\([0-9]*\).*/\1/p')
[ "${v:-0}" -ge 21 ] || { echo "Java 21 or newer is required (found: ${v:-none})" >&2; exit 1; }
if [ "$cfg" = unsafe ]; then
  echo "WARNING: unsafe configuration: local shell with full host access, default passwords. Development and testing only." >&2
  echo "Open http://localhost:8080  (admin/admin, poweru/poweru, guest/guest)"
else
  echo "Open http://localhost:8080  (the administrator password is in the startup log, shown once)"
fi
# GraalJS (run_js) comes from lib-ext, the SQL store from lib-ext/sql, through the loader path; an empty lib-ext starts normally
# (MySQL only: ./fetch-sql-driver.sh puts its driver into .downloaded-libs, see configs/<name>/application.properties)
exec java -Dloader.path=lib-ext,lib-ext/sql -jar jllm-web.jar --spring.config.additional-location="file:./configs/$cfg/" \
  --jllm.plugin-dir=./plugins --jllm.config-dir="./configs/$cfg/config" --jllm.webauth-dir="./configs/$cfg/webauth" --jllm.chats-dir="./chats/$cfg" "$@"
