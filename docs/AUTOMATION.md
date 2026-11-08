# Автоматический запуск

Приложение рассчитано на проверку без участия человека: нет онбординга, диалогов и запросов разрешений при запуске, импорт сразу запускает обработку, а следующий файл принимается в том же запущенном приложении.

## Запуск

```bash
adb install -r -g skvor-<commit>.apk
adb push dialog1.mp3 /sdcard/Download/
adb shell am start -a android.intent.action.VIEW -t audio/mpeg \
  -d file:///sdcard/Download/dialog1.mp3 -p ru.skvor.app
```

- Ключ `-g` выдаёт разрешение на чтение аудио. Без него адрес `file://` не открыть, и задача завершится `FAILED:IMPORT_NO_PERMISSION`
- Для других форматов укажите свой тип: `audio/wav`, `audio/mp4`, `audio/ogg`, `audio/flac`
- Несколько файлов можно отправить подряд: они встанут в очередь и пройдут по порядку
- Тот же путь через интерфейс: `btn_import`, затем файл в системном окне выбора. Окно открывается в `Download`, можно выбрать несколько файлов. Запускать обработку отдельно не нужно
- Папка `/sdcard/Android/data/ru.skvor.app/files/inbox/` видна приложению без разрешений. Файл, положенный туда через `adb push`, появляется на Главной в разделе «Аудио на устройстве» с подписью «Папка приложения». Нажатие на строку запускает обработку

## Результат

Контрактный файл: `/sdcard/Download/Skvor/dialog1.json`. Имя берётся из имени исходного файла без расширения. Повторная обработка того же файла заменяет свой результат, другой файл с тем же именем получает имя с расширением, например `dialog1.wav.json`.

Если записать в `Download` не удалось, тот же файл появляется в `/sdcard/Android/data/ru.skvor.app/files/export/`, и этот путь показывает `txt_output_path`. Служебные данные лежат отдельно в `/sdcard/Download/Skvor/meta/`, поэтому поиск `*.json` в основной папке находит только контрактные файлы.

Файл публикуется целиком: пока запись не закончена, в папке его не видно. Экспорт вручную не нужен.

```bash
adb shell ls /sdcard/Download/Skvor/
```

## Признаки готовности

Строка `txt_status` есть на каждом экране и дублируется в logcat с тегом `PIPELINE`:

```bash
adb logcat -s PIPELINE:I
```

| Значение | Что значит | JSON готов |
|---|---|---|
| `IDLE` | Задачи нет | - |
| `QUEUED` | Файл принят и ждёт очереди | Нет |
| `RUNNING:<STAGE>` | Идёт стадия: `IMPORT`, `DECODE`, `VAD`, `LANG_ID`, `ASR`, `DIARIZE`, `MERGE`, `VALIDATE` | Нет |
| `TRANSCRIPT_READY` | JSON записан, до старта языковой модели 3 с | Да |
| `WAITING_MODEL` | JSON записан, файл языковой модели ещё копируется | Да |
| `RUNNING:LLM` | JSON записан, идёт протокол | Да |
| `DONE` | Всё закончено, протокол и замеры лежат в `meta/` | Да |
| `DONE_LLM_FAILED` | Стенограмма готова, протокол не создан | Да |
| `DONE_META_FAILED` | Стенограмма готова, протокол или замеры не сохранились в `meta/` | Да |
| `FAILED:<CODE>` | Ошибка или прерывание до появления JSON | Нет |
| `CANCELLED` | Обработка отменена | Нет |

Контрактный файл готов при `TRANSCRIPT_READY`. Пауза из-за перегрева остаётся `RUNNING:<STAGE>`: работа продолжится сама. Значения внутри этапа обновляются не чаще раза в секунду. Маскот на экране обработки слегка «дышит», пока экран виден: значение меняется таймером раз в 100 мс, а не каждый кадр. Это движение выключается в «Настройках», «Анимации маскота», и системным отключением анимации.

## Идентификаторы для UiAutomator

