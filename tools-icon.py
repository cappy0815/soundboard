# App-Icon v0.3: Puck als "Lautsprecher" auf dem Eis, darueber ein Equalizer, seitlich Schallwellen
from PIL import Image, ImageDraw, ImageFilter
import os, sys
root = sys.argv[1]
S = 2048  # gross zeichnen, dann verkleinern (glatte Kanten)

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))

# Hintergrund: helles Eis (oben fast weiss, unten eisblau), dazu Kufenspuren und Reif
import random
random.seed(7)
bg = Image.new("RGBA", (S, S))
d = ImageDraw.Draw(bg)
for y in range(S):
    d.line([(0, y), (S, y)], fill=lerp((238, 246, 252, 255), (150, 192, 222, 255), y / S))
frost = Image.new("RGBA", (S, S), (0, 0, 0, 0))
fd = ImageDraw.Draw(frost)
for _ in range(70):  # Kufenspuren: lange, flache Boegen
    x0 = random.randint(-400, S); y0 = random.randint(0, S); r = random.randint(700, 2200)
    a0 = random.randint(200, 330); a1 = a0 + random.randint(15, 45)
    fd.arc([x0 - r, y0 - r, x0 + r, y0 + r], start=a0, end=a1, fill=(255, 255, 255, random.randint(70, 150)), width=random.randint(3, 9))
for _ in range(40):  # feine dunklere Kratzer
    x0 = random.randint(0, S); y0 = random.randint(0, S); l = random.randint(120, 480); dy = random.randint(-60, 60)
    fd.line([(x0, y0), (x0 + l, y0 + dy)], fill=(110, 150, 185, random.randint(40, 90)), width=random.randint(2, 5))
frost = frost.filter(ImageFilter.GaussianBlur(1.5))
bg = Image.alpha_composite(bg, frost)
lines = Image.new("RGBA", (S, S), (0, 0, 0, 0))
ld = ImageDraw.Draw(lines)
ld.rectangle([0, int(S * 0.80), S, int(S * 0.825)], fill=(214, 40, 50, 150))
ld.rectangle([0, int(S * 0.905), S, int(S * 0.925)], fill=(40, 80, 190, 120))
bg = Image.alpha_composite(bg, lines.filter(ImageFilter.GaussianBlur(3)))  # Linien "unter dem Eis"

# abgerundete Maske
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([60, 60, S - 60, S - 60], radius=430, fill=255)

img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
img.paste(bg, (0, 0), mask)
d = ImageDraw.Draw(img)

cx = S // 2
# Puck (3/4-Ansicht): Schatten, Seitenband, Oberseite, Glanzkante
pw, ph, band = 940, 320, 260
py = int(S * 0.60)
sh = Image.new("RGBA", (S, S), (0, 0, 0, 0))
ImageDraw.Draw(sh).ellipse([cx - pw // 2 - 40, py + band - 40, cx + pw // 2 + 40, py + band + ph // 2 + 60], fill=(20, 40, 70, 140))
sh = sh.filter(ImageFilter.GaussianBlur(45))
img = Image.alpha_composite(img, sh)
d = ImageDraw.Draw(img)
d.rectangle([cx - pw // 2, py, cx + pw // 2, py + band], fill=(14, 15, 18))
d.ellipse([cx - pw // 2, py + band - ph // 2, cx + pw // 2, py + band + ph // 2], fill=(14, 15, 18))
d.ellipse([cx - pw // 2, py - ph // 2, cx + pw // 2, py + ph // 2], fill=(70, 76, 88))
d.ellipse([cx - pw // 2 + 10, py - ph // 2 + 10, cx + pw // 2 - 10, py + ph // 2 - 6], fill=(40, 44, 52))
d.ellipse([cx - pw // 2 + 70, py - ph // 2 + 30, cx + pw // 2 - 70, py + ph // 2 - 30], fill=(30, 33, 40))
# Riffelung am Rand
for k in range(-6, 7):
    x = cx + k * 66
    d.line([(x, py + 60), (x, py + band + 40)], fill=(38, 40, 46), width=16)

# Equalizer aus der Puck-Oberseite: Balken mit Verlauf Gelb -> Rot
bars = [0.42, 0.70, 1.0, 0.82, 0.55]
bw, gap = 104, 54
total = len(bars) * bw + (len(bars) - 1) * gap
x0 = cx - total // 2
base = py - 20
maxh = 820
for i, hrel in enumerate(bars):
    x = x0 + i * (bw + gap)
    top = base - int(maxh * hrel)
    bar = Image.new("RGBA", (bw, base - top), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bar)
    for yy in range(base - top):
        t = yy / max(1, base - top - 1)
        bd.line([(0, yy), (bw, yy)], fill=lerp((255, 205, 70, 255), (229, 60, 70, 255), t))
    bm = Image.new("L", bar.size, 0)
    ImageDraw.Draw(bm).rounded_rectangle([0, 0, bw - 1, base - top - 1], radius=bw // 2, fill=255)
    img.paste(bar, (x, top), bm)
d = ImageDraw.Draw(img)

# Schallwellen links und rechts
for side in (-1, 1):
    for j, r in enumerate((170, 290, 410)):
        a = 255 - j * 70
        box = [cx + side * 330 - r, base - 400 - r, cx + side * 330 + r, base - 400 + r]
        start, end = (-40, 40) if side == 1 else (140, 220)
        d.arc(box, start=start, end=end, fill=(214, 60, 50, a), width=44)

# auf runde Ecken beschneiden (Wellen duerfen nicht ueberstehen)
out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
out.paste(img, (0, 0), mask)

for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    p = os.path.join(root, "res", "mipmap-" + name); os.makedirs(p, exist_ok=True)
    out.resize((px, px), Image.LANCZOS).save(os.path.join(p, "ic_launcher.png"))
out.resize((512, 512), Image.LANCZOS).save(os.path.join(root, "icon512.png"))
