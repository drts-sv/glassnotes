# Сторонний код

## GigaAM v3 (модель распознавания речи)

- Автор: SberDevices / команда GigaAM, лицензия MIT
- Источник: https://github.com/salute-developers/GigaAM
- ONNX-конвертация: https://huggingface.co/istupakov/gigaam-v3-onnx (файлы `v3_e2e_ctc.int8.onnx`, `v3_e2e_ctc_vocab.txt`)
- Зеркало: релиз `model-v3` в https://github.com/moznoazachem/giga-pisar-android
- Модель не входит в APK, скачивается при первом запуске (214,5 МиБ).

## Код обвязки распознавания (Kotlin)

- Автор: moznoazachem, лицензия MIT — https://github.com/moznoazachem/giga-pisar-android
- Файлы в `android/kotlin/ru/gigapisar/`: `speech/GigaAmOnnxRecognizer.kt`,
  `audio/AudioRecorder.kt`, `audio/AudioChunker.kt`, `audio/LevelMeter.kt`,
  `model/ModelConfig.kt`, `model/ModelManager.kt`
- Изменения относительно оригинала: пакеты и логика сохранены; в `AudioRecorder.kt` добавлено
  публичное поле `rms` (абсолютная громкость куска PCM) — по адаптивному `level` нельзя отличить
  тишину от речи. Доступ к модели и распознавание вызываются из нашего `SpeechEngine`.

## ONNX Runtime

- Microsoft, лицензия MIT — https://github.com/microsoft/onnxruntime
- Android AAR 1.31.1: `classes.jar` + `lib/arm64-v8a/libonnxruntime.so`

## Silero VAD (определение речи)

| Файл | Автор | Лицензия |
|---|---|---|
| `assets/vad/silero_vad.onnx`, `kotlin/com/glassnotes/app/SileroVad.kt` | Silero Team (https://github.com/snakers4/silero-vad) | MIT |

Что изменено: сама модель и её ядро взяты как есть (~2.2 МБ, sha256 `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`,
источник — `src/silero_vad/data/silero_vad.onnx` из репозитория); написан только свой вызов модели
(обёртка `SileroVad.kt` и накопитель кадров в `AudioRecorder`). Текст лицензии — `android/licenses/silero-vad-MIT.txt`.
Назначение: авто-стоп записи по тишине в шумной обстановке (порог по громкости в шуме не работает).
