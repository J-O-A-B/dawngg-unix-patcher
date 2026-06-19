#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="${APP_DIR:-$ROOT/app}"
JAVA_BIN="${JAVA_BIN:-/opt/homebrew/opt/openjdk@21/bin/java}"
SKIKO_RENDER_API="${SKIKO_RENDER_API:-METAL}"

[ -x "$JAVA_BIN" ] || { echo "Java not found: $JAVA_BIN" >&2; exit 1; }
[ "$(uname -m)" != "arm64" ] || find "$APP_DIR" -maxdepth 1 -name 'skiko-awt-runtime-macos-arm64-*.jar' -print -quit | grep -q . || { echo "Missing macOS ARM Skiko runtime" >&2; exit 1; }

SORT_V="sort"
if sort -V </dev/null >/dev/null 2>&1; then SORT_V="sort -V"; fi

LAUNCHER_JAR="$(find "$APP_DIR" -maxdepth 1 -name 'desktop-desktop-*.jar' | $SORT_V | tail -n 1)"
[ -n "$LAUNCHER_JAR" ] || { echo "Dawn app jars not found: $APP_DIR" >&2; exit 1; }
CLASSPATH="$LAUNCHER_JAR:$(find "$APP_DIR" -maxdepth 1 -name '*.jar' ! -name 'desktop-desktop-*.jar' -print | sort | paste -sd: -)"

cd "$APP_DIR"
exec "$JAVA_BIN" \
  -Dskiko.renderApi="$SKIKO_RENDER_API" \
  -Ddawn.client.allowUnverifiedManifest=true \
  -cp "$CLASSPATH" \
  gg.dawn.launcher.desktop.MainKt
