#!/usr/bin/env bash
# Puts the TV's own home screen back. Two layers, both reversible:
#   1. If the TV was switched on the TV itself (Free My TV > Home Screen > Free My TV), the same screen's *Original*
#      brings back the home screen, the screensaver and every app it turned off. This script opens that screen.
#   2. If scripts/install-tv.sh --set-home was used, the home role is handed back here to the activity it logged.
#
#   scripts/restore-tv.sh <tv-ip> [--uninstall]
#
#   --uninstall  also remove Free My TV, after the restore. Only do this once the TV shows its own home screen again.
set -euo pipefail
cd "$(dirname "$0")/.."

ip="${1:-}"
[ -n "$ip" ] || { sed -n 2,10p "$0" | sed 's/^# \{0,1\}//'; exit 2; }
shift
uninstall=false
[ "${1:-}" = "--uninstall" ] && uninstall=true
command -v adb >/dev/null || { echo "adb not found." >&2; exit 1; }

target="$ip"; case "$ip" in *:*) ;; *) target="$ip:5555" ;; esac
adb connect "$target" | sed 's/^/  /'
adb -s "$target" wait-for-device

log="scripts/.restore/$ip.txt"
if [ -f "$log" ]; then
  stock="$(grep '^stock_home_activity=' "$log" | cut -d= -f2-)"
  echo "install-tv.sh --set-home was used ($log). Handing the home role back to: ${stock:-the system default}"
  if [ -n "$stock" ]; then
    adb -s "$target" shell "pm enable ${stock%%/*}" | sed 's/^/  /' || true
    adb -s "$target" shell "cmd package set-home-activity '$stock'" | sed 's/^/  /'
  fi
  adb -s "$target" shell cmd role remove-role-holder --user 0 android.app.role.HOME io.freemytv 2>/dev/null | sed 's/^/  /' || true
  mv "$log" "$log.restored-$(date +%Y%m%d-%H%M%S)"
fi

# The optional Remote Control service (M3) goes back off, with the other accessibility services left as they were.
if adb -s "$target" shell settings get secure enabled_accessibility_services | tr -d '\r' | grep -q RoomControlService; then
  echo "Switching Remote Control off."
  scripts/remote-access.sh "$ip" off | sed 's/^/  /' || true
fi

# M5: undo install-tv.sh --disable-youtube. The marker on the TV (and the log) name the package; only the YouTube
# packages Room TV knows are ever enabled from here.
ytlog="scripts/.restore/$ip.youtube.txt"
marker="$(adb -s "$target" shell settings get global anannt_room_youtube_disabled 2>/dev/null | tr -d '\r')"
if [ -z "$marker" ] || [ "$marker" = "null" ]; then
  marker=""
  [ -f "$ytlog" ] && marker="$(grep '^command_applied=' "$ytlog" | sed 's/.*disable-user --user 0 //')"
fi
if [ -n "$marker" ]; then
  for pkg in $(echo "$marker" | tr ',' ' '); do
    case "$pkg" in
      com.google.android.youtube.tv|com.google.android.youtube)
        echo "Turning the YouTube app back on: adb shell pm enable $pkg"
        adb -s "$target" shell pm enable "$pkg" | sed 's/^/  /' || true ;;
      *) echo "Ignoring an unknown package in the marker: $pkg" >&2 ;;
    esac
  done
  adb -s "$target" shell settings delete global anannt_room_youtube_disabled >/dev/null 2>&1 || true
  [ -f "$ytlog" ] && mv "$ytlog" "$ytlog.restored-$(date +%Y%m%d-%H%M%S)"
fi
yt_off="$(adb -s "$target" shell pm list packages -d 2>/dev/null | tr -d '\r' | grep -i 'com.google.android.youtube' || true)"
if [ -n "$yt_off" ]; then
  echo "YouTube is still DISABLED on this TV: $yt_off"
  echo "  put it back with: adb -s $target shell pm enable <package>"
else
  echo "The YouTube app is enabled."
fi

echo "Now the TV's own switch: on the TV, Free My TV > Home Screen > Original (opening it for you)."
adb -s "$target" shell am start -n io.freemytv/io.freemytv.SettingsActivity >/dev/null || true
echo "Watch it finish: adb -s $target logcat -s FreeMyTV"
echo "Check:"
adb -s "$target" shell cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null | sed 's/^/  home role: /' || true
adb -s "$target" shell settings get secure screensaver_components | sed 's/^/  screensaver: /'

if $uninstall; then
  holder="$(adb -s "$target" shell cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null | tr -d '\r')"
  if echo "$holder" | grep -q io.freemytv; then
    echo "Free My TV is still the home screen: pick Original on the TV first, then run this again with --uninstall." >&2
    exit 1
  fi
  adb -s "$target" uninstall io.freemytv | sed 's/^/  /'
fi
