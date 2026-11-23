#!/usr/bin/env bash
# Prints the Maven flags that run the tests of the js plugin and of jllm-eval on the fetched GraalJS set (fetching it first):
#   mvn $(scripts/graal-flags.sh) -Peval -pl core/jllm-eval -am test
# Edition: JLLM_GRAAL_EDITION (default community). The edition and version line goes to stderr.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
dir=$("$here/graal-fetch.sh" --dir-only); ver=$(basename "$(dirname "$dir")")
echo "GraalJS ${JLLM_GRAAL_EDITION:-community} $ver in $dir" >&2
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) dir=$(cygpath -m "$dir");; esac
echo "-Dgraal.dir=$dir"
