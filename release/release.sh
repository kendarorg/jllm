#!/usr/bin/env bash
# Builds target/release/jllm-<version>.zip: cli + web + plugins + settings + users + runners + lib-ext (GraalJS).
# Usage: release/release.sh [--build] [--out DIR] [--graal community|oracle] [--accept-oracle-terms]
# GraalJS is not inside the front-end jars: it is resolved with release/graaljs and shipped in lib-ext/ (default edition
# community). The manifests of both editions go to lib-ext/manifests/ for fetch-graaljs; the build fails if a front-end
# jar contains GraalJS or if a resolved pom declares a licence other than UPL, MIT, Apache, BSD, Unicode/ICU.
# Without --build it asks whether to build (mvn clean install, with tests); the default is no: package the existing jars.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)  # this script lives in <root>/release
out="$root/target/release"; build=0; graal=community; accept=""
while [ $# -gt 0 ]; do
  case $1 in
    --build) build=1;;
    --out) out=$2; shift;;
    --graal) graal=$2; shift;;
    --accept-oracle-terms) accept="--accept-terms";;
    *) echo "unknown argument $1" >&2; exit 2;;
  esac
  shift
done
case $graal in community) other=oracle;; oracle) other=community;; *) echo "--graal is community or oracle" >&2; exit 2;; esac
cd "$root"
if [ "$build" = 0 ]; then
  read -r -p "Build with mvn clean install (tests included)? [y/N] " ans || ans=""
  case $ans in [yY]|[yY][eE][sS]) build=1;; esac
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
  mvn -B clean install
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
"$root/release/graaljs/resolve.sh" "$graal" "$stage/lib-ext" $accept
# the other edition: manifest only (nothing of it is shipped; the operator accepts its terms when running fetch-graaljs)
tmpother=$(mktemp -d)
"$root/release/graaljs/resolve.sh" "$other" "$tmpother" --accept-terms
cp "$tmpother/graaljs-$other.manifest" "$stage/lib-ext/manifests/"; rm -rf "$tmpother"
cp "$stage/lib-ext/graaljs-$graal.manifest" "$stage/lib-ext/manifests/"
cd "$out"
if command -v zip >/dev/null; then zip -qr "$name.zip" "$name"
elif command -v python3 >/dev/null || command -v python >/dev/null; then
  "$(command -v python3 || command -v python)" -c 'import shutil,sys; shutil.make_archive(sys.argv[1], "zip", ".", sys.argv[1])' "$name"
else echo "need zip or python to build the archive" >&2; exit 1; fi
sha256sum "$name.zip" > SHA256SUMS
echo "built $out/$name.zip"
echo "publish: gh release create v$version \"$out/$name.zip\" \"$out/SHA256SUMS\" --title \"jllm $version\" --notes-file \"$root/release/template/README.md\""
