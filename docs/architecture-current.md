# Architecture as found (recon, 24 Oct 2026) and what Room TV adds

Fork of simbasjungles/free-my-tv at commit 4d73951 (upstream PR #188 merged). This note maps what is there, how the
home screen is taken and given back, and where the Room TV pieces (phone companion, Zoom, curated YouTube) hang.

## 1. Modules as found

| Piece | Where | What it is |
|---|---|---|
| Google TV / Android TV app | `app/` (Java, package `io.freemytv`, 31 files, about 11,100 lines) | The launcher, the screensaver, the settings screen, the mode switch |
| Build | `build.sh` | No Gradle: aapt2, javac (Java 8 level), d8, zipalign, apksigner. `config.properties` becomes `out/gen/io/freemytv/Config.java`. Signs with `freemytv.keystore` (made on first build) |
| Checks | `tools/check.sh`, `.github/workflows/check.yml` | `build.sh`, `tools/translate.py --check`, the LG and Samsung packages, ES5 lint of `web/` |
| LG and Samsung | `web/`, `webos/`, `tizen/` | The same home screen as an ES5 web app, packed by Python scripts |
| Website | `docs/` | freemytv.io, static, published by `pages.yml` on a push to main |
| Translations | `app/assets/i18n/*.json`, `tools/translate.py` | 25 catalogues keyed by the English string; `--check` fails CI when a key is missing anywhere |

### Processes and components (app/AndroidManifest.xml)

- `SettingsActivity` (`:home` process): the app's icon. Mode switch, screensaver preview, settings, shelf, remote buttons.
- `HomeActivity` (`:home`): the home screen, `category.HOME`. One hand-drawn Canvas (`HomeView`, 3,051 lines, 60 fps,
  no allocations in `onDraw`). AGSL shaders on Android 13+.
- `Screensaver` / `ScreensaverAlone`: the dream service (`:home`, or the main process on Android 11).
- `ModeJob` (main process): the mode switch as a JobScheduler job, so the system keeps the process alive.
- `RemoteKeyService` (`:keys`): accessibility service that only reads the remote's app buttons (TCL, Vestel, KTC,
  Sony, Formovie). On only in Free My TV mode.
- `HomeReceiver` (main process): BOOT_COMPLETED (`HomeMode.bootCheck`) and MY_PACKAGE_REPLACED (`HomeMode.verify`).
- `Watchdog`: 3 crashes of `:home` in 5 min hands the home screen back to the stock one.

## 2. How HOME is taken and given back (the part Room TV must never break)

Everything goes through the TV's own ADB daemon: `Adb.java` speaks the ADB protocol to `127.0.0.1:5555` with the app's
own RSA key (the one-time "Allow USB debugging?" prompt). No root, no network listener, fixed commands only.

`HomeMode.applyFree` (Free My TV mode), in order:
1. Records the stock state in a JSON state file (`files/`): the current HOME role holder, the screensaver
   (`settings get secure screensaver_components`), the Wi-Fi owners (apps that saved a network are never touched:
   turning one off deletes its networks).
2. `cmd package set-home-activity io.freemytv/io.freemytv.HomeActivity`.
3. `settings put secure screensaver_components io.freemytv/.Screensaver`.
4. `pm disable-user --user 0 <pkg>` for the `DEBLOAT` list plus the stock home screen and screensaver, refusing anything
   in `PROTECTED`, `CORE`, `FACTORY` or needed by the TV (`TV_INPUT_HARDWARE`, the Input key). Google TV's setup app
   (`setupwraith`) goes off before the stock home and comes back after it, always.
5. Every disabled package is appended to the state file: that list is the restore command's input.

`HomeMode.applyOriginal` (Original mode) reverses it: `pm enable` each recorded package (setup app last), the stock
home back with `cmd package set-home-activity`, the recorded screensaver back, the remote-key service out of the
enabled accessibility services. `restoreHome` falls back to the known Google home activities and finally
`cmd role remove-role-holder` when the recorded one is gone. `revertHomeNow` is the Watchdog's path; `verifyNow`
runs after boot and updates to complete a half-done switch.

Uninstalled while in Free My TV mode: the disabled apps stay off; the README gives the `pm enable` and
`set-home-activity` lines, and installing again and picking Original does it for you.

Room TV keeps all of this untouched: the only new state on the TV is the pairing data (section 4), and
`scripts/restore-tv.sh` only ever adds the stock-home commands on top.

