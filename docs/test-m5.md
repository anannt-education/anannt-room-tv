# M5 polish: how to verify it on a real TV

Nothing was built or run locally when M5 was written (no JDK or Android SDK on that Mac; CI is the compiler). This is the
script to run. `<tv-ip>` is the TV's address; `T=<tv-ip>:5555`. Write the results next to each step.

## 0. Build and unit tests

```bash
./gradlew :protocol:test :tv:assembleDebug :phone:assembleDebug
```
New tests (all in `:protocol`, JVM): `TodayMeetingsTest` (the day in a time zone and across a clock change, link in the
location, notes, HTML notes and title, bare numbers, declined, cancelled, all-day, overnight, duplicates, next),
`SessionTest` (protocol v3, a session round trip with only title and start, cleaning, past and too far, clearing at the
start, today or tomorrow in the TV's zone), `GuestTest` (token expiry, single use, replacement, strikes, rights, a guest token
cannot sign or pair, rate limits, oversized input, busy, the page escaping) and `BackoffTest` (steps, cap, jitter,
no overflow, when to look the TV up again). `YouTubeCommandsTest` now expects version 3. Expected: `BUILD SUCCESSFUL`.

## 1. Install, pair, and the home shelf

```bash
scripts/install-tv.sh <tv-ip>
adb -s $T logcat -s FreeMyTV            # keep this open
```
Install `phone/build/outputs/apk/debug/phone-debug.apk`, pair as in `docs/test-m1.md`, switch the TV to Free My TV mode on the
TV itself.

1. The shelf reads: first app (Netflix, if installed), **Videos** (second, where YouTube sat), the other apps, and **Scan to
   send a meeting** last. Nothing else moved. Hold OK on a card, move one, drop it: no crash, Videos stays second after a restart.
2. `adb -s $T shell pm list packages -d | grep -i youtube` prints nothing (this install did not disable anything).
3. Hardware check of the layout: is there room for the line along the bottom edge (section 3)? Screenshot the home screen.

## 2. Today's meetings on the phone (a)

Put three events in the phone's calendar for today: one with `https://us02web.zoom.us/j/86123456789?pwd=abc` as the location,
one whose notes hold the text of a Zoom invite (`Meeting ID: 861 2345 6789`, `Passcode: x1y2z3`), one with no Zoom at all, and
an all-day event with a Zoom link. Make a fifth that you decline.

1. Fresh install, calendar permission **not** granted. Use the whole app first: pair, remote, paste a link. Nothing asks for the
   calendar. Open **Today's meetings**: the explanation appears, no system dialog yet.
2. **Allow calendar access**: the system dialog (calendar only). Allow: the list shows the first two events with their times
   (over ones marked "(over)"). The no-Zoom, all-day and declined events are not listed.
3. Tap **Send to Room TV** on one: the phone says "On the TV now. Press OK there to join." and the TV shows the Meeting ready
   card as in M2. Back on the TV rejects it.
4. Deny test: `adb shell pm revoke io.anannt.roomtv.phone android.permission.READ_CALENDAR`, open Today: the explanation and
   "Calendar access is off. The rest of the app works without it." with **Open the phone's settings**. Pairing, remote, paste and
   YouTube still work. Grant it again (`pm grant ...` or the dialog).
5. Revoke while the screen is open (settings, then back): the screen returns to the explanation, no crash.
6. A recurring daily meeting shows today's occurrence only, at today's time. Switch the phone's time zone (Settings > Date) and
   come back: Refresh moves the day.
7. Confirm it is read only: `adb shell dumpsys package io.anannt.roomtv.phone | grep -i calendar` lists `READ_CALENDAR` and no
   `WRITE_CALENDAR`.

## 3. Next session on the TV home screen (b)

1. On the Today screen the card "On the TV's home screen" shows the exact line: "Next session: <title> at <time>". Tap
   **Show on the TV**: the phone says "Shown on the TV's home screen." Press Home on the TV: along the bottom edge, "Next
   session: <title> at <time>" in the TV's language and time format. Screenshot:
   `adb -s $T exec-out screencap -p > docs/m5-next-session.png` (do not commit one that shows real titles).
2. Tick **Hide the title**, Show again: the TV says "Next session: Session at <time>".
3. Tick **Keep the TV up to date while this app is open**; change the next event in the calendar and Refresh: the TV follows.
4. **Clear**: the line goes within 10 s. `adb -s $T shell run-as io.freemytv ls files/` has no `room-home.json` (debug build).
5. A session a minute away: the line disappears by itself within about 25 s of the start time (the overlay reads every 10 s,
   the TV clears the file every 15 s). A session already started is refused: "That meeting has already started."
6. What is on the TV: `adb -s $T shell run-as io.freemytv cat files/room-home.json` is exactly `{"nextTitle":"...","nextStartMs":...}`.
7. Restart: `adb -s $T shell am force-stop io.freemytv`, press Home: the line is back (until the start time).
8. Old TV: with the M4 APK on the TV and the M5 phone, Today's **Show on the TV** says "This TV needs the latest Room TV update
   to show sessions." and Zoom, remote and YouTube still work (protocol v2).

## 4. Guest QR (c)

1. On the shelf open **Scan to send a meeting**: a QR, the text "Scan with the phone's camera. No app needed. Same Wi-Fi as this
   TV." and a button "Turn guest sending off".
2. With a phone that does **not** have the companion app, on the same Wi-Fi: scan it with the camera. A page opens with a box.
   Paste `https://zoom.us/j/86123456789?pwd=abc`, a name, **Send to the TV**. The phone says "Sent". The TV shows Meeting ready,
   "Sent by <name> (guest)" (or "Guest"). The TV does not join by itself: Back rejects it, OK joins (as M2).
