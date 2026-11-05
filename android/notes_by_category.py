#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Заметки GlasNote по категории: прочитать, пометить выполненными, собрать в PLAN.md.

Зачем: пользователь просит «собери заметки категории разработка, сохрани их в todo,
отметь выполненными». Руками долго: подключиться к телефону, сходить в базу, разобрать
категории, отметить статусы, оформить раздел в плане.

Короткие команды (из корня проекта glass-notes):
    python android/notes_by_category.py list dev        # показать заметки категории
    python android/notes_by_category.py collect dev     # + отметить выполненными + раздел в PLAN.md
    python android/notes_by_category.py collect dev --no-done
    python android/notes_by_category.py list            # все категории с числами

Категории — из DEFAULT_CATEGORIES в index.html:
    important Важно · work Работа · personal Личное · shopping Покупки · other Другое · dev Разработка

Как устроено: идём в page-контекст приложения (через device_check.py и CDP), поэтому данные —
из той же базы, что видит пользователь. Отметка делается штатной функцией приложения `markDone(id)`:
она же гасит напоминание и уведомление.

Грабли (проверено на живом телефоне):
- JS-выражения писать ТОЛЬКО ASCII-идентификаторами: кириллица в именах переменных и ключах
  ломает выражение («EXCEPTION: Uncaught»); ключи ответа — латиницей, русский текст собирается в Python.
- Телефон отваливается от Wi-Fi adb; device_check.py переподключается сам. Если CDP висит
  (TimeoutError в http/client.py) — `am force-stop` и запуск приложения: сокет devtools пересоздаётся.
- Перед чтением поднять экран: `input keyevent KEYCODE_WAKEUP`, `wm dismiss-keyguard`.
"""

import io
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CHECK = os.path.join(HERE, "device_check.py")
PLAN = os.path.join(ROOT, "PLAN.md")
CATS = [
    ("important", "Важно"),
    ("work", "Работа"),
    ("personal", "Личное"),
    ("shopping", "Покупки"),
    ("other", "Другое"),
    ("dev", "Разработка"),
]


def cat_name(cid):
    for c, n in CATS:
        if c == cid:
            return n
    return cid


def ask(expr, timeout=240):
    """Спросить приложение через device_check.py и вернуть разобранный JSON."""
    out = subprocess.run(
        [sys.executable, CHECK, "--expr", expr],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    text = (out.stdout or "") + (out.stderr or "")
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        raise SystemExit("нет ответа от приложения:\n" + text[-600:])
    return json.loads(m.group(0))


def expr_list(cid):
    return ("(async function(){ await loadTodos(); const id='%s';"
            " const sel=todos.filter(function(t){return (t.categoryIds||[]).indexOf(id)>=0;})"
            ".sort(function(a,b){return a.id-b.id;});"
            " return JSON.stringify({name:'%s', total:sel.length,"
            " active:sel.filter(function(t){return t.status!=='done';}).length,"
            " rows:sel.map(function(t){return '#'+t.id+' | '+(t.status==='done'?'выполнено':'активно')"
            "+' | '+(t.created?new Date(t.created).toLocaleDateString('ru-RU'):'-')+' | '+t.text;})}); })()"
            % (cid, cat_name(cid)))


def expr_all():
    pairs = ",".join("['%s','%s']" % (c, n) for c, n in CATS)
    return ("(async function(){ await loadTodos(); const cats=[%s];"
            " return JSON.stringify({notesTotal:todos.length,"
            " rows:cats.map(function(c){return c[1]+': '+todos.filter(function(t){"
            "return (t.categoryIds||[]).indexOf(c[0])>=0;}).length;})}); })()" % pairs)


def expr_collect(cid, mark_done):
    loop = ("for(const t of before){ if(t.status!=='done'){ await markDone(t.id); marked++;"
            " await new Promise(function(r){setTimeout(r,120);}); } }") if mark_done else ""
    return ("(async function(){ await loadTodos(); const id='%s';"
            " const sel=function(){return todos.filter(function(t){return (t.categoryIds||[]).indexOf(id)>=0;});};"
            " const before=sel(); const activeBefore=before.filter(function(t){return t.status!=='done';}).length;"
            " let marked=0; %s await loadTodos(); const after=sel().sort(function(a,b){return a.id-b.id;});"
            " return JSON.stringify({name:'%s', notesTotal:todos.length, total:after.length,"
            " activeBefore:activeBefore, marked:marked,"
            " active:after.filter(function(t){return t.status!=='done';}).length,"
            " rows:after.map(function(t){return '#'+t.id+' | '+(t.status==='done'?'выполнено':'активно')"
            "+' | '+(t.created?new Date(t.created).toLocaleDateString('ru-RU'):'-')+' | '+t.text;})}); })()"
            % (cid, loop, cat_name(cid)))


def do_list(cid):
    data = ask(expr_list(cid) if cid else expr_all())
    if cid:
        print("Категория «%s»: %s заметок, активных %s" % (data["name"], data["total"], data["active"]))
    else:
        print("Всего заметок:", data["notesTotal"])
    for line in data["rows"]:
        print("  " + line)


def do_collect(cid, mark_done=True):
    data = ask(expr_collect(cid, mark_done), timeout=360)
    print("«%s»: собрано %s заметок; активных было %s, отмечено %s, осталось %s; всего на телефоне %s"
          % (data["name"], data["total"], data["activeBefore"], data["marked"],
             data["active"], data["notesTotal"]))

    block = "\n\n### Заметки «%s» — собраны %s\n" % (data["name"], time.strftime("%d.%m.%Y"))
    block += ("Считано с телефона" + (" и отмечено выполненными" if mark_done else "") +
              " — короткая команда: `python android/notes_by_category.py collect %s`\n" % cid)
    block += "Всего на телефоне %s заметок; в категории — %s (активных: %s).\n\n" % (
        data["notesTotal"], data["total"], data["active"])
    block += "\n".join("- " + s for s in data["rows"])
    with io.open(PLAN, encoding="utf-8") as f:
        plan = f.read().rstrip("\n")
    with io.open(PLAN, "w", encoding="utf-8", newline="") as f:
        f.write(plan + block)
    print("Раздел дописан в PLAN.md")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        return
    cmd = args[0]
    cid = args[1] if len(args) > 1 else None
    if cid and cid not in [c for c, _ in CATS]:
        print("неизвестная категория %r; известные: %s" % (cid, ", ".join(c for c, _ in CATS)))
        return
    if cmd == "list":
        do_list(cid)
    elif cmd == "collect":
        if not cid:
            print("укажи категорию: " + ", ".join(c for c, _ in CATS))
            return
        do_collect(cid, mark_done="--no-done" not in sys.argv)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
