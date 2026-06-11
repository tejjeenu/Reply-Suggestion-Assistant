# Reply Suggestion Assistant

Android MVP for capturing user-approved screen context, extracting text with Google ML Kit OCR, and sending that text to a backend for reply suggestions.

## What Is Built

- Native Android app in Kotlin + Jetpack Compose.
- User-approved `MediaProjection` screen capture session.
- Foreground screen capture service with Android media projection service type.
- Accessibility-based scroll detection for supported messaging apps.
- Multiple manual screenshot captures per session.
- Background assistant mode that samples screenshots during messaging-app scrolls.
- Accessibility-based launch prompt when a supported messaging app is opened and the background assistant is not already running.
- Closeable suggestion popup shown only when replies are ready, after explicit overlay permission.
- On-device ML Kit OCR for each screenshot.
- Optional two-stage Groq pipeline: Llama 4 Scout extracts screenshot/transcript context, then Llama 3.3 70B writes the final TextMaster AI reply options.
- Editable context review before anything is sent out.
- Reply suggestions through a backend URL, with local mock suggestions when the backend URL is blank.
- Tiny FastAPI backend that can call Groq without putting `GROQ_API_KEY` inside the APK.

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
3. Tap `Enable Messaging Detection`, enable `Reply Assistant` in Android Accessibility settings, then return to the app.
4. Tap `Allow Suggestion Popup`, grant display-over-other-apps permission, then return to the app.
5. Tap `Run in Background`.
6. Open a supported messaging app and scroll the conversation. Reply Assistant starts a capture session when scrolling is detected, samples screenshots while scrolling, waits until scrolling stops, sends the captured batch to the configured backend or local mock flow, and shows a closeable popup when suggestions are ready.
7. Use `Copy` inside the popup to copy a reply, or `Close` to dismiss it.
8. Open `Details` in the main app only when you need backend settings, manual capture fallback, or context review.
9. Leave `Backend URL` blank for mock suggestions, or set it to your backend.

After messaging detection is enabled, opening a supported messaging app while the assistant is not running can show a `Use Reply Assistant` notification. Tapping it opens the app, requests any missing permissions, starts Android's screen-capture consent flow, and then runs the background assistant.

Supported messaging packages currently include WhatsApp, WhatsApp Business, Telegram, Signal, Messenger, Instagram, Discord, Google Messages, Samsung Messages, Google Chat, Slack, Microsoft Teams, Skype, LINE, Viber, Snapchat, and Hinge.

## Backend Setup

The backend uses FastAPI and requires Python 3.10+. Run it from PowerShell before testing the Android app.

1. Create a `.env` file in the project root or `backend` folder:

```text
GROQ_API_KEY=your_groq_api_key
BACKEND_URL=http://YOUR_LAN_IP:3000/suggest
```

The backend also accepts `LLM_API=...` for local compatibility, but `GROQ_API_KEY` is the clearest name.
The Android Gradle build reads `BACKEND_URL` from `.env` and uses it as the default value for the app's `Backend URL` field. The FastAPI backend does not use `BACKEND_URL` as a Groq credential.

2. Create and activate a Python virtual environment:

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
```

If PowerShell blocks activation, run this once in the same terminal and activate again:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\.venv\Scripts\Activate.ps1
```

3. Install backend dependencies:

```powershell
python -m pip install -r requirements.txt
```

4. Start the backend on all network interfaces from inside the `backend` folder:

```powershell
python -m uvicorn main:app --host 0.0.0.0 --port 3000
```

If your terminal is still in the project root, use this instead:

```powershell
python -m uvicorn backend.main:app --host 0.0.0.0 --port 3000
```

Leave this terminal open while testing. You can also run the backend with:

```powershell
python main.py
```

5. Check that the backend is running:

```powershell
Invoke-RestMethod http://127.0.0.1:3000/health
```

You should see `ok` as `True` and `groqConfigured` as `True`. If `groqConfigured` is `False`, check your `.env` key name and value.

6. Find your computer's LAN IP:

```powershell
ipconfig
```

Look for your Wi-Fi adapter's IPv4 address, for example `192.168.1.245`.

7. Use this in the Android app's `Backend URL` field:

```text
http://YOUR_LAN_IP:3000/suggest
```

Example:

```text
http://192.168.1.245:3000/suggest
```

If `BACKEND_URL` is set in `.env` before you build/run from Android Studio, the app field is prefilled with that value. If you change `BACKEND_URL`, rebuild or rerun the app so Gradle regenerates `BuildConfig.DEFAULT_BACKEND_URL`.

8. From your phone browser, test:

```text
http://YOUR_LAN_IP:3000/health
```

If this does not load, your phone is probably not on the same Wi-Fi or Windows Firewall is blocking Python. Allow Python/uvicorn on private networks, then retry.

Optional model overrides before starting the backend:

```powershell
$env:GROQ_VISION_MODEL="meta-llama/llama-4-scout-17b-16e-instruct"
$env:GROQ_TEXT_MODEL="llama-3.3-70b-versatile"
```

`GROQ_MODEL` is still accepted as a legacy alias for the vision model.

Your phone and computer must be on the same Wi-Fi. The Android manifest allows cleartext HTTP for local MVP testing; use HTTPS for production.

## Current Limitations

- Android still requires explicit user approval for each screen-capture session. The app cannot silently start screen capture after reboot or without the MediaProjection prompt.
- The supported-app launch prompt uses Android notifications. On Android 13+, notification permission must be granted before that prompt can appear.
- Messaging-app scroll detection requires the user to enable the app's Accessibility service.
- Automatic detection is limited to known Android messaging package names listed above.
- Some protected screens may capture as black because Android apps can block screen capture.
- ML Kit OCR extracts text locally; when a Groq backend URL is configured, the backend sends compressed screenshots to Llama 4 Scout for visual context, then sends the extracted context plus OCR text to Llama 3.3 70B for final reply generation.
- API keys should stay on the backend. Do not put `GROQ_API_KEY` in Android code.
