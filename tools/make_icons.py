#!/usr/bin/env python3
"""Сборка всех иконок Glass Notes из оригинальных слоёв.

Оригинал восстановлен из установленного APK com.drts.glassnotes (adaptive icon):
  assets/ic_launcher_background.png — фон (белый)
  assets/ic_launcher_foreground.png — лист-чеклист с микрофоном
Оба слоя 432x432 = 108dp при xxxhdpi, то есть уже в правильном масштабе
adaptive icon, поэтому просто накладываем один на другой.

Запуск: python tools/make_icons.py
"""
import os
import sys
from collections import deque

from PIL import Image, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BG = os.path.join(ROOT, 'assets', 'ic_launcher_background.png')
FG = os.path.join(ROOT, 'assets', 'ic_launcher_foreground.png')
ANDROID_RES = os.path.join(ROOT, 'android', 'res')


def composed(size):
    bg = Image.open(BG).convert('RGBA')
    fg = Image.open(FG).convert('RGBA')
    icon = Image.alpha_composite(bg, fg)
    return icon.resize((size, size), Image.LANCZOS)


DARK_THRESHOLD = 170   # всё светлее — считаем листом/фоном, а не значком
CLOSE_RADIUS = 5       # закрыть разрывы и мелкие дырки внутри микрофона


def fill_holes(mask):
    """Заливает полости внутри силуэта: без этого микрофон остаётся контуром."""
    w, h = mask.size
    px = mask.load()
    inside = Image.new('L', (w, h), 0)
    ipx = inside.load()
    for y in range(h):
        for x in range(w):
            ipx[x, y] = 255 - px[x, y]

    q = deque()

    def push(x, y):
        if ipx[x, y] == 255:
            ipx[x, y] = 128
            q.append((x, y))

    for x in range(w):
        push(x, 0)
        push(x, h - 1)
    for y in range(h):
        push(0, y)
        push(w - 1, y)
    while q:
        x, y = q.popleft()
        if x > 0:
            push(x - 1, y)
        if x < w - 1:
            push(x + 1, y)
        if y > 0:
            push(x, y - 1)
        if y < h - 1:
            push(x, y + 1)

    for y in range(h):
        for x in range(w):
            if ipx[x, y] == 255:      # полость, до которой фон не добрался
                px[x, y] = 255
    return mask


def largest_component(mask):
    """Оставляет самую крупную связную область — это микрофон без галочек и строк."""
    w, h = mask.size
    px = mask.load()
    seen = bytearray(w * h)
    best, best_size = None, 0
    for y0 in range(h):
        for x0 in range(w):
            if not px[x0, y0] or seen[y0 * w + x0]:
                continue
            comp = []
            queue = deque([(x0, y0)])
            seen[y0 * w + x0] = 1
            while queue:
                x, y = queue.popleft()
                comp.append((x, y))
                for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                    if 0 <= nx < w and 0 <= ny < h and px[nx, ny] and not seen[ny * w + nx]:
                        seen[ny * w + nx] = 1
                        queue.append((nx, ny))
            if len(comp) > best_size:
                best_size, best = len(comp), comp
    out = Image.new('L', (w, h), 0)
    op = out.load()
    for x, y in (best or []):
        op[x, y] = 255
    return out


def monochrome():
    """Силуэт для theme icons: только микрофон, залитый — в 48 px подробный рисунок не читается."""
    fg = Image.open(FG).convert('RGBA')
    lum = fg.convert('L').load()
    alpha = fg.getchannel('A').load()
    mask = Image.new('L', fg.size, 0)
    mp = mask.load()
    for y in range(fg.size[1]):
        for x in range(fg.size[0]):
            # прозрачный фон тоже имеет яркость 0, поэтому без проверки альфы он станет силуэтом
            mp[x, y] = 255 if (alpha[x, y] > 127 and lum[x, y] < DARK_THRESHOLD) else 0
    mask = largest_component(mask)
    mask = mask.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.MinFilter(3))   # утолщаем и сглаживаем
    mask = fill_holes(mask)
    out = Image.new('RGBA', fg.size, (0, 0, 0, 0))
    out.putalpha(mask)
    return out


def save(img, path, size=None):
    if size:
        img = img.resize((size, size), Image.LANCZOS)
    img.save(path, 'PNG', optimize=True)
    print('%-46s %dx%d' % (os.path.relpath(path, ROOT), img.size[0], img.size[1]))

def main():
    for name, size in (('icon-512.png', 512), ('icon-512-maskable.png', 512),
                       ('icon-192.png', 192), ('apple-touch-icon.png', 180)):
        path = os.path.join(ROOT, name)
        save(composed(size), path)

    for folder, px in (('mipmap-mdpi', 48), ('mipmap-hdpi', 72), ('mipmap-xhdpi', 96),
                       ('mipmap-xxhdpi', 144), ('mipmap-xxxhdpi', 192)):
        out = os.path.join(ANDROID_RES, folder)
        os.makedirs(out, exist_ok=True)
        save(composed(px), os.path.join(out, 'ic_launcher.png'))

    mono = monochrome()
    save(mono, os.path.join(ROOT, 'assets', 'ic_launcher_monochrome.png'))
    drawable = os.path.join(ANDROID_RES, 'drawable-xxxhdpi')
    os.makedirs(drawable, exist_ok=True)
    save(mono, os.path.join(drawable, 'ic_launcher_monochrome.png'))
    opaque = sum(1 for v in mono.getchannel('A').getdata() if v > 127)
    print('покрытие силуэта: %.1f%%' % (100.0 * opaque / (mono.size[0] * mono.size[1])))


if __name__ == '__main__':
    sys.exit(main())
