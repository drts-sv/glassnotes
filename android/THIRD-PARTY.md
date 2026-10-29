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
- Изменения относительно оригинала: версия 1.0-giga, используются как есть;
  доступ к модели и распознавание вызываются из нашего `SpeechEngine`.

## ONNX Runtime

- Microsoft, лицензия MIT — https://github.com/microsoft/onnxruntime
- Android AAR 1.31.1: `classes.jar` + `lib/arm64-v8a/libonnxruntime.so`
