"""Иконка GlasNote — рисуется кодом по образцу исходного приложения.

Мотив образца (`native/docs/icon_reference_original.jpg`): светло-лиловый лист бумаги с загнутым
уголком, слева столбик галочек в кружках, справа короткие линии списка, по центру крупный микрофон
(тёмно-фиолетовая капсула, сине-голубой корпус, скоба и ножка с подставкой), мягкое фиолетовое
свечение. «α» — только маленькая метка dev-сборки в углу, чтобы отличать от боевой.

Запуск:  python tools/make_icon.py
Пишет:   app/src/main/res/mipmap-*/ic_launcher.png, ic_launcher_foreground.png,
         ic_launcher_background.png, ic_launcher_monochrome.png,
         app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml,
         build/icon_show.png — превью (обычная / круглая маска / монохром).
"""

import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
FONT = "C:/Windows/Fonts/arialbd.ttf"

# --- палитра по образцу ---
BG_TOP = (0xFC, 0xFA, 0xFF)
BG_BOTTOM = (0xF1, 0xE9, 0xFD)
GLOW = (0xC9, 0xA6, 0xFF)
SHEET_TOP = (0xF3, 0xEC, 0xFE)
SHEET_BOTTOM = (0xE2, 0xD3, 0xFB)
SHEET_FOLD = (0xFF, 0xFD, 0xFF)
VIOLET = (0x5B, 0x1C, 0xC9)
VIOLET_DARK = (0x2E, 0x0A, 0x6B)
VIOLET_MID = (0x4B, 0x1E, 0x9E)
BLUE_LIGHT = (0x35, 0xC8, 0xF5)
BLUE_DEEP = (0x2D, 0x6D, 0xF0)
CHECK = (0x7B, 0x3F, 0xF2)
LINE = (0x9B, 0x6B, 0xF5)
WHITE = (0xFF, 0xFF, 0xFF)

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def paint(S, shape, top, bottom, y0, y1):
    """Залить фигуру вертикальным градиентом top→bottom в диапазоне y0..y1."""
    mask = Image.new("L", (S, S), 0)
    shape(ImageDraw.Draw(mask))
    grad = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grad)
    span = max(1, y1 - y0)
    for y in range(S):
        t = min(1.0, max(0.0, (y - y0) / span))
        color = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
        gd.line([(0, y), (S, y)], fill=color)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)
    return out


def background(S, glow=0.85):
    """Светлый лиловый фон с мягким фиолетовым свечением по центру."""
    grad = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grad)
    for y in range(S):
        t = y / (S - 1)
        gd.line(
            [(0, y), (S, y)],
            fill=tuple(round(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3)) + (255,),
        )
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    cx, cy, rad = S * 0.5, S * 0.46, S * 0.44
    steps = 90
    for i in range(steps, 0, -1):
        k = i / steps
        r = rad * k
        md.ellipse([cx - r, cy - r, cx + r, cy + r], fill=int(72 * glow * (1 - k) ** 1.5))
    grad.alpha_composite(
        Image.composite(
            Image.new("RGBA", (S, S), GLOW + (255,)),
            Image.new("RGBA", (S, S), (0, 0, 0, 0)),
            mask,
        ),
    )
    return grad


