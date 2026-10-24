# M2 Zoom push: how to verify it on a real TV

No TV was reachable when M2 was written, so this is the script to run. Fill `docs/zoom-launch-matrix.md` as you go.
Use a meeting you own (a Zoom you scheduled for the test); do not put a real meeting ID or passcode in the repo, in
screenshots you commit, or in a bug report. The app never logs them.

## 0. Build and unit tests

```bash
./gradlew :protocol:test :tv:assembleDebug :phone:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. `ZoomParserTest` and `ZoomJoinTest` cover links (`zoom.us/j`, `/w`, `/wc`, `/s`, regional
and vanity subdomains, `zoommtg://`, `zoomus://`, no scheme), invite text (Meeting ID, Passcode, spaced or dashed
IDs), look-alike hosts, and the link builders.

## 1. Install (same Wi-Fi as the phone)

```bash
scripts/install-tv.sh <tv-ip>
adb -s <tv-ip>:5555 logcat -s FreeMyTV      # keep this open
```
Install `phone/build/outputs/apk/debug/phone-debug.apk` on the phone and pair it as in `docs/test-m1.md` section 3.

## 2. Which Zoom is on the TV

```bash
adb -s <tv-ip>:5555 shell pm list packages | grep -i zoom
adb -s <tv-ip>:5555 shell dumpsys package us.zoom.videomeetings | grep -E "versionName|versionCode"
```
Nothing printed means Zoom is not installed: install the official Zoom app from the TV's store (or sideload the
official APK), then repeat. Put the package names in the matrix.

Who handles each link (read-only):
```bash
adb -s <tv-ip>:5555 shell cmd package query-activities --brief -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "zoomus://zoom.us/join?confno=1"
adb -s <tv-ip>:5555 shell cmd package query-activities --brief -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "https://zoom.us/j/1"
```
A Zoom activity in the first answer means route 1 can work. Only a browser (or nothing) in the second means route 2 will
be skipped on this TV; that is a result, not a bug.

## 3. Let the card open over other apps (once)

Android 10+ can refuse an activity started from the background. Free My TV's card opens fine while Free My TV is on
screen; to let it open over Zoom or YouTube:
```bash
adb -s <tv-ip>:5555 shell appops set io.freemytv SYSTEM_ALERT_WINDOW allow
adb -s <tv-ip>:5555 shell appops get io.freemytv SYSTEM_ALERT_WINDOW
```
Test both ways (allowed, then `ignore`) and write the difference in the matrix. If the card is refused the log says
`room: could not open the meeting card` and the phone shows "The TV couldn't show the card".

## 4. Each link by hand, from the computer

Use your test meeting. `<id>` is the digits only, `<pwd>` the `?pwd=` token from its link.

```bash
# route 1: the Zoom deep link
adb -s <tv-ip>:5555 shell "am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d 'zoomus://zoom.us/join?confno=<id>&pwd=<pwd>&uname=Room%20TV'"
# the same aimed at one package
adb -s <tv-ip>:5555 shell "am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -p us.zoom.videomeetings -d 'zoomus://zoom.us/join?confno=<id>&pwd=<pwd>&uname=Room%20TV'"
# route 2: the https link, aimed at the Zoom package
adb -s <tv-ip>:5555 shell "am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -p us.zoom.videomeetings -d 'https://zoom.us/j/<id>?pwd=<pwd>'"
# route 3: just open Zoom
adb -s <tv-ip>:5555 shell monkey -p us.zoom.videomeetings -c android.intent.category.LEANBACK_LAUNCHER 1
```
`Error: Activity not started, unable to resolve Intent` means that route has no handler on this TV. Press Home on the
remote between tests. Record each outcome in the matrix.

## 5. From the phone

1. Open a Zoom invite in Mail or WhatsApp, tap **Share**, pick **Send to Room TV**. The meeting ID shows on the phone;
   tap **Send to Room TV**. Expected on the phone: "On the TV now. Press OK there to join."
2. Expected on the TV: the **Meeting ready** card, "Sent by <phone name>", the meeting ID in large type, **Press OK to
   join** focused, **Cancel**. D-pad left and right move between the two buttons; OK on the first joins.
3. Press OK. Expected log line: `zoom: route=deep-link package=us.zoom.videomeetings` (or whichever route worked). The
   card closes when route 1 or 2 was accepted. If Zoom opens on its own only (route 3), the card stays under it:
   press Back in Zoom to return to the card, which shows the ID and passcode in large type and **Open Zoom**.
4. Same from the app's own box: open Anannt Room TV, paste a link or invite into **Zoom link or invite text** (or tap
   **Paste**), tap **Send to Room TV**.
5. **Join by itself after 10 seconds** (off by default): tick it, send, and watch "Joining in 10 s" count down on the
   TV. Cancel stops it.
6. Check the card is not logged: `adb logcat -d -s FreeMyTV | grep -i -E "passcode|confno|pwd="` prints nothing.
7. Revoke the phone on the TV (Free My TV > Paired Phones), send again: the phone says it was revoked and the TV shows
   no card.

Bad input, expected: a pasted text with no Zoom ID shows "No Zoom meeting ID found in this text." and the send button
stays off. A forged or stale command is refused by the M1 checks before it gets near the card.

## 6. Screenshots for the report

```bash
mkdir -p shots
adb -s <tv-ip>:5555 exec-out screencap -p > shots/m2-card-ready.png
adb -s <tv-ip>:5555 exec-out screencap -p > shots/m2-card-manual.png     # route 3 state
adb -s <tv-ip>:5555 exec-out screencap -p > shots/m2-zoom-joined.png
```
Look at each before sharing: the ID and passcode are on screen. Do not commit `shots/` (use a throwaway meeting).

## 7. Put it back

Nothing in M2 changes the home screen. To remove everything: `scripts/restore-tv.sh <tv-ip>` (and `--uninstall`).
Undo the overlay allowance with `adb shell appops set io.freemytv SYSTEM_ALERT_WINDOW default`.
