#!/usr/bin/env python3
"""Сбор багов из заметок GlasNote по тегам проектов.

Заметка получает тег проекта голосом («в колскрайбе абонент распределяется неверно»)
или вручную чипом категории. Этот скрипт выгружает базу с телефона и раскладывает
активные заметки по проектам: один список на тег.

  python tools/collect_bugs.py                 # показать списки
  python tools/collect_bugs.py --append        # дописать в PLAN.md каждого проекта
  python tools/collect_bugs.py --db путь.db    # без телефона

Телефон должен быть на отладочной сборке (-Pdbg): только она даёт run-as.
"""
import argparse
import datetime as dt
import json
import os
import sqlite3
import subprocess
import sys

ADB = "C:/Users/drts/.android-tools/pt-new/platform-tools/adb.exe"
DEVICE = "192.168.1.152:5555"
PKG = "com.glassnotes.app"

# тег → (название, PLAN.md проекта)
PROJECTS = {
    "p_glasnote": ("GlasNote", "C:/Users/drts/Documents/glass-notes/PLAN.md"),
    "p_callscribe": ("CallScribe", "C:/Users/drts/Documents/CallScribe/PLAN.md"),
    "p_analytics": ("Аналитика", "C:/Users/drts/Documents/Радиан/Аналитика/PLAN.md"),
    "p_airalert": ("AirAlert", "C:/Users/drts/Documents/Python/airalert/PLAN.md"),
    "p_usbterm": ("USB-терминал", "C:/Users/drts/Documents/UsbTerminal/PLAN.md"),
    "p_pricetag": ("Ценник", None),
    "p_routerapi": ("Баланс AI", "C:/Users/drts/Documents/RouterApiBalance-Android/PLAN.md"),
    "p_homeassistant": ("Умный дом", None),
}
DEV_TAG = "dev"


def dump(db_path):
    subprocess.run([ADB, "connect", DEVICE], capture_output=True)
    subprocess.run([ADB, "-s", DEVICE, "shell", "am", "force-stop", PKG], capture_output=True)
    out = subprocess.run(
        [ADB, "-s", DEVICE, "exec-out", f"run-as {PKG} cat databases/glassnotes.db"],
        capture_output=True,
    )
    if out.returncode != 0 or len(out.stdout) < 1024:
        sys.exit("не выгрузилась база: телефон на боевой сборке или отвалился adb")
    with open(db_path, "wb") as f:
        f.write(out.stdout)
    return db_path


def load(db_path):
    con = sqlite3.connect(db_path)
    notes = []
    for (raw,) in con.execute("select json from notes"):
        d = json.loads(raw)
        cats = d.get("categoryIds") or []
        notes.append(
            {
                "id": d.get("id"),
                "text": (d.get("text") or "").strip(),
                "status": d.get("status"),
                "cats": cats,
                "ts": d.get("createdAt") or "",
            }
        )
    con.close()
    return notes


def group(notes):
    """Активные заметки по проектам. Заметка с двумя тегами попадёт в оба списка."""
    groups = {tag: [] for tag in PROJECTS}
    groups[DEV_TAG] = []
    loose = []
    for n in notes:
        if n["status"] == "done":
            continue
        got = [c for c in n["cats"] if c in PROJECTS]
        if got:
            for c in got:
                groups[c].append(n)
        elif DEV_TAG in n["cats"]:
            groups[DEV_TAG].append(n)
        else:
            loose.append(n)
    return groups, loose


def block(title, items):
    if not items:
        return ""
    lines = [f"### {title} ({len(items)})", ""]
    for n in sorted(items, key=lambda x: x["ts"]):
        day = (n["ts"] or "")[:10]
        lines.append(f"- **#{n['id']}** {n['text']}" + (f"  _(записано {day})_" if day else ""))
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db")
    ap.add_argument("--append", action="store_true", help="дописать списки в PLAN.md проектов")
    ap.add_argument("--out", default="C:/tmp/bugs")
    args = ap.parse_args()

    db = args.db or dump(os.path.join(args.out, "live.db"))
    groups, loose = group(load(db))
    os.makedirs(args.out, exist_ok=True)
    today = dt.date.today().strftime("%d.%m.%Y")

    for tag, (title, plan) in PROJECTS.items():
        items = groups.get(tag) or []
        if not items:
            continue
        text = block(f"{title} — замечания {today}", items)
        with open(os.path.join(args.out, f"{tag}.md"), "w", encoding="utf-8") as f:
            f.write(text)
        print(f"\n[{tag}] {len(items)} → {os.path.join(args.out, tag + '.md')}  (план: {plan or '—'})")
        print(text)
        if args.append and plan and os.path.exists(plan):
            with open(plan, "a", encoding="utf-8") as f:
                f.write(f"\n## Собрано из заметок {today}\n\n" + text)
            print(f"  → дописано в {plan}")

    if groups.get(DEV_TAG):
        print(f"\n[dev без проекта] {len(groups[DEV_TAG])} — см. вывод ниже")
        print(block("Разработка без проекта", groups[DEV_TAG]))
    if loose:
        print(f"\n[без тега] {len(loose)} активных заметок: " + ", ".join(f"#{n['id']}" for n in loose))


if __name__ == "__main__":
    main()
