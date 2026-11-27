#!/bin/sh
# Installs the jllm CLI into a container or host: a Java 21 runtime (only when `java -version` is older than 21 or
# missing) and the shaded jllm-cli jar, under DEST (default /opt/jllm).
#
#   install.sh JAR [DEST]
#
# Result: DEST/jllm.jar and DEST/jllm (launcher: `DEST/jllm ARGS` runs `java -jar DEST/jllm.jar ARGS`); a link
# /usr/local/bin/jllm when that directory is writable. The JRE is Eclipse Temurin, pinned below with its SHA-256.
# JLLM_CACHE_DIR (optional): directory that keeps downloaded JRE archives between installs (e.g. a shared volume).
# JLLM_JAVA_OPTS (optional, read by the launcher at run time): JVM options; default "-XX:+UseSerialGC".
# POSIX sh only: images without bash (Alpine, distroless shells) must work.
set -eu

JRE_RELEASE="jdk-21.0.13.1+2"
JRE_BASE="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2"

die() { echo "jllm install: $*" >&2; exit 1; }

[ $# -ge 1 ] || die "usage: install.sh JAR [DEST]"
JAR="$1"
DEST="${2:-/opt/jllm}"
[ -f "$JAR" ] || die "jar not found: $JAR"

java_major() {
    # prints the major version of the java on PATH ("1.8.0_x" -> 8), or 0 when there is none
    if ! command -v java >/dev/null 2>&1; then echo 0; return; fi
    v=$(java -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n 1)
    case "$v" in
        1.*) echo "$v" | cut -d. -f2 ;;
        "") echo 0 ;;
        *) echo "$v" | sed 's/[^0-9].*//' ;;
    esac
}

jre_asset() {
    # prints "FILE SHA256" for this OS/arch
    os=$(uname -s)
    arch=$(uname -m)
    case "$arch" in
        x86_64|amd64) arch=x64 ;;
        aarch64|arm64) arch=aarch64 ;;
        *) die "unsupported architecture: $arch" ;;
    esac
    case "$os" in
        Linux)
            if [ -f /etc/alpine-release ] || (ldd --version 2>&1 | grep -qi musl); then os=alpine-linux; else os=linux; fi ;;
        Darwin) os=mac ;;
        *) die "unsupported OS: $os" ;;
    esac
    case "$os-$arch" in
        linux-x64) echo "OpenJDK21U-jre_x64_linux_hotspot_21.0.13.1_2.tar.gz 18f44fab9065217f3ba61d0eef8a7b5f3c3713aa7293001b0d4ac10ede3850f0" ;;
        linux-aarch64) echo "OpenJDK21U-jre_aarch64_linux_hotspot_21.0.13.1_2.tar.gz 52bc58323d9266538a6361a3cf8365fc1186dd9510d7f33f623681c4aabb7e72" ;;
        alpine-linux-x64) echo "OpenJDK21U-jre_x64_alpine-linux_hotspot_21.0.13.1_2.tar.gz 72835f41a468ae949fc058f8ec6a73d303ca3e5ea165621e08aacb36bb537bba" ;;
        alpine-linux-aarch64) echo "OpenJDK21U-jre_aarch64_alpine-linux_hotspot_21.0.13.1_2.tar.gz 0236751150c82f1ef91be8cc32a36091a95e97650285fea848f2bb82311e5da7" ;;
        mac-x64) echo "OpenJDK21U-jre_x64_mac_hotspot_21.0.13.1_2.tar.gz 10cee6ba4cd83fd4613a3f0bb3ef9dddd68c68d5f3ccfcc7e456707c9e1eafe0" ;;
        mac-aarch64) echo "OpenJDK21U-jre_aarch64_mac_hotspot_21.0.13.1_2.tar.gz 848feb1906c7de79de4f8391636042193fbd46eff86e26299506c4656f62c260" ;;
        *) die "no pinned JRE for $os-$arch" ;;
    esac
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
    else die "neither sha256sum nor shasum found"; fi
}

download() {
    if command -v curl >/dev/null 2>&1; then curl -fsSL --retry 3 -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then wget -q -O "$2" "$1"
    else die "neither curl nor wget found"; fi
}

install_jre() {
    asset=$(jre_asset) || exit 1
    file=${asset% *}; sum=${asset#* }
    cache="${JLLM_CACHE_DIR:-$DEST/download}"
    mkdir -p "$cache"
    archive="$cache/$file"
    if [ ! -f "$archive" ] || [ "$(sha256_of "$archive")" != "$sum" ]; then
        echo "jllm install: downloading $JRE_RELEASE ($file)" >&2
        download "$JRE_BASE/$file" "$archive.part"
        mv "$archive.part" "$archive"
    fi
    [ "$(sha256_of "$archive")" = "$sum" ] || { rm -f "$archive"; die "checksum mismatch for $file"; }
    rm -rf "$DEST/jre"
    mkdir -p "$DEST/jre"
    tar -xzf "$archive" -C "$DEST/jre" --strip-components=1
    if [ -x "$DEST/jre/Contents/Home/bin/java" ]; then
        echo "$DEST/jre/Contents/Home/bin/java"
    else
        echo "$DEST/jre/bin/java"
    fi
}

mkdir -p "$DEST"
if [ "$(java_major)" -ge 21 ]; then
    JAVA=$(command -v java)
    echo "jllm install: using installed java $(java_major) at $JAVA"
else
    JAVA=$(install_jre | tail -n 1)
    [ -x "$JAVA" ] || die "java not found after extracting the JRE"
fi

if [ "$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")" != "$DEST/jllm.jar" ]; then
    cp "$JAR" "$DEST/jllm.jar"
fi
cat > "$DEST/jllm" <<EOF
#!/bin/sh
exec "$JAVA" \${JLLM_JAVA_OPTS:--XX:+UseSerialGC} -jar "$DEST/jllm.jar" "\$@"
EOF
chmod 755 "$DEST/jllm"
chmod 644 "$DEST/jllm.jar"
if [ -d /usr/local/bin ] && [ -w /usr/local/bin ]; then ln -sf "$DEST/jllm" /usr/local/bin/jllm; fi

"$JAVA" -version 2>&1 | head -n 1
echo "jllm install: $DEST/jllm ready ($(du -sh "$DEST" 2>/dev/null | cut -f1) in $DEST)"
