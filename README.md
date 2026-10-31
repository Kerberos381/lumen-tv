# Lumen

A tvOS-style home screen for Android TV and Google TV: a Top Shelf, a glass top row,
spring-animated focus, apps that zoom open from their tile and shrink back into it,
and a Recents switcher on double-press Home.

Built for a Philips Android TV. Works on Android 9 (API 28) and newer.

**Privacy by design:** Lumen has **no internet permission**. Nothing it sees can leave the TV.

## What you get

| Feature | Needs |
| --- | --- |
| Home grid, glass top row, focus springs, zoom open/close | Nothing |
| Long-press OK: Move, Hide, App info, Uninstall | Nothing |
| Top Shelf hero (artwork + name of the focused top-row app) | Nothing |
| Top Shelf "Up Next" rows published by apps | "Top Shelf content" permission, and a TV that lets launchers read them |
| Control Center (Menu): volume, play/pause, Recents, background, TV settings shortcuts | Nothing |
| Backgrounds: app artwork, or Aurora, Dusk ocean, Night desert, Dark (drawn on the TV) | Nothing |
| Date and time on a glass pill that adapts to the backdrop | Nothing |
| Recents (double-press Home) with app artwork | Lumen as default Home, or the helper |
| Recents including apps opened outside Lumen | "Usage access" |
| Recents with live snapshots | The helper, Android 11+ |
| Home always returns to Lumen | The helper |

## Remote

| Button | On Home | In Recents |
| --- | --- | --- |
| D-pad | Move between tiles; ▲ from the top row enters Up Next | ◀ ▶ browse, ▲ close the app, ▼ return |
| OK | Open | Open |
| Hold OK | Options (Move, Hide, App info, Uninstall) | |
| Back | Jump to the top row | Return |
| Home | Back to the top row | Return |
| Home ×2 | Recents | |
| Menu | Control Center | |

Lumen settings are in Control Center and on the **Lumen** tile at the end of the grid, and a **Settings** tile opens
the TV's own settings (the stock launcher's gear is gone once Lumen is your home screen).

## Install

The APK is built by GitHub Actions on every push to `main`. Download
`Lumen-0.1.N.apk` from the newest entry under **Releases**. `SHA256.txt` next to it
lets you verify the file.

### Easiest: ADB from a computer (recommended)

Installing with ADB also avoids Android 13+'s "restricted settings" block on
turning on the helper for sideloaded apps.

1. On the TV: **Settings → Device Preferences → About**, press **Build** 7 times to
   unlock developer options. Then **Device Preferences → Developer options**, turn on
   **USB debugging** / **Network debugging**. Note the TV's IP address
   (**Settings → Network**).
2. On the computer (Mac: `brew install android-platform-tools`):

```sh
adb connect TV_IP:5555            # accept the prompt on the TV
adb install -r Lumen-0.1.N.apk

# Make the Home button open Lumen
adb shell cmd package set-home-activity app.lumen.launcher/.HomeActivity

# Optional: Recents includes apps opened outside Lumen
adb shell appops set app.lumen.launcher GET_USAGE_STATS allow

# Optional: Top Shelf can read Up Next rows
adb shell pm grant app.lumen.launcher com.android.providers.tv.permission.READ_EPG_DATA
```

### Without a computer

Copy the APK to the TV (USB stick, or an app like *Send Files to TV*), open it in a
file manager, allow **Install unknown apps** for that file manager, and install. Open
Lumen from the TV's own home screen, then go to the **Lumen** tile → settings.

## Turn on the helper (optional)

The helper is an accessibility service. It adds snapshots in Recents, double-press Home
from inside any app, and "always return to Lumen" for TVs where the Home button can't be
reassigned.

On the TV: **Settings → Device Preferences → Accessibility → Lumen helper → On**.
Or from Lumen settings → **Lumen helper**.

Via ADB (note: this replaces any other enabled accessibility services):

```sh
adb shell settings put secure enabled_accessibility_services app.lumen.launcher/app.lumen.launcher.HelperService
adb shell settings put secure accessibility_enabled 1
```

What it can and cannot do:

- **Can:** see which app is in front, notice the Home button, take one small snapshot
  (640 px wide) of the app you leave when you press Home. Snapshots are stored only in
  Lumen's private storage and are deleted when you close the app in Recents.
- **Cannot:** read text or controls inside other apps (`canRetrieveWindowContent` is off),
  or swallow key presses. Protected video (Netflix etc.) captures as black, so Lumen
  shows the app's artwork instead.

## Permissions

| Permission | Why |
| --- | --- |
| `PACKAGE_USAGE_STATS` | Recents: when apps were last used. Granted manually ("Usage access"). |
| `KILL_BACKGROUND_PROCESSES` | Recents: ▲ closes the app's background process. |
| `REQUEST_DELETE_PACKAGES` | Long-press → Uninstall opens the system uninstall dialog. |
| `READ_EPG_DATA` | Top Shelf: read Up Next rows apps publish. Asked once on first start. |
| *No* `INTERNET` | Nothing can be sent off the TV. Up Next images are shown only when they are stored on the TV. |

## Known limits

- **Top Shelf content depends on the TV.** Many Android TV builds only let the system
  launcher read other apps' Up Next rows. When nothing comes back, Top Shelf shows the
  app's artwork and name.
- **Default Home.** Some TVs have no "Home app" setting. Use the ADB command above, or
  turn on the helper with "Always return to Lumen" (the stock home screen may flash briefly).
- **Snapshots** need Android 11+. On older TVs Recents shows artwork.
- **Dismiss in Recents** stops the app's background process; it does not remove it from
  Android's own task list.
- **Inputs (HDMI)** stay on the remote's Sources/Input button.

## Build locally

Android Studio (Ladybug or newer) → Open this folder → Run. Or:

```sh
./gradlew assembleRelease
```

Stack: Kotlin, Android framework only (no AndroidX, no third-party libraries).
`minSdk 28`, `targetSdk 34`.

## Signing key

Builds are signed with a personal key that is **not** in the repository. CI reads it from
three GitHub Actions secrets: `LUMEN_KEYSTORE_B64` (the PKCS12 keystore, base64),
`LUMEN_STORE_PASSWORD` and `LUMEN_KEY_PASSWORD`. Without them the build fails rather
than publish an APK that can't update yours.

For local release builds, put the keystore at `keystore/lumen.p12` (ignored by git) and
export the two passwords. Without it, local builds use the debug key.

Keep a backup of the key: an APK signed with a different key cannot install over Lumen,
and switching keys means uninstalling (which resets your layout).

## Code map

| File | What |
| --- | --- |
| `HomeActivity.kt` | Home screen, focus model, scrolling, open/close transitions, move mode |
| `TileView.kt`, `ArtPainter.kt` | Tile drawing: artwork, shadow, sheen, label, focus spring |
| `TopShelf.kt` | Up Next reader (TvContract) and the shelf UI |
| `Overlays.kt` | Long-press menu and the Recents switcher |
| `HelperService.kt` | Optional accessibility helper |
| `RecentsStore.kt` | Recent history, snapshots, usage access |
| `AppRepository.kt` | Installed apps, banners, saved order |
| `SettingsActivity.kt` | Lumen settings |
| `Spring.kt` | Closed-form spring interpolator |
