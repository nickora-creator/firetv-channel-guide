# Fire TV Channel Guide (Classic Recast EPG)

Sideloadable Android TV / Fire TV app that recreates the **classic Fire TV Recast channel guide**: a dense multi-day **channel × time** timeline EPG grid (not the newer On Now / Up Next layout).

**v1 uses sample OTA-style EPG data only** — no Recast API, no live tune.

Target device: **Fire TV Cube (3rd gen)** and other Fire TV / Android TV boxes (leanback launcher).

## Features

- Sticky left channel column (number + call sign, favorites marked)
- Sticky top time axis with ~30-minute slots and a red **now** line
- Program cells sized by duration across ~10 days of sample schedule
- Top detail panel (title, time range, rating, HD, description)
- Filter pills: All / Favorites / Sports / News / Movies / Kids / TV Shows
- D-pad focus navigation; Tune / Record buttons are **stubs** (Toast)
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
- **Select / Play** on a cell updates details and triggers the Tune stub Toast
- Filter pills and Tune/Record are focusable; Down from Tune/Record returns to the grid

## Sample data

`SampleEpgData` generates ~12 Twin Cities–style OTA channels (CBS/ABC/NBC/FOX/PBS/CW/…) with programs spanning about **10 days** relative to device “now”. No network calls.

## Next steps (real EPG + Recast)

1. **EPG source** — Replace `SampleEpgData` with a repository that loads XMLTV, Schedules Direct, or a Recast/antenna backend.
2. **Tune** — Wire `stubTune()` to Recast / HDMI-tuner / external-player intents once a public or reverse-engineered tune API is available.
3. **Record** — Hook Record to a DVR API or local recording service; keep UI stubs until then.
4. **Artwork** — Optional poster in the detail panel (Glide/Coil) when metadata includes images.
5. **Performance** — For multi-week guides, consider windowed loading / recycling instead of a single canvas window.

## Tech

| Item | Choice |
|------|--------|
| Language | Kotlin |
| UI | Custom `EpgGuideView` (Canvas) + AppCompat layouts — reliable dense bidirectional EPG on TV |
| minSdk | 28 |
| targetSdk / compileSdk | 34 |
| Leanback | Manifest leanback launcher; touchscreen not required |

## License

Personal / sideload project for Nick — sample EPG only; not affiliated with Amazon or Fire TV Recast.
