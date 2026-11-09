# Правила R8 для релиза GlasNote.
# Библиотека распознавания работает через JNI: классы вызываются из нативного кода по именам,
# поэтому переименовывать их нельзя — иначе приложение падает на первом же прогоне модели.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }

# Заметки и настройки хранятся в JSON: ключи читаются по строкам, но поля моделей не трогаем —
# так проще отлаживать и это копейки по размеру.
-keep class com.glassnotes.dev.NoteRow { *; }
-keep class com.glassnotes.dev.DueNote { *; }
-keep class com.glassnotes.dev.ParsedNote { *; }
