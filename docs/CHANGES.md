# Change history

## 2026-11-07

### Project skeleton

- What: Gradle project with `:app`, `:core`, `:engine-llm`; shared contract, state and engine interfaces; UI-to-pipeline contract; model fetch script with SHA-256 lock; repository template cleaned of unrelated content.
- Why: everything else depends on a building base and fixed interfaces.
- Files: `src/mobile/android/**`, `scripts/fetch_models.sh`, `config/models.lock`, `README.md`, `src/README.md`, `docs/`.
- Validation: `assembleDevDebug` built an APK; models downloaded and hashed. lifecycle 2.11.0 failed on compileSdk 36, so 2.10.0 is used.
- Limitations: no engine code yet.

### Mascot and app icon

- What: eight mascot poses as WebP for light and dark themes, adaptive launcher icon with a monochrome layer.
- Why: empty, waiting and error states need one recognisable character. Dark variants use the light artwork with a cream die-cut border, because recolouring the outline lost the facial features.
- Files: `app/src/main/res/drawable-nodpi`, `drawable-night-nodpi`, `mipmap-anydpi-v26`, `values/ic_launcher.xml`, manifest icon attribute.
- Validation: resources compile; composites checked by eye on #FCFBF8, #141513 and #0F5C73.
