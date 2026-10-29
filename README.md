# Glass Notes

PWA голосовых напоминаний. Одна страница, без сборки, без зависимостей, офлайн.
Версия в репозитории: **5.10.3** (сборка 25.09.2026).

## Файлы

| Файл | Назначение |
|---|---|
| `index.html` | всё приложение: разметка + CSS + JS в одном файле (2747 строк) |
| `manifest.json` | манифест PWA (standalone, ru, тёмный фон) |
| `sw.js` | сервис-воркер: офлайн-оболочка + `showNotification` + `notificationclick` |
| `icon-192.png`, `icon-512.png` | иконки (скруглённый квадрат, микрофон) |
| `icon-512-maskable.png` | maskable-иконка (full-bleed, безопасная зона) |
| `apple-touch-icon.png` | иконка для iOS |
| `serve.bat` | локальный сервер на :8013 для проверки с телефона |

## Запуск

```bat
serve.bat
```

Затем `http://localhost:8013/`. Для проверки с телефона — `http://<IP-ПК>:8013/`
(голосовой ввод и уведомления требуют HTTPS либо `localhost`; по LAN-IP
`webkitSpeechRecognition` в Chrome работать не будет).

## Архитектура

- **Хранилище** — IndexedDB `glass_notes_db` v4, стор `notes` (keyPath `id`,
  autoIncrement), индексы `status`/`datetime`/`timestamp`.
  Заметка: `{ text, time, datetime, timestamp, status, color, categoryIds, notified, created }`.
- **Настройки** — localStorage: `appTheme`, `undoTime`, `catSheetTimer`,
  `timeDefaults`, `askCategory`, `voiceCategory`.
- **Категории** — захардкожены в `DEFAULT_CATEGORIES` (important, work, personal,
  shopping, other, dev); `REMOVED_CATEGORY_IDS` (`calls`, `home`) вычищаются
  миграцией `migrateNote`.
- **NLU** (`parseDateNLU`) — вырезает из фразы дату, время и категорию:
  «через N мин/час/дн/недел», сегодня/завтра/послезавтра, дни недели,
  `12 мая 2026`, `12.05.26`, `2026-05-12`, `14:30` / «в 14 часов 30 минут»,
  периоды (утром, в обед, вечером…), «… категория работа» в конце фразы.
- **Рендер** — `renderTodos()`: таб → поиск → фильтр даты → фильтр категории,
  сортировка «важные вверх», затем по `timestamp` desc, секция «🔥 Важные».
- **Жесты** — свайп вправо (выполнено/вернуть), влево (удалить),
  долгое нажатие 500 мс (копировать).
- **Голос** — `webkitSpeechRecognition`, `ru-RU`, `continuous = false`.
- **Бэкап** — экспорт/импорт JSON (заметки + настройки), импорт «добавить»
  или «заменить всё».

## APK: нативная обёртка (основной путь)

`android/` — обёртка WebView на чистой Java, без Gradle и AndroidX. Сборка:

```bat
python android/build_apk.py
```

Конвейер: aapt2 → javac → d8 → zip → zipalign → apksigner (тот же, что в
`Documents\RouterApiBalance-Android`), тулчейн в `C:\Users\drts\.android-tools`.
Результат: `android/glass-notes.apk` → копия в `dist/glass-notes-5.10.4.apk`.

Что внутри:

| Часть | Зачем |
|---|---|
| `java/…/AssetServer.java` | HTTP-сервер на 127.0.0.1 из assets: на `file://` в WebView не работают IndexedDB, localStorage и service worker |
| `java/…/SpeechBridge.java` | `android.speech.SpeechRecognizer` — Web Speech API в WebView отсутствует (Chromium 40417848) |
| `java/…/NotificationBridge.java` | системные уведомления Android (в WebView нет `Notification`) |
| `assets/native-shim.js` | подставляет `webkitSpeechRecognition` и `Notification` поверх мостов; **не** перекрывает настоящие API, если они есть |
| `AndroidManifest.xml` | package `com.glassnotes.app`, minSdk 24, targetSdk 35, RECORD_AUDIO + POST_NOTIFICATIONS + INTERNET + VIBRATE |

