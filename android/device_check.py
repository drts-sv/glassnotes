#!/usr/bin/env python3
"""Проверка APK на подключённом телефоне: JS-оценка в WebView через CDP.

Ходит по adb: forward на localabstract:webview_devtools_remote_<pid>,
потом Runtime.evaluate в контексте страницы. Так проверяются вещи, которые
иначе не видны: работает ли шим речи, уходит ли уведомление в систему,
что отвечает android.speech.

Запуск: python android/device_check.py
"""
import asyncio
import json
import subprocess
import sys
import time
import urllib.request

import websockets

import os

# Путь к adb и серийник можно задать снаружи: по Wi-Fi серийник — это host:port,
# а свежий adb (нужен для `adb pair`) лежит отдельно от старого.
ADB = os.environ.get("ADB_BIN", r"C:\adb\adb.exe")
PKG = "com.glassnotes.app"
PORT = 9222
# В adb бывает несколько устройств (телефон + ТВ-приставки) — пинуем серийник.
SERIAL = os.environ.get("ADB_SERIAL", "65041XEA36AFF8")


def adb(*args):
    # -s обязателен: без него adb падает «more than one device/emulator»
    r = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    return (r.stdout or "").strip()


def ensure_device():
    """Беспроводное подключение отваливается само — перед работой переподключаемся.

    Серийник вида host:port означает сеть: без `adb connect` устройство пропадает
    из списка (device not found) и проверки молча уходят не туда.
    """
    if ":" not in SERIAL:
        return
    listing = adb("devices")
    if any(line.startswith(SERIAL) and "\tdevice" in line for line in listing.splitlines()):
        return
    for _ in range(5):
        subprocess.run([ADB, "connect", SERIAL], capture_output=True, text=True)
        time.sleep(2)
        listing = adb("devices")
        if any(line.startswith(SERIAL) and "\tdevice" in line for line in listing.splitlines()):
            print("adb: подключился к " + SERIAL)
            return
    print("adb: не удалось подключиться к " + SERIAL)


def pid_of(package):
    out = adb("shell", "pidof", package)
    return out.split()[0] if out else None


def launch():
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")


def forward(pid):
    adb("forward", "--remove-all")
    adb("forward", f"tcp:{PORT}", f"localabstract:webview_devtools_remote_{pid}")


def targets():
    with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json", timeout=10) as r:
        return json.load(r)


async def evaluate(ws_url, expression, timeout=25):
    async with websockets.connect(ws_url, max_size=8 * 1024 * 1024, open_timeout=15) as ws:
        await ws.send(json.dumps({
            "id": 1,
            "method": "Runtime.evaluate",
            "params": {"expression": expression, "awaitPromise": True, "returnByValue": True},
        }))
        while True:
            msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=timeout))
            if msg.get("id") != 1:
                continue
            res = msg.get("result", {})
            if "exceptionDetails" in res:
                return "EXCEPTION: " + str(res["exceptionDetails"].get("text"))
            return res.get("result", {}).get("value")


