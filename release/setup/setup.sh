#!/usr/bin/env bash
# jllm setup: unzips the distribution that sits next to this script, asks which GraalJS edition to use for run_js and
# downloads it (Maven Central, SHA-256 verified) into the unzipped lib-ext/. Works on Linux and macOS.
# Layout: next to this script jllm-<version>.zip; after setup, jllm-<version>/ holds the jars, run-*.sh/.cmd, plugins/,
# configs/ (unsafe, webSafe, cliSafe) and lib-ext/ (filled with the chosen GraalJS edition; manifests/ lists the pinned jars).
# Usage: ./setup.sh [--zip FILE] [--dir DIR] [--edition community|commercial] [--accept-license] [--repo URL]
#        [--sql mysql|postgres|all|none]
# - --zip: the distribution (default: the only jllm-*.zip next to this script)
# - --dir: where to unzip (default: next to the zip)
# - --edition: skip the question; --accept-license accepts the commercial terms silently
# - --sql: the JDBC drivers of the SQL store to download into lib-ext/ (default all; SQLite is bundled). MySQL is GPL with the
#   FOSS exception: asked y/N unless --accept-license. At run time only the driver of the jdbc url is used
# - --repo: Maven repository the pinned GraalJS jars come from (default Maven Central; SHA-256 checked either way)
# Needs Java 21+ only to run jllm, not for this script. Without a terminal, give --edition (and --accept-license).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
zip=""; dir=""; edition=""; accept=0; sql=all
while [ $# -gt 0 ]; do
  case $1 in
    --zip) zip=$2; shift;;
    --dir) dir=$2; shift;;
    --edition) case $2 in community) edition=community;; commercial|oracle) edition=oracle;; *) echo "edition is community or commercial" >&2; exit 2;; esac; shift;;
    --sql) case ${2:-} in mysql|postgres|all|none) sql=$2;; *) echo "--sql is mysql, postgres, all or none" >&2; exit 2;; esac; shift;;
    --accept-license) accept=1;;
    --repo) export JLLM_MAVEN_REPO=$2; shift;;
    *) echo "unknown argument $1" >&2; exit 2;;
  esac
  shift
done
if [ -z "$zip" ]; then
  set -- "$here"/jllm-*.zip
  [ $# -eq 1 ] && [ -f "$1" ] || { echo "put exactly one jllm-<version>.zip next to setup.sh, or pass --zip FILE" >&2; exit 1; }
  zip=$1
fi
[ -f "$zip" ] || { echo "missing $zip" >&2; exit 1; }
zip=$(cd "$(dirname "$zip")" && pwd)/$(basename "$zip")
[ -n "$dir" ] || dir=$(dirname "$zip")
mkdir -p "$dir"
# the question first, so nothing is unpacked or downloaded when the terms are declined
ask() { # prompt -> answer on the terminal; fails without one
  if [ -t 0 ]; then read -r -p "$1" REPLY
  elif [ -r /dev/tty ] && { : < /dev/tty; } 2>/dev/null; then read -r -p "$1" REPLY < /dev/tty
  else return 1; fi
}
if [ -z "$edition" ]; then
  echo "GraalJS runs the run_js tool. Choose the edition:"
  echo "  1) commercial  Oracle GraalVM, hard memory isolation (Oracle GraalVM Free Terms and Conditions)"
  echo "  2) community   UPL/MIT, weaker isolation (no hard memory separation)"
  ask "Edition [1]: " || { echo "no terminal: pass --edition (and --accept-license for commercial)" >&2; exit 1; }
  case ${REPLY:-1} in 1|commercial) edition=oracle;; 2|community) edition=community;; *) echo "unknown choice" >&2; exit 1;; esac
fi
if [ "$edition" = oracle ] && [ "$accept" = 0 ]; then
  echo "The commercial set is under the Oracle GraalVM Free Terms and Conditions:"
  echo "  https://www.oracle.com/downloads/licenses/graal-free-license.html"
  ask "Do you accept these terms? [y/N] " || { echo "no terminal: read the terms and pass --accept-license" >&2; exit 1; }
  case ${REPLY:-n} in y|Y|yes|YES|Yes) ;; *) echo "terms not accepted: nothing unpacked" >&2; exit 1;; esac
fi
# unzip: preinstalled on macOS and most Linux; else ditto (macOS), bsdtar, python
if command -v unzip >/dev/null; then unzip -q -o "$zip" -d "$dir"
elif command -v ditto >/dev/null; then ditto -x -k "$zip" "$dir"
elif command -v bsdtar >/dev/null; then bsdtar -xf "$zip" -C "$dir"
elif command -v python3 >/dev/null; then python3 -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$zip" "$dir"
else echo "need unzip, ditto, bsdtar or python3" >&2; exit 1; fi
top=$(basename "$zip" .zip)
home="$dir/$top"
[ -d "$home" ] || { echo "$home not found after unzipping" >&2; exit 1; }
chmod +x "$home"/*.sh
cd "$home"
if [ "$edition" = oracle ]; then ./fetch-graaljs.sh oracle --accept-oracle-terms; else ./fetch-graaljs.sh community; fi
if [ "$sql" != none ]; then
  args=("$sql"); [ "$accept" = 0 ] || args+=(--accept-license)
  [ -z "${JLLM_MAVEN_REPO:-}" ] || args+=(--base-url "$JLLM_MAVEN_REPO")
  ./fetch-sql-driver.sh "${args[@]}"
fi
echo "jllm is ready in $home: ./run-cli.sh or ./run-web.sh (Java 21+ needed)"
