#!/usr/bin/env bash
# Fetches the GraalJS jar set into .graal/<graalvm.version>/<edition>/ (the set the release ships, same Maven pom).
# Usage: scripts/graal-fetch.sh [--edition community|oracle] [--refetch] [--accept-oracle-terms] [--dir-only]
# Edition: the flag, else JLLM_GRAAL_EDITION, else community. A marker file (.complete) is written last: when it is there
# the fetch is skipped unless --refetch. The oracle set declares terms (see release/graaljs/resolve.sh): read them first.
# Prints one line with edition and version; --dir-only prints only the folder (for the other scripts).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
edition=${JLLM_GRAAL_EDITION:-community}; refetch=0; accept=""; dironly=0
while [ $# -gt 0 ]; do
  case $1 in
    --edition) edition=$2; shift;;
    --refetch) refetch=1;;
    --accept-oracle-terms) accept="--accept-terms";;
    --dir-only) dironly=1;;
    *) echo "unknown argument $1" >&2; exit 2;;
  esac
  shift
done
case $edition in community|oracle) ;; *) echo "edition is community or oracle" >&2; exit 2;; esac
[ -z "$accept" ] && [ "${JLLM_ACCEPT_ORACLE_TERMS:-0}" = 1 ] && accept="--accept-terms"
version=$(sed -n 's:.*<graalvm.version>\(.*\)</graalvm.version>.*:\1:p' "$root/pom.xml" | head -1)
dir="$root/.graal/$version/$edition"
if [ "$refetch" = 1 ] || [ ! -f "$dir/.complete" ]; then
  rm -f "$dir/.complete"
  "$root/release/graaljs/resolve.sh" "$edition" "$dir" $accept >&2
  : > "$dir/.complete"
fi
if [ "$dironly" = 1 ]; then echo "$dir"; else echo "GraalJS $edition $version in $dir"; fi
