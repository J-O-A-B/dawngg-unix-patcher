#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASM_VERSION="${ASM_VERSION:-9.7}"
OUT="$ROOT/agent/dawn-unix-agent.jar"
JDK21="/opt/homebrew/opt/openjdk@21/bin"
JAVAC_BIN="${JAVAC_BIN:-$([ -x "$JDK21/javac" ] && printf %s "$JDK21/javac" || printf javac)}"
JAR_BIN="${JAR_BIN:-$([ -x "$JDK21/jar" ] && printf %s "$JDK21/jar" || printf jar)}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

CLASS_DIR="$TMP_DIR/classes"
LIB_DIR="$TMP_DIR/lib"
FAT_DIR="$TMP_DIR/fat"
MANIFEST="$TMP_DIR/MANIFEST.MF"
ASM_JAR="$LIB_DIR/asm.jar"

mkdir -p "$CLASS_DIR" "$LIB_DIR" "$FAT_DIR"

CACHE_DIR="${ASM_CACHE_DIR:-$ROOT/agent/.cache}"
ASM_CACHED="$CACHE_DIR/asm-$ASM_VERSION.jar"
if [ ! -f "$ASM_CACHED" ]; then
    mkdir -p "$CACHE_DIR"
    curl -fL -o "$ASM_CACHED.tmp" "https://repo1.maven.org/maven2/org/ow2/asm/asm/$ASM_VERSION/asm-$ASM_VERSION.jar"
    mv "$ASM_CACHED.tmp" "$ASM_CACHED"
fi
cp "$ASM_CACHED" "$ASM_JAR"

"$JAVAC_BIN" -cp "$ASM_JAR" -d "$CLASS_DIR" "$ROOT/agent/DawnUnixAgent.java"

(
    cd "$FAT_DIR"
    "$JAR_BIN" xf "$ASM_JAR"
)

cat > "$MANIFEST" <<'EOF'
Manifest-Version: 1.0
Premain-Class: dev.dawnunix.agent.DawnUnixAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true

EOF

"$JAR_BIN" cfm "$OUT" "$MANIFEST" -C "$CLASS_DIR" . -C "$FAT_DIR" .
echo "built: $OUT"
