from PIL import Image, ImageDraw
import os

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'agon-mark.jpg')
RES = 'app/src/main/res'
BG = (25, 25, 25)

src = Image.open(SRC).convert('RGB')
mark = Image.new('RGBA', src.size, (0, 0, 0, 0))
sp, mp = src.load(), mark.load()
for y in range(src.size[1]):
    for x in range(src.size[0]):
        r, g, b = sp[x, y]
        a = (max(r, g, b) - 38) / 55.0
        a = 0.0 if a < 0 else (1.0 if a > 1 else a)
        if a <= 0.004:
            continue
        if a < 0.995:                      # un-multiply: no dark fringe on the edge
            r = min(255, max(0, int((r - BG[0] * (1 - a)) / a)))
            g = min(255, max(0, int((g - BG[1] * (1 - a)) / a)))
            b = min(255, max(0, int((b - BG[2] * (1 - a)) / a)))
        mp[x, y] = (r, g, b, int(round(a * 255)))
mark = mark.crop(mark.getbbox())
mark.save('mark.png')

def fit(mark, height):
    w = max(1, int(round(height * mark.size[0] / mark.size[1])))
    return mark.resize((w, height), Image.LANCZOS)

def icon(size, ratio, round_mask=False):
    canvas = Image.new('RGBA', (size, size), BG + (255,))
    m = fit(mark, int(round(size * ratio)))
    canvas.alpha_composite(m, ((size - m.size[0]) // 2, (size - m.size[1]) // 2))
    if round_mask:
        mask = Image.new('L', (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        canvas.putalpha(mask)
    return canvas

# Legacy launchers (pre-8.0) draw the bitmap as-is, so the mark sits at 0.66 of the tile.
for folder, size in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
    out = os.path.join(RES, 'mipmap-' + folder)
    os.makedirs(out, exist_ok=True)
    icon(size, 0.66).save(os.path.join(out, 'ic_launcher.png'))
    icon(size, 0.60, True).save(os.path.join(out, 'ic_launcher_round.png'))

# Adaptive foreground: 108dp canvas, of which a launcher only ever shows the inner
# ~66dp circle, so the mark is 0.50 of the canvas = ~54dp inside that circle.
CANVAS = 640
fg = Image.new('RGBA', (CANVAS, CANVAS), (0, 0, 0, 0))
m = fit(mark, int(CANVAS * 0.50))
fg.alpha_composite(m, ((CANVAS - m.size[0]) // 2, (CANVAS - m.size[1]) // 2))
fg.save(os.path.join(RES, 'mipmap-xxxhdpi', 'ic_launcher_foreground.png'))

white = Image.new('RGBA', fg.size, (255, 255, 255, 255))
white.putalpha(fg.getchannel('A'))
white.save(os.path.join(RES, 'mipmap-xxxhdpi', 'ic_launcher_monochrome.png'))
print('written')

# ---- desktop icon (Windows .ico, electron-builder reads build/icon.ico) --------------
# One multi-size .ico: 256 for the taskbar/Explorer large view, 16 for a title-bar corner.
def desktop_icon():
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'desktop', 'build')
    out_dir = os.path.normpath(out_dir)
    os.makedirs(out_dir, exist_ok=True)
    sizes = [16, 24, 32, 48, 64, 128, 256]
    tiles = [icon(n, 0.72 if n >= 48 else 0.82).convert('RGBA') for n in sizes]
    # Pillow writes a multi-image ICO when given the largest and a sizes list.
    big = tiles[-1]
    big.save(os.path.join(out_dir, 'icon.ico'), format='ICO', sizes=[(n, n) for n in sizes],
             append_images=tiles[:-1])
    tiles[-1].save(os.path.join(out_dir, 'icon.png'))
    # Tray / window icon used by the running app.
    icon(64, 0.78).save(os.path.join(out_dir, 'icon-64.png'))
    print('desktop icon written')


desktop_icon()

# ---- preview sheet -------------------------------------------------------------------
def shape(base, kind, scale=1.0):
    s = base.size[0]
    if kind == 'circle':
        mask = Image.new('L', (s, s), 0); ImageDraw.Draw(mask).ellipse((0, 0, s-1, s-1), fill=255)
    elif kind == 'squircle':
        mask = Image.new('L', (s, s), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, s-1, s-1), radius=int(s*0.28), fill=255)
    else:
        mask = Image.new('L', (s, s), 255)
    out = Image.new('RGBA', (s, s), (0, 0, 0, 0)); out.paste(base, (0, 0), mask)
    return out

W = 200
rows = [
    ('adaptive icon (safe zone)', [shape(icon(W, 0.50), k) for k in ('circle', 'squircle')]),
    ('legacy icon', [shape(icon(W, 0.66), k) for k in ('circle', 'squircle')]),
]
sheet = Image.new('RGB', (len(rows)*2*(W+24)+24, (W+24)+24+56), (247, 245, 240))
for r, (label, imgs) in enumerate(rows):
    for c, im in enumerate(imgs):
        sheet.paste(im, (24 + (r*2+c)*(W+24), 24), im)
from PIL import ImageFont
font = ImageFont.load_default()
d = ImageDraw.Draw(sheet)
d.text((24, W+40), 'adaptive (launcher, safe zone)', fill=(60,60,60), font=font)
d.text((24+(W+24), W+40), 'adaptive, squircle launcher', fill=(60,60,60), font=font)
d.text((24+2*(W+24), W+40), 'legacy bitmap', fill=(60,60,60), font=font)
d.text((24+3*(W+24), W+40), 'legacy bitmap, round', fill=(60,60,60), font=font)
sheet.save('app-icon-preview.png')
print('preview saved')