def figure(S, mono=False):
    """Лист с галочками и линиями + микрофон по центру."""
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    ink = (255, 255, 255, 255)

    # ---------- лист ----------
    sx0, sy0, sx1, sy1 = S * 0.235, S * 0.165, S * 0.775, S * 0.745
    fold = S * 0.105
    if mono:
        # В mono-слое заливка склеивает содержимое с листом — рисуем лист контуром.
        d.rounded_rectangle(
            [sx0, sy0, sx1, sy1],
            radius=S * 0.022,
            outline=ink,
            width=max(4, round(S * 0.009)),
        )
    else:
        layer.alpha_composite(
            paint(
                S,
                lambda dd: dd.rounded_rectangle([sx0, sy0, sx1, sy1], radius=S * 0.022, fill=255),
                SHEET_TOP,
                SHEET_BOTTOM,
                sy0,
                sy1,
            ),
        )
    # загнутый уголок: отрезаем треугольник и кладём светлый лепесток
    cut = Image.new("L", (S, S), 0)
    ImageDraw.Draw(cut).polygon(
        [(sx1 - fold, sy0 - 1), (sx1 + 1, sy0 - 1), (sx1 + 1, sy0 + fold)],
        fill=255,
    )
    layer = Image.composite(Image.new("RGBA", (S, S), (0, 0, 0, 0)), layer, cut)
    d = ImageDraw.Draw(layer)
    d.polygon(
        [(sx1 - fold, sy0), (sx1, sy0 + fold), (sx1 - fold, sy0 + fold * 0.60)],
        fill=(ink if mono else SHEET_FOLD + (255,)),
    )

    # ---------- галочки слева ----------
    chk_cx, chk_r = S * 0.325, S * 0.042
    for i in range(4):
        cy = sy0 + S * (0.105 + i * 0.128)
        d.ellipse(
            [chk_cx - chk_r, cy - chk_r, chk_cx + chk_r, cy + chk_r],
            outline=(ink if mono else CHECK + (255,)),
            width=max(3, round(S * 0.011)),
        )
        d.line(
            [
                (chk_cx - chk_r * 0.42, cy),
                (chk_cx - chk_r * 0.06, cy + chk_r * 0.40),
                (chk_cx + chk_r * 0.62, cy - chk_r * 0.50),
            ],
            fill=(ink if mono else VIOLET + (255,)),
            width=max(4, round(S * 0.016)),
            joint="curve",
        )

    # ---------- линии списка справа ----------
    for yy, w in ((0.130, 0.075), (0.197, 0.045), (0.380, 0.070), (0.447, 0.048)):
        y = sy0 + S * yy
        d.rounded_rectangle(
            [S * 0.605, y, S * (0.605 + w), y + S * 0.016],
            radius=S * 0.008,
            fill=(ink if mono else LINE + (255,)),
        )

    # ---------- микрофон ----------
    cx = S * 0.5
    cap_top, cap_bottom = S * 0.230, S * 0.460
    cap_half = S * 0.089
    body_top, body_bottom = S * 0.448, S * 0.625
    yoke_r = S * 0.143
    yoke_w = max(4, round(S * 0.019))
    dark = (ink if mono else VIOLET_DARK + (255,))
    bar_y = S * 0.450

    # скоба-обойма: нижний полукруг, концы ровно под перекладиной
    d.arc(
        [cx - yoke_r, bar_y, cx + yoke_r, bar_y + yoke_r * 2],
        start=0,
        end=180,
        fill=dark,
        width=yoke_w,
    )

    # капсула: тёмно-фиолетовая верхняя часть
    if mono:
        d.rounded_rectangle(
            [cx - cap_half, cap_top, cx + cap_half, cap_bottom],
            radius=cap_half,
            corners=(True, True, False, False),
            fill=ink,
        )
    else:
        layer.alpha_composite(
            paint(
                S,
                lambda dd: dd.rounded_rectangle(
                    [cx - cap_half, cap_top, cx + cap_half, cap_bottom],
                    radius=cap_half,
                    corners=(True, True, False, False),
                    fill=255,
                ),
                (0x24, 0x06, 0x59),
                VIOLET_MID,
                cap_top,
                cap_bottom,
            ),
        )
        d = ImageDraw.Draw(layer)
        # тонкий блик по левому краю капсулы (широкий читался как «палец»)
        d.rounded_rectangle(
            [cx - cap_half * 0.58, cap_top + S * 0.028, cx - cap_half * 0.40, cap_bottom - S * 0.010],
            radius=cap_half * 0.12,
            fill=(0x9F, 0x8C, 0xFF, 60),
        )

    # корпус: сине-голубая нижняя часть
    if mono:
        d.rounded_rectangle(
            [cx - cap_half, body_top, cx + cap_half, body_bottom],
            radius=cap_half * 0.75,
            corners=(False, False, True, True),
            fill=ink,
        )
    else:
        layer.alpha_composite(
            paint(
                S,
                lambda dd: dd.rounded_rectangle(
                    [cx - cap_half, body_top, cx + cap_half, body_bottom],
                    radius=cap_half * 0.75,
                    corners=(False, False, True, True),
                    fill=255,
                ),
                BLUE_DEEP,
                BLUE_LIGHT,
                body_top,
                body_bottom,
            ),
        )
        d = ImageDraw.Draw(layer)
        d.rounded_rectangle(
            [cx - cap_half * 0.58, body_top + S * 0.020, cx - cap_half * 0.34, body_bottom - S * 0.030],
            radius=cap_half * 0.12,
            fill=(0xC8, 0xF2, 0xFF, 90),
        )

    # поперечная перекладина с заклёпками
    d.rounded_rectangle(
        [cx - yoke_r - S * 0.008, bar_y - S * 0.011, cx + yoke_r + S * 0.008, bar_y + S * 0.011],
        radius=S * 0.011,
        fill=dark,
    )
    for side in (-1, 1):
        px = cx + side * yoke_r
        d.ellipse(
            [px - S * 0.015, bar_y - S * 0.015, px + S * 0.015, bar_y + S * 0.015],
            fill=dark,
        )

    # ножка и подставка
    stand_w = S * 0.026
    d.rounded_rectangle(
        [cx - stand_w, body_bottom - S * 0.006, cx + stand_w, S * 0.785],
        radius=stand_w * 0.6,
        fill=dark,
    )
    if not mono:
        d.rounded_rectangle(
            [cx - stand_w * 0.36, body_bottom + S * 0.012, cx + stand_w * 0.30, S * 0.772],
            radius=stand_w * 0.3,
            fill=BLUE_LIGHT + (170,),
        )
    d.rounded_rectangle(
        [cx - S * 0.105, S * 0.783, cx + S * 0.105, S * 0.818],
        radius=S * 0.017,
        fill=dark,
    )
    return layer


