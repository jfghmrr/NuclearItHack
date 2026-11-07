# Project overview

Stenokot is an Android app that turns an audio recording into a verifiable transcript on the device. It was built for the "Нейро-стенографист" case of Nuclear IT Hack 2026 (Hex.team).

## Current state

As of 2026-11-07 the Gradle skeleton, shared types and the UI-to-pipeline contract are committed and compile. Engines, pipeline and UI are under construction. See [CHANGES.md](CHANGES.md) for what has been done and validated. Nothing below is a measured result unless [TEST_RESULTS.md](TEST_RESULTS.md) says so.

## Stack

Verified by a real build on 2026-11-07: AGP 9.3.3, Gradle 9.5.0, Kotlin 2.4.21, Compose BOM 2026.06.01, core-ktx 1.18.0, activity-compose 1.13.0, lifecycle 2.10.0, media3 1.11.1, kotlinx-serialization 1.12.0, coroutines 1.11.0, compileSdk and targetSdk 36, minSdk 29. lifecycle 2.11.0 requires compileSdk 37 and must not be used.

Runtime: sherpa-onnx v2.0.1 for speech, llama.cpp v0.9.0 for the local LLM. Models are listed in [MODEL_MANIFEST.md](MODEL_MANIFEST.md).

## Commands

Run from `src/mobile/android` after `scripts/fetch_models.sh`:

| Purpose | Command | Status |
|---|---|---|
| Core unit tests | `./gradlew :core:test` | Verified |
| Development APK | `./gradlew :app:assembleDevDebug` | Verified |
| Final APK | `./gradlew :app:assembleFullRelease` | Not yet verified |

## Constraints

- The organizers test a stock ARM Android emulator with 4 GB RAM in an automated black-box run. Image, vCPU count and host speed are unknown.
- The app must not declare `INTERNET`. Models must be inside the APK.
- The output JSON contract is exact: `transcript` with `speaker_id`, `start_ms`, `end_ms`, `text`, and nothing else.
- A local LLM is mandatory. It must never alter the transcript.
- Coding stops 2026-11-08 09:30 MSK.

## Context files

[ARCHITECTURE.md](ARCHITECTURE.md), [LOGIC.md](LOGIC.md), [CHANGES.md](CHANGES.md).

## Next steps

Finish engines, reach the first milestone (new MP3 through real models including the LLM to a valid JSON on the 4 GB AVD), then finish the interface and run the device checks.
