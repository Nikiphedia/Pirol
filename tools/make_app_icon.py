"""Erzeugt das PIROL-App-Icon (Adaptive Icon, Legacy, Play-Store, Vorschau).

Aufruf: python -I tools/make_app_icon.py <vorlage.jpg>
Benoetigt Pillow und numpy. Die Vorlage ist ein Mockup mit gelbem Icon;
nur das schwarze Symbol wird per Luminanz freigestellt.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
ICON_DIR = ROOT / "tools" / "icon"

# Zuschnitt innerhalb der gelben Flaeche (Rundung und Rand ausgeschlossen)
CROP = (492, 180, 888, 590)
# Dichte -> (Adaptive-Kantenlaenge dp*d, Legacy-Kantenlaenge)
DENSITIES = {"mdpi": (108, 48), "hdpi": (162, 72), "xhdpi": (216, 96),
             "xxhdpi": (324, 144), "xxxhdpi": (432, 192)}
SAFE = 0.61        # Motivgroesse relativ zur Adaptive-Kante (66/108 dp)
INK = (26, 28, 30)


def extract(src):
    img = Image.open(src).convert("RGB")
    a = np.asarray(img.crop(CROP)).astype(np.float32)
    lum = a @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
    # Gelb: Median der hellen, farbigen Pixel (R hoch, B niedrig)
    yellow_mask = (a[..., 0] > 200) & (a[..., 2] < 90)
    yellow = tuple(int(round(np.median(a[..., c][yellow_mask]))) for c in range(3))
    y_yel = float(np.median(lum[yellow_mask]))
    y_dark = float(np.percentile(lum, 1))
    alpha = np.clip((y_yel - lum) / (y_yel - y_dark), 0, 1)
    # JPEG-Rauschen im Gelb entfernen, Kerne sichern
    alpha[alpha < 0.08] = 0
    alpha[alpha > 0.97] = 1
    ys, xs = np.where(alpha > 0.5)
    alpha = alpha[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    return yellow, Image.fromarray((alpha * 255).astype(np.uint8))


def motif_mask(mask, size, frac):
    """Maske auf size x size, laengste Seite = frac*size, zentriert."""
    w, h = mask.size
    s = frac * size / max(w, h)
    m = mask.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)
    out = Image.new("L", (size, size), 0)
    out.paste(m, ((size - m.width) // 2, (size - m.height) // 2))
    return out


def ink_layer(mask):
    layer = Image.new("RGBA", mask.size, INK + (0,))
    layer.putalpha(mask)
    return layer


def legacy(mask, yellow, size, shape):
    ss = size * 4
    bg = Image.new("RGBA", (ss, ss), (0, 0, 0, 0))
    m = Image.new("L", (ss, ss), 0)
    d = ImageDraw.Draw(m)
    if shape == "round":
        d.ellipse((0, 0, ss - 1, ss - 1), fill=255)
        frac = 0.60
    else:
        d.rounded_rectangle((0, 0, ss - 1, ss - 1), radius=int(ss * 0.2), fill=255)
        frac = 0.68
    bg.paste(Image.new("RGBA", (ss, ss), yellow + (255,)), mask=m)
    bg.alpha_composite(ink_layer(motif_mask(mask, ss, frac)))
    return bg.resize((size, size), Image.LANCZOS)


def preview(mask, yellow, path):
    tile = 256
    big = Image.new("RGBA", (tile, tile), yellow + (255,))
    big.alpha_composite(ink_layer(motif_mask(mask, tile, SAFE)))
    shapes = []
    for kind in ("circle", "squircle", "rounded", "small"):
        sz = 48 if kind == "small" else tile
        base = big.resize((sz, sz), Image.LANCZOS)
        m = Image.new("L", (sz * 4, sz * 4), 0)
        d = ImageDraw.Draw(m)
        e = sz * 4 - 1
        if kind in ("circle", "small"):
            d.ellipse((0, 0, e, e), fill=255)
        elif kind == "squircle":
            d.rounded_rectangle((0, 0, e, e), radius=int(e * 0.34), fill=255)
        else:
            d.rounded_rectangle((0, 0, e, e), radius=int(e * 0.16), fill=255)
        m = m.resize((sz, sz), Image.LANCZOS)
        out = Image.new("RGBA", (sz, sz), (0, 0, 0, 0))
        out.paste(base, mask=m)
        shapes.append(out)
    sheet = Image.new("RGBA", (tile * 3 + 4 * 20, tile + 2 * 20 + 80), (235, 235, 238, 255))
    for i, s in enumerate(shapes[:3]):
        sheet.alpha_composite(s, (20 + i * (tile + 20), 20))
    sheet.alpha_composite(shapes[3], (20, tile + 40))
    sheet.save(path)


def main(src):
    yellow, mask = extract(src)
    hexc = "#%02X%02X%02X" % yellow
    print("Gelb", hexc, "Motiv", mask.size)
    ICON_DIR.mkdir(parents=True, exist_ok=True)
    for dens, (ad, lg) in DENSITIES.items():
        d = RES / f"mipmap-{dens}"
        d.mkdir(parents=True, exist_ok=True)
        fm = motif_mask(mask, ad, SAFE)
        ink_layer(fm).save(d / "ic_launcher_foreground.png")
        mono = Image.new("RGBA", (ad, ad), (0, 0, 0, 0))
        mono.putalpha(fm)
        mono.save(d / "ic_launcher_monochrome.png")
        legacy(mask, yellow, lg, "square").save(d / "ic_launcher.png")
        legacy(mask, yellow, lg, "round").save(d / "ic_launcher_round.png")
    big = Image.new("RGBA", (512, 512), yellow + (255,))
    big.alpha_composite(ink_layer(motif_mask(mask, 512, 0.68)))
    big.convert("RGB").save(ICON_DIR / "pirol_icon_512.png")
    preview(mask, yellow, ICON_DIR / "preview.png")
    (RES / "values").mkdir(exist_ok=True)
    (RES / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <color name="ic_launcher_background">{hexc}</color>\n</resources>\n',
        encoding="utf-8")
    any26 = RES / "mipmap-anydpi-v26"
    any26.mkdir(exist_ok=True)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@color/ic_launcher_background"/>\n'
           '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
           '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>\n'
           '</adaptive-icon>\n')
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (any26 / n).write_text(xml, encoding="utf-8")


if __name__ == "__main__":
    main(sys.argv[1])