def dev_badge(S):
    """Метка dev-сборки: тёмно-фиолетовый кружок с «α» в правом нижнем углу."""
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    r = S * 0.070
    cx = cy = S * 0.830
    d.ellipse(
        [cx - r, cy - r, cx + r, cy + r],
        fill=VIOLET_DARK + (255,),
        outline=WHITE + (210,),
        width=max(2, round(S * 0.008)),
    )
    font = ImageFont.truetype(FONT, round(r * 1.55))
    left, top, right, bottom = d.textbbox((0, 0), "α", font=font)
    d.text(
        (cx - (right - left) / 2 - left, cy - (bottom - top) / 2 - top - r * 0.05),
        "α",
        font=font,
        fill=WHITE + (255,),
    )
    return layer


def legacy_icon(size):
    big = size * 4
    canvas = background(big)
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=round(big * 0.235), fill=255)
    icon = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    icon.paste(canvas, (0, 0), mask)
    icon.alpha_composite(figure(big))
    icon.alpha_composite(dev_badge(big))
    return icon.resize((size, size), Image.LANCZOS)


def foreground(size):
    big = size * 4
    return figure(big).resize((size, size), Image.LANCZOS)


def monochrome(size):
    big = size * 4
    return figure(big, mono=True).resize((size, size), Image.LANCZOS)


def main():
    for name, k in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        legacy_icon(round(48 * k)).save(os.path.join(folder, "ic_launcher.png"))
        foreground(round(108 * k)).save(os.path.join(folder, "ic_launcher_foreground.png"))
        background(round(108 * k)).save(os.path.join(folder, "ic_launcher_background.png"))
        monochrome(round(108 * k)).save(os.path.join(folder, "ic_launcher_monochrome.png"))
        print(f"{name}: ic_launcher {round(48 * k)}px, foreground 108dp")

    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n'
        "</adaptive-icon>\n"
    )
    for tag in ("ic_launcher", "ic_launcher_round"):
        with open(os.path.join(anydpi, f"{tag}.xml"), "w", encoding="utf-8") as f:
            f.write(adaptive)
    print("mipmap-anydpi-v26: ic_launcher.xml, ic_launcher_round.xml")

    out = os.path.join(HERE, "..", "build")
    os.makedirs(out, exist_ok=True)
    preview = Image.new("RGBA", (1180, 460), (0x12, 0x14, 0x1C, 255))
    preview.alpha_composite(legacy_icon(360), (40, 50))
    round_icon = Image.new("RGBA", (360, 360), (0, 0, 0, 0))
    cmask = Image.new("L", (360, 360), 0)
    ImageDraw.Draw(cmask).ellipse([0, 0, 359, 359], fill=255)
    round_icon.paste(legacy_icon(360), (0, 0), cmask)
    preview.alpha_composite(round_icon, (410, 50))
    mono_show = Image.new("RGBA", (360, 360), (0x2A, 0x2E, 0x3A, 255))
    mono_show.alpha_composite(monochrome(360))
    preview.alpha_composite(mono_show, (780, 50))
    path = os.path.join(out, "icon_show.png")
    preview.convert("RGB").save(path)
    print("превью:", os.path.abspath(path))


if __name__ == "__main__":
    main()
