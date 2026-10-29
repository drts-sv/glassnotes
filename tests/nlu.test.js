/**
 * Тесты NLU Glass Notes: разбор даты, времени и категории из фразы.
 *
 * Код берётся прямо из index.html — блок между маркерами NLU START/END,
 * поэтому тест падает, если правку внесли только в тест.
 *
 * Запуск: node tests/nlu.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.join(__dirname, '..');
const html = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');

const a = html.indexOf('// === NLU START ===');
const b = html.indexOf('// === NLU END ===');
if (a < 0 || b < a) throw new Error('в index.html нет маркеров NLU START/END');
const nluCode = html.slice(a, b);

// «Сейчас» для тестов: четверг, 29 октября 2026, 18:43 по Москве.
const FIXED = new Date('2026-10-29T18:43:00+03:00').getTime();

class FakeDate extends Date {
  constructor(...args) {
    if (args.length === 0) super(FIXED);
    else super(...args);
  }
  static now() { return FIXED; }
}

const ctx = {
  console,
  Date: FakeDate,
  DEFAULT_CATEGORIES: [
    { id: 'important', name: 'Важно' },
    { id: 'work', name: 'Работа' },
    { id: 'personal', name: 'Личное' },
    { id: 'shopping', name: 'Покупки' },
    { id: 'other', name: 'Другое' },
    { id: 'dev', name: 'Разработка' },
  ],
  timeDefaults: { morning: '09:00', beforeNoon: '11:00', noon: '13:00', afterNoon: '15:00',
                  evening: '18:00', night: '21:00', today: '12:00', tomorrow: '09:00' },
};
vm.createContext(ctx);
vm.runInContext(nluCode, ctx);
const parseDateNLU = ctx.parseDateNLU;

const fmt = (d) => {
  if (!d) return null;
  const x = new Date(d);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(x.getDate())}.${p(x.getMonth() + 1)}.${x.getFullYear()} ${p(x.getHours())}:${p(x.getMinutes())}`;
};

// Ближайшая суббота после «сейчас» — считаем, а не хардкодим
const sat = new Date(FIXED);
sat.setDate(sat.getDate() + ((6 - sat.getDay() + 7) % 7 || 7));
const SATURDAY = fmt(sat).slice(0, 10) + ' 09:00';

const CASES = [
  // сказал                                        когда                    текст              категория
  ['купить хлеб завтра в 10 категория покупки',    '30.10.2026 10:00',      'купить хлеб',     'shopping'],
  ['купить хлеб завтра в 10:00 категория покупки', '30.10.2026 10:00',      'купить хлеб',     'shopping'],
  ['напомни через пол часа',                       '29.10.2026 19:13',      'Напоминание',     null],
  ['напомни через полчаса',                        '29.10.2026 19:13',      'Напоминание',     null],
  ['через час',                                    '29.10.2026 19:43',      'Напоминание',     null],
  ['через 30 минут',                               '29.10.2026 19:13',      'Напоминание',     null],
  ['через полтора часа',                           '29.10.2026 20:13',      'Напоминание',     null],
  ['через 2 дня',                                  '31.10.2026 18:43',      'Напоминание',     null],
  ['напомни завтра утром',                         '30.10.2026 09:00',      'Напоминание',     null],
  ['завтра вечером',                               '30.10.2026 18:00',      'Напоминание',     null],
  ['в 10 вечера',                                  '29.10.2026 22:00',      'Напоминание',     null],
  ['в 10 утра',                                    '30.10.2026 10:00',      'Напоминание',     null],
  ['в 2 ночи',                                     '30.10.2026 02:00',      'Напоминание',     null],
  ['позвонить маме в 10',                          '30.10.2026 10:00',      'позвонить маме',  null],
  ['позвонить маме в десять',                      '30.10.2026 10:00',      'позвонить маме',  null],
  ['встреча 12 мая 2027 в 15:00',                  '12.05.2027 15:00',      'встреча',         null],
  ['убраться в субботу',                           SATURDAY,                'убраться',        null],
  ['позвонить в банк завтра в 10:30',              '30.10.2026 10:30',      'позвонить в банк', null],
  ['сходить в магазин',                            null,                    'сходить в магазин', null],
];

let failed = 0;
for (const [say, when, text, cat] of CASES) {
  const r = parseDateNLU(say, true);
  const gotWhen = fmt(r.parsed);
  const problems = [];
  if (gotWhen !== when) problems.push(`время: ${gotWhen} вместо ${when}`);
  if (r.cleanText !== text) problems.push(`текст: ${JSON.stringify(r.cleanText)} вместо ${JSON.stringify(text)}`);
  if ((r.categoryId || null) !== cat) problems.push(`категория: ${r.categoryId} вместо ${cat}`);
  if (problems.length) {
    failed++;
    console.log(`FAIL  «${say}»\n      ${problems.join('\n      ')}`);
  } else {
    console.log(`ok    «${say}» → ${when || 'без времени'} | ${r.cleanText}`);
  }
}

console.log(`\n${CASES.length - failed} из ${CASES.length} прошли`);
process.exit(failed ? 1 : 0);