## 3. The Gradle build added around it

```
settings.gradle.kts        :protocol  :tv  :phone        (Gradle 8.11.1 wrapper, AGP 8.7.3, Kotlin 2.0.21)
protocol/                  Kotlin JVM, kotlinx.serialization. Models, HMAC signing, token issuer, pairing math. JUnit tests.
tv/                        Android app io.freemytv. Sources = ../app/src (Java, unchanged layout) + tv/src/main/kotlin
                           (io.freemytv.room). The manifest is generated: app/AndroidManifest.xml + tv/src/main/room-manifest.xml.
                           Config.java is generated from config.properties as build.sh does.
phone/                     Android app io.anannt.roomtv.phone. Compose Material 3, CameraX + ML Kit, OkHttp WebSocket, NsdManager.
```

`./build.sh` and `tools/check.sh` still build the plain launcher from `app/` (the Java side reaches the Kotlin side only
by class name, through `Room.java`, so a build without it compiles and shows no Room rows). The Gradle `:tv` build is
the one to install for Room TV. `.github/workflows/gradle.yml` runs `:protocol:test`, `:tv:assembleDebug` and
`:phone:assembleDebug` on every pull request.

## 4. Room TV on the TV (M1)

```
 PHONE                                       TV (main process)
 scan QR ──────────────────────────────────▶ PairingActivity (Compose, tv-material)  shows {v,tvId,name,ip,port,pairToken}
 POST /pair {deviceId, deviceName,            RoomService (foreground) ──▶ RoomRuntime ──▶ RoomServer (Ktor CIO, host = the
   clientNonce, proof} ─────────────────────▶   TV's private IPv4 only, port 8787)        Nsd (_anannttv._tcp)
 ◀── {tvId, tvName, tvNonce, confirm}           RoomHandler: PairTokenIssuer (2 min, single use), PairedDevices (prefs)
 WS /control: signed envelopes ────────────▶   CommandVerifier (HMAC-SHA256, 30 s window, nonce replay guard) ──▶ route()
 ◀── signed replies (welcome, pong, status)
```

- Entry points on the TV: *Free My TV* › *Pair a Phone* (QR) and *Paired Phones* (list, revoke). Both are also
  `am start`-able (see docs/test-m1.md). The server starts with the home screen, the settings screen and at boot.
- The pairing token never crosses the network: the phone sends `proof = HMAC(token, clientNonce | deviceId)`; both
  derive `secret = HMAC(token, clientNonce | tvNonce | deviceId | tvId)`; the TV's `confirm` lets the phone check it.
- Every later message: `Envelope{v, deviceId, ts, nonce, type, body, sig}`, `sig = HMAC-SHA256(secret,
  "v\ndeviceId\nts\nnonce\ntype\nbody")`. The TV refuses v != 1, unknown devices, |ts - now| > 30 s, bad signatures
  and a nonce seen before (recorded only after the signature passed). Replies are signed by the TV with the same
  secret and verified on the phone.
- LAN only: the server binds one private IPv4 address and refuses callers outside RFC 1918, link-local, CGNAT and
  loopback (loopback so `adb forward` tests work). It rebinds when the TV's address changes.
- Storage: `shared_prefs/room.xml` (tvId), `shared_prefs/room-devices.xml` (paired phones, secrets base64url) on the
  TV; `shared_prefs/room-phone.xml` on the phone. App-private, not backed up (`allowBackup=false`).
- New permission on the TV: `FOREGROUND_SERVICE` (normal). On the phone: `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`.

## 5. Where the next milestones plug in

- M2 Zoom: built, see section 6.
- M3 remote: built, see section 7.
- M4 YouTube: Room DB in `io.freemytv.room`, `yt.play` after the whitelist check, and `HomeApps.HIDDEN` for the
  YouTube tile.
- M5: calendar on the phone, idle screen, auto-reconnect in `TvConnection`, guest QR on `HomeView`.

## 6. Zoom push (M2)

```
 PHONE                                        TV
 share sheet "Send to Room TV" (ShareActivity) ─┐
 paste box (SendPanel)                          ├─ ZoomParser.parse(text) -> ZoomMeeting (:protocol, tested)
                                                │
 ZoomJoin{id, pwd, name, passcode, countdownSec} signed as `zoom.join` ──▶ RoomHandler.zoomJoin: validated() again,
 ◀── signed `ack` {ok, detail}                    RoomRuntime.pending (memory only) ──▶ ZoomReadyActivity (the card)
                                                  OK ──▶ ZoomLauncher.join: deep link, web link, open the app
```

