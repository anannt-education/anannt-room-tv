#!/usr/bin/env bash
# The optional pieces of the phone remote that Android only lets a person switch on from outside the app. All of them
# are off until you run this, and each has an "off" that puts the TV back as it was. Nothing here needs root.
#
#   scripts/remote-access.sh <tv-ip> on|off|status      Remote Control: lets the phone press Back, Home and the arrows
#                                                        while Zoom or another app is in front (an accessibility
#                                                        service; it reads no screen content).
#   scripts/remote-access.sh <tv-ip> usage-on|usage-off  Usage access: lets the TV read which app is in front, so it can
#                                                        confirm that Zoom opened (a package name, nothing else).
#   scripts/remote-access.sh <tv-ip> overlay-on|overlay-off
#                                                        "Display over other apps": lets "Return home" and the Meeting
#                                                        card open while another app is in front.
#
# Android switches accessibility services off when the app is updated: run `on` again after installing a new build.
# docs/remote-limits.md explains what each one can and cannot do.
set -euo pipefail
cd "$(dirname "$0")/.."

ip="${1:-}"; action="${2:-}"
[ -n "$ip" ] && [ -n "$action" ] || { sed -n 2,17p "$0" | sed 's/^# \{0,1\}//'; exit 2; }
command -v adb >/dev/null || { echo "adb not found." >&2; exit 1; }

target="$ip"; case "$ip" in *:*) ;; *) target="$ip:5555" ;; esac
adb connect "$target" >/dev/null
adb -s "$target" wait-for-device
tvsh() { adb -s "$target" shell "$@" | tr -d '\r'; }

svc="io.freemytv/io.freemytv.room.RoomControlService"
short="io.freemytv/.room.RoomControlService"
note_dir="scripts/.restore"
note="$note_dir/$ip-accessibility.txt"

# The enabled list without our entry, so other services (TalkBack, say) are kept exactly as they are.
others() {
  local current part out=""
  current="$(tvsh settings get secure enabled_accessibility_services)"
  [ "$current" = "null" ] && current=""
  IFS=':' read -ra parts <<< "$current"
  for part in "${parts[@]:-}"; do
    [ -z "$part" ] && continue
    [ "$part" = "$svc" ] || [ "$part" = "$short" ] && continue
    case "$part" in *[!A-Za-z0-9_.\$/-]*) echo "Unexpected entry in the accessibility list; leaving it alone." >&2; exit 1 ;; esac
    out="${out:+$out:}$part"
  done
  printf '%s' "$out"
}

case "$action" in
  on)
    rest="$(others)"
    mkdir -p "$note_dir"
    if [ ! -f "$note" ]; then
      {
        echo "date=$(date '+%Y-%m-%d %H:%M:%S %Z')"
        echo "enabled_accessibility_services_before=$(tvsh settings get secure enabled_accessibility_services)"
        echo "accessibility_enabled_before=$(tvsh settings get secure accessibility_enabled)"
      } > "$note"
    fi
    tvsh "settings put secure enabled_accessibility_services '${rest:+$rest:}$svc'"
    tvsh settings put secure accessibility_enabled 1
    echo "Remote Control is on. The TV's Free My TV > Remote Control screen shows it."
    ;;
  off)
    rest="$(others)"
    if [ -n "$rest" ]; then
      tvsh "settings put secure enabled_accessibility_services '$rest'"
    else
      tvsh settings delete secure enabled_accessibility_services >/dev/null
      before="$(grep '^accessibility_enabled_before=' "$note" 2>/dev/null | cut -d= -f2- || true)"
      [ "$before" = "0" ] || [ "$before" = "null" ] && tvsh settings put secure accessibility_enabled 0
    fi
    [ -f "$note" ] && mv "$note" "$note.restored-$(date +%Y%m%d-%H%M%S)"
    echo "Remote Control is off."
    ;;
  status)
    echo "enabled accessibility services: $(tvsh settings get secure enabled_accessibility_services)"
    if tvsh settings get secure enabled_accessibility_services | grep -q "RoomControlService"; then echo "Remote Control: on"; else echo "Remote Control: off"; fi
    echo "usage access: $(tvsh appops get io.freemytv GET_USAGE_STATS | head -1)"
    echo "display over other apps: $(tvsh appops get io.freemytv SYSTEM_ALERT_WINDOW | head -1)"
    ;;
  usage-on) tvsh appops set io.freemytv GET_USAGE_STATS allow;     echo "Usage access: allowed." ;;
  usage-off) tvsh appops set io.freemytv GET_USAGE_STATS default;   echo "Usage access: back to the default (off)." ;;
  overlay-on) tvsh appops set io.freemytv SYSTEM_ALERT_WINDOW allow;   echo "Display over other apps: allowed." ;;
  overlay-off) tvsh appops set io.freemytv SYSTEM_ALERT_WINDOW default; echo "Display over other apps: back to the default (off)." ;;
  *) echo "Unknown action: $action (on, off, status, usage-on, usage-off, overlay-on, overlay-off)" >&2; exit 2 ;;
esac
