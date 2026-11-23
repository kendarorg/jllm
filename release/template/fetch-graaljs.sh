#!/usr/bin/env bash
# Replaces the GraalJS jars of lib-ext/ with the set of one edition, downloaded from Maven Central and verified.
# Usage: ./fetch-graaljs.sh <community|oracle> [--accept-oracle-terms]
# Editions never mix: lib-ext/*.jar (but not the MySQL driver) is emptied first. A download whose SHA-256 differs from the manifest is deleted and
# the script fails (lib-ext is left as it was). The oracle set may declare terms (the manifest says so): read them,
# then pass --accept-oracle-terms. JLLM_MAVEN_REPO overrides the repository URL.
set -euo pipefail
cd "$(dirname "$0")"
edition=${1:-}; accept=0
[ "${2:-}" = "--accept-oracle-terms" ] && accept=1
case $edition in community|oracle) ;; *) echo "usage: $0 <community|oracle> [--accept-oracle-terms]" >&2; exit 2;; esac
manifest="lib-ext/manifests/graaljs-$edition.manifest"
[ -f "$manifest" ] || { echo "missing $manifest" >&2; exit 1; }
repo=${JLLM_MAVEN_REPO:-https://repo1.maven.org/maven2}
terms=$(sed -n 's/^terms=//p' "$manifest" | tr -d '\r')
if [ -n "$terms" ] && [ "$accept" != 1 ]; then
  echo "the $edition set declares terms you must accept: $terms" >&2
  echo "read them, then run again with --accept-oracle-terms" >&2
  exit 3
fi
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
download() {
  if command -v curl >/dev/null; then curl -fsSL -o "$2" "$1"
  elif command -v wget >/dev/null; then wget -q -O "$2" "$1"
  else echo "need curl or wget" >&2; return 1; fi
}
stage=lib-ext/.download
rm -rf "$stage"; mkdir -p "$stage"; trap 'rm -rf "$stage"' EXIT
n=0
while read -r sum path; do
  sum=$(printf '%s' "$sum" | tr -d '\r'); path=$(printf '%s' "$path" | tr -d '\r')
  [[ $sum =~ ^[0-9a-f]{64}$ ]] || continue
  file="$stage/$(basename "$path")"
  echo "fetching $(basename "$path")"
  download "$repo/$path" "$file" || { echo "download failed: $repo/$path" >&2; exit 1; }
  if [ "$(sha256 "$file")" != "$sum" ]; then
    rm -f "$file"; echo "SHA-256 mismatch for $path: file deleted, nothing changed" >&2; exit 1
  fi
  n=$((n+1))
done < "$manifest"
[ "$n" -gt 0 ] || { echo "no jar listed in $manifest" >&2; exit 1; }
find lib-ext -maxdepth 1 -type f \( -name '*.jar' ! -name 'mysql-connector-j-*' -o -name 'graaljs*.manifest' \) -delete # the SQL driver stays
mv "$stage"/*.jar lib-ext/
cp "$manifest" "lib-ext/graaljs-$edition.manifest"
echo "GraalJS $edition $(sed -n 's/^version=//p' "$manifest" | tr -d '\r'): $n jars in lib-ext"
