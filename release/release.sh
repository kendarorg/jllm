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
  [ -n "${JAVA_HOME:-}" ] || { echo "set JAVA_HOME to a JDK 21" >&2; exit 1; }
  "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])' || { echo "JDK 21+ required" >&2; exit 1; }
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
echo "publish: gh release create v$version \"$out/$name.zip\" \"$out/SHA256SUMS\" --title \"jllm $version\" --notes-file \"$root/release/template/README.txt\""
