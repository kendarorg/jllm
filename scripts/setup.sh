#!/usr/bin/env bash
# One-time setup: downloads the GraalJS libraries into .graal/<graalvm.version>/ (git-ignored) and runs the first build.
# Usage: scripts/setup.sh [--edition community] [--accept-license] [--no-build]
# - the version is graalvm.version of the root pom; run this again after it changes
# - default edition: commercial (Oracle GraalVM Free Terms and Conditions, asked y/N before any download).
#   --edition community needs no terms (weaker isolation: no hard memory separation)
# - --accept-license accepts the commercial terms silently; without it and without a terminal the script fails
# - --no-build skips "mvn -DskipTests install"
# The GraalJS libraries are not in the repository: when .graal/ holds them you downloaded them here and accepted the
# terms of the chosen edition.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
edition=oracle; accept=0; build=1
while [ $# -gt 0 ]; do
  case $1 in
    --edition) [ $# -ge 2 ] || { echo "--edition needs a value" >&2; exit 2; }
      case $2 in community) edition=community;; commercial|oracle) edition=oracle;; *) echo "edition is community or commercial" >&2; exit 2;; esac; shift;;
    --accept-license) accept=1;;
    --no-build) build=0;;
    *) echo "unknown argument $1" >&2; exit 2;;
  esac
  shift
done
version=$(sed -n 's:.*<graalvm.version>\(.*\)</graalvm.version>.*:\1:p' "$root/pom.xml" | head -1)
[ -n "$version" ] || { echo "graalvm.version not found in $root/pom.xml" >&2; exit 1; }
if [ "$edition" = oracle ] && [ "$accept" = 0 ]; then
  echo "GraalJS $version (Oracle GraalVM) is under the Oracle GraalVM Free Terms and Conditions:"
  echo "  https://www.oracle.com/downloads/licenses/graal-free-license.html"
  if [ -t 0 ]; then
    read -r -p "Do you accept these terms? [y/N] " answer
  elif [ -r /dev/tty ] && { : < /dev/tty; } 2>/dev/null; then
    read -r -p "Do you accept these terms? [y/N] " answer < /dev/tty
  else
    echo "no terminal to ask on: read the terms and run again with --accept-license" >&2; exit 1
  fi
  case $answer in y|Y|yes|YES|Yes) ;; *) echo "terms not accepted: nothing downloaded" >&2; exit 1;; esac
fi
graal="$root/.graal"; target="$graal/$version"
mkdir -p "$target"
"$root/release/graaljs/resolve.sh" "$edition" "$target" --accept-terms --repo "$target/repo"
# other versions (and the old .graal/<version>/<edition> layout) are obsolete
for d in "$graal"/*/; do
  [ -d "$d" ] || continue
  [ "$(basename "$d")" = "$version" ] || { echo "removing old $d"; rm -rf "$d"; }
done
for d in "$target"/*/; do
  [ -d "$d" ] || continue
  [ "$(basename "$d")" = repo ] || { echo "removing old $d"; rm -rf "$d"; }
done
if [ "$build" = 1 ]; then
  cd "$root"
  mvn -DskipTests install
fi
