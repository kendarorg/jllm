#!/usr/bin/env bash
# Builds target/release/jllm-<version>.zip: cli + web + plugins + settings + users + runners + an empty lib-ext, and
# copies setup.sh / setup.ps1 (release/setup) next to the zip as separate release files.
# Usage: release/release.sh [--build] [--tests] [--out DIR]
# No GraalJS jar is shipped: lib-ext/manifests/ holds the manifests of both editions (resolved with release/graaljs) and
# the user runs setup.sh / setup.ps1, which unzips the distribution, asks the edition and fetches it with fetch-graaljs.
# The build fails if a front-end jar contains GraalJS or if a resolved pom declares a licence other than UPL, MIT,
# Apache, BSD, Unicode/ICU (the Oracle terms are only recorded in the manifest, never accepted here).
# Without --build it asks whether to build (mvn clean install); the default is no: package the existing jars.
# When building it asks whether to run the tests (default yes; --tests answers yes). JaCoCo is never run (no agent, no report module).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)  # this script lives in <root>/release
out="$root/target/release"; build=0; tests=0
while [ $# -gt 0 ]; do
  case $1 in
    --build) build=1;;
    --tests) tests=1;;
    --out) out=$2; shift;;
    *) echo "unknown argument $1" >&2; exit 2;;
  esac
  shift
done
cd "$root"
if [ "$build" = 0 ]; then
  read -r -p "Build with mvn clean install? [y/N] " ans || ans=""
  case $ans in [yY]|[yY][eE][sS]) build=1;; esac
fi
if [ "$build" = 1 ] && [ "$tests" = 0 ]; then
  read -r -p "Run the tests? [Y/n] " ans || ans="n"
  case $ans in [nN]|[nN][oO]) ;; *) tests=1;; esac
fi
if [ "$build" = 1 ]; then
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
  mvn_args=(-B -Djacoco.skip=true -pl '!jacoco' clean install)
  [ "$tests" = 1 ] || mvn_args+=(-DskipTests)
  mvn "${mvn_args[@]}"
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
# SQL store: the starter and its jars go to lib-ext/sql (fetch-graaljs empties lib-ext/*.jar, not lib-ext/sql); the JDBC
# drivers (MySQL GPL, PostgreSQL BSD) are never shipped: fetch-sql-driver downloads them into lib-ext and checks the manifests
sqlstarter="web/jllm-sql-spring-starter/target"
mkdir -p "$stage/lib-ext/sql"
cp "$sqlstarter/jllm-sql-spring-starter-$version.jar" "$sqlstarter"/lib-sql/*.jar "$stage/lib-ext/sql/"
cp scripts/fetch-sql-driver.sh scripts/fetch-sql-driver.ps1 scripts/fetch-sql-driver.cmd "$stage/"
chmod +x "$stage"/fetch-sql-driver.sh
cp LICENSE NOTICE "$stage/"
chmod +x "$stage"/*.sh
# neither front end may carry GraalJS (it is provided through lib-ext)
list_zip() { # entry names of a zip, whichever tool exists
  if command -v unzip >/dev/null; then unzip -Z1 "$1"
  elif command -v jar >/dev/null; then jar tf "$1"
  else "$(command -v python3 || command -v python)" -c 'import sys,zipfile; print("\n".join(zipfile.ZipFile(sys.argv[1]).namelist()))' "$1"; fi
}
for j in "$stage/jllm-cli.jar" "$stage/jllm-web.jar"; do
  if list_zip "$j" | grep -qE '^org/graalvm/|^com/oracle/truffle/|(^|/)(polyglot|truffle-[a-z]+|js-language|js-isolate[a-z0-9-]*|regex|graaljs)-[0-9][^/]*\.jar$'; then
    echo "$j contains GraalJS classes or jars: it must be provided through lib-ext, not bundled" >&2; exit 1
  fi
done
mkdir -p "$stage/lib-ext/manifests"
cp web/jllm-sql/src/main/resources/db/drivers/*.manifest "$stage/lib-ext/manifests/"
# manifests only, nothing is shipped: the operator accepts the terms of the oracle edition in setup / fetch-graaljs
for ed in community oracle; do
  tmped=$(mktemp -d)
  "$root/release/graaljs/resolve.sh" "$ed" "$tmped" --accept-terms
  cp "$tmped/graaljs-$ed.manifest" "$stage/lib-ext/manifests/"; rm -rf "$tmped"
done
cp release/setup/setup.sh release/setup/setup.ps1 "$out/"; chmod +x "$out/setup.sh"
cd "$out"
if command -v zip >/dev/null; then zip -qr "$name.zip" "$name"
elif command -v python3 >/dev/null || command -v python >/dev/null; then
  "$(command -v python3 || command -v python)" -c 'import shutil,sys; shutil.make_archive(sys.argv[1], "zip", ".", sys.argv[1])' "$name"
else echo "need zip or python to build the archive" >&2; exit 1; fi
sha256sum "$name.zip" > SHA256SUMS
echo "built $out/$name.zip"
echo "publish: gh release create v$version \"$out/$name.zip\" \"$out/SHA256SUMS\" \"$out/setup.sh\" \"$out/setup.ps1\" --title \"jllm $version\" --notes-file \"$root/release/template/README.md\""
