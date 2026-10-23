# M1 pairing: how to verify it on a real TV

No TV was reachable when M1 was written, so this is the script to run. Everything here is read-only on the TV except
installing the APK and the pairing data the app writes for itself. Times in IST where it matters.

## 0. Build

```bash
./gradlew :protocol:test :tv:assembleDebug :phone:assembleDebug
```
Expected: `BUILD SUCCESSFUL`, protocol tests green (token expiry, single use, HMAC, replay, QR payload),
`tv/build/outputs/apk/debug/tv-debug.apk` and `phone/build/outputs/apk/debug/phone-debug.apk`.

## 1. Install on the TV (same Wi-Fi as the phone)

```bash
scripts/install-tv.sh <tv-ip>            # adb connect, install, opens Free My TV's settings
adb -s <tv-ip>:5555 logcat -s FreeMyTV   # keep this open in a second terminal
```
On the TV accept *Allow USB debugging?* (tick *Always allow*). Do not use `--set-home` for this test: pairing works
in either home mode.

Expected in the log within a few seconds of the settings screen opening:
```
room: serving on http://192.168.x.y:8787 (tv tv-..., "<TV name>")
room: advertised as "Anannt Room TV <name>" (_anannttv._tcp, port 8787)
```
If instead `room: no private IPv4 address`, the TV is not on Wi-Fi or Ethernet.

## 2. The server from the computer (before any phone)

```bash
curl -s http://<tv-ip>:8787/status
```
Expected: `{"v":1,"tvId":"tv-...","tvName":"...","serverTime":...,"pairedPhones":0}`.

```bash
curl -s -X POST http://<tv-ip>:8787/pair -H 'content-type: application/json' \
  -d '{"v":1,"deviceId":"curl","deviceName":"curl","clientNonce":"n","proof":"x"}'
```
Expected: HTTP 401 `{"error":"bad-or-expired-token"}` and a log line `room: pairing refused (bad-or-expired-token)`.

```bash
curl -s -X POST http://<tv-ip>:8787/command -H 'content-type: application/json' \
  -d '{"v":1,"deviceId":"nobody","ts":0,"nonce":"n","type":"ping","body":"{}","sig":"x"}'
```
Expected: HTTP 401 `{"error":"unknown-device"}`.

## 3. The QR on the TV

On the TV: *Free My TV* › *Pair a Phone* (or `adb shell am start -n io.freemytv/io.freemytv.room.PairingActivity`).
Expected: title "Pair a phone", three steps, the TV's name, "On this network at <ip>, port 8787", a countdown
"This code works once and for 1:59 more", a white square with the QR on the right, buttons *New code* and
*Paired phones (0)* with the focus on *New code*. Screenshot:

```bash
adb -s <tv-ip>:5555 exec-out screencap -p > docs/shots/m1-pairing.png
```

Checks:
- The countdown reaches 0:00 and a new code appears by itself (about two minutes).
- *New code* changes the QR at once.
- Decode the QR with any QR reader app: `{"v":1,"tvId":"tv-...","name":"...","ip":"...","port":8787,"pairToken":"..."}`.

## 4. Pair the phone

```bash
adb -s <phone-serial> install -r phone/build/outputs/apk/debug/phone-debug.apk
```
Open *Anannt Room TV* on the phone, tap *Scan TV code*, allow the camera, point it at the TV.
Expected on the phone: "Pairing with <TV name>…", then the card says **Connected to Room TV** with the TV's name and
address. Expected on the TV: the line under the address turns gold, "Paired with <phone name>", and a new QR appears.
Expected in the log:
```
room: paired "<phone name>" from 192.168.x.z
```
Tap *Ping the TV*: the card shows "Round trip N ms".

Screenshot the TV again (`m1-paired.png`) and the phone (`adb -s <phone> exec-out screencap -p > docs/shots/m1-phone.png`).

## 5. Single use and expiry, on the real thing

- Scan the same code with a second phone (or screenshot the QR and scan the picture) after the first pairing:
  expected "Pairing failed: That code was already used or ran out. Press New code on the TV and scan again."
- Press *New code*, wait more than two minutes, then scan: the same message.

## 6. Replay and tampering

With the phone connected, from the computer:
```bash
adb -s <phone-serial> logcat -s RoomTV   # the phone's side
```
- Change the TV's clock by more than a minute (Settings › System › Date and time, turn automatic off) and tap
  *Reconnect*: expected "The phone's clock and the TV's clock differ by more than 30 s." Turn automatic time back on;
  *Reconnect* works again.
- Capture one signed command and resend it: `adb -s <tv-ip>:5555 logcat -s FreeMyTV` shows the first `hello` accepted
  and, for the resend of the same JSON to `POST /command`, HTTP 401 `{"error":"replay"}`. (A quick way: the phone's
  log prints nothing secret, so copy the JSON from a packet capture on the computer; or write a tiny script with the
  protocol module. The unit test `SigningTest` covers the same path without a TV.)

## 7. Paired phones and revoke

On the TV: *Free My TV* › *Paired Phones* (or `am start -n io.freemytv/io.freemytv.room.PairedPhonesActivity`).
Expected: one row with the phone's name, "Paired <date>", "Last seen <date>", a *Revoke* button. Press it once:
"Press again to revoke"; press again: the row goes. On the phone, tap *Ping the TV* or *Reconnect*: expected
"This phone was revoked on the TV. Scan a new code to pair again." Scan a new code: paired again.

## 8. Address change and discovery

Give the TV another IP (router DHCP reservation, or switch it from Wi-Fi to Ethernet). Log expected:
`room: serving on http://<new ip>:8787`. On the phone: *Find the TV on this network*: the TV appears with
"(this TV)"; tapping it updates the address and the card goes back to Connected.

## 9. Restart and reboot

- `adb shell am force-stop io.freemytv`, then open the home screen: the log shows `room: serving` again within seconds.
- Reboot the TV: after boot, `curl http://<tv-ip>:8787/status` answers (HomeReceiver starts the service).

## 10. Reversibility (nothing of Free My TV's restore changed)

- *Free My TV* › *Home Screen* › *Free My TV*, then *Original*: the log shows the usual switch lines, and
  `adb shell cmd role get-role-holders --user 0 android.app.role.HOME` is the stock launcher again.
- `scripts/restore-tv.sh <tv-ip>` prints the home role and screensaver; both stock.
- `adb shell dumpsys package io.freemytv | grep -A3 'requested permissions'` lists FOREGROUND_SERVICE as the only
  addition to the upstream list.

## Record

Put the screenshots in `docs/shots/` and note TV brand, model and Android version plus anything that differed from
the expectations above in the M1 PR.
