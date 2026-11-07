# Автоматический запуск

Приложение рассчитано на проверку без участия человека: нет онбординга, диалогов и запросов разрешений при запуске, импорт сразу запускает обработку.

## Запуск

```bash
adb install -r -g app-full-release.apk
adb push dialog1.mp3 /sdcard/Download/
adb shell am start -a android.intent.action.VIEW -t audio/mpeg \
  -d file:///sdcard/Download/dialog1.mp3 -n ru.stenokot.app/.MainActivity
```

## Результат

`/sdcard/Download/Stenokot/dialog1.json`. Служебные данные лежат отдельно в `/sdcard/Download/Stenokot/meta/`.

## Признаки готовности

Строка `txt_status` видна на каждом экране и дублируется в logcat с тегом `PIPELINE`: `IDLE`, `QUEUED`, `RUNNING:<STAGE>`, `TRANSCRIPT_READY`, `WAITING_MODEL`, `DONE`, `DONE_LLM_FAILED`, `FAILED:<CODE>`, `CANCELLED`. Контрактный файл готов при `TRANSCRIPT_READY`.

## Идентификаторы для UiAutomator

`testTag` доступны как `resource-id`: `btn_import`, `btn_start`, `btn_cancel`, `btn_export`, `txt_status`, `txt_output_path`, `list_transcript`, `row_utt_<n>`, `list_audio`, `row_audio_<n>`.
