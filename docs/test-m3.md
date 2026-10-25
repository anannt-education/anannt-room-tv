# M3 phone remote: how to verify it on a real TV

No TV was reachable when M3 was written (and nothing was built locally: CI is the compiler), so this is the script to
run. Fill in `docs/zoom-launch-matrix.md` for the join routes as in `docs/test-m2.md`. Use a Zoom meeting you own and do
not commit its ID or passcode. `docs/remote-limits.md` says what each result should be and why.

## 0. Build and unit tests

```bash
./gradlew :protocol:test :tv:assembleDebug :phone:assembleDebug
```
`NavTest` (keys, signing, replay, stale, room name), `RateLimiterTest` (floods, refill, per-phone buckets) and
`JoinWatchTest` (joined, unconfirmed, fallback after 20 s) are the new ones. Expected: `BUILD SUCCESSFUL`.

## 1. Install and pair

```bash
scripts/install-tv.sh <tv-ip>
adb -s <tv-ip>:5555 logcat -s FreeMyTV      # keep this open
```
Install `phone/build/outputs/apk/debug/phone-debug.apk`, pair as in `docs/test-m1.md` section 3. Switch the TV to Free My
TV mode on the TV itself (Free My TV > Home Screen > Free My TV) for the Home tests.

TV facts to write down: `adb shell getprop ro.build.version.release`, and
`adb shell cmd role get-role-holders --user 0 android.app.role.HOME` (Free My TV should be the holder).

## 2. Nothing switched on: our own screens

On the phone: **Open the remote**. Expected: a green dot and "Connected to <TV>", and a note that Back, Home and arrows
over Zoom need Remote Control.

1. On the TV, Free My TV's home screen is showing. Press the phone's arrows and OK: focus moves and OK opens the tile.
   Log: `nav key 20 used by HomeActivity` (19 up, 20 down, 21 left, 22 right, 23 OK).
2. Hold an arrow for 2 s: it repeats about 7 times a second and the TV stays smooth.
3. Open Free My TV settings on the TV (the icon), use the phone's arrows, OK, Back: the settings screen follows.
4. Open Free My TV > Paired Phones, press the phone's Back: it closes.
5. Volume: press Vol + and Vol -. The TV's volume bar shows and moves. Mute toggles.
   `adb -s <tv-ip>:5555 shell dumpsys audio | grep -A4 "STREAM_MUSIC"` shows the level change. On a TV whose sound system
   owns the volume the phone says "This TV's volume is set by its sound system" (`volume-fixed`).

## 3. Over Zoom with nothing switched on

Send a test meeting from the phone and join it from the card (as in `docs/test-m2.md`). Zoom is in front.

1. Press an arrow on the phone: it says "Another app is open on the TV. To use Back, Home and arrows over it, turn on
   Remote Control" (`needs-remote-control`). Back says the same. Volume still works.
2. Press **Home** on the phone. Two results are both correct, write down which one you get:
   - the home screen comes forward (the overlay allowance was already on from M2), or
   - the phone says "Android didn't let the TV bring that app forward" (`blocked`) or, if the TV cannot see the front app,
     "Sent. This TV can't see which app is in front" (`unconfirmed`) and Zoom stays in front.
3. `scripts/remote-access.sh <tv-ip> overlay-on`, join again, press Home: expected, our home screen is in front.
   `scripts/remote-access.sh <tv-ip> overlay-off` puts it back.

## 4. Remote Control on

```bash
scripts/remote-access.sh <tv-ip> status      # "Remote Control: off", before
scripts/remote-access.sh <tv-ip> on
scripts/remote-access.sh <tv-ip> status      # "Remote Control: on"
adb -s <tv-ip>:5555 shell dumpsys accessibility | grep -i RoomControl
```
On the TV: Free My TV > **Remote Control** says "Remote Control is on" and shows both commands with this TV's address.
Settings > Accessibility lists "Anannt Room TV Remote". The phone, after reconnecting (or reopening the remote), no
longer shows the note about Remote Control.

With Zoom in front:
1. **Back**: Zoom goes back one screen. Log: no `nav key` line (it went to the system); the phone shows no error.
2. **Home**: our home screen comes forward even without the overlay allowance.
3. **Arrows and OK**: on Android 13+ they move focus in Zoom; write down what they do on Zoom's TV screens. On Android 12
   and older the phone says "This TV's Android is older than 13" (`needs-android-13`).
4. With Zoom closed and Free My TV in front, arrows still go to our own screen first (log `nav key ... used by`).
5. Other accessibility services are untouched: `adb shell settings get secure enabled_accessibility_services` lists them
   before and after `on` and `off`.

## 5. Return home, End meeting, Open Zoom

With Zoom in front and a meeting running:

