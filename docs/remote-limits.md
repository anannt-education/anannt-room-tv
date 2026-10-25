# What the phone remote can and cannot do on an Android TV (M3)

The phone is not the TV's remote control. It is a client of the Room TV app, and Android lets an ordinary, non-root app
do very little to other apps. This page says exactly what the M3 remote does, what needs a switch the person turns on
by hand, and what is not possible. Nothing here has been run on a real TV yet: `docs/test-m3.md` is the script, and the
"expected" columns are what the code is written to do.

## What works with no setup

| Phone key | Our own screens in front (Free My TV home and settings, the Room cards) | Zoom or another app in front |
|---|---|---|
| Up, Down, Left, Right, OK, Back | Works. The key is played into the screen as a normal key press. | **Does not work.** The TV answers `needs-remote-control` and the phone says so. |
| Home | Works (brings our home screen to the front). | Works only if Android lets us start it from the background (see "Return home"), else `blocked`. |
| Volume up, down, mute | Works. | Works. It goes to AudioManager, whatever is in front. The system volume bar shows. |
| Open Zoom | Works. | Same rule as Home. |
| End meeting / return home | Works if Zoom is not in front. | See "Return home". |

Volume does nothing, and the TV says `volume-fixed`, when the TV's volume is fixed because a soundbar or an AV receiver
(HDMI-CEC, ARC) owns it. That is a property of the TV set-up; an app cannot change it.

## The options for keys over another app, and why only one is used

| Route | Needs | Verdict |
|---|---|---|
| `Instrumentation.sendKeySync` / `InputManager.injectInputEvent` | `INJECT_EVENTS`, a signature permission | Not available to an installed app. Not used. |
| `adb shell input keyevent` | A shell. The app already talks to the TV's own ADB for the home-screen switch (`Adb.java`, 127.0.0.1, once-only "Allow USB debugging?") | **Would work with no accessibility service**, but it would make every paired phone a remote shell trigger, and it needs the debugging trust to stay on. Not used in M3. See the open question in the PR. |
| `AccessibilityService.dispatchGesture` | A touch screen | TVs have no pointer, so there is nothing to tap. Not used, and the service declares no gesture capability. |
| `AccessibilityService.performGlobalAction` | The person turns the service on | **Used.** BACK and HOME on every Android version; DPAD_UP, DOWN, LEFT, RIGHT and CENTER only on Android 13 (API 33) and newer. |
| A normal Android TV app | Nothing | Can only receive keys while it is in front. That is the "own screens" column above. |

So Back, Home and the arrows over Zoom need the **Remote Control** service, and the arrows over Zoom also need
Android 13 or newer (on older TVs the answer is `needs-android-13`; Back and Home still work). Whether Android 13's D-pad
actions reach Zoom's TV-style screens is the main thing to check on a real TV.

## Remote Control (the optional accessibility service)

- **Off by default and never turned on by the app.** It appears in Settings > Accessibility as "Anannt Room TV Remote"
  only after you turn it on with `scripts/remote-access.sh <tv-ip> on`. `off` turns it off again and leaves every other
  accessibility service (TalkBack, say) exactly as it was. Free My TV > Remote Control on the TV explains it and shows
  both commands.
- **What it can do:** press Back, Home and (Android 13+) the arrows and OK for the phone; and learn the package name of
  the app whose window came to the front.
- **What it cannot do:** it declares `canRetrieveWindowContent="false"`, no key filter and no gestures
  (`tv/src/main/res/xml/room_control_service.xml`). It cannot read what is on screen, what is typed, or what Zoom shows.
  It keeps one string: the last front package, in memory, and drops it when the service stops. The service does nothing
  on its own; it acts only on a command that passed the signature, replay, rate-limit and pairing checks.
- **The trade-off:** an enabled accessibility service is a stronger permission than anything else this app holds, and
  Android shows it as such. If a paired phone is lost, revoke it on the TV (Free My TV > Paired Phones) and turn Remote
  Control off. Android switches accessibility services off when the app is updated, so run `on` again after an update.
- Without it, the phone's remote still does everything in the first table.

## Return home (and End meeting)

"Return home" must bring Free My TV's launcher forward, from the background, while Zoom is in front. Since Android 10
an app cannot start an activity from the background unless it is allowed to. The TV tries, in order:

1. **Remote Control on:** ask the system for Home (`GLOBAL_ACTION_HOME`). This is a system action, so it is not
   restricted. It lands on whichever launcher is the home screen; if that is not Free My TV (the TV is in *Original*
   mode), the TV then starts our home screen directly.
2. **Otherwise:** start `HomeActivity` (`ACTION_MAIN` + `CATEGORY_HOME`). Android lets this through only when the app
   holds "Display over other apps" (`SYSTEM_ALERT_WINDOW`). Allow it once with
   `scripts/remote-access.sh <tv-ip> overlay-on`. Without it Android drops the start silently, with no error.
3. **Check:** when the TV can see the front app (Remote Control on, or usage access, below) it waits up to 1.5 s for our
   home screen to be in front and answers `blocked` if it is not, so the phone never claims a success that did not
   happen. When it cannot see, it answers `unconfirmed`.

"End meeting" adds one step after Home is confirmed or unconfirmed: `ActivityManager.killBackgroundProcesses` on Zoom's
packages (the `KILL_BACKGROUND_PROCESSES` permission, a normal one). That ends the call and lets go of the camera and
the microphone. It only works on an app that is no longer in front, which is why Home comes first; on `blocked`
the TV does not try it. It is the same as swiping Zoom away, not a Zoom API, so Zoom may show "reconnecting" to the host
for a moment.

## Which app is in front (and what "joined" means)

The M2 card now stays up, as "Joining Zoom…", until Zoom is in front or 20 s have passed, and the phone is told. The TV
can read the front app from two optional sources, neither of which reads anything but a package name:

| Source | How to turn it on | Notes |
|---|---|---|
| Remote Control (above) | `scripts/remote-access.sh <tv-ip> on` | Window changes. System bars, the volume bar and the keyboard are ignored. |
| Usage access | `scripts/remote-access.sh <tv-ip> usage-on` (`appops set io.freemytv GET_USAGE_STATS allow`) | Needs the manifest permission `PACKAGE_USAGE_STATS`. Not every TV firmware keeps usage events; check in the test script. |

The phone then gets one of these (`zoom.state`):

| Phase | Meaning |
|---|---|
| `joining` | OK was pressed and the link was handed to Zoom. |
| `joined` | Zoom's app is in front. **It does not mean the meeting accepted the room:** a waiting room, a passcode prompt or a host who has not started the meeting looks the same from outside Zoom. The TV does not automate Zoom, so it cannot know. |
| `unconfirmed` | Handed to Zoom, but the TV has no source for the front app. Honest, not a failure. |
| `fallback` | Zoom would not take the link, or never came forward in 20 s. The ID and passcode are on the TV's screen in large type with **Open Zoom**. |
| `failed` | Zoom is installed but could not be started. The same card is shown. |
| `cancelled` / `ended` | Cancelled on the TV, or "End meeting" was used. |

## Permissions added in M3

| Permission | Why | Granted by |
|---|---|---|
| `io.freemytv.permission.ROOM_NAV` (signature, ours) | Keeps other apps from sending keys to our screens | Install (same app) |
| `KILL_BACKGROUND_PROCESSES` | End meeting | Install (normal permission) |
| `PACKAGE_USAGE_STATS` | Optional front-app check | The person: `scripts/remote-access.sh <tv-ip> usage-on` |
| `SYSTEM_ALERT_WINDOW` (from M2) | Return home / the card over other apps | The person: `scripts/remote-access.sh <tv-ip> overlay-on` |
| Accessibility service `RoomControlService` | Back, Home, arrows over other apps | The person: `scripts/remote-access.sh <tv-ip> on` |

The phone app adds no permission.

## Flood protection

After a command's signature, timestamp and nonce have passed, each paired phone has a token bucket: keys get 10 at once
and 12 more per second; every other command gets 6 at once and 2 per second. A command with no token is dropped with no
reply and no work done, and the socket stays open. The phone applies a slightly stricter limit of its own, so a held
arrow (one press every 140 ms after a 450 ms hold) never reaches the TV's limit. The limiter is `RateLimiter` and
`CommandLimits` in `:protocol`, tested in `RateLimiterTest`.