- `:protocol` `Zoom.kt`: `ZoomParser` (links on zoom.us, zoomgov.com and zoom.com with any subdomain, `/j /w /wc /s`,
  `zoommtg://` and `zoomus://`, invite text with Meeting ID and Passcode, IDs split by spaces, dots or dashes, 9 to 11
  digits), `ZoomJoin` (`validated()` is run on both sides), `ZoomLinks` (the two public link formats), `Ack`.
- A link's `?pwd=` token goes into the join links as it is; the short typed passcode from the invite text is only
  shown on the card in the manual state. A personal link (`zoom.us/my/name`) has no meeting ID and is refused.
- TV: `ZoomReadyActivity` (singleTask, not exported) reads the meeting from `RoomRuntime.pending`, never from the
  intent, so the passcode is not in an extra or a log. `ZoomLauncher` finds the Zoom package (`us.zoom.videomeetings`,
  `us.zoom.zrc`, else any handler of `zoomus://`), and logs only the route taken. Strings go through `RoomText.java`
  (a public Java bridge over `Ui.tr`) so `tools/translate.py` sees them: all 25 catalogues have them.
- Manifest additions (`tv/src/main/room-manifest.xml`): `<queries>` for the two Zoom packages and the `zoomus`,
  `zoommtg` and `https://zoom.us` link types; `SYSTEM_ALERT_WINDOW` (not granted by installing) so the card can open
  over another app; the card activity.
- Phone: `ShareActivity` (ACTION_SEND text/plain, label "Send to Room TV"), `SendPanel` (paste, name, optional 10 s
  countdown, send), `TvConnection.sendZoomJoin` (waits for the TV's ack). The optional countdown is chosen on the phone
  and is off by default.
- What is not verified: every route on a real TV. `docs/zoom-launch-matrix.md` and `docs/test-m2.md` are the script.

## 7. Phone remote (M3)

```
 PHONE  RemoteScreen: D-pad, OK, Back, Home, volume, Open Zoom, End meeting / return home
   nav.key{key} | nav.returnHome{endMeeting} | zoom.open        signed like every command
   ◀── signed `ack` {ok, detail}  and pushed `zoom.state` {phase, detail}        auto-reconnect, heartbeat, state badge
 TV   RoomHandler: verify (HMAC, 30 s, nonce) ─▶ CommandLimits (token bucket per phone; extra presses dropped silently)
        ─▶ RoomActions = TvActions
             keys UP/DOWN/LEFT/RIGHT/OK/BACK ─▶ Nav.send (ordered broadcast, signature permission) ─▶ the screen on show in
                  :home or main plays it as a key press ─▶ "native"
                  else RoomControlService.performGlobalAction ─▶ "accessibility", else "needs-remote-control"
             volume ─▶ AudioManager (FLAG_SHOW_UI)
             home / return home ─▶ GLOBAL_ACTION_HOME or start HomeActivity, then check with ForegroundProbe
                  End meeting ─▶ ActivityManager.killBackgroundProcesses(Zoom)
```

- `:protocol` `Nav.kt`: `NavKey`, `NavKeyCommand`, `ReturnHome`, `NavResult` (the ack details), `ZoomPhase`/`ZoomState`,
  `JoinWatch` (the 20 s rule), `RateLimiter`/`CommandLimits`, and the room-name default. All JVM-tested.
- TV: `app/src/io/freemytv/Nav.java` (receiver in each screen that is on show, sender for the server), `TvActions`,
  `RoomControlService` (optional accessibility service, off by default), `ForegroundProbe`, `RoomActivity` (base class
  that attaches `Nav`), `RemoteAccessActivity` and `RoomNameActivity` (rows in Free My TV settings),
  `ZoomReadyActivity` (the "Joining Zoom…" state), `RoomRuntime.reportZoom` and `RoomHandler.push` (state events down
  the phone's own socket, signed).
- Phone: `RemoteScreen`, `TvConnection` (retries after 1, 2, 4, 8, then every 15 s; a heartbeat every 8 s; permanent
  failures such as a revoked phone are not retried).
- `docs/remote-limits.md` is the honest account of what Android allows; `docs/test-m3.md` is the test script.

