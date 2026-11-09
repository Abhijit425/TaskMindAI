# Architecture

```
features (Compose UI + ViewModels)
   │  observe StateFlow, send intents
   ▼
domain (models + repository interfaces)  ◄──  data (Room, DataStore, files, export, WorkManager)
   ▲
   │
ai (AiEngine → providers)      camera (CameraX, sampling)      core (DI, theme, voice, security)
```

- **MVVM.** Each screen has a `@HiltViewModel` that exposes `StateFlow` UI state plus a `Channel` of one-off events (navigation, share intents, permission requests).
- **Hilt** wires everything. `AppModule` provides Json, OkHttp, Retrofit, the Room database and the repository bindings.
- **Coroutines and Flow** are used throughout. Room DAOs return `Flow`, image work runs on `Dispatchers.Default` and network calls run on `Dispatchers.IO`. Nothing blocks the UI thread.

## AI provider abstraction
`CloudVisionProvider` takes a provider-neutral `VisionRequest` (system prompt, messages with Base64 JPEG images, and a token limit) and returns text. There are two implementations:
- `AnthropicVisionProvider` calls the Messages API using `image` content blocks.
- `OpenAiVisionProvider` calls Chat Completions using `image_url` data URLs and works with any compatible base URL.

`AiEngine` is the only entry point the UI uses. It:
1. Reads settings to choose the provider (Claude, OpenAI or on-device).
2. Enforces **consent**, then the **API key**, then **connectivity**, in that order. Each failure produces a user-readable `AiException`.
3. Resizes and compresses images before upload, and caches repeated analyses.
4. Exposes `uploadsInFlight`, which drives the visible "Uploading…" indicators.

To add a provider (for example Gemini or a local server), implement `CloudVisionProvider`, add a value to `AiProviderType` and add a branch in `AiEngine.callCloud`. No UI changes are needed.

### Structured results
Analysis prompts ask for a strict JSON object containing subject, confidence, confidence_note, summary, sections[], key_facts, applications, safety, verification_note and extracted_text. Each mode defines its own section titles: for example, Plant uses Water / Sunlight / Soil, and Medicine uses only what is printed on the label. `AnalysisParser` tolerates Markdown fences and falls back to showing the raw text with the confidence set to *uncertain*. It never invents structure.

## Live mode pipeline
```
CameraX ImageAnalysis (960×720 RGBA, KEEP_ONLY_LATEST, own executor)
  → FrameThrottle (≤ 1 frame / 700 ms, dropped while previous frame still processing)
  → ML Kit in parallel: OCR · barcodes · object detection   (on-device, never uploaded)
  → optional ML Kit translation when the visible text changes
  → cloud description only if: cloud on ∧ consent ∧ key ∧ interval elapsed ∧ scene changed
        scene change = mean |Δ luminance| over a 16×16 grid ≥ threshold
```
The analysis use case is only bound while Live mode is running and not paused. The torch, pinch-to-zoom and tap-to-focus come from `LifecycleCameraController`.

## Database (Room, `ai_reality_scanner.db`)
| Entity | Purpose |
|---|---|
| `ScanSession` | One scan or conversation: mode, category, title, favourite, saved, notes, provider, timestamps |
| `ScannedImage` | Image file path(s) for a session (2 for comparisons) |
| `ScanResult` | Structured analysis (lists stored as JSON) |
| `ConversationMessage` | Follow-up Q&A (USER, ASSISTANT, or SYSTEM notes, which are never sent to the AI) |
| `ScanCategory` | Seeded with one category per scan mode and used for history filters |
| `UserPreferences` | Key/value store for app-generated data (for example the daily Discover facts cache) |

User settings themselves live in **Preferences DataStore**, and API keys in the **Keystore-encrypted** store.

## Voice
- `SpeechToTextManager` wraps `SpeechRecognizer` as a cold `callbackFlow`. Cancelling the collection immediately stops the mic.
- `TextToSpeechManager` splits long text into chunks within `getMaxSpeechInputLength()`, strips Markdown, exposes `isSpeaking` and reports missing voice data.

## Performance notes for the Vivo V40
- The preview uses `PreviewView` in PERFORMANCE (SurfaceView) mode, which is hardware-composited.
- Captures use CAPTURE_MODE_MINIMIZE_LATENCY. They are decoded and rotated off the main thread, then capped at 2048 px for storage and 1280 px for upload by default.
- Analysis frames are kept small and throttled. ML Kit runs on its own threads, and the bundled models need no network.
- Coil handles thumbnails, with memory and disk caching.
- The cleanup worker only runs when the battery is not low.