1. Phone: **End meeting / return home**, confirm. Expected: our home screen is in front within about 2 s; the phone shows
   no error and "The meeting was ended."
2. Zoom is gone, not just hidden: `adb -s <tv-ip>:5555 shell pidof us.zoom.videomeetings` prints nothing a few seconds
   later (or `adb shell dumpsys activity processes | grep -i zoom` shows no running process). The camera light is off.
3. On a TV where Home is `blocked`, End meeting must **not** close Zoom: the phone shows the blocked hint and Zoom keeps
   running. Check this once with `overlay-off`, Remote Control off, usage access on (so the TV can see the front app).
4. **Open Zoom** on the phone with our home screen in front: Zoom opens. With Zoom already open: nothing changes, no
   error. With Zoom not installed (rename test only if you have a spare TV): "Zoom isn't installed on the TV."
5. Usage access, if wanted: `scripts/remote-access.sh <tv-ip> usage-on`. Then with Remote Control off, Home and Open Zoom
   report `blocked` or success instead of `unconfirmed`. If the TV's firmware has no usage events they stay
   `unconfirmed`; write that down.

## 6. The M2 behaviour changes

1. **Room name.** On the TV: Free My TV > **Room Name**: it shows "Anannt Room TV". Change it to "Room 4", Save. Send a
   meeting from the phone with the name box empty: in Zoom's participant list the room is "Room 4". Set it empty and
   Save: it is "Anannt Room TV" again. The phone's name box says "optional"; a name typed there still wins.
2. **Joining state.** Send a meeting, press OK. Expected: the card does not vanish; it reads "Joining Zoom…" with Cancel.
   Within 20 s Zoom comes forward. The phone's line goes "Joining…" then "Zoom is open on the TV."
3. **Fallback.** On a TV without Zoom installed (or where Zoom accepts neither link), press OK. Expected: the card
   switches at once to the large ID and passcode ("Zoom is not installed on this TV" or "Open Zoom, then enter..."), and
   the phone says "Zoom didn't take the link. The meeting ID and passcode are on the TV to type." When Zoom is installed
   but ignores the links, the same happens after 20 s (log `zoom state fallback (timeout)`).
4. **Cancel** on the TV during "Joining": the phone says "Cancelled on the TV."
5. **No front-app source** (Remote Control off, usage access off): after OK, Zoom opens, the card is covered, and the
   phone says "The TV handed the meeting to Zoom. It can't see the screen, so it can't confirm." (`unconfirmed`).
6. Log lines to expect: `room: zoom state joining (deep-link)`, then `joined` / `unconfirmed` / `fallback (timeout)`.
   They carry no meeting ID or passcode: `adb logcat -d -s FreeMyTV | grep -i -E "passcode|confno|pwd="` prints nothing.

## 7. Connection state and reconnecting

Watch the dot at the top of the remote.
1. Phone Wi-Fi off for 10 s, then on: orange "Reconnecting (try n), next in s s" with the reason, then green.
2. `adb -s <tv-ip>:5555 shell am force-stop io.freemytv`, wait 5 s, then
   `adb -s <tv-ip>:5555 shell am start -n io.freemytv/io.freemytv.SettingsActivity`: the phone shows retries, then
   reconnects by itself within about 15 s of the server coming back.
3. **Try now** reconnects at once.
4. Revoke the phone on the TV (Free My TV > Paired Phones): the phone turns red with "This phone was revoked on the TV.
   Scan a new code to pair again." and does **not** keep retrying.
5. Heartbeat: block the TV at the router for 25 s without closing the socket; the phone leaves green within about 30 s.

## 8. Floods and bad commands

- Hold the phone's arrow and Vol + for 10 s: the TV never lags and the log has no error. (The exact limits are in the
  unit tests, `RateLimiterTest`.)
- From a computer, an unsigned or forged command is refused as in M1: `curl -s -X POST http://<tv-ip>:8787/command -d '{}'`
  prints `{"error":"bad-json"}`.

## 9. Screenshots for the report

```bash
mkdir -p shots
adb -s <tv-ip>:5555 exec-out screencap -p > shots/m3-remote-access.png     # Free My TV > Remote Control
adb -s <tv-ip>:5555 exec-out screencap -p > shots/m3-card-joining.png
adb -s <tv-ip>:5555 exec-out screencap -p > shots/m3-room-name.png
```
Do not commit `shots/`.

## 10. Put it back

```bash
scripts/remote-access.sh <tv-ip> off
scripts/remote-access.sh <tv-ip> usage-off
scripts/remote-access.sh <tv-ip> overlay-off
scripts/restore-tv.sh <tv-ip>        # also switches Remote Control off if it was left on
```
The home screen itself is restored exactly as before: Free My TV > Home Screen > Original.
