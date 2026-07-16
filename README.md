# NovelGrabber — Android

**Download web novels and light novels, read them offline, and listen to them with text‑to‑speech — on Android.**

The native Android client for **NovelGrabber**. It turns web serials into a clean, ad‑free personal library. The app reads novels aloud using the phone's **on‑device Google TTS voices** (with background playback and lock‑screen controls). There's no account, no server, and nothing leaves your device.

---

## Features

* **Grab whole novels** from a chapter‑1 or contents URL, in batches of any size.


* **Built‑in browser** to browse supported sites, solve captchas/log in, and scrape the page you're on — no copy‑pasting links.


* **Library** with cover‑art cards, reading progress, search, and **categories** (General / Completed / your own) plus one‑click **Auto‑sort** that groups multi‑volume series by title similarity.


* **Reader** with four themes (Dark / Dark Sepia / Sepia / Light — applied app‑wide), adjustable font/size/spacing, resume‑where‑you‑left‑off, and LN **illustrations** rendered inline.


* **Text‑to‑speech:** Utilizes the phone's own **on‑device Google voices**, with background playback + lock‑screen controls.


* **EPUB & PDF export** per novel (images included), and **import** existing `.epub` files/folders into the library.


* **Built‑in ad blocker** for the browser (host‑based, tuned for the ad networks novel sites use).



---

## Build

**Requirements:** Android SDK (compileSdk 36, minSdk 24), JDK 21.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

```

Sideload the APK, then install/enable **Speech Recognition & Synthesis by Google** so the on‑device TTS voices are available.

## Layout

```
app/src/main/
├── java/com/novelgrabber/app/   # MainActivity, Engine, TtsService, Library, EpubRead/Writer, AdBlock…
└── assets/                      # app.html (UI), reader.html (reader), sites.json (per-site rules)

```

## Legal

For personal, offline reading only. Respect the terms of service of the sites you use and the copyright of the works you download. This project is not affiliated with any of the sites listed.

## License
MIT — see [`LICENSE`](LICENSE).
