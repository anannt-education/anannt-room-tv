# M4 curated YouTube: how to verify it on a real TV

Nothing was built or run locally when M4 was written (no JDK or Android SDK on that Mac; CI is the compiler), so this is
the script to run, and `docs/youtube-limits.md` says what each result should be and why. Use a YouTube video and a
playlist you are happy to approve, plus one video that does not allow embedding (many music videos and some films do not).
Do not commit IDs you do not want public. `<tv-ip>` is the TV's address.

## 0. Build and unit tests

```bash
./gradlew :protocol:test :tv:assembleDebug :phone:assembleDebug
```
The new tests are `YouTubeParserTest` (every link form, parameters, lookalike hosts, bad IDs), `WhitelistTest` (allow and
deny, duplicates, removal, invalid IDs), `AdminPinTest` (hashing, verify, lockout schedule, persistence across a restart,
reset) and `YouTubeCommandsTest` (protocol v2, v1 still verifies, old welcome). Expected: `BUILD SUCCESSFUL`. The Room
table and the screens are not covered by JVM tests (they need a device): sections 3 to 9 below are their test.

## 1. Install and pair

```bash
scripts/install-tv.sh <tv-ip>
adb -s <tv-ip>:5555 logcat -s FreeMyTV      # keep this open
```
Install `phone/build/outputs/apk/debug/phone-debug.apk`, pair as in `docs/test-m1.md`. Switch the TV to Free My TV mode on
the TV itself (Free My TV > Home Screen > Free My TV).

Write down the facts the report needs:
```bash
adb -s <tv-ip>:5555 shell getprop ro.build.version.release
adb -s <tv-ip>:5555 shell pm list packages | grep -i youtube          # which YouTube packages exist
adb -s <tv-ip>:5555 shell pm list packages -d | grep -i youtube       # none: nothing is disabled
adb -s <tv-ip>:5555 shell pm list packages | grep -i webview          # Android System WebView is needed by the player
```

## 2. The home screen: YouTube is hidden, Videos is second

1. On the TV's home screen the second card on the shelf (where YouTube sat) is **Videos** (since M5; it was first in M4) (a play symbol and the word). The YouTube card that
   Free My TV normally shows is not there.
2. Open **All Apps**: YouTube is not in the grid. Open Free My TV > **Shelf**: YouTube is not in the list of apps to add.
3. `adb shell pm list packages | grep -i youtube` still lists the package, and `pm list packages -d` does not: it is hidden,
   not disabled.
4. With nothing approved, OK on **Videos** shows "No approved videos yet…" and an **Admin PIN** button.
5. Hold OK on a shelf card to move it, and drop it: the shelf order is saved and the Videos card stays second (no crash).

## 3. Admin PIN on the TV

