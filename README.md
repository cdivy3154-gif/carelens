# CareLens

CareLens is a privacy-first, offline Android app for patients in India to organize and understand their medical documents in English and Hindi.

## Principles

- All patient data stays on the device.
- No internet access or cloud processing.
- Document-backed insights with clear safety boundaries.
- English and Hindi from first launch.

## Current milestone: Secure vault (Phase 3)

The first Android prototype includes:

- English and Hindi onboarding copy.
- Language selection that updates the complete prototype interface.
- App PIN or password vault setup and login.
- A 12-word, user-held recovery phrase for resetting a forgotten lock.
- Automatic locking whenever the app leaves the foreground, plus a manual lock action.
- A deliberately confirmed vault erase flow that destroys the encryption key and local vault files.
- An encrypted, randomly generated vault key protected by the selected secret.
- Explicitly disabled Android cloud backup and device-to-device transfer.
- No `INTERNET` permission and no cloud SDKs.
- Screenshot and screen-recording protection while the app is visible.

Encrypted document storage and bundled offline OCR foundations are present but are not exposed in the UI until Phases 6 and 7. Biometric unlock, medical analysis, and document import screens remain future milestones.

## Build requirements

- Android Studio with JDK 17
- Android SDK Platform 37

Open this directory in Android Studio and let it sync Gradle.
