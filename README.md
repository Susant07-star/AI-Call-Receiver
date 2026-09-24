# AI Call Assistant

AI Call Assistant is a research prototype for automated phone-call handling. The repository currently contains two separate pieces:

- A native Android app that observes cellular call state, tests public auto-answer APIs, and diagnoses what audio an ordinary Android app can capture during a call.
- A Node.js backend that accepts Twilio calls, streams call audio to Gemini Live, sends generated audio back to Twilio, and optionally sends a transcript summary to Telegram.

The Android app and backend are not connected to each other yet. The Android app is a device-capability experiment; the Twilio/Gemini flow runs independently through the backend.

## Current Status

| Component | Status | Entry point |
| --- | --- | --- |
| Android Experiment 1 | Functional prototype | `MainActivity` |
| Android Experiment 2 | Functional diagnostic prototype | `Experiment2Activity` |
| Twilio to Gemini bridge | Development prototype | `backend/server.js` |
| Outbound test-call helper | Development utility | `backend/call-me.js` |

The Android application version is `0.2.0`, with `minSdk 23`, `targetSdk 36`, and `compileSdk 36`.

## Features

### Android Experiment 1: call state and auto-answer

- Monitors `RINGING`, `OFFHOOK`, and `IDLE` states.
- Uses `TelephonyCallback.CallStateListener` on Android 12/API 31 and later, with `PhoneStateListener` compatibility below API 31.
- Displays a caller number when Android provides one.
- Starts a 25-second countdown while a call is ringing.
- Attempts `TelecomManager.acceptRingingCall()` after the countdown.
- Displays a diagnostic log in the app and writes Logcat entries under `AI_CALL_ASSISTANT`.

### Android Experiment 2: live call audio feasibility

- Opens from the Experiment 1 screen.
- Starts automatically when `OFFHOOK` is detected, or manually from the diagnostic screen.
- Probes supported `AudioRecord` sources, including `MIC`, `VOICE_COMMUNICATION`, `VOICE_CALL`, uplink/downlink sources, and `UNPROCESSED` where available.
- Records up to 10 seconds from the best initialized source at 16 kHz, mono, PCM 16-bit.
- Calculates RMS energy and classifies the recording as silence, microphone audio, or audio requiring manual inspection.
- Saves a WAV file and diagnostic report under the app's external files directory.
- Supports copying or sharing the diagnostic log.

### Backend: Twilio and Gemini Live

- `GET /ping` provides a health check.
- `GET` or `POST /incoming-call` returns TwiML that speaks a short prompt and opens a WebSocket media stream.
- `/media-stream` receives Twilio mu-law audio, converts it to PCM, sends it to Gemini Live, converts Gemini PCM audio back to Twilio mu-law, and returns it to the caller.
- Uses round-robin selection across comma-separated `GEMINI_API_KEYS` values.
- Sends a Markdown-formatted call transcript summary to Telegram when Telegram configuration is present.

## Requirements

### Android

- Android Studio with an Android SDK that includes API 36.
- JDK supported by the installed Android Gradle Plugin (JDK 17 or newer is recommended).
- A physical Android phone with cellular calling service for meaningful call tests. Emulators and Wi-Fi-only devices cannot reproduce normal cellular-call behavior.

### Backend

- Node.js 20 or newer.
- A Twilio account and phone number with voice calling enabled.
- One or more Gemini API keys with access to the configured Gemini Live model.
- A public HTTPS endpoint for Twilio, with WebSocket support. During local development, use a tunnel such as ngrok or Cloudflare Tunnel.
- Optional Telegram bot token and chat ID for call summaries.

## Android Setup

1. Open the repository in Android Studio.
2. Allow Gradle to sync and select the `app` run configuration.
3. Connect a physical Android phone with a SIM and mobile service.
4. Install and open the app.
5. Grant the requested permissions. The app needs `READ_PHONE_STATE` and `ANSWER_PHONE_CALLS` for Experiment 1; Experiment 2 additionally needs `RECORD_AUDIO`.
6. Use the main screen for call-state monitoring, or open Experiment 2 for audio diagnostics.

The command-line debug build is:

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is generated under `app/build/outputs/apk/debug/`.

Useful Logcat filters:

```text
tag:AI_CALL_ASSISTANT
tag:AI_CALL_EXP2
```

## Backend Setup

Install dependencies:

```powershell
cd backend
npm install
```

Create `backend/.env` locally. Do not commit this file:

```dotenv
PORT=8080
GEMINI_API_KEYS=your_first_gemini_key,your_second_gemini_key
TELEGRAM_BOT_TOKEN=your_telegram_bot_token
TELEGRAM_CHAT_ID=your_telegram_chat_id

TWILIO_ACCOUNT_SID=ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
TWILIO_AUTH_TOKEN=replace_with_your_twilio_auth_token
TWILIO_INCOMING_CALL_URL=https://your-public-host.example/incoming-call
```

Start the server:

```powershell
npm start
```

Verify that it is running:

```powershell
Invoke-RestMethod http://localhost:8080/ping
```

Expected response:

```json
{"status":"alive"}
```

Expose the server through a public HTTPS URL and configure the Twilio phone number's voice webhook to point to:

```text
https://your-public-host.example/incoming-call
```

The public host must support secure WebSockets because the server advertises `wss://<host>/media-stream` in its TwiML response.

## Optional Outbound Call Test

`backend/call-me.js` starts a call from the first Twilio number in the account to the first verified outgoing caller ID. It reads all credentials and the webhook URL from the environment:

```powershell
cd backend
node call-me.js
```

The script requires `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, and `TWILIO_INCOMING_CALL_URL`. It does not contain account credentials in source code.

## Call Flow

```text
Caller
	-> Twilio phone number
	-> POST/GET /incoming-call
	-> Twilio Media Streams over WebSocket
	-> /media-stream
	-> Gemini Live
	-> /media-stream
	-> Twilio
	-> Caller
```

When the WebSocket closes, the backend sends the captured AI transcript to Telegram if both Telegram variables are configured.

## Platform Limitations

- Caller-number visibility is controlled by Android and may require restricted `READ_CALL_LOG` access. A normal user-installed app may not receive the number.
- `ANSWER_PHONE_CALLS` and `TelecomManager.acceptRingingCall()` do not guarantee auto-answer. The API is deprecated from Android 10/API 29, and device policy, the default dialer, carrier software, or manufacturer restrictions may block it.
- The project deliberately avoids hidden APIs, reflection, root access, accessibility workarounds, and shell commands.
- Ordinary apps generally cannot capture both sides of a cellular call. Experiment 2 reports what the current device exposes; a successful `AudioRecord` initialization does not prove that caller audio is present.
- Audio diagnostics are intended for an active call. Results from idle mode are not evidence that call audio can be captured.
- The backend expects the Gemini Live model configured in `server.js` to be available to the supplied API key. Model availability and API behavior can change.
- The backend currently trusts the incoming WebSocket and webhook path. Add authentication, request validation, rate limiting, and production logging controls before exposing it beyond testing.

## Security Notes

- Keep `backend/.env` out of version control and rotate any credential that has ever been exposed.
- Never put Twilio auth tokens, Gemini keys, or Telegram tokens in source code, screenshots, issue reports, or client-side code.
- Use HTTPS/WSS and a restricted deployment in production.
- The call transcript may contain personal information. Review Telegram access, retention, and logging before enabling summaries.

## Project Layout

```text
app/                         Android application
	src/main/                  Manifest, Kotlin sources, and resources
backend/                     Node.js Twilio/Gemini bridge and utilities
	server.js                  Fastify HTTP and WebSocket server
	call-me.js                 Optional outbound-call helper
build.gradle                 Android Gradle plugin configuration
settings.gradle              Gradle repositories and included modules
gradlew.bat                  Gradle wrapper for Windows
```

## License

No license file is currently included. Until a license is added, assume that the repository is not licensed for redistribution or commercial use.
