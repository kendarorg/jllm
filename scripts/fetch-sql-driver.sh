#!/usr/bin/env bash
# Downloads the JDBC drivers of the SQL store from Maven Central, where the web app finds them: source checkout
# .downloaded-libs/<db>/<version>/ (git-ignored); release zip lib-ext/ (next to the GraalJS jars, which fetch-graaljs and
# this script leave alone). SQLite is bundled and needs no download. At run time only the driver of the jdbc url is loaded.
# Usage: scripts/fetch-sql-driver.sh [mysql|postgres|all] [--accept-license] [--base-url URL]   (default: all)
# - MySQL (mysql-connector-j) is GPLv2 with the Universal FOSS Exception, never bundled: whoever fetches it accepts those
#   terms (asked y/N; --accept-license accepts silently; without a terminal and without the flag it fails).
#   PostgreSQL (BSD-2-Clause) needs no acceptance.
# - version and SHA-256 come from the manifests (web/jllm-sql/src/main/resources/db/drivers/<db>.manifest; in a release
#   zip lib-ext/manifests/<db>.manifest, copied to lib-ext/); a download whose SHA-256 differs is deleted and the script fails
# - source checkout: the manifest version must equal mysql.version / postgresql.version of the root pom
# - --base-url replaces https://repo1.maven.org/maven2 (a mirror, or file:///... for tests)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
base=https://repo1.maven.org/maven2; accept=0; which=all
while [ $# -gt 0 ]; do
  case $1 in
    mysql|postgres|all) which=$1;;
    --accept-license) accept=1;;
    --base-url) [ $# -ge 2 ] || { echo "--base-url needs a value" >&2; exit 2; }; base=$2; shift;;
    *) echo "unknown argument $1" >&2; exit 2;;
  esac
  shift
done
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
fetch() { # $1 = mysql | postgres
  local db=$1 manifest out release=0 mv
  if [ -f "$here/lib-ext/manifests/$db.manifest" ]; then
    manifest="$here/lib-ext/manifests/$db.manifest"; out="$here/lib-ext"; release=1
  else
    local root; root=$(cd "$here/.." && pwd)
    manifest="$root/web/jllm-sql/src/main/resources/db/drivers/$db.manifest"
    [ -f "$manifest" ] || { echo "manifest not found: $manifest" >&2; exit 1; }
    mv=$(sed -n 's/^version=//p' "$manifest" | tr -d '\r')
    local prop=mysql.version; [ "$db" = postgres ] && prop=postgresql.version
    local pv; pv=$(sed -n "s:.*<$prop>\(.*\)</$prop>.*:\1:p" "$root/pom.xml" | head -1)
    [ "$pv" = "$mv" ] || { echo "$prop $pv (root pom) differs from the manifest version $mv" >&2; exit 1; }
    out="$root/.downloaded-libs/$db/$mv"
  fi
  [ -f "$manifest" ] || { echo "manifest not found: $manifest" >&2; exit 1; }
  local line sum path jar
  line=$(grep -E '^[0-9a-f]{64}  ' "$manifest" | head -1 | tr -d '\r')
  sum=${line%%  *}; path=${line#*  }; jar=$(basename "$path")
  if grep -q '^accept=required' "$manifest" && [ "$accept" = 0 ]; then
    echo "$jar is under: $(sed -n 's/^license=//p' "$manifest" | tr -d '\r')"
    echo "  $(sed -n 's/^terms=//p' "$manifest" | tr -d '\r')"
    local answer=n
    if [ -t 0 ]; then
      read -r -p "Do you accept these terms? [y/N] " answer
    elif [ -r /dev/tty ] && { : < /dev/tty; } 2>/dev/null; then
      read -r -p "Do you accept these terms? [y/N] " answer < /dev/tty
    else
      echo "no terminal to ask on: read the terms and run again with --accept-license" >&2; exit 1
    fi
    case $answer in y|Y|yes|YES|Yes) ;; *) echo "terms not accepted: $jar not downloaded" >&2; exit 1;; esac
  fi
  mkdir -p "$out"
  local tmp="$out/$jar.part"
  curl -fsSL -o "$tmp" "$base/$path" || { rm -f "$tmp"; echo "download failed: $base/$path" >&2; exit 1; }
  local actual; actual=$(sha256 "$tmp")
  if [ "$actual" != "$sum" ]; then
    rm -f "$tmp"; echo "SHA-256 mismatch for $path (expected $sum, got $actual): file deleted, nothing changed" >&2; exit 1
  fi
  case $db in mysql) rm -f "$out"/mysql-connector-j-*.jar;; postgres) rm -f "$out"/postgresql-*.jar;; esac
  mv "$tmp" "$out/$jar"
  [ "$release" = 0 ] || cp "$manifest" "$out/$db.manifest"
  echo "$jar in $out"
}
if [ "$which" = all ]; then fetch mysql; fetch postgres; else fetch "$which"; fi
