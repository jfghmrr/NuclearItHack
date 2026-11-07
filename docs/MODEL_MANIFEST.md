# Модели

Файлы скачивает `scripts/fetch_models.sh`, хэши закреплены в `config/models.lock`.

| Назначение | Модель | Источник | Лицензия |
|---|---|---|---|
| Распознавание RU | GigaAM v3 e2e RNNT int8 | k2-fsa/sherpa-onnx, релиз `asr-models` | MIT |
| Распознавание EN | Parakeet TDT 110M int8 | k2-fsa/sherpa-onnx, релиз `asr-models` | Код Apache-2.0, веса CC-BY-4.0 |
| Определение речи | Silero VAD | k2-fsa/sherpa-onnx, релиз `asr-models` | MIT |
| Сегментация говорящих | pyannote segmentation 3.0 int8 | k2-fsa/sherpa-onnx, релиз `speaker-segmentation-models` | MIT |
| Эмбеддинги говорящих | TitaNet small | k2-fsa/sherpa-onnx, релиз `speaker-recongition-models` | Apache-2.0 по карточке NGC, README Scribbit указывает CC BY 4.0 |
| Языковая модель | Qwen3-0.6B Q4_K_M | unsloth/Qwen3-0.6B-GGUF, коммит `50968a4468`, основа Qwen/Qwen3-0.6B | Apache-2.0 |

Модели дополнительно не дообучались: в работе используются опубликованные веса.
