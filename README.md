# WhatsApp Reply Assistant

An Android companion for WhatsApp that automatically builds opt-in local conversation memory and uses it with the message currently on screen to suggest replies.

This is not a WhatsApp plugin. WhatsApp does not provide a consumer plugin API that can read personal chats. The app instead uses Android's user-approved screen capture and a user-approved accessibility service that reacts only to WhatsApp and WhatsApp Business. A manual WhatsApp export remains available only as an optional way to backfill older messages.

## How it works

1. Enter a label for the conversation/contact and explicitly enable **Automatic conversation memory**.
2. Tap **Start Capture** and approve Android's screen-capture prompt.
3. Enable WhatsApp detection and the suggestion popup when prompted.
4. Tap **Run in Background**, open that WhatsApp conversation, and scroll normally.
5. Text visible in user-approved captures is OCR'd and accumulated in app-private storage for that label.
6. When scrolling stops, semantic retrieval selects the most relevant remembered context and the generative model returns three replies.

Automatic memory cannot recover messages that have never appeared on screen. To backfill them, scroll through older messages once or optionally use WhatsApp's **Export chat > Without media** and import the `.txt` file.

Automatic memory is bounded to 120 unique snapshots/100,000 characters per conversation label. Up to 80,000 characters are sent to the configured backend only when generating a reply. The backend uses a local BERT-family sentence encoder to select up to 16,000 characters of relevant context before calling Groq. Images and media are not added to long-term memory.

The name field matters: it tells the model which historical messages were written by you, so it can match your style instead of copying the other participant.

## What is built

- Kotlin + Jetpack Compose Android app (`minSdk 23`, `compileSdk 35`, Java 17).
- Import support for common Android and iPhone WhatsApp `.txt` export formats, including multiline messages.
- Opt-in automatic local memory built from viewed WhatsApp OCR context, with pause and clear controls.
- Per-contact labels so automatically remembered conversations do not mix.
- Semantic context retrieval with `sentence-transformers/all-MiniLM-L6-v2`, plus a lexical fallback.
- Persisted participant names, message count, and bounded conversation excerpt.
- WhatsApp-only accessibility detection (`com.whatsapp` and `com.whatsapp.w4b`).
- User-approved MediaProjection capture and on-device ML Kit OCR.
- Background scroll sampling and a closeable reply overlay.
- FastAPI backend with an optional two-stage Groq vision/text pipeline.
- API prompting that treats the current screen as the reply target and retrieved history as relationship and writing-style context.
- Local mock suggestions when no backend URL is configured.

## Android setup

Open this folder in Android Studio, let Gradle sync, then run the `app` configuration on a real Android phone. You can also install from PowerShell after the Android SDK is configured:

```powershell
.\gradlew.bat installDebug
```

To run unit tests:

```powershell
.\gradlew.bat testDebugUnitTest
```

Android requires fresh screen-capture approval for each capture session. The app cannot silently read WhatsApp messages or WhatsApp's private database. Accessibility access is used only to detect WhatsApp window and scroll events; `canRetrieveWindowContent` is disabled.

## Backend setup

The backend needs Python 3.10+.

1. Create `.env` in the project root or `backend` folder:

```text
GROQ_API_KEY=your_groq_api_key
BACKEND_URL=http://YOUR_LAN_IP:3000/suggest
```

2. Create the environment and install dependencies:

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
```

3. Start the API:

```powershell
python -m uvicorn main:app --host 0.0.0.0 --port 3000
```

4. Verify it:

```powershell
Invoke-RestMethod http://127.0.0.1:3000/health
```

Set the Android app's backend URL to `http://YOUR_LAN_IP:3000/suggest`. The phone and computer must be on the same Wi-Fi, and Windows Firewall must allow Python on private networks. If `BACKEND_URL` exists in `.env` before the Android build, it becomes the field's default value.

Optional model overrides:

```powershell
$env:GROQ_VISION_MODEL="meta-llama/llama-4-scout-17b-16e-instruct"
$env:GROQ_TEXT_MODEL="llama-3.3-70b-versatile"
$env:CONTEXT_EMBEDDING_MODEL="sentence-transformers/all-MiniLM-L6-v2"
```

Keep the Groq key on the backend—never put it in the APK. Use HTTPS and restricted CORS before any production deployment.

## Request shape

`POST /suggest` accepts the current WhatsApp context plus optional imported and automatically remembered history:

```json
{
  "source_app": "WhatsApp",
  "tone": "casual, natural, helpful",
  "context_text": "OCR text from the current conversation",
  "chat_history": "Alex: ...\nJamie: ...",
  "chat_participants": ["Alex", "Jamie"],
  "user_name": "Jamie",
  "conversation_name": "Alex",
  "automatic_history": "[Viewed WhatsApp context]\n...",
  "images": []
}
```

The backend validates imported history at 60,000 characters and automatic history at 80,000 characters. The MiniLM encoder retrieves relevant history; Groq generates exactly three clean, copyable suggestions. If the embedding model cannot load, retrieval falls back to a local lexical scorer.

## Current limitations

- WhatsApp export formats vary by locale. The parser supports the common bracketed iPhone form and date/time-hyphen Android form.
- Automatic memory sees only text present in user-approved captures; it is not a silent full-account export.
- Use a separate conversation label for each contact to avoid mixing context.
- Group exports can be imported, but the experience is optimized for a one-to-one chat.
- Protected screens can appear black if WhatsApp or Android blocks capture.
- The Android manifest currently permits cleartext HTTP for LAN development only.
- CORS is open for local development and must be restricted for production.
