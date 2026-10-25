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
- M4 YouTube: built, see section 8.
- M5: built, see section 9.

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


## 8. Curated YouTube (M4)

```
 PHONE  share / paste a YouTube link ──▶ YouTubeParser.check (:protocol, tested) ──▶ YouTubeSend (admin PIN field)
        whitelist.add{kind,id,pin} | whitelist.remove{kind,id,pin} | whitelist.list | yt.play{kind,id}      protocol v2, signed
        ◀── signed `ack` {ok, detail, triesLeft, waitMs}   ◀── `whitelist` {items, pinSet, locked}   ◀── pushed `whitelist.result`
 TV   RoomHandler: verify (HMAC, 30 s, nonce, version 1..2) ─▶ v2-only commands refused on a v1 envelope
        whitelist.add    ─▶ validate the ref ─▶ PinGuard.verify ─▶ already listed? ─▶ RoomRuntime.askApproval
                              ─▶ VideoApproveActivity ("Approve this video?", oEmbed title and thumbnail if reachable)
                              OK ─▶ Whitelist.add ─▶ Room table ─▶ push whitelist.result;  Back or leaving ─▶ rejected / cancelled
        whitelist.remove ─▶ validate ─▶ PinGuard.verify ─▶ Whitelist.remove
        yt.play          ─▶ validate ─▶ Whitelist.isAllowed ─▶ RoomRuntime.playVideo ─▶ VideoPlayerActivity (checks the list again)
 Home shelf: "Videos" card first ─▶ VideosActivity (approved items only); the YouTube app is not drawn (HomeApps.ROOM_HIDDEN)
```

- `:protocol` `YouTube.kt`: `YtRef`, `YouTubeParser`, the payloads, `WhitelistStore` + `Whitelist` (the rules: valid IDs only, no
  duplicates, allowed only if listed). `AdminPin.kt`: `PinHasher` (PBKDF2-HMAC-SHA256, 100,000 rounds, 16-byte salt) and
  `PinGuard` (set, verify, clear, lockout kept in the same storage). All JVM-tested.
- Protocol v2: `PROTOCOL_VERSION = 2`, `MIN_PROTOCOL_VERSION = 1`. A signed envelope carries its version in the signature, the
  TV accepts 1 and 2, answers each command in the version it came in, and remembers each phone's last version to push in.
  A phone says hello as v1 (any TV takes it), reads `Welcome.protocol` (absent on an old TV means 1) and speaks the lower
  of the two. v2-only commands on a v1 envelope get `needs-protocol-2`. The QR, `/pair` and its answer stay at version 1
  (`PAIRING_VERSION`), so an old phone can still pair with a new TV.
- TV: `VideoDb.kt` (Room table `whitelist` in `databases/room-videos.db`: type, id, title, addedAt, addedBy; primary key type+id; IGNORE
  on a duplicate), `AdminPin.kt` (`PrefsPinStorage`, `commit()`), `OEmbed.kt`, `VideoApproveActivity`, `VideosActivity`,
  `AdminPinActivity`, `VideoPlayerActivity`, `YouTubeApp`. Room runs through KSP; the player is
  `com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.2` (13.0.0 needs a newer compileSdk than this build uses).
- Launcher (Java): `HomeApps.ROOM_VIDEOS` card second on the shelf (M5; first in M4), `ROOM_HIDDEN` keeps the YouTube packages out of the shelf, All
  Apps and the shelf picker. Only in the Gradle build (`Room.available`). Nothing is disabled, so nothing needs restoring.
- Phone: `YouTubeScreens.kt` (`YouTubeSend`, `VideosScreen`, `PinField`), `SendPanel` (Zoom first, then YouTube), `TvConnection`
  (`callAck`, `sendWhitelistAdd/Remove`, `sendYtPlay`, `refreshWhitelist`, version negotiation).
- New permissions: none. INTERNET was already there (the player and oEmbed use it). `<queries>` now also names the two YouTube
  packages (package visibility on Android 11+, names only). No new service, receiver or exported entry that takes data.
- Details of what this does and does not hold back: `docs/youtube-limits.md`. Test script: `docs/test-m4.md`.


## 9. Polish (M5): today's meetings, next session, guest QR, reconnect