Установка:

```bat
adb install -r dist\glass-notes-5.10.4.apk
```

Подпись — debug-ключом `android/debug.keystore` (пароль `android`), для Google Play
нужен собственный ключ.

Проверено без телефона: шим поднимает `webkitSpeechRecognition`/`Notification` там,
где их нет, вызов моста речи уходит с `ru-RU`, финальный результат создаёт заметку
через NLU, просроченное напоминание уходит в `NativeNotify.show`. Что проверяется
только на устройстве: доступность `SpeechRecognizer` и диалог разрешений.

## Сборка APK через WebToApp (альтернатива)

В WebToApp голосовой ввод работать не будет (в WebView нет Web Speech API, а моста
на `android.speech` у него нет), поэтому основной путь — нативная обёртка выше.

Проект собирается через [shiaho777/web-to-app](https://github.com/shiaho777/web-to-app)
(локальный клон: `C:\Users\drts\web-to-app`). JDK и Android SDK на ПК не нужны —
сборка идёт на телефоне.

1. Собрать `dist/probe.zip` (внутри `probe.html` → `index.html`) и посмотреть в APK,
   есть ли `webkitSpeechRecognition`. В Android WebView его **нет**
   ([Chromium 40417848](https://issues.chromium.org/issues/40417848), статус New),
   значит голосовой ввод в APK не заработает.
2. Импорт `dist/glass-notes-webtoutapp.zip`: Create → **HTML**, entry `index.html`.
3. Load mode — `AUTO` или `LOCAL_HTTP`: страница с манифестом и SW должна получить
   реальный origin `http://localhost:PORT`, иначе SW и secure context недоступны.
4. Special settings → **Notification polyfill = ON** (по умолчанию выключен).
   В WebView нет `Notification`, но полифил мостит `new Notification()` на
   `NativeBridge.showWebNotification()` → системные уведомления Android.
   Наш `showLocalNotification()` доходит туда через ветку `legacyNotification`.
5. Permissions: `RECORD_AUDIO` (микрофон — `RESOURCE_AUDIO_CAPTURE` поддерживается),
   `POST_NOTIFICATIONS` (приходит с полифилом), `INTERNET` для `LOCAL_HTTP`.

Ограничения APK-варианта: нет голосового ввода (WebView), напоминания работают
только когда приложение запущено.

## Что изменено в 5.10.4

1. **Уведомления через service worker.** `showLocalNotification` сначала пробует
   `registration.showNotification()` (работает на Android), а `new Notification()`
   оставлен как запасной путь для десктопа.
2. **Догон просроченных напоминаний.** `checkReminders` теперь берёт всё, что уже
   наступило и не отмечено (а не только окно 2 минуты), плюс проверка при
   `visibilitychange` и сразу при старте. Просрочка старше суток не поднимается,
   не больше 3 уведомлений за тик.
3. **Утечка слушателей устранена.** Перед перерисовкой списка старые карточки
   снимают свои `document`-слушатели (`detachSwipes()`), вместо накопления при
   каждом `renderTodos`.
4. **Путь к SW** — `register('sw.js')` вместо `'/sw.js'`.
5. **Разрешение на уведомления** запрашивается независимо от регистрации SW
   и повторяется по первому жесту (на Android это обязательно).
6. **Версия** — единственная константа `APP_VERSION`, подставляется в footer,
   «О приложении» и экспорт бэкапа.

## Известные проблемы (осталось)

- Нет повторов и snooze у напоминаний: одна заметка — одно срабатывание.
- Отмена удаления одна: второе удаление до `undoLastDelete()` молча теряет первое.
- Голосовой `onresult` может добавить несколько заметок из одной фразы.
- Напоминания работают, только пока приложение запущено (в фоне Android может
  убить процесс) — системный push без сервера недостижим.

