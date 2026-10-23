#!/usr/bin/env bash
# Installs the Room TV build of Free My TV on a Google TV / Android TV over the network, and opens its settings so the
# person switches the home screen on the TV itself (Free My TV > Home Screen > Free My TV): that switch records what
# it changes and is what *Original* undoes. Nothing on the TV is disabled from here.
#
#   scripts/install-tv.sh <tv-ip> [--apk path] [--set-home] [--pair]
#
#   --apk       the APK to install (default: tv/build/outputs/apk/debug/tv-debug.apk, built by
#               ./gradlew :tv:assembleDebug)
#   --set-home  also make Free My TV the home screen straight from here, with plain `cmd package set-home-activity`.
#               Lighter than the in-app switch (no screensaver, nothing turned off) and logged to
#               scripts/.restore/<ip>.txt so scripts/restore-tv.sh can undo it. Prefer the switch on the TV.
#   --pair      open the Pair a Phone screen after installing.
set -euo pipefail
cd "$(dirname "$0")/.."

ip="${1:-}"
[ -n "$ip" ] || { sed -n 2,14p "$0" | sed 's/^# \{0,1\}//'; exit 2; }
shift
apk="tv/build/outputs/apk/debug/tv-debug.apk"
set_home=false
pair=false
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) apk="$2"; shift 2 ;;
    --set-home) set_home=true; shift ;;
    --pair) pair=true; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools (brew install --cask android-platform-tools)." >&2; exit 1; }
[ -f "$apk" ] || { echo "No APK at $apk. Build it first: ./gradlew :tv:assembleDebug" >&2; exit 1; }

target="$ip"; case "$ip" in *:*) ;; *) target="$ip:5555" ;; esac
echo "Connecting to $target (the TV may ask 'Allow USB debugging?': accept and tick 'Always allow')"
adb connect "$target" | sed 's/^/  /'
adb -s "$target" wait-for-device
brand="$(adb -s "$target" shell getprop ro.product.brand | tr -d '\r')"
model="$(adb -s "$target" shell getprop ro.product.model | tr -d '\r')"
release="$(adb -s "$target" shell getprop ro.build.version.release | tr -d '\r')"
echo "TV: $brand $model, Android $release"

echo "Installing $apk"
adb -s "$target" install -r "$apk" | sed 's/^/  /'

# TCL: background jobs (the mode switch, the watchdog) are blocked until the app may auto-start (see CLAUDE.md).
if [ "$(echo "$brand" | tr '[:upper:]' '[:lower:]')" = "tcl" ]; then
  adb -s "$target" shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow" 2>/dev/null | sed 's/^/  /' || true
fi

if $set_home; then
  mkdir -p scripts/.restore
  log="scripts/.restore/$ip.txt"
  current="$(adb -s "$target" shell cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null | tr -d '\r' | head -1)"
  stock="$(adb -s "$target" shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null \
            | tr -d '\r' | grep -E '^\s+[a-zA-Z0-9_.]+/' | grep -v '^\s*io.freemytv/' | head -1 | tr -d ' ')"
  {
    echo "date=$(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "tv=$brand $model Android $release"
    echo "home_role_holder_before=$current"
    echo "stock_home_activity=$stock"
    echo "command_applied=cmd package set-home-activity io.freemytv/io.freemytv.HomeActivity"
    echo "restore_command=cmd package set-home-activity $stock"
  } > "$log"
  echo "Setting the home screen (logged to $log; undo with scripts/restore-tv.sh $ip)"
  adb -s "$target" shell cmd package set-home-activity io.freemytv/io.freemytv.HomeActivity | sed 's/^/  /'
fi

if $pair; then
  adb -s "$target" shell am start -n io.freemytv/io.freemytv.room.PairingActivity >/dev/null
  echo "The TV shows the pairing code."
else
  adb -s "$target" shell am start -n io.freemytv/io.freemytv.SettingsActivity >/dev/null
  echo "The TV shows Free My TV's settings. On the TV: Home Screen > Free My TV to switch; Pair a Phone for the code."
fi
echo "Log: adb -s $target logcat -s FreeMyTV"
