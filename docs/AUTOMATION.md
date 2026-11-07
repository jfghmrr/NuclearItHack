# Автоматический запуск

Приложение рассчитано на проверку без участия человека: нет онбординга, диалогов и запросов разрешений при запуске, импорт сразу запускает обработку.

## Запуск

```bash
adb install -r -g app-full-release.apk
adb push dialog1.mp3 /sdcard/Download/
adb shell am start -a android.intent.action.VIEW -t audio/mpeg \
  -d file:///sdcard/Download/dialog1.mp3 -n ru.stenokot.app/.MainActivity
```

- Ключ `-g` выдаёт разрешение на чтение аудио: без него адрес `file://` не открыть, задача завершится `FAILED:IMPORT_NO_PERMISSION`
- Для других форматов укажите свой тип: `audio/wav`, `audio/mp4`, `audio/ogg`, `audio/flac`
- Несколько файлов можно отправить подряд: они встанут в очередь и пройдут по порядку
- Тот же путь через интерфейс: нажатие `btn_import`, затем файл в системном окне выбора. Запускать обработку отдельно не нужно
- Папка `/sdcard/Android/data/ru.stenokot.app/files/inbox/` видна приложению без разрешений. Файл, положенный туда через `adb push`, появляется в списке «Найденные аудио» с подписью «Папка приложения»

## Результат

Контрактный файл: `/sdcard/Download/Stenokot/dialog1.json`. Имя берётся из имени исходного файла без расширения. Если записать в `Download` не удалось, тот же файл появляется в `Android/data/ru.stenokot.app/files/export/`, и этот путь показывает `txt_output_path`. Служебные данные лежат отдельно в `/sdcard/Download/Stenokot/meta/`, поэтому поиск `*.json` в основной папке находит только контрактные файлы.

Файл появляется автоматически, экспорт вручную не нужен.

## Признаки готовности

Строка `txt_status` видна на каждом экране и дублируется в logcat с тегом `PIPELINE`:

```bash
adb logcat -s PIPELINE:I
```

| Значение | Что значит | Файл готов |
|---|---|---|
| `IDLE` | Задачи нет | - |
| `QUEUED` | Файл принят и ждёт очереди | Нет |
| `RUNNING:<STAGE>` | Идёт стадия: `IMPORT`, `DECODE`, `VAD`, `LANG_ID`, `ASR`, `DIARIZE`, `MERGE`, `VALIDATE` | Нет |
| `TRANSCRIPT_READY` | JSON записан, до старта языковой модели 3 с | Да |
| `WAITING_MODEL` | JSON записан, файл языковой модели ещё копируется | Да |
| `RUNNING:LLM` | JSON записан, идёт протокол | Да |
| `DONE` | Всё закончено | Да |
| `DONE_LLM_FAILED` | Стенограмма готова, протокол не создан | Да |
| `FAILED:<CODE>` | Ошибка или прерывание до появления файла | Нет |
| `CANCELLED` | Обработка отменена | Нет |

Контрактный файл готов при `TRANSCRIPT_READY`. Пауза из-за перегрева остаётся `RUNNING:<STAGE>`: работа продолжится сама. Прогресс обновляется не чаще раза в секунду, бесконечных анимаций нет: они не мешают `uiautomator` дождаться простоя интерфейса.

Файл публикуется целиком: пока запись не закончена, в папке его не видно. Проверка в обход статуса:

```bash
adb shell ls /sdcard/Download/Stenokot/
```

## Идентификаторы для UiAutomator

`testTag` доступны как `resource-id` и не зависят от локали устройства.

| Экран | Идентификаторы |
|---|---|
| Все | `txt_status`, `btn_back` |
| Главная | `btn_import`, `btn_import_all`, `txt_models`, `btn_start`, `btn_resume`, `card_file`, `card_job`, `list_audio`, `row_audio_<n>`, `row_audio_permission`, `list_queue`, `row_queue_<n>`, `list_history`, `row_history_<n>`, `btn_delete_<n>` |
| Обработка | `txt_file_name`, `txt_preflight`, `txt_stage`, `txt_stage_index`, `txt_stage_percent`, `txt_stage_counter`, `progress_stage`, `txt_elapsed`, `txt_remaining`, `txt_status_hint`, `list_stages`, `row_stage_<STAGE>`, `btn_start`, `btn_cancel` |
| Результат | `tab_transcript`, `tab_protocol`, `list_transcript`, `row_utt_<n>`, `btn_play`, `slider_player`, `txt_output_path`, `btn_export`, `btn_export_protocol`, `btn_import` |
| Ошибка | `txt_error_title`, `txt_error_body`, `btn_models`, `btn_start`, `btn_import` |
| Проверка | `txt_offline`, `list_models`, `row_model_<n>`, `txt_rtf_core`, `txt_rtf_full`, `list_stage_metrics`, `btn_export_meta` |

Индекс `<n>` начинается с нуля.

## Ошибки

Ошибка не показывает модальных окон. Приложение остаётся живым, `txt_status` показывает `FAILED:<CODE>`, а следующий файл можно отправить той же командой.

| Код | Когда |
|---|---|
| `STORAGE_LOW` | Не хватает места |
| `IMPORT_READ_FAILED`, `IMPORT_NO_PERMISSION` | Файл не читается или нет доступа |
| `DECODE_UNSUPPORTED_FORMAT` | Не аудио или нет декодера |
| `DECODE_CORRUPT` | Файл повреждён или обрезан |
| `MODEL_LOAD_FAILED` | Модель не загрузилась |
| `LOW_MEMORY` | Система остановила обработку из-за памяти |
| `CONTRACT_INVALID` | Результат не прошёл проверку, файл не записан |
| `FGS_TIMEOUT` | Система остановила службу, чекпойнт сохранён |
| `INTERRUPTED` | Прерывание без известной причины |

Тишина не считается ошибкой: файл `{"transcript":[]}` записывается, статус `DONE`.
