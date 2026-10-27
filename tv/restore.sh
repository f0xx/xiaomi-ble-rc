#!/bin/bash
# Put package enablement and settings back from a snapshot folder.
# ADB is left enabled. Kodi files are restored only with --kodi.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$HERE/common.sh"

with_kodi=0
if [[ "${1:-}" == "--kodi" ]]; then
  with_kodi=1
  shift
fi
snap="${1:-}"
if [[ -z "$snap" || ! -d "$snap" ]]; then
  echo "usage: $0 [--kodi] <snapshot-folder>" >&2
  exit 1
fi
require_tv

if [[ -f "$snap/disabled-by-us.txt" ]]; then
  while IFS= read -r pkg; do
    [[ -z "$pkg" || "$pkg" == \#* ]] && continue
    echo "enable $pkg"
    tv_shell pm enable --user 0 "$pkg" || echo "warn: enable $pkg" >&2
  done < "$snap/disabled-by-us.txt"
fi

# Packages disabled now that were enabled in the snapshot.
if [[ -f "$snap/packages-disabled.txt" ]]; then
  tv_shell pm list packages -d | sed 's/^package://' | sort > /tmp/tv-disabled-now.txt
  sed 's/^package://' "$snap/packages-disabled.txt" | sort > /tmp/tv-disabled-then.txt
  comm -13 /tmp/tv-disabled-then.txt /tmp/tv-disabled-now.txt | while IFS= read -r pkg; do
    [[ -z "$pkg" ]] && continue
    echo "enable $pkg"
    tv_shell pm enable --user 0 "$pkg" || echo "warn: enable $pkg" >&2
  done
fi

python3 - "$snap" << 'PY'
import subprocess, sys
snap = sys.argv[1]
serial = __import__("os").environ.get("ADB_SERIAL", "192.168.200.240:5555")
skip = {"adb_enabled", "adb_wifi_enabled"}
for ns in ("system", "secure", "global"):
    path = f"{snap}/settings-{ns}.txt"
    try:
        lines = open(path, encoding="utf-8", errors="replace")
    except FileNotFoundError:
        continue
    for raw in lines:
        raw = raw.rstrip("\n").rstrip("\r")
        if not raw or "=" not in raw:
            continue
        key, val = raw.split("=", 1)
        if key in skip:
            continue
        cmd = ["adb", "-s", serial, "shell", "settings", "put", ns, key, val]
        r = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if r.returncode != 0:
            print(f"warn: settings {ns} {key}", file=sys.stderr)
PY

if [[ -f "$snap/installed-by-us.txt" ]]; then
  while IFS= read -r pkg; do
    [[ -z "$pkg" || "$pkg" == \#* ]] && continue
    echo "uninstall $pkg"
    adb_tv uninstall "$pkg" || echo "warn: uninstall $pkg" >&2
  done < "$snap/installed-by-us.txt"
fi

if [[ "$with_kodi" == "1" ]]; then
  if [[ ! -d "$snap/kodi/.kodi" ]]; then
    echo "no kodi tree in $snap" >&2
    exit 1
  fi
  echo "restore kodi"
  tv_shell am force-stop org.xbmc.kodi
  adb_tv shell mkdir -p /sdcard/Android/data/org.xbmc.kodi/files
  adb_tv push "$snap/kodi/.kodi" /sdcard/Android/data/org.xbmc.kodi/files/
fi

assert_adb_still_up
echo "restored from $snap"
