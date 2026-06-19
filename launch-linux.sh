#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="${1:-$ROOT/app}"
DAWN_HOME="${DAWN_HOME:-$HOME/.dawn}"
AUTO_PATCH_LINUX_FCEF="${AUTO_PATCH_LINUX_FCEF:-1}"
JAVA_BIN="${JAVA_BIN:-java}"
AGENT_JAR="$ROOT/agent/dawn-unix-agent.jar"
export DAWN_HOME

[ -d "$APP_DIR" ] || { echo "Dawn app dir not found: $APP_DIR" >&2; exit 1; }
find "$APP_DIR" -maxdepth 1 -name 'jcefmaven-*.jar' -print -quit | grep -q . || { echo "Dawn JCEF jars not found: $APP_DIR" >&2; exit 1; }

cd "$APP_DIR"

SORT_V="sort"
if sort -V </dev/null >/dev/null 2>&1; then SORT_V="sort -V"; fi

LAUNCHER_JAR="$(find . -maxdepth 1 -name 'desktop-desktop-*.jar' | $SORT_V | tail -n 1)"
[ -n "$LAUNCHER_JAR" ] || { echo "Dawn app jars not found: $APP_DIR" >&2; exit 1; }
CLASSPATH="$LAUNCHER_JAR:$(find . -maxdepth 1 -name '*.jar' ! -name 'desktop-desktop-*.jar' -print | sort | paste -sd: -)"

if [ "$AUTO_PATCH_LINUX_FCEF" != "0" ]; then
    [ -f "$AGENT_JAR" ] || "$ROOT/build-agent.sh"
    CLASSPATH="$AGENT_JAR:$CLASSPATH"
    exec "$JAVA_BIN" \
        -javaagent:"$AGENT_JAR" \
        -cp "$CLASSPATH" \
        -Dskiko.renderApi=SOFTWARE_COMPAT \
        gg.dawn.launcher.desktop.MainKt
fi

exec "$JAVA_BIN" \
    -cp "$CLASSPATH" \
    -Dskiko.renderApi=SOFTWARE_COMPAT \
    gg.dawn.launcher.desktop.MainKt
