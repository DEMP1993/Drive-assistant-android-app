# Drive Assistant — Android App

The companion app for **[Drive Assistant](https://github.com/DEMP1993/Drive-assistant-device-firmware)** —
a small, round display for your car dashboard or bike handlebar. The app
connects your phone to the display over Bluetooth Low Energy and sends it
your **Google Maps turn-by-turn navigation** (maneuver icon, distance,
street name) and your **current music**, so your phone can stay in your
pocket with the screen off.

> Want the display itself? The step-by-step guide is on the project website
> **[getdriveassistant.com](https://getdriveassistant.com)**; the firmware
> and technical details are in the
> **[firmware repository](https://github.com/DEMP1993/Drive-assistant-device-firmware)**.

## What the app does

- Reads the ongoing **Google Maps navigation notification** (with your
  permission) and forwards the maneuver icon, distance and street name to
  the display — about once per second, only when something changed.
- Mirrors the **current media playback** (title/artist) to the display's
  music screen and executes the display's buttons (play/pause, next,
  previous) on your phone's active player.
- **Connects automatically** when the display is switched on — even while
  the app is closed — and reconnects by itself after a drop. On Android 12+
  the display is paired once as a companion device, so Android itself
  watches for it and starts the app. A small notification is shown only
  while the display is connected.
- Includes a **test packet** button so you can verify the connection before
  you drive.

> **Honest note:** Google Maps has no official interface for navigation
> data, so the app interprets the Maps notification. This works well, but
> can depend on the Maps version and language — there is a debug mode to
> adapt it (see below). **iOS is not supported.**

## Privacy

The app asks for **notification access**, and Android shows its general
warning for that permission. What the app actually does with it:

- It processes **only Google Maps notifications** — anything from any other
  app is discarded immediately (see the package filter at the top of every
  callback in `MapsNotificationListenerService.kt`), plus the media playback
  state (title/artist) for the music screen.
- It has **no internet permission** (check `AndroidManifest.xml`) — it is
  technically unable to send anything anywhere. Your data goes exclusively
  over Bluetooth to your own display.

Don't take our word for it — the code is right here.

## Install

### Option A — download the APK (recommended)

1. On your phone, open the [latest release](https://github.com/DEMP1993/Drive-assistant-android-app/releases/latest)
   and download `DriveAssistant-<version>.apk`.
2. Tap the downloaded file. Android asks whether to allow installs from
   this source (browser or Files app) — allow it, then tap **Install**.
3. Open **Drive Assistant** and follow the set-up steps below.

Updates: download the new APK and install it over the old one — your
settings are kept. (All releases are signed with the same key.)

### Option B — build it yourself

1. Install [Android Studio](https://developer.android.com/studio).
2. Open this project folder (*Open*, not *Import*) and let the Gradle sync
   finish — the Gradle wrapper is completed automatically on first sync.
3. Connect your phone via USB (USB debugging enabled) and press **Run ▶**.

Requires Android 8 or newer.

## Set up (on the phone)

The app guides you through three steps, each with a status indicator:

1. **Allow notification access** — needed to read the Google Maps
   navigation notification (and to see your media playback).
2. **Switch on the display and pair it once** — on Android 12+ the app
   shows the system dialog "Pair with Drive Assistant" (from 0.21). After
   that it connects automatically. If it doesn't, **Search & connect** does
   it manually. Use **send test packet** to check everything works.
3. **Start navigating in Google Maps** — the display comes alive.

Tips for reliable background operation:

- Exclude the app from battery optimization / allow autostart. Some phone
  brands (e.g. Xiaomi/HyperOS) restrict background apps aggressively — the
  in-app **help dialog (ⓘ)** covers the needed switches. On Xiaomi, Redmi
  and POCO phones the app detects a missing autostart permission and shows
  a button that opens the right settings page.
- The small "Drive Assistant active" notification (shown while the display
  is connected) is intentional — it keeps the system from stopping the app
  mid-drive. Lock the app in the recent-apps overview so "close all"
  doesn't end it; if that happens anyway, the app reconnects on its own.
- After switching Bluetooth off and on, open the app once so the automatic
  connection is armed again.
- Google Maps notifications must be enabled on your phone.

## Adapting the parser (debug mode)

If a maneuver shows as wrong or "unknown" in your language:

1. `DEBUG_DUMP = true` (default) in `MapsNotificationListenerService.kt`
   logs the raw notification fields.
2. Filter Logcat for the tag `MapsListener` to see `title`, `text`,
   `subText`, `bigText` of the Maps notification.
3. Add matching keywords in `NavParser.kt` (`detectManeuver` /
   `streetMarkers`). Pull requests welcome!

The tag `BleManager` shows scan/connect/send status.

## For developers

The app is a small Kotlin project (no external BLE libraries): a
`NotificationListenerService` reads Maps and media sessions, `NavParser`
maps notification text to maneuver tokens, and `BleManager` talks to the
display. The BLE protocol is documented in the firmware repository:
[PROTOCOL.md](https://github.com/DEMP1993/Drive-assistant-device-firmware/blob/main/PROTOCOL.md).

## License

Copyright (c) 2026 Marius Pöhler.

This project is **source-available** under the
[PolyForm Noncommercial License 1.0.0](LICENSE.md): you may use, modify and
share it freely for **personal and other noncommercial purposes**.
**Commercial use requires a separate license** — contact
<info@getdriveassistant.com>.

This project is not affiliated with or endorsed by Google. "Google Maps" is
a trademark of Google LLC.
