# Change history

## 2026-11-07

### Project skeleton

- What: Gradle project with `:app`, `:core`, `:engine-llm`; shared contract, state and engine interfaces; UI-to-pipeline contract; model fetch script with SHA-256 lock; repository template cleaned of unrelated content.
- Why: everything else depends on a building base and fixed interfaces.
- Files: `src/mobile/android/**`, `scripts/fetch_models.sh`, `config/models.lock`, `README.md`, `src/README.md`, `docs/`.
- Validation: `assembleDevDebug` built an APK; models downloaded and hashed. lifecycle 2.11.0 failed on compileSdk 36, so 2.10.0 is used.
- Limitations: no engine code yet.
