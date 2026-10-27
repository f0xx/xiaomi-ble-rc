#!/bin/bash
# Snapshot settings, package state, and Kodi onto a date-tagged folder.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$HERE/common.sh"
require_tv

stamp="$(date +%Y-%m-%d-%H%M%S)"
dest="$TV_ROOT/$stamp"
mkdir -p "$dest/kodi"

echo "snapshot $dest" >&2
tv_shell getprop > "$dest/getprop.txt"
tv_shell settings list system > "$dest/settings-system.txt"
tv_shell settings list secure > "$dest/settings-secure.txt"
tv_shell settings list global > "$dest/settings-global.txt"
tv_shell pm list packages -f -u > "$dest/packages-all.txt"
tv_shell pm list packages -d > "$dest/packages-disabled.txt"
tv_shell dumpsys deviceidle > "$dest/deviceidle.txt"
tv_shell 'cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME' > "$dest/home.txt"
tv_shell 'ip -4 -br addr; echo ---; cmd wifi status' > "$dest/network.txt"

echo "kodi" >&2
adb_tv pull /sdcard/Android/data/org.xbmc.kodi/files/.kodi "$dest/kodi/.kodi"

cat > "$dest/WHAT-THIS-IS.txt" << EOF
MiTV-MOOR2 snapshot $stamp
Serial: $ADB_SERIAL
Taken as shell, without root. Firmware partitions are not in this folder.
Kodi copy is /sdcard/Android/data/org.xbmc.kodi/files/.kodi (the library on shared storage).
Restore package enablement and settings with:
  $HERE/restore.sh $dest
Restore that Kodi tree as well with:
  $HERE/restore.sh --kodi $dest
This script never turns adb_enabled off.
EOF

ln -sfn "$dest" "$TV_ROOT/latest"
echo "$dest"
assert_adb_still_up