CHECKS = [
    ("страница", "document.title"),
    ("среда", "JSON.stringify({origin:location.origin,secure:isSecureContext,ver:document.getElementById('appVersion').textContent,"
              "sr:typeof window.SpeechRecognition,wsr:typeof window.webkitSpeechRecognition,notif:typeof window.Notification,"
              "perm:(window.Notification&&window.Notification.permission)||'n/a',"
              "speechNative:!!(window.NativeSpeech&&window.NativeSpeech.isAvailable&&window.NativeSpeech.isAvailable()),"
              "notifyBridge:typeof window.NativeNotify,"
              "sw:('serviceWorker' in navigator),swCtl:!!(navigator.serviceWorker&&navigator.serviceWorker.controller),"
              "idb:'indexedDB' in window})"),
    ("NLU по фразе с телефона", "(async()=>{ await addTodo('купить хлеб завтра в 10 категория покупки');"
                          " await new Promise(r=>setTimeout(r,600));"
                          " const n=todos[0]; const d=n.datetime?new Date(n.datetime):null;"
                          " return JSON.stringify({текст:n.text, когда:d?d.toLocaleString('ru-RU',{day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit'}):'нет', кат:n.categoryIds}); })()"),
    ("insets под строку состояния", "JSON.stringify({ver:document.getElementById('appVersion').textContent,"
                                   "varTop:getComputedStyle(document.documentElement).getPropertyValue('--inset-top').trim(),"
                                   "padTop:getComputedStyle(document.body).paddingTop,"
                                   "varBottom:getComputedStyle(document.documentElement).getPropertyValue('--inset-bottom').trim()})"),
    ("сплэш события", "(async()=>{ renderTodos(); const c=document.querySelector('.result-card-wrapper .result-content');"
                      " if(!c) return 'нет карточек'; c.click(); await new Promise(r=>setTimeout(r,400));"
                      " const out={sheet:document.getElementById('noteSheet').classList.contains('visible'),"
                      " when:document.getElementById('noteSheetWhen').textContent,"
                      " text:document.getElementById('noteSheetText').textContent,"
                      " editLabel:document.getElementById('noteSheetEditBtn').textContent,"
                      " editorBefore:document.getElementById('editOverlay').classList.contains('active')};"
                      " document.getElementById('noteSheetEditBtn').click(); await new Promise(r=>setTimeout(r,500));"
                      " out.editorAfter=document.getElementById('editOverlay').classList.contains('active');"
                      " out.sheetAfter=document.getElementById('noteSheet').classList.contains('visible');"
                      " return JSON.stringify(out); })()"),
    ("уведомление в систему", "(()=>{ try { new Notification('🔔 Тест с телефона',{body:'мост уведомлений работает',tag:'devcheck'});"
                              " return 'отправлено'; } catch(e){ return 'ОШИБКА: '+e.message; } })()"),
    ("просроченное напоминание",
     "(async()=>{ const past=new Date(Date.now()-60000).toISOString();"
     " const id=await addNote({text:'просроченное с телефона',time:'00:00',datetime:past,timestamp:Date.now(),"
     " status:'active',color:'color-1',categoryIds:[],notified:false,created:new Date().toISOString()});"
     " checkReminders(await getAllNotes()); await new Promise(r=>setTimeout(r,900));"
     " const n=(await getAllNotes()).find(x=>x.id===id); return 'notified='+!!n.notified; })()"),
    ("голос через кнопку приложения (ждём до 20 с)",
     "(async()=>{ const log=[]; const fab=()=>document.getElementById('cancelFab').classList.contains('visible');"
     " const toast=()=>document.getElementById('toastMessage').textContent;"
     " document.getElementById('voiceBtn').click();"
     " await new Promise(r=>setTimeout(r,600)); log.push('fab='+fab()+' text='+document.getElementById('cancelFabText').textContent);"
     " const t0=Date.now();"
     " while(Date.now()-t0<20000){ await new Promise(r=>setTimeout(r,400)); if(!fab()) break; }"
     " log.push('fab_после='+fab()); log.push('тост='+toast());"
     " log.push('заметок='+todos.length); return log.join(' | '); })()"),
]


async def main():
    ensure_device()
    pid = pid_of(PKG)
    if not pid:
        launch()
        time.sleep(5)
        pid = pid_of(PKG)
    if not pid:
        print("приложение не запущено")
        return 1
    print(f"pid={pid}")
    forward(pid)
    time.sleep(2)

    pages = [t for t in targets() if t.get("type") == "page"]
    if not pages:
        print("нет devtools-целей:", targets())
        return 1
    ws_url = pages[0]["webSocketDebuggerUrl"]
    print("цель:", pages[0].get("url"))
    print()

    # одиночное выражение: python android/device_check.py --expr "<js>"
    if len(sys.argv) > 2 and sys.argv[1] == "--expr":
        try:
            tmo = float(sys.argv[3]) if len(sys.argv) > 3 else 25.0
        except ValueError:
            tmo = 25.0
        print(await evaluate(ws_url, sys.argv[2], timeout=tmo))
        return 0

    for name, expr in CHECKS:
        value = await evaluate(ws_url, expr)
        print(f"--- {name}\n    {value}")

    print()
    print("--- уведомления в шторке (dumpsys):")
    dump = adb("shell", "dumpsys", "notification", "--noredact")
    lines = [l.strip() for l in dump.splitlines() if PKG in l]
    for l in lines[:12]:
        print("    " + l[:180])
    if not lines:
        print("    ничего от " + PKG)
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
