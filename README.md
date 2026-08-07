# CareLens

CareLens is a privacy-first, offline Android app for patients in India to organize and understand their medical documents in English and Hindi.

## Principles

- All patient data stays on the device.
- No internet access or cloud processing.
- Document-backed insights with clear safety boundaries.
- English and Hindi from first launch.

## Current milestone: Offline reading and document-grounded insights (Phases 7–8)

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
- Language preference persists on the device and is used from the locked screen onward.
- Existing document-backed answers, safety notes, and recommendations support English and Hindi.
- Photos and PDFs selected from the device are immediately encrypted into app-private storage.
- Camera scans use a short-lived local staging file that is encrypted and then deleted.
- Imported photos and PDFs are read using bundled English and Devanagari OCR with no network access.
- OCR text is encrypted in the vault; a decrypted input is created in private cache only while OCR runs, then removed.
- A private, document-backed timeline copies detected dates, lab results, medicines, diagnoses, and prescription instructions with their document/page source.
- Patients can ask questions about the readable documents in their vault and see document-and-page citations.
- Follow-up language is surfaced from the uploaded records with a clear non-diagnostic safety boundary.

Biometric unlock remains a future user-facing milestone.

## Build requirements

- Android Studio with JDK 17
- Android SDK Platform 37

Open this directory in Android Studio and let it sync Gradle.
