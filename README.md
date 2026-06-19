# Dawn Launcher on macOS and Linux

Runs Dawn's Java/Compose launcher from the official Windows package on macOS and Linux.

## Install

macOS or Linux:

```bash
curl -fsSL https://raw.githubusercontent.com/J-O-A-B/dawngg-unix-patcher/main/install.sh | sh
```

The installer downloads this repo, installs dependencies, asks the official Dawn API for the current Windows launcher, extracts it, prepares `app/`, and adds Dawn Launcher to your apps.

Linux needs a working desktop keyring. Supported package managers: `pacman`, `apt`, `dnf`, `zypper`.

## macOS

Run from Applications or:

```bash
./launch-mac.sh
```

## Linux

Run from your app launcher or:

```bash
./launch-linux.sh app
```

The Linux agent (a `-javaagent`):

- builds `agent/dawn-unix-agent.jar` if missing;
- seeds the Linux Feather natives Dawn does not host (`fcef`, `fjni`, `favif`, `fwebp`, `fdiscord`) into the Dawn client cache, built on the fly from the stripped binaries in `payload/`;
- builds the `cef_binary` runtime jar from the launcher's bundled JCEF, and fixes the Discord SDK native name;
- bytecode-patches two launcher classes in memory (no files modified on disk): `MinecraftLaunchFlow.prepare(...)` to run the provisioning at launch, and `DawnClientInstaller.hashMatches(...)` so the launcher accepts the seeded natives instead of re-downloading them (Dawn 404s the Linux natives).

## Tested

Tested working on Linux (kernel `7.0.11`, NVIDIA driver `610.43.02`). It may not
work on every machine - the embedded browser (CEF) can fail to start depending on
your GPU, drivers or compositor.

## Disclaimer

This repackages Dawn's official Windows launcher to run on macOS and Linux. It is
not affiliated with or endorsed by Dawn, and using it may violate Dawn's terms of
service. Use at your own risk.

`launch-mac.sh` runs the client with `-Ddawn.client.allowUnverifiedManifest=true`.
This is required because the app is repacked from Dawn's MSIX, which invalidates the
original launcher manifest signature. It only skips that manifest check and bypasses
no account or security control. Don't copy the flag elsewhere without understanding it.

## Contact

Discord: `joab722`
