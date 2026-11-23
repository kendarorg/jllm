#!/usr/bin/env bash
# Resolves the GraalJS jar set of one edition with Maven (release/graaljs/pom.xml) into a folder, re-checks the licence
# declared by every resolved pom and writes the manifest next to the jars.
# Usage: release/graaljs/resolve.sh <community|oracle> <out-dir> [--accept-terms]
# - empties <out-dir>/*.jar and <out-dir>/graaljs*.manifest first, so editions never mix
# - the version is read from the root pom (property graalvm.version), the only place it is declared
# - licences: UPL, MIT, Apache, BSD and the Unicode/ICU licence pass; anything else fails. The "GraalVM Free Terms and
#   Conditions" (declared by the non-community isolate) is recorded as terms= in the manifest and fails unless
#   --accept-terms is given: whoever runs the oracle set accepts those terms.
# Manifest (graaljs-<edition>.manifest): edition=, version=, optional terms=, then "<sha256>  <path in Maven Central>".
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
edition=${1:-}; out=${2:-}; accept=0
[ "${3:-}" = "--accept-terms" ] && accept=1
case $edition in community|oracle) ;; *) echo "usage: resolve.sh <community|oracle> <out-dir> [--accept-terms]" >&2; exit 2;; esac
[ -n "$out" ] || { echo "usage: resolve.sh <community|oracle> <out-dir> [--accept-terms]" >&2; exit 2; }
version=$(sed -n 's:.*<graalvm.version>\(.*\)</graalvm.version>.*:\1:p' "$root/pom.xml" | head -1)
[ -n "$version" ] || { echo "graalvm.version not found in $root/pom.xml" >&2; exit 1; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mvn -q -B -f "$here/pom.xml" -P"$edition" "-Dgraalvm.version=$version" "-Dgraal.out=$work" \
  -Dmdep.useRepositoryLayout=true dependency:copy-dependencies >&2
# licences of every resolved pom (jar or pom artifact)
terms=""
while IFS= read -r pom; do
  names=$(tr '\n' ' ' < "$pom" | grep -o '<licenses>.*</licenses>' | grep -o '<name>[^<]*</name>' | sed 's/<[^>]*>//g' || true)
  [ -n "$names" ] || { echo "no licence declared in $pom" >&2; exit 1; }
  while IFS= read -r n; do
    if echo "$n" | grep -qiE 'Universal Permissive|^MIT|Apache|BSD|Unicode|ICU'; then :
    elif echo "$n" | grep -qiE 'GraalVM Free Terms|GFTC'; then
      case "$terms" in *"$n"*) ;; *) terms="${terms:+$terms; }$n";; esac
    else echo "unacceptable licence '$n' in $pom" >&2; exit 1; fi
  done <<< "$names"
done < <(find "$work" -name '*.pom' | sort)
if [ -n "$terms" ] && [ "$accept" = 0 ]; then
  echo "the $edition set declares terms the operator must accept: $terms" >&2
  echo "read them, then run again with --accept-terms (or JLLM_ACCEPT_ORACLE_TERMS=1 in the fetch scripts)" >&2
  exit 3
fi
mkdir -p "$out"
rm -f "$out"/*.jar "$out"/graaljs*.manifest
manifest="$out/graaljs-$edition.manifest"
{
  echo "# jllm GraalJS jar set: <sha256>  <path in Maven Central>"
  echo "edition=$edition"; echo "version=$version"
  [ -z "$terms" ] || echo "terms=$terms"
} > "$manifest"
while IFS= read -r jar; do
  cp "$jar" "$out/"
  echo "$(sha256 "$jar")  ${jar#"$work"/}" >> "$manifest"
done < <(find "$work" -name '*.jar' | sort)
echo "GraalJS $edition $version: $(grep -c '^[0-9a-f]\{64\}' "$manifest") jars in $out" >&2
