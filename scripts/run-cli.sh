#!/usr/bin/env bash
# Runs the built CLI (jllm-cli/target) with the fetched GraalJS set on the class path. Build first: mvn -DskipTests package
# Usage: scripts/run-cli.sh [cli arguments]    Edition: JLLM_GRAAL_EDITION (default community)
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
dir=$("$root/scripts/graal-fetch.sh" --dir-only); ver=$(basename "$(dirname "$dir")")
version=$(sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' "$root/pom.xml" | head -1)
jar="$root/jllm-cli/target/jllm-cli-$version.jar"
[ -f "$jar" ] || { echo "missing $jar: run mvn -DskipTests package" >&2; exit 1; }
sep=:; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; jar=$(cygpath -w "$jar"); dir=$(cygpath -w "$dir");; esac
echo "GraalJS ${JLLM_GRAAL_EDITION:-community} $ver from $dir" >&2
plugins=(); [ -d "$root/target/plugins" ] && plugins=(--plugin-dir "$root/target/plugins")
exec java "-Djllm.graal.dir=$dir" -cp "$jar${sep}$dir/*" org.kendar.llm.cli.Main "${plugins[@]}" "$@"
