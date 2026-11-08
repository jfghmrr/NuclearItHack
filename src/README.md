# Сборка и запуск

Android-проект находится в `src/mobile/android`. Итоговый APK один, все модели лежат внутри него.

## Что нужно

| Компонент | Версия |
|---|---|
| JDK | 21, подходит JBR из Android Studio |
| Android SDK | platform 36, build-tools 36.0.0 |
| NDK | 28.2.13676358 для приложения, 29.0.13113456 для llama.cpp |
| CMake | 3.31.6 |
| Утилиты | `git`, `curl`, `tar`, `shasum` |

```bash
sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;28.2.13676358" "ndk;29.0.13113456" "cmake;3.31.6"
```

Версии проекта: AGP 9.3.3, Gradle 9.5.0 через обёртку, Kotlin 2.4.21, Compose BOM 2026.06.01, compileSdk и targetSdk 36, minSdk 29. Остальное закреплено в `src/mobile/android/gradle/libs.versions.toml`.

## Сборка из чистого клона

```bash
git clone --recurse-submodules https://github.com/jfghmrr/NuclearItHack.git
cd NuclearItHack
./scripts/fetch_models.sh
cd src/mobile/android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleFullRelease
```

Результат: `app/build/outputs/apk/full/release/app-full-release.apk`.

- Подмодуль `third_party/llama.cpp` описан в `.gitmodules` и закреплён на теге v0.9.0. Если клон сделан без `--recurse-submodules`, выполните `git submodule update --init --depth 1`
- Модели и нативные библиотеки sherpa-onnx не хранятся в Git. Скрипт скачивает их, модели занимают 1270,7 МиБ, и сверяет SHA-256 по `config/models.lock`. Сеть нужна сборке, приложению нет
- Каталог моделей задаёт переменная `STENOKOT_MODELS_DIR`, по умолчанию `models` в корне репозитория
- Без `keystore.properties` release-сборка подписывается отладочным ключом

## Сборка в CI

Тот же путь из чистого клона выполняет GitHub Actions: [.github/workflows/release.yml](../.github/workflows/release.yml). Запуск по тегу `v*` или вручную.

1. Подмодуль, JDK 21, компоненты Android SDK
2. `scripts/fetch_models.sh` с кэшем по `config/models.lock`
3. Тесты `:core:test` и `:app:testDevDebugUnitTest`
4. `:app:assembleFullRelease`, подпись ключом команды из секретов репозитория
5. Проверка `aapt2 dump permissions`: сборка падает, если в APK есть `INTERNET` или `ACCESS_NETWORK_STATE`
6. Публикация APK `skvor-<commit>.apk` в Releases, SHA-256 в описании релиза

Пробный запуск 42394754449 на коммите `f0bba51` собрал APK из чистого клона примерно за 17 минут.

## Варианты сборки

| Вариант | Назначение |
|---|---|
| `full` | Итоговый APK, модели внутри `assets/models` без сжатия |
| `dev` | Разработка: модели читаются из `Android/data/<пакет>/files/models`, APK маленький и ставится быстро |

## Запуск

```bash
adb install -r -g app/build/outputs/apk/full/release/app-full-release.apk
adb push dialog1.mp3 /sdcard/Download/
adb shell am start -a android.intent.action.VIEW -t audio/mpeg \
  -d file:///sdcard/Download/dialog1.mp3 -p ru.skvor.app
```

Результат появится в `/sdcard/Download/Skvor/dialog1.json`.

## Тесты

```bash
cd src/mobile/android
./gradlew :core:test :app:testDevDebugUnitTest
```

Тесты идут на JVM и не требуют устройства. Результаты в [docs/TEST_RESULTS.md](../docs/TEST_RESULTS.md).
