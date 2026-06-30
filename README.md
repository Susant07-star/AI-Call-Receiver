# AI Call Assistant - Experiment 1

This is a native Android/Kotlin feasibility experiment for incoming call detection and public API auto-answer attempts.

It intentionally does not include AI, speech recognition, text-to-speech, messaging, networking, storage, or databases.

## What It Tests

1. Detect incoming phone-call state changes.
2. Show the caller number if Android exposes it.
3. Track `RINGING`, `OFFHOOK`, and `IDLE`.
4. Start a visible 25-second countdown while ringing.
5. Stop the countdown when the call is answered manually or ends.
6. Attempt to answer after 25 seconds with the best public Android API available.
7. Log every step to Logcat and the on-screen debug log.

## Platform Limits

- Call-state detection uses `TelephonyCallback.CallStateListener` on Android 12/API 31+ and `PhoneStateListener` on older supported Android versions.
- Caller number visibility is limited. Android only provides incoming numbers to ordinary apps in narrow cases, typically requiring `READ_CALL_LOG`; that permission is restricted and may not be grantable or useful on modern devices unless the app is allowlisted/default dialer/carrier-privileged.
- Auto-answer uses `TelecomManager.acceptRingingCall()`. It exists from Android 8.0/API 26, is deprecated from Android 10/API 29, and requires `ANSWER_PHONE_CALLS` or privileged `MODIFY_PHONE_STATE`.
- `MODIFY_PHONE_STATE` is not available to normal Play/user-installed apps. This project does not use reflection, hidden APIs, accessibility hacks, or shell commands to answer calls.
- Device manufacturer dialers, carrier apps, Android version, default dialer role, and permission policy can still prevent answering. The app logs the exact failure if the public API is denied or fails.

## Test Instructions

1. Open this folder in Android Studio.
2. Sync Gradle and run the `app` configuration on a physical Android phone with calling service. Emulators and Wi-Fi-only devices are not suitable for this experiment.
3. Grant the requested permissions when prompted.
4. Keep the app visible on screen.
5. Call the device from another phone.
6. Confirm that the UI and Logcat show `RINGING` and a 25-second countdown.
7. Test manual answer before 25 seconds; the countdown should stop and state should become `OFFHOOK`.
8. Test rejecting or ending the call; the countdown should stop and state should become `IDLE`.
9. Let a call ring for 25 seconds; the app should attempt `TelecomManager.acceptRingingCall()` and log success or the platform/security failure.

Use this Logcat filter:

```text
tag:AI_CALL_ASSISTANT
```
