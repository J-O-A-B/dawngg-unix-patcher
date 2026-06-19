#!/usr/bin/env sh
set -eu

REPO="https://github.com/J-O-A-B/dawngg-unix-patcher"
DIR="${DAWN_UNIX_DIR:-dawngg-unix-patcher}"

need() { command -v "$1" >/dev/null 2>&1; }
say() { printf '%s\n' "$*"; }
die() { say "error: $*" >&2; exit 1; }
run() { say "+ $*"; "$@"; }
curlf() { run curl -fL --retry 5 --retry-all-errors --retry-delay 5 "$@"; }

install_deps() {
    os="$(uname -s)"
    if [ "$os" = Darwin ]; then
        need brew || die "install Homebrew first: https://brew.sh"
        run brew install p7zip openjdk@21
        return
    fi

    if need pacman; then
        run sudo pacman -S --needed p7zip jdk-openjdk curl gnome-keyring
    elif need apt-get; then
        run sudo apt-get update
        run sudo apt-get install -y --no-install-recommends p7zip-full default-jdk curl gnome-keyring
    elif need dnf; then
        run sudo dnf install -y --setopt=install_weak_deps=False p7zip p7zip-plugins java-21-openjdk-devel curl gnome-keyring
    elif need zypper; then
        run sudo zypper install -y --no-recommends p7zip java-21-openjdk-devel curl gnome-keyring
    else
        die "unsupported distro; install p7zip, JDK 21, curl and gnome-keyring manually"
    fi
}

fetch_repo() {
    if [ -f launch-mac.sh ] && [ -f launch-linux.sh ] && [ -f build-agent.sh ]; then
        return
    fi

    need tar || die "tar is required"
    mkdir -p "$DIR"
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    curlf "$REPO/archive/refs/heads/main.tar.gz" -o "$tmp/repo.tar.gz"
    tar -xzf "$tmp/repo.tar.gz" -C "$tmp"
    src="$(find "$tmp" -maxdepth 1 -type d -name 'dawngg-unix-patcher-*' | head -n 1)"
    [ -n "$src" ] || die "failed to unpack repo"
    cp -R "$src"/. "$DIR"/
    cd "$DIR"
}

download_dawn() {
    mkdir -p _downloads extracted

    api="$(curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 'https://dawn.gg/api/launcher/beta-download?platform=windows')"
    if need jq; then
        path="$(printf '%s' "$api" | jq -r '.url // empty')"
    else
        path="$(printf '%s' "$api" | sed -n 's/.*"url"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
    fi
    [ -n "$path" ] || die "Dawn API did not return a launcher URL"
    curlf "https://dawn.gg$path" -o _downloads/dawn-launcher.download

    mv _downloads/dawn-launcher.download _downloads/dawn-launcher.exe

    appinstaller="$(strings -a _downloads/dawn-launcher.exe | grep -E 'https://downloads\.dawn\.gg/.+appinstaller' | head -n 1 || true)"
    if [ -z "$appinstaller" ]; then
        base="$(strings -a _downloads/dawn-launcher.exe | grep -E '^https://downloads\.dawn\.gg/.*/$' | head -n 1 || true)"
        name="$(strings -a _downloads/dawn-launcher.exe | grep -E '^dawn-launcher\.appinstaller$' | head -n 1 || true)"
        [ -z "$base" ] || [ -z "$name" ] || appinstaller="$base$name"
    fi

    if [ -n "$appinstaller" ]; then
        curlf "$appinstaller" -o _downloads/dawn-launcher.appinstaller

        msix="$(grep -o 'https://[^"]*\.msix' _downloads/dawn-launcher.appinstaller | head -n 1 || true)"
        [ -n "$msix" ] || die "could not find msix URL"
        curlf "$msix" -o _downloads/dawn-launcher.msix
    elif 7z l _downloads/dawn-launcher.exe | grep -q 'AppxManifest.xml'; then
        mv _downloads/dawn-launcher.exe _downloads/dawn-launcher.msix
    else
        die "could not find appinstaller or msix payload"
    fi

    extract_msix
}

extract_msix() {
    rm -rf app extracted
    mkdir -p extracted
    run 7z x _downloads/dawn-launcher.msix -oextracted -y
    [ -d extracted/app ] || die "msix did not contain app/"
    mv extracted/app ./app
}

fetch_skiko_runtime() {
    target="$1"
    jar="$(find app -maxdepth 1 -name 'skiko-awt-*.jar' ! -name 'skiko-awt-runtime-*' | head -n 1)"
    ver="$(printf '%s\n' "$jar" | sed -n 's/.*skiko-awt-\([0-9][0-9.]*\)\.jar$/\1/p')"
    [ -n "$ver" ] || die "could not determine bundled Skiko version"
    runtime="app/skiko-awt-runtime-$target-$ver.jar"
    [ -f "$runtime" ] || curlf -o "$runtime" \
        "https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-$target/$ver/skiko-awt-runtime-$target-$ver.jar"
}

setup_platform() {
    os="$(uname -s)"
    arch="$(uname -m)"

    if [ "$os" = Darwin ] && [ "$arch" = arm64 ]; then
        fetch_skiko_runtime macos-arm64
    fi

    if [ "$os" = Linux ]; then
        case "$arch" in
            x86_64|amd64) fetch_skiko_runtime linux-x64 ;;
            aarch64|arm64) fetch_skiko_runtime linux-arm64 ;;
            *) die "unsupported Linux architecture: $arch" ;;
        esac
        [ -f payload/libfcef.so ] || say "warning: payload/libfcef.so is missing; restore it or the in-game browser won't work on Linux"
        run ./build-agent.sh
    fi
}

install_shortcut() {
    root="$(pwd)"
    icon="$root/app/app.ico"

    if [ "$(uname -s)" = Darwin ]; then
        bundle="$HOME/Applications/Dawn Launcher.app"
        mkdir -p "$bundle/Contents/MacOS"
        cat > "$bundle/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key>
  <string>Dawn Launcher</string>
  <key>CFBundleIdentifier</key>
  <string>gg.dawn.unix.launcher</string>
  <key>CFBundleName</key>
  <string>Dawn Launcher</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
</dict>
</plist>
EOF
        cat > "$bundle/Contents/MacOS/Dawn Launcher" <<EOF
#!/usr/bin/env sh
exec "$root/launch-mac.sh"
EOF
        chmod +x "$bundle/Contents/MacOS/Dawn Launcher"
        say "App installed: $bundle"
        return
    fi

    esc_exec() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/`/\\`/g; s/\$/\\$/g'; }
    exec_launch="$(esc_exec "$root/launch-linux.sh")"
    exec_app="$(esc_exec "$root/app")"

    desktop_dir="$HOME/.local/share/applications"
    mkdir -p "$desktop_dir"
    cat > "$desktop_dir/dawn-launcher.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Dawn Launcher
Exec="$exec_launch" "$exec_app"
Icon=$icon
Terminal=false
Categories=Game;
StartupNotify=true
EOF
    chmod +x "$desktop_dir/dawn-launcher.desktop"
    need update-desktop-database && update-desktop-database "$desktop_dir" >/dev/null 2>&1 || true
    say "Desktop entry installed: $desktop_dir/dawn-launcher.desktop"
}

install_deps
fetch_repo
download_dawn
setup_platform
install_shortcut

say ""
say "Installed in: $(pwd)"
case "$(uname -s)" in
    Darwin) say "Run from Applications: Dawn Launcher" ;;
    Linux) say "Run from your app launcher: Dawn Launcher" ;;
esac