3. The page again (back button, reload): "This code was already used." The QR on the TV is a different one now (screenshot).
4. Send nonsense: "That doesn't look like a Zoom link or invite." with the box still there; three nonsense sends in a row burn the
   code ("already used").
5. Wait 5 minutes without scanning: the QR on screen changes by itself; a page opened from the old code says "This code has run out."
6. While a Meeting ready card is on screen, a guest send says "The TV is showing another meeting. Try again in a moment." and the
   card is not replaced.
7. Rights, from the computer (`<ip>` the TV, `<tok>` a live token read from the QR):
```bash
curl -s -o /dev/null -w '%{http_code}\n' http://<tv-ip>:8787/guest/send -d 't=wrong&text=x'        # 403
curl -s -o /dev/null -w '%{http_code}\n' http://<tv-ip>:8787/control                                 # 400/426: needs a signed command
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://<tv-ip>:8787/command -d '{}'                 # 400: not a signed envelope
python3 - <<'PY'
import urllib.request
r = urllib.request.Request("http://<tv-ip>:8787/guest/send", data=b"t=<tok>&text=" + b"x"*20000, method="POST")
try: urllib.request.urlopen(r)
except Exception as e: print(e)                                                                       # 413
PY
```
   A guest token never opens a socket session, a key press, the Videos list or the PIN: none of those routes accept it.
8. Rate limit: send 10 wrong requests quickly from one phone: the later ones say "Too many tries. Wait a minute and try again."
9. Off switch: press **Turn guest sending off**: the QR is replaced by "Guest sending is off." and the old page says "Guest sending
   is switched off on this TV." Turn it on: a new code appears.
10. Logs: `adb -s $T logcat -d -s FreeMyTV | grep -i guest` shows "a guest proposed a meeting" or the refusal word, never the link,
    the passcode or the token.

## 5. Reconnect (d)

Keep the phone's Home screen open and read its status card.
1. **Wi-Fi drop**: switch the phone's Wi-Fi off for 20 s, on again. The card goes "Reconnecting…" with "Trying again in N s
   (attempt k)", then "Connected to Room TV" within a second or two of Wi-Fi coming back (the network callback).
2. **TV reboot**: `adb -s $T reboot`. The phone shows Reconnecting with the countdown while the TV is down, and reconnects
   by itself after the TV is back (the foreground service starts at boot; give it a minute).
3. **Address change**: give the TV another address (reserve a different one in the router and renew, or switch the TV
   from Wi-Fi to Ethernet) while the phone shows Reconnecting. After the second failure it says "Looking for the TV on this
   network…", then connects at the new address (`adb logcat -s RoomTV` on the phone: "the TV moved to ..."). Settings are not
   needed. If the phone and TV are on different VLANs or the router blocks mDNS, this cannot work: say so in the report.
4. **Keepalive**: leave the phone connected 10 minutes with the screen on: it stays "Connected", round trip under about 100 ms.
   Pull the TV's Ethernet or Wi-Fi: the phone notices within about 20 s.
5. **Server survives restarts**: `adb -s $T shell am force-stop io.freemytv`, then `adb -s $T shell am start -n
   io.freemytv/io.freemytv.SettingsActivity`; the phone reconnects. Then `adb -s $T shell am kill io.freemytv` (background
   kill): the log shows `room: serving on ...` again within a few seconds of the next start, or 15 s at the latest
   (`room: the server on ... did not answer; binding it again` if the listener had died).
6. Multiple attempts do not stack: tap **Reconnect** ten times quickly: one socket, no flicker loop.

## 6. The optional YouTube step (e)

Off by default; run only if you want YouTube gone from the TV.
```bash
scripts/install-tv.sh <tv-ip> --disable-youtube
```
1. It prints the log path and `adb -s <tv-ip>:5555 shell pm enable com.google.android.youtube.tv`. `cat scripts/.restore/<tv-ip>.youtube.txt`
   shows `command_applied`, `restore_command` and the marker.
2. `adb -s $T shell pm list packages -d | grep youtube` lists `com.google.android.youtube.tv`;
   `adb -s $T shell settings get global anannt_room_youtube_disabled` prints the package.
3. Nothing but Videos opens YouTube content now. An approved video the in-app player refuses (embedding off) can no longer
   fall back to the YouTube app: the TV says so ("The YouTube app is not installed on this TV.").
4. **Undo from the TV**: Free My TV > Home Screen > **Original**. When it finishes, `pm list packages -d | grep youtube` is empty and
   the setting reads `null`. (Run step 1 again first if you want to test the other path.)
5. **Undo from the computer**: `scripts/restore-tv.sh <tv-ip>` prints "Turning the YouTube app back on: adb shell pm enable
   com.google.android.youtube.tv", then "The YouTube app is enabled.", and renames the log to `*.restored-<time>`.
6. Without the flag nothing changes: `install-tv.sh <tv-ip>` run alone never touches YouTube.

## 7. Restore still works

`scripts/restore-tv.sh <tv-ip>` and Free My TV > Home Screen > Original return the TV's own home screen, as in `docs/test-m1.md`.
Room TV adds no other change to undo: the guest page, the overlay and the calendar need nothing restored; the home card file
is app-private and goes with the app.

## 8. What to send back

For the report: the TV model and Android version; whether the bottom line and the last tile look right on this TV (screenshots);
which calendar apps and accounts the phone used; whether mDNS found the TV after an address change; the result of every numbered
step above that did not behave as written.