Videos > **Admin PIN** (or `adb shell am start -n io.freemytv/io.freemytv.room.AdminPinActivity`).
1. "Set an admin PIN": type `4821` with the arrows and OK (or the remote's number keys), then **Save**. "Type it again to
   confirm": type `4821`, Save. The screen closes. A wrong second entry shows "The two PINs are different. Start again."
2. Open it again: it says a PIN is set and shows **Reset PIN**. One press changes the label to "Press again to reset";
   waiting 6 s puts it back.
3. The PIN is stored hashed and is not in any log:
```bash
adb -s <tv-ip>:5555 shell run-as io.freemytv cat shared_prefs/room-admin.xml   # salt, hash, iterations; no "4821"
adb -s <tv-ip>:5555 logcat -d -s FreeMyTV | grep -c 4821                       # 0
```
`run-as` works on the debug build only.

## 4. Share a YouTube link from the phone

On the phone, in YouTube (or any app), Share a video > **Send to Room TV**. The panel shows "YouTube video found", the ID and
a PIN box. Also test pasting into the main screen's box. Try each form and write down which ones the phone accepts:
`youtu.be/<id>?si=…`, `youtube.com/watch?v=<id>&list=…&t=30s`, `m.youtube.com`, `music.youtube.com/watch?v=<id>`,
`/shorts/<id>`, `/live/<id>`, `/embed/<id>`, `/playlist?list=<id>`. A link to a channel, a search, or
`youtube.com.example.com/watch?v=…` shows a red message and no PIN box.

1. **Wrong PIN**: enter `0000`, ask. The phone says "Wrong PIN. 4 tries left…". The TV shows nothing. Log:
   `room: admin PIN wrong for whitelist.add, 4 free tries left, locked 0 ms` (no PIN in it).
2. **Lockout**: four more wrong PINs. The fifth says "Too many wrong PINs. The TV will check one again in 30 s." Entering
   `4821` during the wait gives the same message (the right PIN is not checked). After 30 s `4821` works. Repeat the five
   wrong tries a second time: the lock is longer (1 min after the next wrong try).
3. **It survives a restart**: cause a lockout, then `adb shell am force-stop io.freemytv` and reopen Free My TV. The phone
   still says locked, with the time left; Videos > Admin PIN > Reset PIN clears it.
4. **PIN not set** (test once, after Reset PIN): the phone says "No admin PIN is set yet. On the TV: Videos, then Admin PIN."
5. **Right PIN**: the TV shows **Approve this video?** with "Sent by <phone>", the ID and, if the TV is online, the
   thumbnail and title (offline: just the ID; turn the TV's Wi-Fi off and on to see both). The phone says "On the TV now…".
   - **OK** approves: the phone says "Approved on the TV." and the share sheet closes by itself.
   - Share it again: the phone says "That is already on the approved list." and the TV shows nothing.
   - Share a second video and press **Back**: the phone says "Rejected on the TV.", the list is unchanged.
   - Share a third and press the phone remote's Home (or let it sit and press Home on the TV): the phone says the question was
     dropped.
   - Share a playlist: **Approve this playlist?**; approve it.

## 5. The list on the phone and the TV

1. Phone main screen > **Approved videos**: the items, newest first, with title (if the TV had one), kind and who added it.
   No PIN is needed to read the list. If no PIN is set it says so.
2. TV > **Videos**: the same items. A new approval appears without reopening it.
3. Phone **Remove**: asks for the PIN; wrong PIN shows the tries message; the right one removes it and the TV list updates.

## 6. Play

1. TV > Videos > OK on the video: the player opens in a moment, video plays. Keys: OK pauses and resumes; Left and Right skip
   10 s; Up and Down do nothing for a single video; Back returns to the list. The bottom hint line shows for about 6 s.
2. The phone's **Play on the TV** does the same. The phone remote (M3) arrows, OK and Back work in the player.
3. Let a short video end: the picture goes back to its first frame (no suggestion grid) and the hint line stays. OK plays it again.
4. Playlist: OK on it starts the first video; at the end the next one plays by itself; Down and Up move to the next and
   previous video. Write down what you see at the end of the last video and when paused (limits page, "What it cannot do").
5. Hold an arrow, press every key the remote has: nothing opens the YouTube page, nothing leaves the player except Back.
6. **Not approved is refused.** Remove an item on the phone, then:
```bash
adb -s <tv-ip>:5555 shell am start -n io.freemytv/io.freemytv.room.VideoPlayerActivity --es kind video --es id dQw4w9WgXcQ
```
   (any ID not on the list). Expected: the screen closes at once, log `room: player refused, not on the list`. A badly
   formed ID (`--es id "x';alert(1)"`) closes it too, without a log line about the player.

## 7. Fallback to the YouTube app

Approve a video that does not allow embedding and play it. Expected: the player shows "This video can't be played here.
Press OK to open it in the YouTube app" (log `room: player error …`; write down which error: `VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER`
or `UNKNOWN` (YouTube's 153)). OK opens the YouTube app on that video (log `opened the YouTube app (vnd.youtube)` or
`(https)`). Press Back in the YouTube app and move around: it lets you. That is the limit, see the limits page.
Also write down: does an ordinary video give error 153 (a missing or untrusted referer) in the in-app player on this TV? The
player's origin is the library's default, `https://www.youtube.com`, so it should not; if it does, say so in the PR.

## 8. Old and new, side by side (protocol v1 and v2)

Download the M3 APKs from the M3 PR's CI run (`gh run download <run-id> --repo anannt-education/anannt-room-tv -n apks -D
/tmp/m3-apks`; put the folder anywhere that is not this repo).
1. New TV (this build) with the **M3 phone** app: pair, then remote keys, Open Zoom, send a Zoom meeting. All work as in
   `docs/test-m3.md`. The phone does not show YouTube features (it does not have them). Log has no `bad-version`.
2. **M3 TV** with the new phone: pair and use the remote and Zoom as before. **Approved videos** says the TV runs an older
   app; a YouTube share says the same after the PIN is typed.

## 9. Restore

```bash
scripts/restore-tv.sh <tv-ip>
```
It prints "YouTube was never disabled by Room TV". Then on the TV: Free My TV > Home Screen > **Original**. Expected: the
TV's own home screen, with its YouTube tile, and `pm list packages -d | grep -i youtube` still empty. The approved list and
the PIN stay on the TV (app data): `adb shell pm clear io.freemytv` would remove them, along with Free My TV's own data, so
do it only after Original, and only if you want a clean slate.

## 10. Screenshots for the report

```bash
mkdir -p shots
adb -s <tv-ip>:5555 exec-out screencap -p > shots/m4-home-videos-tile.png
adb -s <tv-ip>:5555 exec-out screencap -p > shots/m4-approve.png
adb -s <tv-ip>:5555 exec-out screencap -p > shots/m4-videos-list.png
adb -s <tv-ip>:5555 exec-out screencap -p > shots/m4-player.png
adb -s <tv-ip>:5555 exec-out screencap -p > shots/m4-admin-pin.png
```
Do not commit `shots/`. A WebView is drawn on its own surface: if `screencap` shows black where the video is, write that
down and take a photo instead.
