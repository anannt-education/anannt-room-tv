# Zoom launch matrix (to fill on a real TV)

No TV was reachable when M2 was written, so every result cell below is empty on purpose. Nothing here is a claim
about what Zoom does on a TV: fill it in from `docs/test-m2.md`, then say which route to keep.

Rules the TV follows: the official Zoom app only, Zoom's public link formats only, no automation of Zoom's screens,
no SDK. The log line for each attempt is `zoom: route=<deep-link|web-link|app-only|not-installed|failed> package=<name>`
and never carries the meeting ID, passcode or link.

## The three routes, in the order the TV tries them

| # | Route | What the TV does | Counts as worked when |
|---|---|---|---|
| 1 | `deep-link` | `ACTION_VIEW zoomus://zoom.us/join?confno=<id>&pwd=<pwd>&uname=<name>`, aimed at the Zoom package when one is installed | Zoom opens and shows the meeting (or its join or waiting-room screen) without anyone typing the ID |
| 2 | `web-link` | `ACTION_VIEW https://zoom.us/j/<id>?pwd=<pwd>`, set to the Zoom package (skipped when no Zoom package or the app doesn't handle that link) | Same as route 1 |
| 3 | `app-only` | Opens Zoom's launcher activity. The card stays up with the meeting ID and passcode in large type and an **Open Zoom** button | Zoom opens; the person types the ID and passcode from the card |

`not-installed` (no Zoom package and nothing handles `zoomus://`) shows "Zoom is not installed on this TV" with the ID
and passcode in large type. `failed` is Zoom installed but not launchable; the card shows the same large ID and passcode.

## Which Zoom app is on the TV

Fill from `adb shell pm list packages | grep -i zoom`. The TV recognises these packages, and also any app that
handles `zoomus://` links, so an unlisted name still works.

| Package | Seen on this TV? | App name and version (`adb shell dumpsys package <pkg> \| grep versionName`) | Notes |
|---|---|---|---|
| `us.zoom.videomeetings` (Zoom, the usual phone and tablet app) | | | |
| `us.zoom.zrc` (Zoom Rooms controller) | | | |
| other: | | | |

## Results per TV

One block per TV model. Android and Google TV version from `adb shell getprop ro.build.version.release` and the model
from `ro.product.model`.

**TV:** (brand, model, Android version, date tested, tester)

| Test | Route 1 deep-link | Route 2 web-link | Route 3 app-only |
|---|---|---|---|
| `adb shell am start` of that link by hand (docs/test-m2.md step 4) | | | |
| Joined from the phone while Free My TV was on screen | | | |
| Joined from the phone while Zoom was already open | | | |
| Joined from the phone while YouTube was in front | | | |
| Meeting with a link token (`?pwd=`) | | | |
| Meeting with only a typed passcode | | | |
| Meeting with no passcode | | | |
| Webinar link (`/w/`) | | | |
| Name shown in the meeting matches the phone's "Name in the meeting" | | | |

Cell values: **works** (joined with no typing), **opens Zoom only** (Zoom opened but ignored the meeting), **no
handler** (Android refused), **error** (what the screen said). Add a line under the table for anything odd.

## The decision this gives us

- Route that should lead on this TV:
- Does the card open over Zoom or YouTube with `SYSTEM_ALERT_WINDOW` allowed? Without it?
- Does Zoom on this TV ask for a sign-in before it will join? (If so, route 3 is the honest answer.)
- Anything the card should say that it doesn't:
