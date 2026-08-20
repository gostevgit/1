# HeyGlass Listen Translate

Minimal Android 12+ app for one job only:

**Bluetooth glasses microphone → Gemini 3.5 Live Translate → Bluetooth glasses speakers.**

## Current MVP

- Android 12+ (API 31+).
- Uses Android communication-device routing for Bluetooth SCO / BLE headset audio.
- Gemini model: `gemini-3.5-live-translate-preview`.
- Input audio: raw mono PCM16 at 16 kHz, 100 ms chunks.
- Output audio: raw mono PCM16 at 24 kHz.
- `echoTargetLanguage=false`, so speech already in the target language is not repeated.
- Foreground service keeps translation active with the screen off.
- Android acoustic echo cancellation + noise suppression are enabled when supported.
- Built-in audio diagnostic plays a short tone to the glasses and measures microphone level.

## First physical test

1. Pair/connect the HeyCyan/HeyGen glasses in Android Bluetooth settings.
2. Install the debug APK.
3. Grant Microphone, Nearby devices/Bluetooth and Notifications permissions.
4. Tap **TEST GLASSES AUDIO**.
   - You should hear a short beep in the glasses.
   - Then speak normally for ~2 seconds.
   - The app reports the measured microphone level.
5. Enter a Gemini API key.
6. Select the target language (Russian is the default).
7. Tap **START LISTEN TRANSLATE** and speak another language near the glasses.

## Security note

For the hardware/MVP test, the Gemini API key is stored in Android app-private preferences and sent only over TLS to Google's Gemini WebSocket endpoint. Do not publish a key in this repository.

Once the physical audio path is confirmed, switch the app to short-lived Gemini ephemeral tokens issued by a tiny backend. That removes the long-lived API key from the phone.

## Build locally

Requirements: JDK 17, Android SDK 35, Gradle 8.9.

```bash
cd HeyGlassTranslate
gradle :app:assembleDebug
```

APK:

`app/build/outputs/apk/debug/app-debug.apk`

## Branch purpose

This is a standalone MVP rather than a full CyanBridge fork because Listen Translate does not need camera, chat, local agents, media sync, or the vendor UI. The Bluetooth routing approach is based on the working communication-device routing already present in CyanBridge's meeting capture implementation.
