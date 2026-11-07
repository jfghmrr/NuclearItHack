# Стенокот

Android-приложение, которое превращает аудиозапись разговора в проверяемый протокол прямо на устройстве: текст дословно, говорящие, временные метки, оглавление и протокол со ссылками на реплики и звук. Без интернета и без облачных сервисов.

Кейс «Нейро-стенографист», Nuclear IT Hack 2026, Hex.team.

## Что делает приложение

1. Принимает аудио: импорт из памяти устройства, «Открыть с помощью», «Поделиться».
2. Распознаёт речь, определяет говорящих и расставляет временные метки.
3. Автоматически сохраняет `Download/Stenokot/<имя>.json` строго по контракту задания:

```json
{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день!"}]}
```

4. Показывает стенограмму сразу, затем локальная языковая модель строит оглавление и протокол. Модель не меняет текст, говорящих и время: каждый пункт ведёт к дословной реплике и звуку.

## Сборка

Требования: JDK 21, Android SDK 36, NDK 28.2.13676358 и 29.0.13113456, CMake 3.31.6. Подробности в [src/README.md](src/README.md).

```bash
git clone --recurse-submodules https://github.com/jfghmrr/NuclearItHack.git
cd NuclearItHack
./scripts/fetch_models.sh
cd src/mobile/android
./gradlew :app:assembleFullRelease
```

## Документация

| Файл | Содержание |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Архитектура и технические решения |
| [docs/AUTOMATION.md](docs/AUTOMATION.md) | Запуск и проверка без участия человека |
| [docs/LIMITATIONS.md](docs/LIMITATIONS.md) | Ограничения решения |
| [docs/TEST_RESULTS.md](docs/TEST_RESULTS.md) | Методика и результаты тестирования |
| [docs/TESTING.md](docs/TESTING.md) | Матрица проверок |
| [docs/SECURITY.md](docs/SECURITY.md) | Безопасность и приватность |
| [docs/MODEL_MANIFEST.md](docs/MODEL_MANIFEST.md) | Модели, источники, лицензии, хэши |
