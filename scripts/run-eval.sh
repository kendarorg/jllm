#!/usr/bin/env bash
# Runs the evaluation module (the GraalJS set comes from .downloaded-libs/graal/, see scripts/setup).
# Usage: scripts/run-eval.sh [maven arguments, default: test]
# Needs a model (see core/jllm-eval/README.md); only run it yourself, never from a build.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
gver=$(sed -n 's:.*<graalvm.version>\(.*\)</graalvm.version>.*:\1:p' "$root/pom.xml" | head -1)
[ -d "$root/.downloaded-libs/graal/$gver/repo" ] || { echo "GraalJS $gver is not set up: run scripts/setup.sh" >&2; exit 1; }
cd "$root"
[ $# -gt 0 ] || set -- test
exec mvn -Peval -pl core/jllm-eval -am "$@"
