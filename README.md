# CareLens

CareLens is a privacy-first, offline Android app for patients in India to organize and understand their medical documents in English and Hindi.

## Principles

- All patient data stays on the device.
- No internet access or cloud processing.
- Document-backed insights with clear safety boundaries.
- English and Hindi from first launch.

## Current milestone

The first Android prototype includes:

- English and Hindi onboarding copy.
- Language selection that updates the complete prototype interface.
- App PIN or password vault setup.
- An encrypted, randomly generated vault key protected by the selected secret.
- Explicitly disabled Android cloud backup and device-to-device transfer.
- No `INTERNET` permission and no cloud SDKs.

Document import, biometric unlock, encrypted document storage, local OCR, and medical analysis are separate upcoming milestones. The prototype does not accept medical documents yet.

## Build requirements

- Android Studio with JDK 17
- Android SDK Platform 37

Open this directory in Android Studio and let it sync Gradle.
