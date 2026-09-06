# Fire TV Channel Guide (Classic Recast EPG)

Sideloadable Android TV / Fire TV app that recreates the **classic Fire TV Recast channel guide**: a dense multi-day **channel × time** timeline EPG grid (not the newer On Now / Up Next layout).

**v1.0.4** uses **sample OTA-style EPG data** (leads with confirmed Recast **2.1** / **2.2**). Tuning uses **Ch − / Ch +** (`input keyevent 167` / `166`) after bringing Live TV forward — digit entry does **not** tune on Recast Cube and is disabled.

Target device: **Fire TV Cube (3rd gen)** and other Fire TV / Android TV boxes (leanback launcher).

## Features

- Sticky left channel column (number + call sign, favorites marked)
- Sticky top time axis with ~30-minute slots and a red **now** line
- Program cells sized by duration across ~10 days of sample schedule
- Top detail panel (title, time range, rating, HD, description)
- Filter pills: All / Favorites / Sports / News / Movies / Kids / TV Shows
- **Ch − / Ch +** — open Live TV, then send CHANNEL_DOWN (167) / CHANNEL_UP (166)
- Program select still opens Live TV and best-effort CHANNEL_UP (optional TvContract VIEW if a system id is visible)
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
- **ADB debugging on** so the app can run `input keyevent` (same groups as `adb shell input`). Debuggable debug APK required. No root.

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
- **Select / Play** on a cell updates details and best-effort opens Live TV + Ch+
- Use **Ch −** / **Ch +** to step Recast channels (e.g. 2.1 ↔ 2.2)
- Filter pills and action buttons are focusable; Down from them returns to the grid

## How Ch+/Ch− work (v1.0.4)

Device testing on Fire TV Cube Recast showed:

| Method | Result |
|--------|--------|
| `input text` / digit keyevents | Does **not** tune; often pauses Live TV |
| `input keyevent 166` (CHANNEL_UP) | **Does** change Recast channel (e.g. 2.1 → 2.2) |
| `input keyevent 167` (CHANNEL_DOWN) | **Does** change Recast channel |
| `input keyevent 85` (play/pause) | Resumes when paused (not used by the app) |

App behavior:

1. On launch, `RecastTuner` still queries `TvContract.Channels` (usually empty for 3P apps on Fire OS).
2. **Ch +** / **Ch −**:
   - Launch `com.amazon.tv.livetv/.TvChannelsPlayerActivityAlias`
   - Wait ~650ms
   - `input keyevent 166` or `167`
3. **Program select**: prefer TvContract VIEW if a system id matched; else same as Ch + (Live TV + CHANNEL_UP).
4. Digit-entry path remains in code behind `ENABLE_EXPERIMENTAL_DIGIT_ENTRY = false` (off by default).
5. Parental PIN can block input — ignored in code (optional failure toast if shell `input` fails).

Shell equivalents:

```bash
# Bring Live TV forward, then:
input keyevent 166   # Ch +
input keyevent 167   # Ch −
```

Optional TvContract path (rare for 3P apps):

```bash
am start -a android.intent.action.VIEW -d content://android.media.tv/channel/<ID> \
  -n com.amazon.tv.livetv/.TvChannelsPlayerActivityAlias
```

## Sample data

`SampleEpgData` leads with confirmed Recast channels **2.1** and **2.2** (PBS-style names), plus a handful of generic OTA placeholders (`4.1`, `5.1`, `9.1`, `11.1`, …) — not tied to a specific DMA. Programs span about **10 days** relative to device “now”. No network calls for the guide grid.

## Next steps

1. **EPG source** — Replace `SampleEpgData` with XMLTV, Schedules Direct, or your real Recast lineup numbers.
2. **Record** — Hook Record to a DVR API or local recording service; keep UI stubs until then.
3. **Artwork** — Optional poster in the detail panel (Glide/Coil) when metadata includes images.
4. **Performance** — For multi-week guides, consider windowed loading / recycling instead of a single canvas window.

## Tech

| Item | Choice |
|------|--------|
| Language | Kotlin |
| UI | Custom `EpgGuideView` (Canvas) + AppCompat layouts — reliable dense bidirectional EPG on TV |
| Tune | `RecastTuner` → Live TV + CHANNEL_UP/DOWN keyevents; optional TvContract VIEW |
| minSdk | 28 |
| targetSdk / compileSdk | 34 |
| Leanback | Manifest leanback launcher; touchscreen not required |

## License

Personal / sideload project for Nick — sample EPG + system Live TV channel step; not affiliated with Amazon or Fire TV Recast.
