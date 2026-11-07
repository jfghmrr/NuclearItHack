# Модели

Файлы скачивает `scripts/fetch_models.sh`, хэши закреплены в [config/models.lock](../config/models.lock). В итоговом APK все модели лежат в `assets/models` без сжатия.

**Модели не дообучались.** В работе используются опубликованные веса. Настраиваются только пороги и логика вокруг моделей.

| Назначение | Модель | Источник | Размер | Лицензия |
|---|---|---|---|---|
| Распознавание RU | GigaAM v3 e2e RNNT int8 | k2-fsa/sherpa-onnx, релиз `asr-models` | 221,2 МиБ | MIT |
| Распознавание EN | Parakeet TDT 110M int8 | k2-fsa/sherpa-onnx, релиз `asr-models` | 130,2 МиБ | Код Apache-2.0, веса CC-BY-4.0 |
| Определение речи | Silero VAD | k2-fsa/sherpa-onnx, релиз `asr-models` | 0,6 МиБ | MIT |
| Сегментация говорящих | pyannote segmentation 3.0 int8 | k2-fsa/sherpa-onnx, релиз `speaker-segmentation-models` | 1,5 МиБ | MIT |
| Эмбеддинги говорящих | TitaNet small | k2-fsa/sherpa-onnx, релиз `speaker-recongition-models` | 38,4 МиБ | Apache-2.0 по карточке NGC |
| Языковая модель | Qwen3-0.6B Q4_K_M | unsloth/Qwen3-0.6B-GGUF, коммит `50968a4468`, основа Qwen/Qwen3-0.6B | 378,3 МиБ | Apache-2.0 |

Всего 770 МиБ. Размеры посчитаны по скачанным файлам.

Лицензия TitaNet small указана по чтению карточки модели на NGC, где она ссылается на лицензию NeMo Toolkit. Это не юридическое заключение. Официальный репозиторий Qwen содержит только вариант Q8_0, поэтому Q4_K_M взят из сторонней сборки, и её коммит закреплён.

## Файлы

| Модель | Каталог | Файлы |
|---|---|---|
| GigaAM v3 e2e | `asr-ru/` | `encoder.int8.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt` |
| Parakeet TDT 110M | `asr-en/` | `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt` |
| Silero VAD | `vad/` | `silero_vad.onnx` |
| pyannote segmentation 3.0 | `diar/` | `segmentation.int8.onnx` |
| TitaNet small | `diar/` | `titanet_small.onnx` |
| Qwen3-0.6B Q4_K_M | `llm/` | `qwen3-0.6b-q4_k_m.gguf` |

## Как проверить хэши

```bash
./scripts/fetch_models.sh
cd models
shasum -a 256 -c ../config/models.lock
```

Скрипт сам сверяет хэши после скачивания и завершается с ошибкой при несовпадении.

Перечень хэшей вшивается в сборку из `config/models.lock`. Экран проверки и `metadata.json` каждого прогона показывают именно его, без чтения сотен мегабайт на устройстве. При копировании на диск языковая модель сверяется со своим хэшем целиком. Файлы ONNX при запуске заново не пересчитываются.

## Движки

| Движок | Версия | Откуда |
|---|---|---|
| sherpa-onnx с ONNX Runtime | v2.0.1 | Нативные библиотеки из релиза k2-fsa/sherpa-onnx, скачивает `scripts/fetch_models.sh` |
| llama.cpp | v0.9.0, коммит `cd959bae99` | Подмодуль `third_party/llama.cpp`, собирается вместе с приложением |

## Почему эти модели

- GigaAM v3 e2e даёт пунктуацию, регистр и междометия прямо из звука. Пунктуация входит в требования, а языковая модель интонацию не слышит
- Parakeet TDT 110M закрывает английские записи и загружается только при уверенном определении языка
- TitaNet small используется без квантования
- Qwen3-0.6B Q4_K_M укладывается в память рядом с остальным приложением на 4 ГБ, потому что стадии идут по очереди. Замер пика - в [TEST_RESULTS.md](TEST_RESULTS.md)

Цена выбора GigaAM e2e описана в [LIMITATIONS.md](LIMITATIONS.md).