`testTag` доступны как `resource-id` и не зависят от языка устройства. Индекс `<n>` начинается с нуля.

| Экран | Основные идентификаторы |
|---|---|
| Все | `txt_status`, `btn_back` |
| Главная | `btn_import`, `btn_import_all`, `btn_record`, `btn_settings`, `txt_models`, `txt_library_search`, `list_audio`, `row_audio_<n>`, `row_audio_permission`, `list_queue`, `row_queue_<n>`, `list_history`, `row_history_<n>`, `card_job`, `btn_start`, `btn_resume`, `btn_delete_<n>` |
| Обработка | `card_file`, `txt_file_name`, `txt_preflight`, `list_stages`, `row_stage_<STAGE>`, `txt_stage`, `txt_stage_index`, `txt_stage_percent`, `txt_stage_counter`, `progress_stage`, `txt_elapsed`, `txt_remaining`, `txt_status_hint`, `btn_start`, `btn_processing_menu`, `btn_cancel`, `btn_cancel_confirm` |
| Итог обработки | `txt_done_title`, `btn_open_transcript`, `btn_export`, `txt_output_path`, `btn_to_library` |
| Результат | `tab_transcript`, `tab_protocol`, `btn_export`, `btn_result_menu`, `list_transcript`, `row_utt_<n>`, `list_speakers`, `btn_search`, `txt_search`, `btn_play`, `slider_player`, `txt_player_time`, `banner_llm`, `btn_import` |
| Экспорт | `sheet_export`, `row_export_transcript`, `btn_export_protocol`, `btn_export_save`, `btn_share` |
| Файл результата | `panel_export`, `txt_output_path`, `txt_output_meta`, `btn_copy_path`, `btn_open_folder`, `btn_share` |
| Протокол | `txt_llm_draft`, `list_chapters`, `row_chapter_<n>`, `list_protocol`, `row_protocol_<n>`, `txt_protocol_state`, `btn_goto_utterance` |
| Ошибка | `txt_error_title`, `txt_error_body`, `btn_error_details`, `btn_copy_diagnostics`, `btn_models`, `btn_start`, `btn_import` |
| Модели | `list_models`, `row_model_<n>`, `btn_check_models`, `txt_model_integrity` |
| Приватность | `txt_offline`, `btn_permissions`, `list_permissions` |
| Диагностика | `txt_rtf_core`, `txt_rtf_full`, `list_stage_metrics`, `txt_json_valid`, `txt_json_path`, `btn_copy_diagnostics`, `btn_export_meta` |
| Запись | `txt_rec_elapsed`, `progress_rec_level`, `btn_rec_stop`, `btn_rec_cancel` |

## Ошибки

Ошибка не показывает модальных окон. Приложение остаётся живым, `txt_status` показывает `FAILED:<CODE>`, а следующий файл можно отправить той же командой.

| Код | Когда |
|---|---|
| `STORAGE_LOW` | Не хватает места |
| `IMPORT_READ_FAILED`, `IMPORT_NO_PERMISSION` | Файл не читается или нет доступа |
| `DECODE_UNSUPPORTED_FORMAT` | Не аудио или нет декодера |
| `DECODE_CORRUPT` | Файл повреждён или обрезан |
| `MODEL_LOAD_FAILED` | Модель речи не загрузилась |
| `LOW_MEMORY` | Система остановила обработку из-за памяти |
| `CONTRACT_INVALID` | Результат не прошёл проверку, файл не записан |
| `FGS_TIMEOUT` | Система остановила службу, чекпойнт сохранён |
| `INTERRUPTED` | Прерывание без известной причины |

Тишина не считается ошибкой: записывается `{"transcript":[]}`, статус `DONE`.

## Что проверено на устройстве

Запуск через `am start` с `VIEW` проверен на эмуляторе и на Honor. Повреждённый MP3 и тишина проверены на эмуляторе. Пути с `inbox`, папкой `export` и записью в logcat есть в коде, но на последней сборке отдельно не прогонялись. Подробно в [TEST_RESULTS.md](TEST_RESULTS.md).
