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

## Известные проблемы

1. **Уведомления на Android.** `new Notification()` в Chrome на Android бросает
   `TypeError` — нужен `ServiceWorkerRegistration.showNotification` (в `sw.js`
   обработчик уже есть, страница его пока не использует).
2. **Пропущенные напоминания теряются.** `checkReminders` срабатывает только
   если срок попал в окно `(now − 120 c, now]`; при спящем телефоне интервал
   5 с не тикает, и просроченная заметка больше не напомнит никогда.
3. **Утечка слушателей.** `renderTodos` пересоздаёт DOM, а `initSwipe` вешает
   `mousemove`/`mouseup` на `document` без снятия → рост числа слушателей
   (особенно при вводе в поиске).
4. **Путь SW.** `navigator.serviceWorker.register('/sw.js')` — абсолютный,
   ломается при открытии из подпапки.
5. **Разрешение на уведомления** запрашивается только внутри `.then` регистрации
   SW: если SW не поднялся, разрешение не спросим.
6. **Версия продублирована** в footer, info-модалке и `exportBackup()`.
