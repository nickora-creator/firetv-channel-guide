# Fire TV Channel Guide (Classic Recast EPG)

Sideloadable Android TV / Fire TV app that recreates the **classic Fire TV Recast channel guide**: a dense multi-day **channel × time** timeline EPG grid (not the newer On Now / Up Next layout).

**v1.0.1** uses **sample OTA-style EPG data** for the grid, but **Tune** opens real **Live TV / Recast** via the system TV provider (`TvContract`) and Amazon Live TV player intent.

Target device: **Fire TV Cube (3rd gen)** and other Fire TV / Android TV boxes (leanback launcher).

## Features

- Sticky left channel column (number + call sign, favorites marked)
- Sticky top time axis with ~30-minute slots and a red **now** line
- Program cells sized by duration across ~10 days of sample schedule
- Top detail panel (title, time range, rating, HD, description)
- Filter pills: All / Favorites / Sports / News / Movies / Kids / TV Shows
- **Tune** — matches the focused guide channel to a system TV channel (prefer Recast/Hedwig `input_id`) by `display_number` (with callSign/name fallback), then starts Live TV: `ACTION_VIEW` on `content://android.media.tv/channel/<id>` (explicit `com.amazon.tv.livetv/.TvChannelsPlayerActivityAlias` when resolvable)
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
4. On Tune / program select: resolve guide `Channel.number` (then callSign/name) → system channel id →  
   `am`-equivalent: `ACTION_VIEW` + `content://android.media.tv/channel/<ID>` targeting Live TV when possible.
5. **Failure modes**: missing TV listings permission, empty provider, or no number/name match → Toast with reason; optionally still opens Live TV without a specific channel.

Sample guide numbers are Twin Cities–style and may not match every Recast lineup (e.g. WHRO/PBS). Matching is by **display number** (and name fallback), not hard-coded IDs — align sample numbers with your lineup for reliable hits.

Shell reference (device):

```bash
am start -a android.intent.action.VIEW -d content://android.media.tv/channel/<ID> \
  -n com.amazon.tv.livetv/.TvChannelsPlayerActivityAlias
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
| Tune | `RecastTuner` → `TvContract` + Live TV VIEW intent |
| minSdk | 28 |
| targetSdk / compileSdk | 34 |
| Leanback | Manifest leanback launcher; touchscreen not required |

## License

Personal / sideload project for Nick — sample EPG + system Live TV tune; not affiliated with Amazon or Fire TV Recast.
