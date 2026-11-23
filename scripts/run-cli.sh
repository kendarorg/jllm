#!/usr/bin/env bash
# Runs the built CLI (jllm-cli/target) with the GraalJS set of .downloaded-libs/graal/<graalvm.version>/repo on the class path.
# Needs scripts/setup first, then mvn -DskipTests package. Usage: scripts/run-cli.sh [cli arguments]
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
gver=$(sed -n 's:.*<graalvm.version>\(.*\)</graalvm.version>.*:\1:p' "$root/pom.xml" | head -1)
dir="$root/.downloaded-libs/graal/$gver"
[ -d "$dir/repo" ] || { echo "GraalJS $gver is not set up: run scripts/setup.sh" >&2; exit 1; }
version=$(sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' "$root/pom.xml" | head -1)
jar="$root/jllm-cli/target/jllm-cli-$version.jar"
[ -f "$jar" ] || { echo "missing $jar: run mvn -DskipTests package" >&2; exit 1; }
sep=:; win=0; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) win=1; sep=";";; esac
cp="$jar"
while IFS= read -r j; do cp="$cp$sep$j"; done < <(find "$dir/repo" -name '*.jar' | sort)
if [ "$win" = 1 ]; then cp=$(cygpath -wp "$cp" 2>/dev/null || echo "$cp"); dir=$(cygpath -w "$dir"); fi
echo "GraalJS $gver from $dir" >&2
plugins=(); [ -d "$root/target/plugins" ] && plugins=(--plugin-dir "$root/target/plugins")
exec java "-Djllm.graal.dir=$dir" -cp "$cp" org.kendar.llm.cli.Main "${plugins[@]}" "$@"
