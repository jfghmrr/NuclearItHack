# Материалы демонстрации

Шаблон организаторов просит видео передачи, исходный и полученный файлы, логи, сравнение контрольных сумм и фото стенда. Ниже то же самое для нашего кейса: на входе аудиофайл, на выходе контрактный JSON.

| Что просят | Где у нас |
|---|---|
| Видео работы | [skvor_tour_honor.mp4](skvor_tour_honor.mp4), 5 мин 49 с: первый запуск, знакомство, импорт через системный выбор файла, обработка, стенограмма, плеер, говорящие, поиск, правка реплики, протокол, английская запись, настройки, тёмная тема, «Помощь». Короткое видео 110 с: [skvor_demo_honor.mp4](skvor_demo_honor.mp4) |
| Исходные файлы | [input/Podcast_RU.mp3](input/Podcast_RU.mp3), 80 с, три голоса, русский; [input/Team_meeting_EN.mp3](input/Team_meeting_EN.mp3), 60 с, четыре голоса, английский |
| Полученные файлы | [output/Podcast_RU.json](output/Podcast_RU.json), [output/Team_meeting_EN.json](output/Team_meeting_EN.json) |
| Логи | [logs/](logs): `metadata.json` каждого прогона с временем стадий, версиями и SHA-256 моделей, строки журнала `PIPELINE` |
| Сравнение контрольных сумм | [checksums.png](checksums.png) и [logs/checksums.txt](logs/checksums.txt): SHA-256 входа и результата на диске совпадают с записанными в `metadata.json` |
| Стенд | Телефон Honor NTN-LX1, Android 13, Snapdragon 662, 6 ГБ RAM, без сети у приложения. Видео снято с экрана телефона через `scrcpy` по `adb`, без монтажа и ускорения |

Видео и файлы сняты на сборке 1.1.0, 8 ноября 2026, до переименования папки результатов: путь в `metadata.json` поэтому отличается от `Download/Skvor` в 1.2.0. Журналы оставлены как есть, без правки.

Источники звука: `Podcast_RU.mp3` - фрагмент набора leshinsky/ru-youtube-diarization (Apache-2.0 по карточке набора), `Team_meeting_EN.mp3` - фрагмент AMI Meeting Corpus IS1008a (CC BY 4.0, Jean Carletta et al.).
