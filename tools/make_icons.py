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
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BG = os.path.join(ROOT, 'assets', 'ic_launcher_background.png')
FG = os.path.join(ROOT, 'assets', 'ic_launcher_foreground.png')
ANDROID_RES = os.path.join(ROOT, 'android', 'res')


def composed(size):
    bg = Image.open(BG).convert('RGBA')
    fg = Image.open(FG).convert('RGBA')
    icon = Image.alpha_composite(bg, fg)
    return icon.resize((size, size), Image.LANCZOS)


def main():
    for name, size in (('icon-512.png', 512), ('icon-512-maskable.png', 512),
                       ('icon-192.png', 192), ('apple-touch-icon.png', 180)):
        path = os.path.join(ROOT, name)
        composed(size).save(path, 'PNG', optimize=True)
        print('%-24s %dx%d' % (name, size, size))

    for folder, px in (('mipmap-mdpi', 48), ('mipmap-hdpi', 72), ('mipmap-xhdpi', 96),
                       ('mipmap-xxhdpi', 144), ('mipmap-xxxhdpi', 192)):
        out = os.path.join(ANDROID_RES, folder)
        os.makedirs(out, exist_ok=True)
        composed(px).save(os.path.join(out, 'ic_launcher.png'), 'PNG', optimize=True)
        print('%-24s %dx%d' % (folder + '/ic_launcher.png', px, px))


if __name__ == '__main__':
    main()
