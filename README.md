# NovelGrabber — Android

**Download web novels and light novels, read them offline, and listen to them with text‑to‑speech — on Android.**

The native Android client for **NovelGrabber**. It turns web serials into a clean, ad‑free personal library. The app reads novels aloud using the phone's **on‑device Google TTS voices** (with background playback and lock‑screen controls). There's no account, no server, and nothing leaves your device.

---

## Features

* **Grab whole novels & Queued Scrape:** Download from a chapter‑1 or contents URL in batches of any size, or toggle **Queued Scrape** to queue multiple novels directly from the built‑in browser with live chapter progress (`0/total`), status badges (**In queue**, **Scraping**, **Done**), and automatic back‑to‑back downloading.

* **Built‑in browser** to browse supported sites, solve captchas/log in, and scrape or queue the page you're on — no copy‑pasting links.

* **Library & Inline Collection Cards:** Cover‑art cards, reading progress, scroll position memory, search, and inline **Collection** cards in `All`, `Recent`, and `Completed`, plus smart **Auto‑sort** (ordered subsequence + fuzzy prefix matching with a live sorting progress modal) that groups multi‑volume series automatically.

* **Reader with Arabic & RTL Support:** Four themes (Dark / Dark Sepia / Sepia / Light — applied app‑wide), automatic right‑to‑left (`dir="auto"`) rendering for Arabic and RTL text, auto‑scrolling to the active chapter in the Table of Contents, adjustable font/size/spacing, resume‑where‑you‑left‑off, and LN **illustrations** rendered inline.

* **Text‑to‑speech:** Utilizes the phone's own **on‑device Google voices** (including automatic Arabic voice selection and RTL punctuation chunking), with background playback + lock‑screen controls and custom pronunciation rules.

* **Zero‑Copy Linked EPUB & PDF Import + Export:** Export any novel to EPUB or PDF (images included), or import existing `.epub` and `.pdf` files/folders into the library as lightweight links without duplicating storage.

* **Built‑in ad blocker** for the browser (host‑based, tuned for the ad networks novel sites use).

---

## Build

**Requirements:** Android SDK (compileSdk 36, minSdk 24), JDK 21.

```bash
./gradlew assembleDebug
# Prebuilt APK is also included at the repository root: NovelGrabber.apk
```

Sideload `NovelGrabber.apk`, then install/enable **Speech Recognition & Synthesis by Google** so the on‑device TTS voices are available.

## Layout

```
app/src/main/
├── java/com/novelgrabber/app/   # MainActivity, Engine, Library, EpubImport/Read/Writer, TtsService, ScrapeService…
└── assets/                      # app.html (UI & Queue), reader.html (Reader & TTS), sites.json (per-site rules)
```

## Legal

For personal, offline reading only. Respect the terms of service of the sites you use and the copyright of the works you download. This project is not affiliated with any of the sites listed.

## License
MIT — see [`LICENSE`](LICENSE).
