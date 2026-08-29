# Reply Suggestion Assistant

An Android companion that builds opt-in conversation context from messaging apps and suggests replies in a chosen style.

The assistant is not a plugin for any messaging service and never reads an app's private database. It uses Android screen capture and accessibility permissions that the user must explicitly grant. Accessibility identifies messaging-app events and, only after the scan button is pressed, performs scroll gestures toward older messages. Message text comes from screenshots, on-device OCR, and optional text transcripts.

## How it works

1. Enter a label for the conversation and, if wanted, enable **Automatic conversation memory**.
2. Choose a response mode: Casual, Flirty, Funny, Serious, Supportive, or Professional.
3. Tap **Start Capture** and approve Android's screen-capture prompt.
4. Enable messaging-app detection and the reply popup when prompted.
5. Tap **Run in Background**, open the conversation at its latest messages, and open the floating Reply Assistant panel.
6. Press **Scan conversation to top**. The first screenshot is preserved as the local reply target, then the accessibility service performs upward-conversation scroll gestures and captures older context until repeated screenshots indicate that the top was reached.
7. A vision model extracts visible messages, emoji, sender layout, and relevant media cues. A local BERT sentence encoder retrieves the global-history chunks most relevant to the local reply target before the text model creates three replies.

The scan only starts when its button is pressed; ordinary user scrolling does not generate replies. The app can only understand content that appears in user-approved captures. Protected screens can appear black if the messaging app or Android blocks capture.

## Supported messaging apps

The accessibility service is no longer WhatsApp-specific. It recognizes common apps including WhatsApp, Telegram, Signal, Messenger, Instagram, Discord, Google and Samsung Messages, Teams, Slack, Viber, LINE, WeChat, KakaoTalk, Skype, and Mattermost. It also recognizes other Android apps that declare the Social app category.

This category check prevents the assistant from reacting to every ordinary app scroll while allowing messaging apps outside the built-in catalog. Unknown or incorrectly categorized apps can still be used with manual or delayed capture.

## Context and privacy boundaries

- The latest screen is kept separately as local reply context and remains authoritative about what needs a response.
- The scan retains up to 80,000 characters of global OCR history, preserving both the recent and oldest ends if the limit is reached.
- Up to 12 representative screenshots are retained locally. For each suggestion request, the reply target and its two nearest screenshots are selected for vision analysis; OCR history supplies the remaining scanned context.
- Optional automatic memory is bounded to 120 unique snapshots and 100,000 stored characters per conversation label.
- Up to 80,000 characters of automatic memory can be sent to the configured backend only when generating a reply.
- Images are used for the current request and are not added to long-term memory.
- Imported history is stored in app-private preferences and is capped before it reaches the backend.
- `canRetrieveWindowContent` remains disabled; the accessibility service does not inspect message nodes.

## Models

The backend uses two complementary stages:

- `qwen/qwen3.6-27b` extracts an ordered transcript and relevant visible media context from screenshot batches.
- `sentence-transformers/bert-base-nli-mean-tokens` embeds the current OCR plus vision context and retrieves the most semantically relevant conversation chunks. If the BERT model cannot load, retrieval falls back to a local lexical scorer.
- `openai/gpt-oss-120b` generates exactly three short, copyable replies using the selected response mode and retrieved context.

Model names can be overridden with environment variables.

## Android setup

Open this folder in Android Studio, let Gradle sync, then run the `app` configuration on a real Android phone. You can also install from PowerShell after the Android SDK is configured:

```powershell
.\gradlew.bat installDebug
```

Run Android unit tests with:

```powershell
.\gradlew.bat testDebugUnitTest
```

Android requires fresh screen-capture approval for each capture session. The manifest permits cleartext HTTP for LAN development only.

## Backend setup

The backend needs Python 3.10+.

1. Create `.env` in the project root or `backend` folder:

```text
GROQ_API_KEY=your_groq_api_key
BACKEND_URL=http://YOUR_LAN_IP:3000/suggest
```

2. Install and run the service:

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m uvicorn main:app --host 0.0.0.0 --port 3000
```

3. Verify it:

```powershell
Invoke-RestMethod http://127.0.0.1:3000/health
python -m unittest test_main -v
```

Set the Android app's backend URL to `http://YOUR_LAN_IP:3000/suggest`. The phone and computer must be on the same Wi-Fi, and Windows Firewall must allow Python on private networks. If `BACKEND_URL` exists before the Android build, it becomes the field's default value.

Optional model overrides:

```powershell
$env:GROQ_VISION_MODEL="qwen/qwen3.6-27b"
$env:GROQ_TEXT_MODEL="openai/gpt-oss-120b"
$env:CONTEXT_EMBEDDING_MODEL="sentence-transformers/bert-base-nli-mean-tokens"
```

Keep the Groq key on the backend—never put it in the APK. Use HTTPS and restricted CORS before production deployment.

## Request shape

`POST /suggest` accepts OCR context, optional history, and ordered screenshots:

```json
{
  "source_app": "Signal",
  "response_mode": "funny",
  "tone": "dry humour, no emoji",
  "context_text": "[Signal local reply target]\nlatest messages...",
  "scanned_history": "[Signal older context 1]\n...\n[Signal older context 2]\n...",
  "chat_history": "Alex: ...\nJamie: ...",
  "chat_participants": ["Alex", "Jamie"],
  "user_name": "Jamie",
  "conversation_name": "Alex",
  "automatic_history": "[Viewed Signal context]\n...",
  "images": []
}
```

Valid response modes are `casual`, `flirty`, `funny`, `serious`, `supportive`, and `professional`. Unknown values safely fall back to `casual`.

## Current limitations

- Automatic scan gestures require Android 7.0 or newer; Android 6 supports manual capture only.
- “Full conversation” means the portion that the scan successfully displayed. Lazy loading, network delays, protected screens, or a 120-gesture safety limit can prevent reaching the true beginning.
- Top detection uses Android scroll metadata when available and repeated OCR similarity as a fallback.
- App category metadata is controlled by each messaging app. An unknown app that does not declare itself Social requires manual capture.
- Generic transcript import supports common WhatsApp timestamps and `Name: message` lines; other export formats may need conversion.
- Group transcripts are supported, but conversation labels must be chosen carefully to avoid mixing memories.
- CORS is open and cleartext HTTP is enabled for local development; both must be restricted for production.
