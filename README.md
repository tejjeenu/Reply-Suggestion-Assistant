# Reply Suggestion Assistant

Android MVP for capturing user-approved screen context, extracting text with Google ML Kit OCR, and sending that text to a backend for reply suggestions.

## What Is Built

- Native Android app in Kotlin + Jetpack Compose.
- User-approved `MediaProjection` screen capture session.
- Foreground screen capture service with Android media projection service type.
- Multiple manual screenshot captures per session.
- On-device ML Kit OCR for each screenshot.
- Editable context review before anything is sent out.
- Reply suggestions through a backend URL, with local mock suggestions when the backend URL is blank.
- Tiny Node backend that can call Groq without putting `GROQ_API_KEY` inside the APK.

## Android Setup

Open this folder in Android Studio, let Gradle sync, then run the `app` configuration on a real Android phone.

The app uses:

- `compileSdk 35`
- `minSdk 23`
- Java 17
- ML Kit text recognition: `com.google.mlkit:text-recognition:16.0.1`

On your phone:

1. Enable Developer Options.
2. Enable USB debugging.
3. Connect the phone by USB.
4. Run the app from Android Studio.

## Install On Your Phone

The easiest route is Android Studio:

1. Install Android Studio.
2. Open this project folder.
3. Let Android Studio install/sync the required Android SDK packages.
4. Plug in your Android phone with USB debugging enabled.
5. Select your phone in the device dropdown.
6. Press Run.

PowerShell install, once Android Studio has installed the SDK:

```powershell
.\gradlew.bat installDebug
```

The app will install as `Reply Assistant`.

## App Flow

1. Tap `Start Capture`.
2. Approve Android's screen capture prompt.
3. Tap `Capture After 5 Seconds`.
4. Switch to the target app or screen before the countdown finishes.
5. Review or edit the extracted OCR text.
6. Leave `Backend URL` blank for mock suggestions, or set it to your backend.
7. Tap `Generate Replies`.

## Backend Setup

The backend has no npm dependencies and requires Node 18+.

```powershell
cd backend
$env:GROQ_API_KEY="your_groq_api_key"
npm start
```

Optional model override:

```powershell
$env:GROQ_MODEL="llama-3.3-70b-versatile"
```

Find your computer's LAN IP:

```powershell
ipconfig
```

Use this in the Android app:

```text
http://YOUR_LAN_IP:3000/suggest
```

Your phone and computer must be on the same Wi-Fi. The Android manifest allows cleartext HTTP for local MVP testing; use HTTPS for production.

## Current Limitations

- This first version uses an in-app capture button, not a floating overlay.
- Some protected screens may capture as black because Android apps can block screen capture.
- OCR extracts text only. It does not describe photos or non-text visual context.
- API keys should stay on the backend. Do not put `GROQ_API_KEY` in Android code.
