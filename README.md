# Reply Suggestion Assistant

Android MVP for capturing user-approved screen context, extracting text with Google ML Kit OCR, and sending that text to a backend for reply suggestions.

## What Is Built

- Native Android app in Kotlin + Jetpack Compose.
- User-approved `MediaProjection` screen capture session.
- Foreground screen capture service with Android media projection service type.
- Multiple manual screenshot captures per session.
- Optional floating capture panel shown over other apps after explicit overlay permission.
- Floating panel workflow for collecting screenshots, generating replies, and copying replies without returning to the main app.
- On-device ML Kit OCR for each screenshot.
- Optional multimodal Groq/Llama 4 Scout analysis of the screenshot image plus OCR text, including visible image details like appearance, objects, activity, setting, and mood.
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
3. Tap `Allow Floating Button`, grant display-over-other-apps permission, then return to the app.
4. Tap `Open Floating Button`.
5. Switch to the target app and use the floating panel:
   - `Capture` collects one screenshot.
   - `Reply` processes the collected screenshots.
   - `Copy` copies a generated reply.
   - `Clear` resets the panel context when screenshots or replies exist.
6. Open `Details` in the main app only when you need backend settings, manual capture fallback, or context review.
7. Leave `Backend URL` blank for mock suggestions, or set it to your backend.

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

4. Start the backend on all network interfaces:

```powershell
python -m uvicorn main:app --host 0.0.0.0 --port 3000
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

Optional model override before starting the backend:

```powershell
$env:GROQ_MODEL="meta-llama/llama-4-scout-17b-16e-instruct"
```

Your phone and computer must be on the same Wi-Fi. The Android manifest allows cleartext HTTP for local MVP testing; use HTTPS for production.

## Current Limitations

- The floating panel is user-triggered. It does not automatically detect messaging apps.
- Automatic foreground-app detection would need an additional, carefully justified accessibility or usage-access design.
- Some protected screens may capture as black because Android apps can block screen capture.
- ML Kit OCR extracts text locally; when a Groq backend URL is configured, the backend also sends a compressed screenshot image to Llama 4 Scout for visual context such as photos, shared images, objects, activities, and scene details.
- API keys should stay on the backend. Do not put `GROQ_API_KEY` in Android code.
