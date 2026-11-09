# AI Reality Scanner

An Android AI vision assistant. Point your phone's camera at something and get an explanation of what it is. It covers objects, plants, documents, products, machines, landmarks, medicine labels, animals and currency notes. After a scan you can keep asking follow-up questions by text or voice.

Built with Kotlin, Jetpack Compose (Material 3), CameraX, Hilt, Room, DataStore, WorkManager, Retrofit/OkHttp, Google ML Kit and Android's speech APIs.
The target phone is the **Vivo V40** (Android 14, Funtouch OS). The minimum supported version is Android 10.

> **Status:** The project compiles, and GitHub Actions builds the debug APK. It has **not** been tested on a Vivo V40 or any other physical device. Before relying on it, test it on your own phone (see `documentation/INSTALL_VIVO_V40.md`).

---

## Features

| Area | What it does |
|---|---|
| **Scanner** | Full-screen CameraX preview with a Scan button and 9 scan modes. Also has flash, gallery import, voice questions, a shortcut to your most recent scan, pinch-to-zoom and tap-to-focus. |
| **Scan modes** | Object, Plant, Document, Product, Machine, Landmark, Medicine label, Animal, Currency. Each mode has its own prompt and its own result sections. |
| **AI vision engine** | You can switch between providers: **Anthropic Claude**, **OpenAI** (or any OpenAI-compatible endpoint) and **on-device ML Kit** (offline). |
| **Honesty** | Every result shows a confidence level and explains it. It separates what was observed from what the AI inferred and flags what needs checking. The prompts forbid made-up model numbers and locations. |
| **Result screen** | Shows the image, subject, confidence, summary, expandable sections, key facts, applications, safety notes and extracted text (copy or translate). Also: barcodes, personal notes, Listen, Save, Share, PDF export, re-analyse and delete. |
| **Ask anything** | Unlimited follow-up questions for each scan. The original image is kept with the conversation and sent as context. |
| **Live mode** | Every ~0.7 s, on-device ML Kit detects objects (with boxes), reads text, decodes QR codes and barcodes, and can translate text. Optionally, the cloud AI describes the scene, but only when the interval has passed **and** the scene has changed. The screen shows clearly when a frame is uploading, plus Pause and Stop buttons. |
| **Voice** | Speech-to-text questions and text-to-speech answers in English, हिन्दी and বাংলা. There's a stop-speaking button, an option to read answers aloud automatically, and an optional hands-free mode that keeps listening after each answer. |
| **Discover** | Topic cards that start the matching scan mode, a "compare two scans" entry point, and AI-generated interesting facts. Facts are cached once per day per topic and labelled as AI-generated. |
| **AI Chat** | Continue the conversation about any scan, or start a text-only chat. |
| **History** | Search across titles, subjects, summaries, extracted text and notes. Filter by favourites or category. You can delete scans, favourite them, add notes, export a PDF report or export all history as JSON. |
| **Compare** | Choose two saved scans. The AI identifies both and explains the similarities and differences. |
| **Privacy** | You must give explicit consent before any image is uploaded, and you can revoke it. Your API key is encrypted with the Android Keystore. The microphone is only requested once you enable voice. Everything is stored in private app storage and excluded from backups. You can delete all history or all images. |
| **Background** | A WorkManager job runs daily, only when the battery is not low. It deletes unsaved scans older than 24 h, applies your auto-delete setting and removes orphaned files. |

## Quick start

1. **Get the APK.** Download `AIRealityScanner.apk` from the repo's **Releases → latest**, or from the latest **Actions** run's artifact. You can also build it yourself (see `documentation/BUILD.md`).
2. **Install it** on the Vivo V40 (see `documentation/INSTALL_VIVO_V40.md`).
3. **Add a key.** Open **Settings → AI vision engine**, choose Claude or OpenAI and paste your API key (see `documentation/API_CONFIGURATION.md`). Tap **Test connection**.
4. **Scan.** Go back to **Scanner**, pick a mode and tap the big white button.

No API key? Choose **On-device (offline)**. It reads text, decodes barcodes and recognises broad categories without uploading anything.

## Project structure

```
AIRealityScanner/
├── app/                              Android application module
│   └── src/main/java/com/abhijit/airealityscanner/
│       ├── core/        DI (Hilt), theme & UI components, Keystore key storage,
│       │                network monitor, image utils, voice (TTS/STT)
│       ├── domain/      Models (ScanMode, ScanAnalysis, ScanSession, AppSettings)
│       │                and repository interfaces
│       ├── data/        Room DB (6 entities), DataStore settings, image storage,
│       │                PDF/JSON export, WorkManager cleanup
│       ├── camera/      CameraX setup/preview, frame throttling, scene change detection
│       ├── ai/          AiEngine, provider abstraction (Claude/OpenAI), prompts,
│       │                JSON parser, ML Kit (OCR/barcode/labels/objects/translate),
│       │                on-device analyzer
│       ├── features/
│       │   ├── scanner/   Camera screen + live mode
│       │   ├── result/    Scan result + conversation
│       │   ├── discover/  Discover mode + AI facts
│       │   ├── chat/      AI Chat tab
│       │   ├── history/   Scan history
│       │   ├── compare/   Compare two scans
│       │   └── settings/  Settings
│       └── navigation/  Bottom navigation + routes
├── documentation/                    Guides (API, permissions, build, install, architecture, feature status)
├── output/AIRealityScanner.apk       Debug APK (placed here by the CI build)
└── .github/workflows/build.yml       CI: build APK → artifact + "latest" release, run unit tests
```

The layers are organised as clean-architecture packages inside a single Gradle module. That keeps the build simple for one developer while keeping the dependency direction (features → domain ← data/ai) clear. See `documentation/ARCHITECTURE.md`.

## Documentation

- `documentation/API_CONFIGURATION.md`: getting and entering Claude / OpenAI keys, models, costs
- `documentation/PERMISSIONS.md`: every permission, when it is requested, and why
- `documentation/BUILD.md`: building with Android Studio, the command line or GitHub Actions
- `documentation/INSTALL_VIVO_V40.md`: installing on a Vivo V40 (Funtouch OS specifics)
- `documentation/ARCHITECTURE.md`: layers, data flow, live-mode pipeline, database
- `documentation/FEATURE_STATUS.md`: what is implemented, the limitations, and what was not possible and why

## Tests

- `./gradlew testDebugUnitTest` runs JVM unit tests. They cover AI response parsing, Claude/OpenAI request format and HTTP error handling (with MockWebServer), conversation normalisation, frame throttling, scene-change detection and TTS text chunking. CI runs these on every push.
- `./gradlew connectedDebugAndroidTest` runs Room persistence tests on a device or emulator.

## Disclaimer

AI identifications can be wrong. This app does not give medical advice, does not detect counterfeit currency and cannot certify that a machine is safe. Always verify important information.
