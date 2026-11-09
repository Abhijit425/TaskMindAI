# Permissions and privacy

| Permission | Type | When it's requested | Why |
|---|---|---|---|
| `CAMERA` | Runtime | When the Scanner screen first opens. If you deny it, the screen explains why it's needed, and gallery import still works. | Live preview, capture and Live mode. The camera is bound to the Scanner screen's lifecycle and released when you leave the screen. |
| `RECORD_AUDIO` | Runtime | **Only** after you turn on voice features (Settings → Voice, or the mic button, which asks first). | Spoken questions through Android's `SpeechRecognizer`. The mic is active only while the red "Listening…" indicator is visible. |
| `INTERNET` | Install-time | n/a | Calls to the AI provider you chose. Also the one-time ML Kit translation model download. |
| `ACCESS_NETWORK_STATE` | Install-time | n/a | Detects when you're offline so the app can show a clear message instead of a timeout. |

Storage permissions are not requested. Gallery import uses the Android **Photo Picker**, which only gives the app the single photo you choose.

## No hidden camera or microphone access

- Analysis frames are only processed while Live mode is on. The analysis use case is unbound when Live mode is paused or stopped.
- A visible status pill shows **LIVE · on-device**, **Uploading frame to …**, **Paused** or **Microphone on**.
- The Android 12+ system camera and mic privacy indicators also appear whenever the hardware is in use.

## Cloud consent

The first time an image would be uploaded, a dialog explains what is sent, to whom and when. Nothing is uploaded unless you choose **Allow cloud analysis**. You can revoke consent at any time in Settings. You can also choose **On-device only** for Live mode, or use the on-device provider for everything.

## Data storage

- Images are stored in app-private storage (`files/scans/`), not in your gallery.
- Results and conversations are kept in a private Room database. Settings are kept in DataStore.
- API keys are encrypted (AES-256-GCM, Android Keystore).
- `allowBackup=false` and the data-extraction rules exclude all of this from cloud backup and device transfer.
- **Delete controls:**
  - Per scan: delete the scan, or delete only its image.
  - Settings: delete all history, delete all images, or auto-delete after 7/30/90 days.
  - Unsaved scans are removed after 24 h.
