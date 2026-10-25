# Curated YouTube: what it holds back and what it cannot (M4)

Room TV plays YouTube only for items on an approved list. This page says exactly where that holds, and where Android or
YouTube leaves a gap. Nothing here has been run on a real TV yet: `docs/test-m4.md` is the script, and every "should"
below is something that script checks.

## How an item gets on the list

1. A phone shares or pastes a YouTube link. The phone reads it with `YouTubeParser` (a video, a Short, an embed, a live
   stream or a playlist on youtube.com, m., music., youtu.be or youtube-nocookie.com; look-alike hosts and anything with
   an odd ID are refused with a message).
2. The phone asks for the admin PIN and sends `whitelist.add` (signed, protocol v2). The TV checks the PIN against its
   salted hash. A wrong PIN, a lockout or no PIN set is answered on the phone and nothing is shown on the TV.
3. Only a right PIN puts "Approve this video?" on the TV. OK adds the item to a Room table; Back rejects. The phone is
   told which. So an item needs both the PIN (someone with the admin's secret) and OK (someone in front of the TV).
4. Removing an item is `whitelist.remove` and needs the PIN.

`yt.play` (from the phone or the TV's Videos tile) looks the item up first: not on the list, nothing starts, and the phone
is told `not-approved`. The player activity looks it up once more before it loads anything, whoever started it.

## The in-app player (android-youtube-player, YouTube's IFrame player in a WebView)

What it does: controls off, `rel=0`, annotations off, captions as the video's default, no fullscreen button, every key
taken by our activity (OK play or pause, Left and Right skip 10 s, Up and Down previous and next in a playlist, Back
stops) so the D-pad can never focus a link inside the player; the WebView is told to drop any attempt to load another
page; at the end of a single video the player is cued again so YouTube's end-of-video suggestions are replaced by the
first frame. A playlist plays through its own videos only.

What it cannot do:
- `rel=0` no longer means "no related videos". YouTube now limits them to the same channel; it does not remove them.
  The end-of-video grid is covered by the cue-again trick for a single video. At the end of a playlist, and for a few
  seconds when a video is paused, YouTube may still draw a same-channel suggestion over the picture. It cannot be
  clicked (no key reaches the page), but it can be seen.
- An embed shows what the video's owner allows. Some videos refuse to play in an embed (YouTube error 101 or 150; 153 means a referer problem and should not happen with the default origin).
  The player then says so and offers OK to open the item in the YouTube app (below).
- Ads: YouTube may show ads in an embed, as it does anywhere. This app does not and cannot block them.
- Age-restricted or members-only videos do not play in an embed.
- The player needs the internet and YouTube's own page (`youtube.com/iframe_api`). Offline, it shows nothing.
- A playlist can change on YouTube after it was approved. Approving a playlist approves whatever is in it later.
  Approve a playlist you control or trust.

## The fallback: the official YouTube app

Only after the list check, and only on request (OK on the error screen). The TV hands the YouTube app one item:
`vnd.youtube:<id>`, then `https://www.youtube.com/watch?v=<id>`; a playlist opens its `playlist?list=` page. Once the YouTube
app is open, Android gives this app no way to restrict it. It cannot prevent:
- autoplay of the next recommended video after the item ends;
- the app's home, search, voice search, other channels, the sign-in picker and every recommendation;
- someone pressing Back, moving around and opening something else;
- the TV's own YouTube button, if the remote has one (TCL, Sony and others). It opens the app directly.
That is why the in-app player is the default and the fallback is a deliberate choice on the error screen.

## Hiding the YouTube app from the launcher

`HomeApps.ROOM_HIDDEN` (`app/src/io/freemytv/HomeApps.java`) lists `com.google.android.youtube.tv` and
`com.google.android.youtube`. In the Room TV (Gradle) build only, the launcher leaves them out of the shelf, "All Apps" and
Settings > Shelf, and puts a **Videos** tile second on the shelf (YouTube's old place) instead (the tile opens `VideosActivity`, the approved
list). A plain `build.sh` build has no such list and no Videos tile.

This is a filter in our own drawing code. Nothing is disabled, uninstalled or changed on the TV:
- `adb shell pm list packages -d` (disabled packages) never lists YouTube because of Room TV;
- there is nothing to log or undo, so `scripts/restore-tv.sh` and Free My TV > Home Screen > Original need no extra step.
  The original home screen shows YouTube as before. `restore-tv.sh` prints whether YouTube is enabled, as a check.

It is not a lock. YouTube is still on the TV and reachable by: the remote's YouTube button, Google Assistant ("open
YouTube"), casting from a phone (Chromecast built-in), Settings > Apps, `adb shell am start`, and a link opened from
another app. Stopping those needs a managed device or a TV-level app lock, which is outside "no root, no disabling system
packages". If a room needs that, disable the YouTube package yourself with `adb shell pm disable-user --user 0
com.google.android.youtube.tv`, write the line down, and undo it with `pm enable`; Room TV will not do it for you.

## The admin PIN

- 4 to 8 digits, set on the TV (Videos > Admin PIN), never on a phone. Stored as a salt and a PBKDF2-HMAC-SHA256 hash
  (100,000 rounds) in `shared_prefs/room-admin.xml`; the PIN itself is never stored or logged.
- 5 wrong tries are free; the 5th and each wrong try after a lockout locks checking for 30 s, 1 min, 2 min, 4 min and so
  on up to an hour. The count and the lock end time are on disk, so killing or restarting the app does not give tries
  back. While locked, even the right PIN is not checked.
- Reset on the TV: Videos > Admin PIN > Reset PIN (press twice), then choose a new one. It needs the TV's remote and no
  old PIN, because that is the way out of a forgotten PIN. So whoever holds the TV remote can replace the PIN. Reset also
  clears the lockout.
- The PIN travels from the phone to the TV inside a signed command over plain WebSocket on the LAN (the same trust as the
  rest of the protocol: an eavesdropper on the Wi-Fi can read it but cannot send commands without a paired phone's secret).
  If that is not enough for a room, put the TV on a network that does not share Wi-Fi with guests.

## What leaves the TV

`OEmbed` asks `https://www.youtube.com/oembed` once per approval question for a title and thumbnail (no key, no account;
YouTube sees the TV's address and the ID) and fetches the thumbnail only from `*.ytimg.com`. If that fails the prompt
shows the ID. Playing a video loads YouTube's player, so YouTube sees that, as it would on any device.
