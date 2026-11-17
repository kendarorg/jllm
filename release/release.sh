#!/usr/bin/env bash
# Builds target/release/jllm-<version>.zip: cli + web + plugins + settings + users + runners.
# Usage: release/release.sh [--skip-build] [--out DIR]
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)  # this script lives in <root>/release
out="$root/target/release"; skip=0
while [ $# -gt 0 ]; do
  case $1 in
    --skip-build) skip=1;;
    --out) out=$2; shift;;
    *) echo "unknown argument $1" >&2; exit 2;;
  esac
  shift
done
cd "$root"
if [ "$skip" = 0 ]; then
  is_jdk() { [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])'; }
  # JAVA_HOME, else jenv, else the JDKs of IntelliJ (~/.jdks, the lowest 21+), else java on the PATH
  if ! { [ -n "${JAVA_HOME:-}" ] && is_jdk "$JAVA_HOME"; }; then
    JAVA_HOME=""
    if command -v jenv >/dev/null; then d=$(jenv getjava 2>/dev/null) && is_jdk "$d" && JAVA_HOME="$d"; fi
    [ -n "$JAVA_HOME" ] || for d in $(ls -d "$HOME"/.jdks/*/ 2>/dev/null | sort -V); do is_jdk "${d%/}" && { JAVA_HOME="${d%/}"; break; }; done
    if [ -z "$JAVA_HOME" ] && command -v java >/dev/null; then
      h=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")"); is_jdk "$h" && JAVA_HOME="$h"
    fi
    [ -n "$JAVA_HOME" ] || { echo "JDK 21+ not found: set JAVA_HOME to a JDK 21" >&2; exit 1; }
    export JAVA_HOME
  fi
  echo "using JDK $JAVA_HOME"
  rm -rf target/plugins
  mvn -B -DskipTests clean install
fi
version=$(sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' pom.xml | head -1)
name="jllm-$version"; stage="$out/$name"
rm -rf "$stage" "$out/$name.zip"; mkdir -p "$stage/plugins"
cp "jllm-cli/target/jllm-cli-$version.jar" "$stage/jllm-cli.jar"
cp "jllm-web/target/jllm-web-$version.jar" "$stage/jllm-web.jar"
shopt -s nullglob
plugins=(target/plugins/*.jar)
[ ${#plugins[@]} -ge 5 ] || { echo "expected 5 plugin jars in target/plugins, found ${#plugins[@]}" >&2; exit 1; }
cp "${plugins[@]}" "$stage/plugins/"
cp -r release/template/. "$stage/"
cp LICENSE NOTICE "$stage/"
chmod +x "$stage"/*.sh
cd "$out"
if command -v zip >/dev/null; then zip -qr "$name.zip" "$name"
elif command -v python3 >/dev/null || command -v python >/dev/null; then
  "$(command -v python3 || command -v python)" -c 'import shutil,sys; shutil.make_archive(sys.argv[1], "zip", ".", sys.argv[1])' "$name"
else echo "need zip or python to build the archive" >&2; exit 1; fi
sha256sum "$name.zip" > SHA256SUMS
echo "built $out/$name.zip"
echo "publish: gh release create v$version \"$out/$name.zip\" \"$out/SHA256SUMS\" --title \"jllm $version\" --notes-file \"$root/release/template/README.md\""
