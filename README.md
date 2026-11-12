# GlasNote — голосовые заметки с офлайн-распознаванием речи

Android-приложение для голосовых заметок на русском. Речь распознаётся **на телефоне**,
без интернета: 224 МБ модель [GigaAM v3](https://github.com/salute-developers/GigaAM)
в ONNX (int8), живое распознавание по мере говорения, VAD [Silero](https://github.com/snakers4/silero-vad).
Интернет нужен только для двух вещей — скачать модель при первом запуске и (по желанию)
почистить текст через DeepSeek.

Нативно на Kotlin + Jetpack Compose, `minSdk 26`.

## Что умеет

- **Запись одной кнопкой.** Экран записи: таймер, уровень микрофона, живой текст по мере речи.
  Распознавание на устройстве: 24 с звука → ~3 с счёта.
- **Живой транскрипт** (`LiveTranscriber`): аудио режется по паузам, каждая фраза уходит в модель
  отдельно, результат дописывается в поле — можно править текст на ходу.
- **Разбор фразы** (`DateNlu`): «завтра в 9 позвонить в сервис» → заметка + время + категория.
  Понимает «через 20 минут», дни недели, «12 мая», `12.05`, `14:30`, «утром/вечером».
- **Отборы и поиск**: активные/выполненные, «🔥 Важные», по категориям, по датам, полнотекстовый поиск.
- **Напоминания** (`AlarmManager` + `BootReceiver`), свайпы по карточке, отмена удаления.
- **Чистка текста через DeepSeek** — опционально, только текст или текст + 1–3 пункта пояснения.
  Стоимость каждого запроса считается по `usage` ответа и показывается под заметкой.
  Ключ лежит в `ApiKeyStore` (AES/GCM + AndroidKeyStore), в открытом виде не хранится.
- **Бэкап** — экспорт/импорт JSON.

## Структура репозитория

| Путь | Что это |
|---|---|
| `native/` | **основное приложение**: Kotlin/Compose, пакет `com.glassnotes.dev` |
| `native/app/src/main/java/ru/gigapisar/model/` | скачивание и проверка модели (докачка, SHA-256) |
| `native/app/src/main/java/com/glassnotes/dev/` | экраны, хранилище (SQLite), NLU, DeepSeek, VAD |
| `android/` | прежняя WebView-обёртка PWA на чистой Java, без Gradle — см. `android/README.md` |
| `index.html`, `sw.js`, `manifest.json` | PWA-версия (одна страница, без сборки) |
| `tools/`, `tests/` | вспомогательные скрипты и тесты |

## Сборка

Нужны JDK 17 и Android SDK (compileSdk 35). Gradle-обёртки в репозитории нет:

```bash
cd native
gradle assembleDebug          # APK: native/app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release-сборка подписывается ключом, который **не** лежит в репозитории: положи свой
`android/debug.keystore` (alias `androiddebugkey`) или поправь `signingConfigs` в
`native/app/build.gradle.kts`. Подпись нельзя менять у уже установленного приложения —
Android откажет с `UPDATE_INCOMPATIBLE`.

## Модель

При первом запуске приложение качает `v3_e2e_ctc.int8.onnx` (224 893 347 байт) с
[Hugging Face](https://huggingface.co/istupakov/gigaam-v3-onnx) в свой внутренний каталог,
проверяет SHA-256 и только потом подменяет боевой файл. Скачивание идёт в foreground-сервисе
с докачкой (`Range: bytes=N-`), поэтому его можно прервать, а экран — погасить.

Модель `e2e_ctc` — end-to-end: расставляет точки, запятые и заглавные буквы сама, постобработка
пунктуации не нужна.

## Лицензии

Код приложения — MIT (`LICENSE`). Модель GigaAM — MIT,
[`salute-developers/GigaAM`](https://github.com/salute-developers/GigaAM),
ONNX-экспорт — [`istupakov/gigaam-v3-onnx`](https://huggingface.co/istupakov/gigaam-v3-onnx).
VAD — [Silero VAD](https://github.com/snakers4/silero-vad) (MIT).
