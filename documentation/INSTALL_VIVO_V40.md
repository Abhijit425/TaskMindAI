# Installing on a Vivo V40

> These steps are written for Funtouch OS 14 / 15 on the Vivo V40. Menu names can differ slightly between OS updates.
> The app has **not** yet been tested on a physical Vivo V40. Please report anything that doesn't work.

## Method A: Install the APK directly (easiest)
1. On the phone, open the GitHub repo in Chrome, go to **Releases → latest** and download **AIRealityScanner.apk**.
2. Open the downloaded file, either from Chrome's download notification or from **File Manager → Downloads**.
3. If Android blocks it, tap **Settings** in the prompt and allow **Install unknown apps** for Chrome or File Manager. On Vivo this setting is also under **Settings → Security & privacy → More security settings → Install unknown apps**.
4. Vivo may show a **security scan**, or ask for your **Vivo account password** before installing an app from outside the store. This is normal for sideloaded apps. Tap **Install** and, if asked, enter your Vivo account password. If it only shows "Install via the App Store", tap **More** or **Continue installing**.
5. Open **AI Reality Scanner** and allow camera access when asked.

## Method B: USB from a computer (Android Studio or adb)
1. **Turn on Developer options:** go to **Settings → About phone → Software information** and tap **Build number** 7 times.
2. **Turn on USB debugging:** go to **Settings → System → Developer options → USB debugging**. Vivo also has **Install via USB**. Turn that on too (this may require a Vivo account sign-in or an inserted SIM).
3. Connect a USB cable, set the USB mode to **File transfer** and accept the "Allow USB debugging?" prompt.
4. Install from the computer:
   ```bash
   adb install -r output/AIRealityScanner.apk
   ```
   You can also press **Run ▶** in Android Studio.

## Recommended Vivo settings
Funtouch OS can be aggressive about stopping background apps.
- **Settings → Battery → Background power consumption management → AI Reality Scanner → Allow** (optional). This only affects the daily history clean-up, which is low priority anyway. Camera, scanning and voice all run in the foreground and need no special setting.
- For Hindi or Bengali voice output, open **Settings → System → Language & input → Text-to-speech output**. Select **Speech Services by Google** as the engine and install the Hindi / Bengali voice data. You can also use the **Install voice data** button in the app's Settings.
- For voice input, make sure the **Google** app is installed and up to date. It provides speech recognition.

## First run checklist
1. **Settings → AI vision engine:** add a Claude or OpenAI key, then tap **Test connection**.
2. **Scanner:** choose a mode, then tap **Scan**. Accept the cloud consent dialog.
3. **Voice:** **Settings → Voice → Enable voice features**. Allow the microphone, choose a language and tap **Test voice**.
4. **Live mode:** tap the video icon at the top right of the Scanner. Watch the status pill so you can see when frames are being uploaded.

## Updating
Install a newer APK over the old one. Because the debug signature stays the same, your history and settings are kept.

## Uninstalling
Long-press the icon and choose **Uninstall**. All scans, images and keys are deleted with the app.
