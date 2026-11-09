# Build instructions

## Requirements
- JDK 17
- Android SDK Platform 35 and Build-Tools 35 (Android Studio installs these automatically)
- Internet access to Google Maven and Maven Central on the first build
- Versions used: Gradle 8.11.1 (wrapper included), Android Gradle Plugin 8.7.3, Kotlin 2.1.0

## Option 1: Android Studio (Windows, macOS or Linux)
1. Install a current stable Android Studio (Ladybug or newer).
2. Choose **File → Open** and select the `AIRealityScanner` folder.
3. Let Gradle sync. The first sync downloads the dependencies.
4. Connect your phone with USB debugging on (see `INSTALL_VIVO_V40.md`) and press **Run ▶**.
5. To build an APK without a phone, choose **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The output goes to `app/build/outputs/apk/debug/app-debug.apk`.

## Option 2: Command line
```bash
./gradlew assembleDebug            # Windows: gradlew.bat assembleDebug
./gradlew testDebugUnitTest        # unit tests
mkdir -p output && cp app/build/outputs/apk/debug/app-debug.apk output/AIRealityScanner.apk
```

## Option 3: GitHub Actions (no local setup)
Every push to `main` runs `.github/workflows/build.yml`. That workflow:
1. Builds the debug APK and copies it to `output/AIRealityScanner.apk`.
2. Uploads it as the **AIRealityScanner-debug-apk** workflow artifact.
3. Runs the unit tests.
4. Publishes it to the **latest** pre-release, so you can download it directly on your phone from *Releases*.

You can also start a build by hand: **Actions → Build debug APK → Run workflow**.

## Signing
Debug builds are signed with the debug keystore committed at `app/debug.keystore` (public, standard password `android`). Because every build uses the same signature, a new APK installs over the old one and your history is kept. For a Play Store release, create your own release keystore and **never** commit it.

## APK size
ML Kit's on-device models (OCR, barcode, labelling, object detection) are bundled, so the app works offline. The build includes only `arm64-v8a` (the Vivo V40) and `x86_64` (emulators), which keeps the APK smaller.
