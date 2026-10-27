#!/bin/bash
# Shared helpers for the MiTV-MOOR2 maintenance scripts.
set -euo pipefail

TV_ROOT="${TV_ROOT:-/home/foxx/xiaomi-tv-backup/mitv-moor2}"
ADB_SERIAL="${ADB_SERIAL:-192.168.200.240:5555}"
TV_MODEL="MiTV-MOOR2"

adb_tv() {
  adb -s "$ADB_SERIAL" "$@" </dev/null
}

tv_shell() {
  adb_tv shell "$@" | tr -d '\r'
}

require_tv() {
  local state model adb_on
  state="$(adb_tv get-state | tr -d '\r')"
  if [[ "$state" != "device" ]]; then
    echo "adb $ADB_SERIAL is $state" >&2
    exit 1
  fi
  model="$(tv_shell getprop ro.product.model)"
  if [[ "$model" != "$TV_MODEL" ]]; then
    echo "refusing $ADB_SERIAL: model is '$model'" >&2
    exit 1
  fi
  adb_on="$(tv_shell settings get global adb_enabled)"
  if [[ "$adb_on" != "1" ]]; then
    echo "adb_enabled is '$adb_on'; refusing to continue" >&2
    exit 1
  fi
}

assert_adb_still_up() {
  local state ip
  state="$(adb_tv get-state | tr -d '\r')"
  if [[ "$state" != "device" ]]; then
    echo "lost adb after the last step ($state)" >&2
    exit 1
  fi
  ip="$(tv_shell ip -4 -o addr show eth0)"
  echo "adb up, ethernet: $ip" >&2
}
