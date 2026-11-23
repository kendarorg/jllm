#!/usr/bin/env bash
# Runs the built web app (jllm-web/target) with the fetched GraalJS set on the loader path. Build first: mvn -DskipTests package
# Usage: scripts/run-web.sh [web arguments]    Edition: JLLM_GRAAL_EDITION (default community)
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
dir=$("$root/scripts/graal-fetch.sh" --dir-only); ver=$(basename "$(dirname "$dir")")
version=$(sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' "$root/pom.xml" | head -1)
jar="$root/jllm-web/target/jllm-web-$version.jar"
[ -f "$jar" ] || { echo "missing $jar: run mvn -DskipTests package" >&2; exit 1; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) jar=$(cygpath -w "$jar"); dir=$(cygpath -w "$dir");; esac
echo "GraalJS ${JLLM_GRAAL_EDITION:-community} $ver from $dir" >&2
plugins=(); [ -d "$root/target/plugins" ] && plugins=("--jllm.plugin-dir=$root/target/plugins")
exec java "-Dloader.path=$dir" "-Djllm.graal.dir=$dir" -jar "$jar" "${plugins[@]}" "$@"
