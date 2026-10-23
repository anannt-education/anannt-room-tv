# Anannt Room TV: Free My TV + Phone Companion. Brief for Claude Code

## Goal
Turn a stock Android/Google TV into a calm, controlled room screen for Anannt sessions:
- An ad-free home screen built on Free My TV (github.com/simbasjungles/free-my-tv).
- One-tap Zoom joins sent from a phone. Nobody types meeting IDs or passcodes on a remote.
- A YouTube section that only plays approved videos and playlists.
- The phone is the controller and admin console. No root, fully reversible, and one-button restore to the stock launcher.

## Architecture
```
 PHONE (companion app)                      TV (Free My TV fork, set as HOME)
 ─────────────────────                      ─────────────────────────────────
 Scan pairing QR  ───────────────────────▶  Shows QR: {tvId, ip, port, pairToken, pubKey}
 Share sheet / paste Zoom link              Local server (Ktor, LAN only, port 8787)
 Calendar: "Today's meetings"                 ├─ POST /pair        (token → device key)
 Remote: D-pad, volume, back, home            ├─ WS   /control     (signed commands)
 Whitelist manager (YouTube)                  └─ GET  /status
          │   same Wi-Fi, mDNS (_anannttv._tcp) discovery
          └──────── JSON commands over WebSocket, HMAC-signed ─────────▶
                                            Command router
                                              ├─ zoom.join  → Zoom intent / deep link, else "Meeting ready" card
                                              ├─ zoom.prefill → shows ID/passcode big on screen
                                              ├─ yt.play    → whitelist check → player
                                              ├─ whitelist.update → Room DB
                                              └─ nav.*      → accessibility-free key handling inside own app
```
No cloud is needed for the MVP. An optional relay (a small Node/Cloudflare Worker) can come later for "send from anywhere".

## Key decisions for Claude to verify, not assume
1. **Zoom on TV.** Check which Zoom app is installable on the target TV (Zoom for Android TV / "Zoom Rooms" / phone APK sideloaded) and its package name, using `adb shell pm list packages | grep -i zoom`. Test these in order:
   - `zoomus://zoom.us/join?confno=<id>&pwd=<pwd>&uname=<name>` via ACTION_VIEW
   - `https://zoom.us/j/<id>?pwd=<pwd>` via ACTION_VIEW targeted at the Zoom package
   - Launch the Zoom app plus a "Meeting ready" overlay showing the ID and passcode in large type (fallback)
   Record which route works in `docs/zoom-launch-matrix.md`. Use only the official Zoom app and public link formats. No UI automation of Zoom, and no SDK use without a key.
2. **Parsing.** Accept zoom.us/j/…, zoom.us/w/…, vanity subdomains (anannt.zoom.us), and pasted invite text (regex for "Meeting ID" and "Passcode"). Unit-test every format.
3. **Security.** Pairing tokens are single-use and valid for 2 minutes. After pairing, the phone stores a per-device secret, and every command carries an HMAC plus a timestamp, with replay rejected after 30 s. The server only binds when the TV is on a private network and refuses unpaired commands. The TV keeps a "Paired phones" screen with revoke.
4. **YouTube whitelist.** Store video and playlist IDs in a Room DB on the TV, managed from the phone. Playback goes through the official YouTube app intent (`vnd.youtube:<id>`) only after a whitelist check, or through an in-app player (android-youtube-player by PierfrancescoSoffritti) so related videos are hidden. The YouTube app stays hidden from the launcher grid.
5. **Reversibility.** Keep Free My TV's restore flow. Document the ADB commands to set and unset HOME, and never disable system packages without a logged restore command.

## Tech stack
- **TV:** Kotlin, Jetpack Compose for TV (`androidx.tv:tv-material`), Ktor server (CIO) for HTTP and WebSocket, ZXing core for the QR, Android NsdManager for mDNS, Room, and DataStore.
- **Phone:** Kotlin and Compose on Android first. Use CameraX + ML Kit barcode scanning, OkHttp WebSocket and NsdManager. Register a share target (ACTION_SEND text/plain) so "Share → Anannt TV" works straight from Zoom, Gmail, Calendar or WhatsApp. Calendar comes later through CalendarContract (read-only) to list today's events that have Zoom links.
- **Shared:** A `:protocol` Kotlin module with @Serializable command models, versioned (`v: 1`).
- **iOS:** Out of the MVP. Keep the protocol plain JSON so a Swift or PWA client can follow.

## MVP order
1. **M1, pairing.** The TV shows a QR and the phone scans it, pairs, and shows "Connected to Room TV". The TV lists paired phones and can revoke them.
2. **M2, Zoom push.** Share a Zoom link to the TV, and the TV shows a "Meeting ready, press OK to join" card with a 10 s countdown option. Join goes through the best route from the launch matrix.
3. **M3, phone remote.** D-pad, OK, back, home and volume inside our app, plus "End meeting / return home".
4. **M4, YouTube whitelist.** Add from the phone by sharing a YouTube link to the TV, which offers "Approve". The TV shows only approved items, and an admin PIN on the phone protects edits.
5. **M5, polish.** Today's meetings from the phone calendar, a TV idle screen showing the next session, auto-reconnect, and a QR on the TV home for guests ("scan to send a meeting").
6. **Later.** Optional cloud relay, iOS client, and multi-TV management.

## Definition of done per milestone
- Unit tests for parsing, HMAC and the whitelist, plus an instrumented test for the command router.
- A manual test script in `docs/test-<milestone>.md`, run on a real TV over `adb connect <tv-ip>:5555`.
- APKs built with `./gradlew :tv:assembleDebug :phone:assembleDebug`, and an install script `scripts/install-tv.sh`.
- No new permissions beyond what's documented, and a passing restore test.
