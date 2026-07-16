# NovelGrabber — Android

The native Android client for **NovelGrabber**. Downloads web/light novels, reads them offline
with four themes, and reads them aloud using the phone's **on‑device Google TTS voices**
(background playback + lock‑screen controls).

See the [main README](../Novel%20Scraper%20Final/README.md) for the full project overview.

## Build

Requirements: Android SDK (compileSdk 36, minSdk 24), JDK 21.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Sideload the APK, then install/enable **Speech Recognition & Synthesis by Google** so the
on‑device TTS voices are available.

## Layout

```
app/src/main/
├── java/com/novelgrabber/app/   # MainActivity, Engine, TtsService, Library, EpubRead/Writer, AdBlock…
└── assets/                      # app.html (UI), reader.html (reader), sites.json (per-site rules)
```