```
 PHONE  Today screen: READ_CALENDAR asked only here ──▶ PhoneCalendar (CalendarContract.Instances, read only)
          ──▶ TodayMeetings.load (:protocol: day in the phone's zone, Zoom filter, ZoomParser) ──▶ one tap: zoom.join (as M2)
          next meeting ──▶ session.next{title, startMs} | session.clear        protocol v3, signed
 TV    RoomHandler ─▶ NextSessionRules.check (future, within 14 days) ─▶ RoomRuntime.setNext ─▶ room-home.json (filesDir)
       :home process: HomeOverlay (a TextView along the bottom edge of the home screen) reads that file every 10 s:
          "Next session: <title> at <time>", hidden at the start time; the main process also deletes it then.
 Guest  home shelf, last tile "Scan to send a meeting" ─▶ GuestActivity: QR of http://<tv-ip>:8787/guest?t=<token>
        guest phone camera ─▶ browser ─▶ GET /guest (form) ─▶ POST /guest/send {t, text, name}
          GuestGate: size limits ─▶ rate limit (per caller, and for everybody) ─▶ GuestTokenIssuer (5 min, single use, 3 strikes)
          ─▶ ZoomParser ─▶ busy? ─▶ redeem ─▶ RoomRuntime.showMeeting(from = guest) ─▶ the same card: OK joins, Back rejects
```

- Protocol v3 (`PROTOCOL_VERSION = 3`, floor still 1): new commands `session.next` and `session.clear` (`Commands.V3_ONLY`,
  `Commands.minVersion`). A v1 or v2 envelope carrying them is refused with `needs-protocol-3`; every older command keeps
  working in its own version. The QR and `/pair` still say 1. A phone speaks the lower of its version and the TV's, so a
  v3 phone with an older TV works and just can't show a session (the Today screen says so).
- What the TV shows of a session (`NextSession`): a title (cleaned, 80 characters at most) and a start time. Nothing else
  crosses: no link, passcode, attendees or notes. The phone's Today screen shows the exact line before it is sent, and
  has "Hide the title" (sends "Session"). The title is visible to anyone in the room, so that is the person's choice.
  The Zoom link is deliberately not part of it: the home screen has nothing that could use it without a new surface.
  The TV keeps it in `files/room-home.json` (app-private) so the :home process can draw it, and removes it at the start time.
- Calendar: read only by construction (`CalendarSource` has one method, `instances`; the manifest has READ_CALENDAR and
  never WRITE_CALENDAR). Instances, so recurring meetings show their occurrence for today in the phone's zone (a DST day
  is 23 or 25 hours). Kept: timed events with a Zoom link or invite in the location, notes or title (the text must say
  "zoom"); left out: all-day, declined, cancelled. Known limit: a link wrapped by a mail security gateway (a
  `safelinks` redirect) is not a zoom.us link and is not found unless the invite text has "Meeting ID".
- Guest path, and why a web page: a guest has no app and no pairing, a phone camera opens a URL everywhere, and the
  alternative (a guest flow in the companion app) needs the app installed first. So the TV serves one small page. Trade-offs
  accepted: plain HTTP on the LAN (as the rest of the protocol today; the token is single-use and the TV still asks OK), and
  the page is English only. Rights: the token carries one right, `PROPOSE_MEETING`. It cannot sign a command (signing needs a
  paired phone's secret), press a key, touch the list or the PIN. The page and its POST are LAN-only like every route, send
  `no-store`, a CSP with no script, no referrer. A form post larger than 8 KB, or of unknown size, is not read. The TV
  never decides to join: the card waits for OK, `countdownSec` is forced to 0, and a guest cannot push aside a card or an
  approval question that is already on screen (`busy`). Switchable: the guest screen has a button that turns guest sending off.
- Reconnect (phone): waits are 1, 2, 4, 8, then 15 s with jitter (`Backoff`); the second failure and then every third one
  look the TV up again by mDNS (`PhoneApp.relocate`, `Discovery.findTv` matches the tvId, IPv4 only) and follow it to a new
  address; the network coming back (`NetworkCallback.onAvailable`) retries at once (`TvConnection.nudge`); the home card
  counts the seconds down and says when it is looking. Keepalive: OkHttp pings every 20 s, the app pings every 8 s and
  drops a TV silent for 20 s, the server pings every 20 s with a 40 s timeout (all unchanged and now documented).
- Reconnect (TV): `RoomRuntime` checks every 15 s that its server really answers (a TCP connect to its own address) and binds
  again when it doesn't, or when an earlier start failed (before, a failed start was never retried until the network
  changed). The service is START_STICKY, started again by the home screen and at boot, as before.
- The optional YouTube step: `install-tv.sh --disable-youtube` (off by default) runs `pm disable-user --user 0
  com.google.android.youtube.tv`, logs `scripts/.restore/<ip>.youtube.txt` first, and sets the TV setting
  `anannt_room_youtube_disabled`. `HomeMode.applyOriginal` (the in-app Original) reads that setting and turns the package
  back on, `restore-tv.sh` does the same from the computer. Only the YouTube packages in `HomeApps.ROOM_HIDDEN` are ever
  enabled that way.
- New permissions: phone `READ_CALENDAR` (asked on the Today screen only). TV: none. New component: `GuestActivity`.
