# Feature status

✅ implemented · ⚠️ implemented with limitations · ❌ not possible (with the alternative used)

## Core
| Feature | Status | Notes |
|---|---|---|
| CameraX preview, capture, flash, zoom, focus | ✅ | Uses `LifecycleCameraController` |
| Gallery import | ✅ | Android Photo Picker (no storage permission needed) |
| 8 requested scan modes + Currency | ✅ | Mode-specific prompts and result sections |
| Claude vision | ✅ | Messages API, configurable model |
| OpenAI vision | ✅ | Chat Completions, configurable model and base URL |
| Local image recognition / offline object detection | ⚠️ | ML Kit labelling and object detection. Broad categories only (it can't identify species, models or landmarks) |
| OCR | ✅ | ML Kit Latin + Devanagari. ⚠️ ML Kit has **no Bengali-script OCR**, so for Bengali documents the cloud model reads the text |
| Follow-up conversations with the image as context | ✅ | Unlimited per scan, stored in Room |
| Voice input / output, EN / HI / BN | ✅ | ⚠️ Depends on the phone's speech services and installed TTS voices (the app detects missing voices and links to the installer) |
| Hands-free continuous conversation | ✅ | Optional setting |
| History: search, favourites, notes, delete, export | ✅ | PDF report per scan; JSON export of all history |
| Explicit cloud consent, Keystore-encrypted keys | ✅ | |

## Real-time camera (section 4)
| Requirement | Status | Notes |
|---|---|---|
| Periodic frame sampling, throttling, resizing | ✅ | 700 ms on-device, configurable cloud interval (2–30 s) |
| Scene change detection | ✅ | 16×16 luminance difference with an adjustable threshold |
| Object detection | ✅ | On-device boxes and labels drawn over the preview |
| AI scene description and voice narration | ✅ | Only on scene change |
| Visible upload indicator, Pause / Resume, Stop | ✅ | |
| "True" streaming video understanding | ❌ | The Claude and OpenAI image APIs take still images, not a video stream. **Alternative:** sampled still frames, gated by scene change, as described above |

## Advanced (section 13)
| # | Feature | Status |
|---|---|---|
| 1–2 | Compare two objects / explain differences | ✅ Compare screen |
| 3 | Real-time text detection | ✅ Live mode (on-device OCR) |
| 4 | Translate text in camera view | ✅ Live mode and the result screen (ML Kit on-device; one-time model download per language) |
| 5 | Household tools | ✅ Object mode prompt |
| 6 | Electronic components | ✅ Object / Machine prompts |
| 7 | Scene description | ✅ Live mode |
| 8 | Currency notes | ⚠️ Currency mode identifies the currency and denomination. **It is not counterfeit detection.** It can't verify authenticity from a photo, and the app says so |
| 9 | Product packaging | ✅ Product mode + OCR hint |
| 10 | Educational explanations | ✅ Discover + facts |
| 11 | QR codes / barcodes | ✅ On-device in Live mode and on every scan result. Links open only after confirmation |
| 12 | Downloadable scan report | ✅ PDF via the share sheet |

**Verified facts vs AI guesses:** every result shows a confidence badge, a confidence note and an "Observed vs AI-inferred" section. A footer says the content is AI-generated. The app has no external fact-checking database, so facts are labelled as AI output rather than presented as verified.

## Known limitations
- The app has not yet been tested on a physical Vivo V40 (or any device). The build and unit tests run in CI only.
- Cloud features need your own paid API key.
- The ML Kit translation model (about 30 MB per language) downloads on first use, and that first download needs internet.
- Landmark location is only given when the AI judges the landmark unambiguous or there are visible clues. Otherwise it says the location can't be determined.
