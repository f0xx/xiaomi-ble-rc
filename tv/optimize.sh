#!/bin/bash
# Snapshot first, then disable the unused-package list and speed the apps we keep.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$HERE/common.sh"

PROTECTED='
com.google.android.apps.tv.launcherx
org.xbmc.kodi
com.google.android.youtube.tv
com.mitv.livetv
com.mitv.livetv.ci_mediatek
com.mediatek.tvinput
mitv.service
com.mediatek.tv.service
com.xiaomi.mitv.tvmanager
com.xiaomi.android.tvsetup.partnercustomizer
com.mediatek.android.leanbacklauncher.partnercustomizer
com.xiaomi.gtv.settings
com.android.tv.settings
com.xiaomi.mitv.scenemode
com.mitv.videoplayer
com.google.android.apps.mediashell
com.google.android.katniss
com.google.android.gms
com.android.shell
com.android.bluetooth
com.android.systemui
com.android.providers.settings
com.google.android.packageinstaller
com.android.inputdevices
com.android.vending
'

snap="$("$HERE/backup.sh")"
echo "using $snap"
: > "$snap/disabled-by-us.txt"
: > "$snap/installed-by-us.txt"

while IFS= read -r pkg; do
  [[ -z "$pkg" || "$pkg" == \#* ]] && continue
  if grep -qx "$pkg" <<< "$PROTECTED"; then
    echo "refuse protected $pkg" >&2
    exit 1
  fi
  echo "disable $pkg"
  if tv_shell pm disable-user --user 0 "$pkg"; then
    echo "$pkg" >> "$snap/disabled-by-us.txt"
  else
    echo "warn: disable $pkg" >&2
  fi
done < "$HERE/unused-packages.txt"

echo "animations off"
tv_shell settings put global window_animation_scale 0
tv_shell settings put global transition_animation_scale 0
tv_shell settings put global animator_duration_scale 0

for pkg in org.xbmc.kodi com.google.android.apps.tv.launcherx com.mitv.livetv com.mitv.livetv.ci_mediatek; do
  echo "keep warm $pkg"
  tv_shell cmd deviceidle whitelist "+$pkg" || true
  tv_shell cmd package compile -m speed-profile -f "$pkg" || echo "warn: compile $pkg" >&2
done

apk="${SMARTTUBE_APK:-/tmp/smarttube/SmartTube_stable_32.68_armeabi-v7a.apk}"
if [[ ! -f "$apk" ]]; then
  echo "missing $apk" >&2
  exit 1
fi
echo "install smarttube"
adb_tv install -r "$apk"
echo "org.smarttube.stable" >> "$snap/installed-by-us.txt"
cp -n "$apk" "$snap/SmartTube.apk" || true

for comp in \
  org.smarttube.stable/com.liskovsoft.smartyoutubetv2.common.misc.RemoteControlReceiver \
  org.smarttube.stable/com.liskovsoft.leanbackassistant.channels.UpdateChannelsReceiver
do
  echo "no boot $comp"
  tv_shell pm disable "$comp" || echo "warn: $comp" >&2
done
tv_shell am set-standby-bucket org.smarttube.stable rare || true
tv_shell cmd appops set org.smarttube.stable RUN_IN_BACKGROUND ignore || true
tv_shell cmd appops set org.smarttube.stable RUN_ANY_IN_BACKGROUND ignore || true

home="$(tv_shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME)"
if ! grep -q 'com.google.android.apps.tv.launcherx' <<< "$home"; then
  echo "home is no longer the Google TV launcher" >&2
  echo "$home" >&2
  exit 1
fi

tv_shell 'ip -4 -br addr; echo ---; cmd wifi status' > "$snap/network-after.txt"
assert_adb_still_up
echo "done $snap"
