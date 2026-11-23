#!/usr/bin/env bash
# Runs the built web app (jllm-web/target) with the GraalJS set of .graal/<graalvm.version>/repo on the loader path.
# Needs scripts/setup first, then mvn -DskipTests package. Usage: scripts/run-web.sh [web arguments]
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
gver=$(sed -n 's:.*<graalvm.version>\(.*\)</graalvm.version>.*:\1:p' "$root/pom.xml" | head -1)
dir="$root/.graal/$gver"
[ -d "$dir/repo" ] || { echo "GraalJS $gver is not set up: run scripts/setup.sh" >&2; exit 1; }
version=$(sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' "$root/pom.xml" | head -1)
jar="$root/jllm-web/target/jllm-web-$version.jar"
[ -f "$jar" ] || { echo "missing $jar: run mvn -DskipTests package" >&2; exit 1; }
win=0; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) win=1;; esac
loader=""
while IFS= read -r j; do
  [ "$win" = 1 ] && j=$(cygpath -w "$j")
  loader="${loader:+$loader,}$j"
done < <(find "$dir/repo" -name '*.jar' | sort)
if [ "$win" = 1 ]; then jar=$(cygpath -w "$jar"); dir=$(cygpath -w "$dir"); fi
echo "GraalJS $gver from $dir" >&2
plugins=(); [ -d "$root/target/plugins" ] && plugins=("--jllm.plugin-dir=$root/target/plugins")
exec java "-Dloader.path=$loader" "-Djllm.graal.dir=$dir" -jar "$jar" "${plugins[@]}" "$@"
