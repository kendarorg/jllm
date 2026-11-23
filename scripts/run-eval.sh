#!/usr/bin/env bash
# Runs the evaluation module on the fetched GraalJS set (the graal-dir profile swaps the provided set for it).
# Usage: scripts/run-eval.sh [maven arguments, default: test]    Edition: JLLM_GRAAL_EDITION (default community)
# Needs a model (see core/jllm-eval/README.md); only run it yourself, never from a build.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
dir=$("$root/scripts/graal-fetch.sh" --dir-only); ver=$(basename "$(dirname "$dir")")
echo "GraalJS ${JLLM_GRAAL_EDITION:-community} $ver from $dir" >&2
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) dir=$(cygpath -m "$dir");; esac
cd "$root"
[ $# -gt 0 ] || set -- test
exec mvn -Peval -pl core/jllm-eval -am "-Dgraal.dir=$dir" "$@"
