# Сборка и запуск

Android-проект находится в `src/mobile/android`.

## Что нужно

| Компонент | Версия |
|---|---|
| JDK | 21 (подходит JBR из Android Studio) |
| Android SDK | platform 36, build-tools 36.0.0 |
| NDK | 28.2.13676358 для приложения, 29.0.13113456 для llama.cpp |
| CMake | 3.31.6 |

```bash
sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;28.2.13676358" "ndk;29.0.13113456" "cmake;3.31.6"
```

## Сборка из чистого клона

```bash
git clone --recurse-submodules https://github.com/jfghmrr/NuclearItHack.git
cd NuclearItHack
./scripts/fetch_models.sh
cd src/mobile/android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleFullRelease
```

Модели и нативные библиотеки sherpa-onnx не хранятся в Git: скрипт скачивает их и сверяет SHA-256 по `config/models.lock`. Без `keystore.properties` release-сборка подписывается отладочным ключом.

## Варианты сборки

| Вариант | Назначение |
|---|---|
| `full` | Итоговый APK, модели внутри `assets/models` без сжатия |
| `dev` | Разработка: модели читаются из `Android/data/<пакет>/files/models` |

## Запуск

```bash
adb install -r -g app/build/outputs/apk/full/release/app-full-release.apk
adb push запись.mp3 /sdcard/Download/
adb shell am start -a android.intent.action.VIEW -t audio/mpeg \
  -d file:///sdcard/Download/запись.mp3 -n ru.stenokot.app/.MainActivity
```

Результат появится в `/sdcard/Download/Stenokot/запись.json`. Подробнее в [docs/AUTOMATION.md](../docs/AUTOMATION.md).

## Тесты

```bash
cd src/mobile/android
./gradlew :core:test
```
