"""Иконка GlasNote (dev) — рисуется кодом, без внешних картинок.

Мотив тот же, что в шапке приложения: кольцо прогресса вокруг знака. Знак — греческая «α»,
чтобы dev-сборку было видно от боевой (владелец: «добавь в неё значок альфы»).

Запуск:  python tools/make_icon.py
Пишет:   app/src/main/res/mipmap-*/ic_launcher.png, ic_launcher_foreground.png,
         app/src/main/res/values/colors.xml (цвет фона адаптивной иконки),
         build/icon_preview.png (для показа владельцу).
"""

import math
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
BG_TOP = (0x1B, 0x20, 0x2E)
BG_BOTTOM = (0x0B, 0x0D, 0x14)
GLOW = (0x3D, 0x7B, 0xFF)
ACCENT = (0x4D, 0x8CFF - 0x00, 0xFF) if False else (0x3D, 0x7B, 0xFF)
WHITE = (0xFF, 0xFF, 0xFF)
FONT = "C:/Windows/Fonts/arialbd.ttf"

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def gradient(size):
    """Вертикальный градиент с синим свечением из центра."""
    img = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(img)
    for y in range(size):
        t = y / (size - 1)
        r = round(BG_TOP[0] + (BG_BOTTOM[0] - BG_TOP[0]) * t)
        g = round(BG_TOP[1] + (BG_BOTTOM[1] - BG_TOP[1]) * t)
        b = round(BG_TOP[2] + (BG_BOTTOM[2] - BG_TOP[2]) * t)
        d.line([(0, y), (size, y)], fill=(r, g, b))
    # свечение: мягкое пятно акцента в верхней трети
    glow = Image.new("L", (size, size), 0)
    gd = ImageDraw.Draw(glow)
    cx, cy, rad = size * 0.5, size * 0.44, size * 0.46
    steps = 60
    for i in range(steps, 0, -1):
        k = i / steps
        r = rad * k
        gd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=int(58 * (1 - k) ** 1.6))
    tint = Image.new("RGB", (size, size), GLOW)
    img = Image.composite(tint, img, glow.point(lambda v: v))
    # возвращаем градиент там, где свечение слабое
    base = Image.new("RGB", (size, size))
    bd = ImageDraw.Draw(base)
    for y in range(size):
        t = y / (size - 1)
        bd.line(
            [(0, y), (size, y)],
            fill=(
                round(BG_TOP[0] + (BG_BOTTOM[0] - BG_TOP[0]) * t),
                round(BG_TOP[1] + (BG_BOTTOM[1] - BG_TOP[1]) * t),
                round(BG_TOP[2] + (BG_BOTTOM[2] - BG_TOP[2]) * t),
            ),
        )
    return Image.blend(base, img, 1.0)


def ring_and_alpha(size, scale=1.0, bg=None):
    """Кольцо прогресса и «α» по центру. scale — доля от полного размера холста."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx = cy = size / 2
    radius = size * 0.315 * scale
    stroke = size * 0.062 * scale
    box = [cx - radius, cy - radius, cx + radius, cy + radius]

    # дорожка кольца: белый 0.16
    d.ellipse(box, outline=(255, 255, 255, 41), width=round(stroke))

    # дуга акцента: 78 % от полного круга, старт сверху
    d.arc(box, start=-90, end=-90 + 360 * 0.78, fill=ACCENT + (255,), width=round(stroke))

    # α
    font = ImageFont.truetype(FONT, round(size * 0.40 * scale))
    text = "α"
    left, top, right, bottom = d.textbbox((0, 0), text, font=font)
    d.text(
        (cx - (right - left) / 2 - left, cy - (bottom - top) / 2 - top - size * 0.012 * scale),
        text,
        font=font,
        fill=WHITE + (255,),
    )
    return img


def legacy_icon(size):
    """Полная иконка для Android до 8.0 и круглых масок: тёмный скруглённый квадрат + знак."""
    big = size * 4
    canvas = gradient(big)
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=round(big * 0.235), fill=255)
    icon = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    icon.paste(canvas, (0, 0), mask)
    icon.alpha_composite(ring_and_alpha(big, scale=1.0))
    return icon.resize((size, size), Image.LANCZOS)


def foreground(size):
    """Передний слой адаптивной иконки: 108 dp с содержимым внутри безопасной зоны 66 dp."""
    big = size * 4
    layer = ring_and_alpha(big, scale=0.95)
    return layer.resize((size, size), Image.LANCZOS)


def main():
    for name, k in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        legacy = legacy_icon(round(48 * k))
        legacy.save(os.path.join(folder, "ic_launcher.png"))
        fg = foreground(round(108 * k))
        fg.save(os.path.join(folder, "ic_launcher_foreground.png"))
        print(f"{name}: ic_launcher {legacy.size[0]}px, foreground {fg.size[0]}px")

    os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        "    <monochrome android:drawable=\"@mipmap/ic_launcher_monochrome\" />\n"
        "</adaptive-icon>\n"
    )
    for tag in ("ic_launcher", "ic_launcher_round"):
        with open(os.path.join(RES, "mipmap-anydpi-v26", f"{tag}.xml"), "w", encoding="utf-8") as f:
            f.write(adaptive)
    print("mipmap-anydpi-v26: ic_launcher.xml, ic_launcher_round.xml")

    # фон адаптивной иконки и монохромный слой — на каждую плотность
    for name, k in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{name}")
        gradient(round(108 * k)).save(os.path.join(folder, "ic_launcher_background.png"))
        mono = Image.new("L", (round(108 * k * 4),) * 2, 0)
        md = ImageDraw.Draw(mono)
        big = round(108 * k * 4)
        cx = cy = big / 2
        radius = big * 0.315 * 0.95
        stroke = round(big * 0.062 * 0.95)
        box = [cx - radius, cy - radius, cx + radius, cy + radius]
        md.ellipse(box, outline=255, width=stroke)
        font = ImageFont.truetype(FONT, round(big * 0.40 * 0.95))
        text = "α"
        left, top, right, bottom = md.textbbox((0, 0), text, font=font)
        md.text((cx - (right - left) / 2 - left, cy - (bottom - top) / 2 - top), text, font=font, fill=255)
        mono.resize((round(108 * k),) * 2, Image.LANCZOS).save(os.path.join(folder, "ic_launcher_monochrome.png"))

    preview = Image.new("RGBA", (1024, 512), (0x10, 0x12, 0x18, 255))
    preview.alpha_composite(legacy_icon(430), (60, 41))
    safe = Image.new("RGBA", (432, 432), (0x1A, 0x1E, 0x28, 255))
    safe.alpha_composite(Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png")), (0, 0))
    preview.alpha_composite(gradient(432).convert("RGBA"), (560, 40))
    preview.alpha_composite(Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png")), (560, 40))
    out = os.path.join(HERE, "..", "build")
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, "icon_preview.png")
    preview.convert("RGB").save(path)
    print("предпросмотр:", os.path.abspath(path))


if __name__ == "__main__":
    main()
