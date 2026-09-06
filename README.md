# Fire TV Channel Guide (Classic Recast EPG)

Sideloadable Android TV / Fire TV app that recreates the **classic Fire TV Recast channel guide**: a dense multi-day **channel × time** timeline EPG grid (not the newer On Now / Up Next layout).

**v1.0.3** uses **sample OTA-style EPG data** for the grid. **Tune** prefers a `TvContract` Live TV VIEW when a system channel id is visible; otherwise it opens Live TV and injects channel digits (`input text` + DPAD_CENTER) — the same pattern as `adb shell input text`.

Target device: **Fire TV Cube (3rd gen)** and other Fire TV / Android TV boxes (leanback launcher).

## Features

- Sticky left channel column (number + call sign, favorites marked)
- Sticky top time axis with ~30-minute slots and a red **now** line
- Program cells sized by duration across ~10 days of sample schedule
- Top detail panel (title, time range, rating, HD, description)
- Filter pills: All / Favorites / Sports / News / Movies / Kids / TV Shows
- **Tune** — prefers `TvContract` Live TV VIEW when a system id is visible; otherwise opens Live TV and injects channel digits (`input text` + DPAD_CENTER)
- **Record** remains a stub (Toast)
- Dark theme aligned with classic Recast guide aesthetics

## Project layout

```
firetv-channel-guide/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/nickora/firetv/channelguide/
│       │   ├── MainActivity.kt
│       │   ├── data/Models.kt
│       │   ├── data/SampleEpgData.kt
│       │   ├── tv/RecastTuner.kt
│       │   └── ui/EpgGuideView.kt
│       └── res/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradle wrapper
└── README.md
```

## Requirements

- JDK 17+ (project compiles with Java 17 bytecode; JDK 21 works)
- Android SDK with **compileSdk 34** (Android Studio Ladybug+ recommended)
- For sideload: `adb` from platform-tools
- On device: `READ_TV_LISTINGS` / EPG read so the app can query `TvContract.Channels` (declared in the manifest; grant via system settings / `adb` if Fire OS prompts)

## Build

From this directory:

```bash
./gradlew assembleDebug
```

Debug APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

Release (unsigned / package-only):

```bash
./gradlew assembleRelease
```

### If the Gradle wrapper is missing

```bash
gradle wrapper --gradle-version 8.7
# or with Android Studio: open the project and let it generate the wrapper
```

Then re-run `./gradlew assembleDebug`.

## Sideload to Fire TV Cube

1. On the Cube: **Settings → My Fire TV → Developer options**
   - Turn on **ADB**
   - Turn on **Apps from Unknown Sources** (if prompted later)
2. Note the Cube IP: **Settings → My Fire TV → About → Network**
3. From your PC:

```bash
adb connect <cube-ip>:5555
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

4. Launch **Channel Guide** from the Apps library (or):

```bash
adb shell am start -n com.nickora.firetv.channelguide/.MainActivity
```

### D-pad tips

- Move within the grid with the remote directional pad
- **Select / Play** on a cell updates details and **Tunes** to that channel in Live TV / Recast
- Filter pills and Tune/Record are focusable; Down from Tune/Record returns to the grid

## How Tune works

1. On launch, `RecastTuner` queries `TvContract.Channels.CONTENT_URI` for `_ID`, display number/name, `input_id`, browsable.
2. Prefers rows whose `input_id` contains `hedwig` (Recast TV input), falling back to any browsable match.
3. Builds a map keyed by normalized display number (`4.1` / `4-1` → same key).
4. On Tune / program select:
   - **If a system channel id matches** (rare for third-party apps on Fire OS): `ACTION_VIEW` + `content://android.media.tv/channel/<ID>` targeting Live TV.
   - **Else (usual path)**: launch `com.amazon.tv.livetv/.TvChannelsPlayerActivityAlias` with `ACTION_VIEW` (no channel URI), wait ~2s, then best-effort shell:
     - `input text <number>` (sanitized digits / `.` / `-` only; hyphen form like `15-1` sent as `15.1`)
     - `input keyevent 23` (DPAD_CENTER)
   - Failure Toast only if digit injection fails: `Couldn't send channel keys — is ADB debugging still on?`
5. Digit entry needs a **debuggable** install with ADB debugging enabled so the app can run `input` (same groups as `adb shell input`). No root.

Sample guide numbers are Twin Cities–style — align them with your Recast lineup for the best experience.

Shell reference (TvContract path):

```bash
am start -a android.intent.action.VIEW -d content://android.media.tv/channel/<ID> \
  -n com.amazon.tv.livetv/.TvChannelsPlayerActivityAlias
```

Digit-entry equivalent (what the app injects after opening Live TV):

```bash
input text 4.1
input keyevent 23
```

## Sample data

`SampleEpgData` generates ~12 Twin Cities–style OTA channels (CBS/ABC/NBC/FOX/PBS/CW/…) with programs spanning about **10 days** relative to device “now”. No network calls for the guide grid.

## Next steps

1. **EPG source** — Replace `SampleEpgData` with XMLTV, Schedules Direct, or Recast lineup numbers so guide rows match the device map.
2. **Record** — Hook Record to a DVR API or local recording service; keep UI stubs until then.
3. **Artwork** — Optional poster in the detail panel (Glide/Coil) when metadata includes images.
4. **Performance** — For multi-week guides, consider windowed loading / recycling instead of a single canvas window.

## Tech

| Item | Choice |
|------|--------|
| Language | Kotlin |
| UI | Custom `EpgGuideView` (Canvas) + AppCompat layouts — reliable dense bidirectional EPG on TV |
| Tune | `RecastTuner` → `TvContract` VIEW, else Live TV + `input text` digit entry |
| minSdk | 28 |
| targetSdk / compileSdk | 34 |
| Leanback | Manifest leanback launcher; touchscreen not required |

## License

Personal / sideload project for Nick — sample EPG + system Live TV tune; not affiliated with Amazon or Fire TV Recast.
