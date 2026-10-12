"""glassGram's extra launcher icons: the pointer arrow on a set of original backgrounds.

Each icon is an adaptive icon made of two WebP layers (the background art and the arrow, 432 px
for the 108dp grid) and the shared monochrome layer, plus the legacy launcher icons for Android 7.
The pictures are SVG, rendered by headless Chromium on one sheet and cut and encoded with
ImageMagick and ffmpeg.

Usage: python3 gen_icon_pack.py <repo root> [--preview <png>]
"""
import math
import os
import random
import re
import subprocess
import sys
import tempfile

REPO = sys.argv[1]
RES = os.path.join(REPO, 'TMessagesProj/src/main/res')
CHROME = '/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell'
if not os.path.exists(CHROME):
    CHROME = '/opt/pw-browsers/chromium'

SIZE = 432  # 108dp at xxxhdpi
DENSITIES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}

# The arrow of gen_icons.py, in the 108 icon space
TIP, LEFT, NOTCH, RIGHT = (75, 33), (33, 50), (54.35, 53.65), (58, 75)
BACK = (45.5, 62.5)
POLY = [TIP, LEFT, NOTCH, RIGHT]
PATH = 'M%g,%g L%g,%g L%g,%g L%g,%g Z' % (TIP + LEFT + NOTCH + RIGHT)
STROKE = 5


# --- Arrows ---------------------------------------------------------------------------------

def arrow_solid(tip, back, shadow=None, gid='s'):
    out = (f'<defs><linearGradient id="{gid}g" gradientUnits="userSpaceOnUse" x1="{TIP[0]}" y1="{TIP[1]}" x2="{BACK[0]}" y2="{BACK[1]}">'
           f'<stop offset="0" stop-color="#{tip}"/><stop offset="1" stop-color="#{back}"/></linearGradient>')
    if shadow:
        out += (f'<filter id="{gid}f" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="2.2"/></filter>')
    out += '</defs>'
    if shadow:
        color, opacity = shadow
        out += (f'<path d="{PATH}" transform="translate(0 2.2)" fill="#{color}" stroke="#{color}" stroke-width="{STROKE}" '
                f'stroke-linejoin="round" opacity="{opacity}" filter="url(#{gid}f)"/>')
    out += f'<path d="{PATH}" fill="url(#{gid}g)" stroke="url(#{gid}g)" stroke-width="{STROKE}" stroke-linejoin="round"/>'
    return out


def arrow_glow(tip, back, glow, strength=4.0, gid='g'):
    return (f'<defs><filter id="{gid}f" x="-80%" y="-80%" width="260%" height="260%"><feGaussianBlur stdDeviation="{strength}"/></filter></defs>'
            f'<path d="{PATH}" fill="#{glow}" stroke="#{glow}" stroke-width="{STROKE + 2}" stroke-linejoin="round" opacity="0.85" filter="url(#{gid}f)"/>'
            + arrow_solid(tip, back, gid=gid + 'i'))


def arrow_outline(color, glow, gid='o'):
    return (f'<defs><filter id="{gid}f" x="-80%" y="-80%" width="260%" height="260%"><feGaussianBlur stdDeviation="3"/></filter></defs>'
            f'<path d="{PATH}" fill="none" stroke="#{glow}" stroke-width="7" stroke-linejoin="round" opacity="0.9" filter="url(#{gid}f)"/>'
            f'<path d="{PATH}" fill="none" stroke="#{color}" stroke-width="3.6" stroke-linejoin="round"/>'
            f'<path d="{PATH}" fill="none" stroke="#FFFFFF" stroke-width="1.2" stroke-linejoin="round" opacity="0.85"/>')


def arrow_glass(tint='FFFFFF', gid='q'):
    """A frosted glass arrow: a translucent body, a light rim and a highlight at the tip."""
    return (f'<defs><linearGradient id="{gid}b" gradientUnits="userSpaceOnUse" x1="{TIP[0]}" y1="{TIP[1]}" x2="{BACK[0]}" y2="{BACK[1]}">'
            f'<stop offset="0" stop-color="#{tint}" stop-opacity="0.92"/><stop offset="1" stop-color="#{tint}" stop-opacity="0.6"/></linearGradient>'
            f'<linearGradient id="{gid}r" gradientUnits="userSpaceOnUse" x1="33" y1="33" x2="75" y2="75">'
            f'<stop offset="0" stop-color="#FFFFFF" stop-opacity="0.95"/><stop offset="0.5" stop-color="#FFFFFF" stop-opacity="0.35"/>'
            f'<stop offset="1" stop-color="#FFFFFF" stop-opacity="0.8"/></linearGradient>'
            f'<filter id="{gid}f" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="2.5"/></filter></defs>'
            f'<path d="{PATH}" transform="translate(0 2.5)" fill="#1E1B4B" stroke="#1E1B4B" stroke-width="{STROKE}" stroke-linejoin="round" opacity="0.32" filter="url(#{gid}f)"/>'
            f'<path d="{PATH}" fill="url(#{gid}b)" stroke="url(#{gid}b)" stroke-width="{STROKE}" stroke-linejoin="round"/>'
            f'<path d="{PATH}" fill="none" stroke="url(#{gid}r)" stroke-width="1.6" stroke-linejoin="round"/>'
            f'<ellipse cx="66" cy="39" rx="6" ry="2.2" transform="rotate(-38 66 39)" fill="#FFFFFF" opacity="0.7"/>')


def inside(x, y, poly):
    """Point in polygon (even-odd)."""
    result = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            result = not result
        j = i
    return result


def grown(poly, d):
    """The polygon pushed out from its centroid by d, roughly the stroked arrow."""
    cx = sum(p[0] for p in poly) / len(poly)
    cy = sum(p[1] for p in poly) / len(poly)
    out = []
    for x, y in poly:
        l = math.hypot(x - cx, y - cy)
        out.append((x + (x - cx) / l * d, y + (y - cy) / l * d))
    return out


def arrow_pixel(cell=4.0):
    poly = grown(POLY, 2.0)
    filled = set()
    for gx in range(0, int(108 / cell)):
        for gy in range(0, int(108 / cell)):
            if inside((gx + 0.5) * cell, (gy + 0.5) * cell, poly):
                filled.add((gx, gy))
    out = []
    edge = set()
    for gx, gy in filled:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (gx + dx, gy + dy)
            if n not in filled:
                edge.add(n)
    for gx, gy in edge:
        out.append(f'<rect x="{gx * cell}" y="{gy * cell}" width="{cell}" height="{cell}" fill="#3B1F0E"/>')
    for gx, gy in filled:
        # lighter towards the tip
        t = ((gx + 0.5) * cell - BACK[0]) * (TIP[0] - BACK[0]) + ((gy + 0.5) * cell - BACK[1]) * (TIP[1] - BACK[1])
        t /= (TIP[0] - BACK[0]) ** 2 + (TIP[1] - BACK[1]) ** 2
        color = 'FFE45C' if t > 0.55 else 'FFC53D' if t > 0.15 else 'FF9F1C'
        out.append(f'<rect x="{gx * cell}" y="{gy * cell}" width="{cell}" height="{cell}" fill="#{color}"/>')
    # a white glint
    hx, hy = max(filled, key=lambda c: c[0] - c[1])
    out.append(f'<rect x="{(hx - 1) * cell}" y="{(hy + 1) * cell}" width="{cell}" height="{cell}" fill="#FFFFFF"/>')
    return f'<g shape-rendering="crispEdges">{"".join(out)}</g>'


def arrow_sketch(seed=7):
    rnd = random.Random(seed)
    out = ['<defs><clipPath id="skc"><path d="' + PATH + '"/></clipPath></defs>']
    # hatching inside
    lines = []
    for k in range(-40, 120, 3):
        lines.append(f'<line x1="{k}" y1="20" x2="{k + 60}" y2="90" stroke="#374151" stroke-width="0.6" opacity="0.55"/>')
    out.append(f'<g clip-path="url(#skc)">{"".join(lines)}</g>')
    # three wobbly outlines
    for _ in range(3):
        pts = [(x + rnd.uniform(-1.1, 1.1), y + rnd.uniform(-1.1, 1.1)) for x, y in POLY]
        d = 'M' + ' L'.join(f'{x:.2f},{y:.2f}' for x, y in pts) + ' Z'
        out.append(f'<path d="{d}" fill="none" stroke="#1F2937" stroke-width="{rnd.uniform(0.9, 1.4):.2f}" '
                   f'stroke-linejoin="round" stroke-linecap="round" opacity="0.85"/>')
    return ''.join(out)


# --- Backgrounds ----------------------------------------------------------------------------

def linear(stops, x1=0, y1=0, x2=108, y2=108, gid='bg'):
    s = ''.join(f'<stop offset="{o}" stop-color="#{c}"/>' for o, c in stops)
    return (f'<defs><linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{s}</linearGradient></defs>'
            f'<rect width="108" height="108" fill="url(#{gid})"/>')


def radial(stops, cx=54, cy=54, r=76, gid='rg'):
    s = ''.join(f'<stop offset="{o}" stop-color="#{c}"/>' for o, c in stops)
    return (f'<defs><radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">{s}</radialGradient></defs>'
            f'<rect width="108" height="108" fill="url(#{gid})"/>')


def highlight(alpha=0.22):
    return (f'<defs><radialGradient id="hl" gradientUnits="userSpaceOnUse" cx="30" cy="18" r="70">'
            f'<stop offset="0" stop-color="#FFFFFF" stop-opacity="{alpha}"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient></defs>'
            f'<rect width="108" height="108" fill="url(#hl)"/>')


def blobs(items, blur=9):
    out = f'<defs><filter id="bl" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="{blur}"/></filter></defs><g filter="url(#bl)">'
    for cx, cy, r, color, op in items:
        out += f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="#{color}" opacity="{op}"/>'
    return out + '</g>'


def stars(seed, count, color='FFFFFF', keep_out=True, sizes=(0.35, 0.9)):
    rnd = random.Random(seed)
    out = ''
    for _ in range(count):
        x, y = rnd.uniform(4, 104), rnd.uniform(4, 104)
        if keep_out and inside(x, y, grown(POLY, 6)):
            continue
        r = rnd.uniform(*sizes)
        out += f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r:.2f}" fill="#{color}" opacity="{rnd.uniform(0.45, 1):.2f}"/>'
    return out


def sparkle(x, y, s, color='FFFFFF', op=1.0):
    return (f'<path d="M{x},{y - s} Q{x + s * 0.18},{y - s * 0.18} {x + s},{y} Q{x + s * 0.18},{y + s * 0.18} {x},{y + s} '
            f'Q{x - s * 0.18},{y + s * 0.18} {x - s},{y} Q{x - s * 0.18},{y - s * 0.18} {x},{y - s} Z" fill="#{color}" opacity="{op}"/>')


def bg_space():
    return (radial([(0, '27235E'), (0.6, '141238'), (1, '0A0A1F')], cx=40, cy=30, r=95)
            + stars(11, 70) + sparkle(24, 26, 3.2) + sparkle(84, 80, 2.6, op=0.9) + sparkle(30, 82, 1.8, op=0.8))


def bg_nebula():
    return (radial([(0, '1A1035'), (1, '07060F')], r=90)
            + blobs([(30, 34, 22, 'C026D3', 0.75), (78, 70, 26, '2563EB', 0.8), (62, 28, 14, 'F472B6', 0.55), (34, 84, 14, '7C3AED', 0.6)], blur=10)
            + stars(5, 55, sizes=(0.3, 0.75)) + sparkle(86, 24, 2.4))


def bg_aurora():
    return (linear([(0, '0B1026'), (1, '111C3A')], 0, 0, 0, 108)
            + blobs([(20, 40, 20, '34D399', 0.8), (54, 28, 18, '22D3EE', 0.75), (88, 44, 20, 'A78BFA', 0.8), (60, 84, 22, 'F472B6', 0.35)], blur=11)
            + stars(3, 20, sizes=(0.25, 0.55)))


def bg_neon():
    grid = ''
    for i in range(0, 109, 9):
        grid += f'<line x1="{i}" y1="62" x2="{54 + (i - 54) * 2.2:.1f}" y2="108" stroke="#C026D3" stroke-width="0.5" opacity="0.55"/>'
    for k, y in enumerate([64, 68, 74, 82, 93, 107]):
        grid += f'<line x1="0" y1="{y}" x2="108" y2="{y}" stroke="#C026D3" stroke-width="0.5" opacity="{0.25 + k * 0.08:.2f}"/>'
    return (linear([(0, '0A0612'), (0.58, '1A0B2E'), (0.6, '12081F'), (1, '0A0612')], 0, 0, 0, 108)
            + f'<circle cx="54" cy="62" r="20" fill="#F472B6" opacity="0.18"/>' + grid)


def bg_liquid():
    return (linear([(0, 'FDE68A'), (1, 'F9A8D4')])
            + blobs([(22, 26, 26, 'F472B6', 0.95), (88, 30, 24, '60A5FA', 0.95), (80, 88, 26, 'A78BFA', 0.9), (24, 86, 22, 'FBBF24', 0.9)], blur=12)
            + highlight(0.25))


def bg_bubbles():
    out = linear([(0, '38BDF8'), (1, '1D4ED8')]) + highlight(0.2)
    rnd = random.Random(4)
    for _ in range(16):
        x, y, r = rnd.uniform(6, 102), rnd.uniform(6, 102), rnd.uniform(2, 7)
        if inside(x, y, grown(POLY, 8)):
            continue
        out += (f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r:.1f}" fill="#FFFFFF" fill-opacity="0.08" stroke="#FFFFFF" stroke-opacity="0.55" stroke-width="0.6"/>'
                f'<circle cx="{x - r * 0.35:.1f}" cy="{y - r * 0.35:.1f}" r="{r * 0.22:.2f}" fill="#FFFFFF" opacity="0.7"/>')
    return out


def bg_ice():
    out = linear([(0, 'E0F2FE'), (0.55, '7DD3FC'), (1, '0284C7')]) + highlight(0.35)
    rnd = random.Random(9)
    # frost: a few thin cracks from the corners, and a sprinkle of ice crystals
    for x, y in ((0, 10), (108, 96), (100, 0), (6, 108)):
        d = f'M{x:.1f},{y:.1f}'
        for _ in range(3):
            x += (54 - x) * rnd.uniform(0.12, 0.22) + rnd.uniform(-5, 5)
            y += (54 - y) * rnd.uniform(0.12, 0.22) + rnd.uniform(-5, 5)
            d += f' L{x:.1f},{y:.1f}'
        out += f'<path d="{d}" fill="none" stroke="#FFFFFF" stroke-width="0.45" opacity="0.55"/>'
    out += stars(31, 26, sizes=(0.3, 0.7))
    return out


def bg_lava():
    return (radial([(0, '3B0D06'), (1, '120403')], r=90)
            + blobs([(20, 84, 22, 'F97316', 0.9), (88, 26, 18, 'DC2626', 0.85), (86, 92, 14, 'FBBF24', 0.6), (18, 20, 12, 'EA580C', 0.6)], blur=10))


def bg_pixel():
    out = linear([(0, '7DD3FC'), (1, 'BAE6FD')], 0, 0, 0, 108)
    for cx, cy, w in ((22, 26, 5), (82, 84, 4), (84, 22, 3)):
        c = 3
        cells = [(0, 1), (1, 0), (1, 1), (2, 0), (2, 1), (3, 1), (-1, 1)] + [(i, 2) for i in range(-1, w)]
        for gx, gy in cells:
            out += f'<rect x="{cx + gx * c}" y="{cy + gy * c}" width="{c}" height="{c}" fill="#FFFFFF"/>'
    out += '<rect x="0" y="96" width="108" height="12" fill="#4ADE80"/><rect x="0" y="96" width="108" height="3" fill="#86EFAC"/>'
    return f'<g shape-rendering="crispEdges">{out}</g>'


def bg_sakura():
    out = linear([(0, 'FFF1F5'), (1, 'FBCFE8')]) + highlight(0.4)
    rnd = random.Random(21)
    for _ in range(18):
        x, y = rnd.uniform(4, 104), rnd.uniform(4, 104)
        if inside(x, y, grown(POLY, 7)):
            continue
        a = rnd.uniform(0, 180)
        s = rnd.uniform(1.6, 3.2)
        out += (f'<ellipse cx="{x:.1f}" cy="{y:.1f}" rx="{s:.1f}" ry="{s * 0.55:.1f}" transform="rotate({a:.0f} {x:.1f} {y:.1f})" '
                f'fill="#F9A8D4" opacity="{rnd.uniform(0.55, 0.95):.2f}"/>')
    return out


def bg_code():
    out = linear([(0, '03140A'), (1, '000000')], 0, 0, 0, 108)
    rnd = random.Random(12)
    for col in range(0, 108, 6):
        length = rnd.randint(4, 14)
        start = rnd.uniform(-20, 80)
        for i in range(length):
            y = start + i * 5
            if 0 <= y <= 106:
                op = 0.15 + 0.75 * (i / length)
                w = rnd.choice((1.6, 2.4, 3.2))
                out += f'<rect x="{col + 1.4}" y="{y:.1f}" width="{w}" height="2.4" rx="0.4" fill="#22C55E" opacity="{op:.2f}"/>'
    return out


def bg_sketch():
    out = '<rect width="108" height="108" fill="#F6F1E7"/>'
    for y in range(12, 108, 8):
        out += f'<line x1="0" y1="{y}" x2="108" y2="{y}" stroke="#93C5FD" stroke-width="0.35" opacity="0.7"/>'
    out += '<line x1="14" y1="0" x2="14" y2="108" stroke="#F87171" stroke-width="0.45" opacity="0.7"/>'
    return out


def bg_rainbow():
    colors = ['E40303', 'FF8C00', 'FFED00', '008026', '24408E', '732982']
    out = ''
    for i, c in enumerate(colors):
        out += f'<rect x="-40" y="{-40 + i * 32}" width="200" height="33" fill="#{c}" transform="rotate(-35 54 54)"/>'
    return out + highlight(0.18)


def bg_holo():
    return (linear([(0, 'C4B5FD'), (0.35, 'A5F3FC'), (0.65, 'FBCFE8'), (1, 'FDE68A')])
            + blobs([(80, 24, 20, 'F0ABFC', 0.7), (24, 80, 20, '67E8F9', 0.7), (84, 86, 16, 'FDE68A', 0.6)], blur=12)
            + highlight(0.35))


def bg_forest():
    out = linear([(0, '0F2027'), (0.6, '134E4A'), (1, '064E3B')], 0, 0, 0, 108)
    out += '<circle cx="80" cy="24" r="7" fill="#FEF3C7" opacity="0.9"/>' + stars(8, 18, sizes=(0.25, 0.5))
    rnd = random.Random(5)
    for layer, (color, base, h) in enumerate((('0B3B2E', 92, 26), ('052E22', 100, 22))):
        x = -6 + layer * 5
        while x < 112:
            hh = h * rnd.uniform(0.7, 1.15)
            w = hh * 0.55
            out += f'<path d="M{x:.1f},{base - hh:.1f} L{x + w / 2:.1f},{base} L{x - w / 2:.1f},{base} Z" fill="#{color}"/>'
            x += w * rnd.uniform(0.55, 0.85)
        out += f'<rect x="0" y="{base}" width="108" height="{108 - base}" fill="#{color}"/>'
    return out


def bg_midnight():
    return (linear([(0, '0F172A'), (1, '1E293B')])
            + '<circle cx="78" cy="28" r="10" fill="#E2E8F0"/><circle cx="83" cy="24" r="9" fill="#172033"/>'
            + stars(15, 30, sizes=(0.3, 0.7)) + sparkle(24, 78, 2.2, op=0.9))


def bg_planet():
    return (radial([(0, '1E3A8A'), (1, '0B1026')], cx=54, cy=54, r=80) + stars(17, 40, sizes=(0.3, 0.7))
            + '<circle cx="84" cy="82" r="9" fill="#F97316"/><circle cx="81" cy="79" r="3" fill="#FDBA74" opacity="0.6"/>')


def ring_back():
    return '<ellipse cx="54" cy="54" rx="38" ry="12" transform="rotate(-25 54 54)" fill="none" stroke="#93C5FD" stroke-width="2.2" opacity="0.9"/>'


def ring_front():
    # the near half of the ring passes over the arrow
    return ('<defs><clipPath id="rf"><rect x="0" y="54" width="108" height="60" transform="rotate(-25 54 54)"/></clipPath></defs>'
            '<ellipse cx="54" cy="54" rx="38" ry="12" transform="rotate(-25 54 54)" fill="none" stroke="#BFDBFE" stroke-width="2.2" clip-path="url(#rf)"/>')


# id: (title en, title ru, background svg, foreground svg)
ICONS = [
    ('ocean', 'Ocean', 'Океан', linear([(0, '22D3EE'), (0.5, '0EA5E9'), (1, '1E3A8A')]) + highlight(0.22), arrow_solid('FFFFFF', 'CFEFFF', ('0B2A55', 0.35))),
    ('sunset', 'Sunset', 'Закат', linear([(0, 'FFB38A'), (0.5, 'FF6A88'), (1, '8B5CF6')], 0, 0, 40, 108), arrow_solid('2A1B3D', '4C2E6E', ('2A1B3D', 0.25))),
    ('aurora', 'Aurora', 'Сияние', bg_aurora(), arrow_solid('FFFFFF', 'D1FAE5', ('000000', 0.35))),
    ('amethyst', 'Amethyst', 'Аметист', radial([(0, '3B1F5C'), (1, '120B1C')], r=80), arrow_glow('EDE9FE', '8B5CF6', 'A78BFA')),
    ('mint', 'Mint', 'Мята', linear([(0, 'A7F3D0'), (1, '059669')]) + highlight(0.25), arrow_solid('FFFFFF', 'D1FAE5', ('064E3B', 0.3))),
    ('ruby', 'Ruby', 'Рубин', linear([(0, 'FB7185'), (1, '9F1239')]) + highlight(0.22), arrow_solid('FFFFFF', 'FFE4E6', ('4C0519', 0.35))),
    ('coral', 'Coral', 'Коралл', linear([(0, 'FDBA74'), (1, 'F43F5E')]) + highlight(0.22), arrow_solid('FFFFFF', 'FFEDD5', ('7C2D12', 0.3))),
    ('citrus', 'Citrus', 'Цитрус', linear([(0, 'FDE047'), (1, 'F97316')]) + highlight(0.25), arrow_solid('FFFFFF', 'FEF9C3', ('9A3412', 0.35))),
    ('gold', 'Gold', 'Золото', radial([(0, '2A2418'), (1, '0C0A06')], r=80), arrow_glow('FEF3C7', 'D97706', 'F59E0B', 3.2)),
    ('graphite', 'Graphite', 'Графит', linear([(0, '9CA3AF'), (1, '374151')]) + highlight(0.3), arrow_solid('FFFFFF', 'E5E7EB', ('111827', 0.35))),
    ('black', 'Black', 'Чёрная', '<rect width="108" height="108" fill="#000000"/>', arrow_solid('FFFFFF', 'A1A1AA')),
    ('pastel', 'Pastel', 'Пастель', linear([(0, 'FBCFE8'), (0.5, 'C7D2FE'), (1, 'A5F3FC')]) + highlight(0.3), arrow_solid('1F2937', '4B5563', ('1F2937', 0.2))),
    ('liquid', 'Glass', 'Стекло', bg_liquid(), arrow_glass()),
    ('holo', 'Holo', 'Голограмма', bg_holo(), arrow_glass()),
    ('space', 'Space', 'Космос', bg_space(), arrow_glow('FFFFFF', 'C7D2FE', '818CF8', 3.5)),
    ('nebula', 'Nebula', 'Туманность', bg_nebula(), arrow_glow('FFFFFF', 'F5D0FE', 'E879F9', 3.5)),
    ('planet', 'Orbit', 'Орбита', bg_planet() + ring_back(), arrow_solid('FFFFFF', 'C7D2FE') + ring_front()),
    ('midnight', 'Midnight', 'Полночь', bg_midnight(), arrow_solid('F1F5F9', '94A3B8', ('000000', 0.4))),
    ('neon', 'Neon', 'Неон', bg_neon(), arrow_outline('F472B6', 'EC4899')),
    ('code', 'Code', 'Код', bg_code(), arrow_glow('BBF7D0', '16A34A', '22C55E', 3.0)),
    ('lava', 'Lava', 'Лава', bg_lava(), arrow_glow('FFF7ED', 'FB923C', 'F97316', 3.5)),
    ('ice', 'Ice', 'Лёд', bg_ice(), arrow_glass('E0F2FE')),
    ('bubbles', 'Bubbles', 'Пузыри', bg_bubbles(), arrow_solid('FFFFFF', 'DBEAFE', ('1E3A8A', 0.35))),
    ('forest', 'Forest', 'Лес', bg_forest(), arrow_solid('FFFFFF', 'D1FAE5', ('000000', 0.4))),
    ('sakura', 'Sakura', 'Сакура', bg_sakura(), arrow_solid('F472B6', 'BE185D', ('9D174D', 0.25))),
    ('rainbow', 'Rainbow', 'Радуга', bg_rainbow(), arrow_solid('FFFFFF', 'F3F4F6', ('000000', 0.45))),
    ('pixel', 'Pixel', 'Пиксель', bg_pixel(), arrow_pixel()),
    ('sketch', 'Sketch', 'Набросок', bg_sketch(), arrow_sketch()),
]


# --- Rendering ------------------------------------------------------------------------------

def run(cmd):
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=300)


def scoped(svg, prefix):
    """The tiles share one page, and with it their ids: each gets its own."""
    svg = re.sub(r'id="([^"]+)"', lambda m: f'id="{prefix}{m.group(1)}"', svg)
    return re.sub(r'url\(#([^)]+)\)', lambda m: f'url(#{prefix}{m.group(1)})', svg)


def render_sheet(tiles, columns, out):
    """Renders SVG bodies (108 space) as SIZE px tiles on one transparent sheet."""
    rows = (len(tiles) + columns - 1) // columns
    cells = ''.join(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="{SIZE}" height="{SIZE}">{scoped(t, f"t{i}_")}</svg>'
                    for i, t in enumerate(tiles))
    html = (f'<!doctype html><html><head><style>html,body{{margin:0;padding:0;background:transparent;overflow:hidden}}'
            f'body{{display:grid;grid-template-columns:repeat({columns},{SIZE}px);grid-auto-rows:{SIZE}px;width:{columns * SIZE}px}}'
            f'svg{{display:block}}</style></head><body>{cells}</body></html>')
    with tempfile.NamedTemporaryFile('w', suffix='.html', delete=False) as f:
        f.write(html)
        page = f.name
    try:
        run([CHROME, '--headless', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
             '--default-background-color=00000000', f'--window-size={columns * SIZE},{rows * SIZE}',
             f'--screenshot={out}', 'file://' + page])
    finally:
        os.unlink(page)
    return rows


def crop(sheet, index, columns, out):
    x, y = (index % columns) * SIZE, (index // columns) * SIZE
    run(['convert', sheet, '-crop', f'{SIZE}x{SIZE}+{x}+{y}', '+repage', out])


def webp(src, out, quality=88):
    run(['ffmpeg', '-y', '-loglevel', 'error', '-i', src, '-c:v', 'libwebp', '-quality', str(quality),
         '-compression_level', '6', '-pix_fmt', 'yuva420p', out])


def legacy(bg, fg, size, shape, out, tmp):
    """The visible middle of the adaptive icon (17..91 of 108), masked to a squircle or a circle."""
    inner = round(SIZE * 74 / 108)
    off = round(SIZE * 17 / 108)
    flat = os.path.join(tmp, 'flat.png')
    run(['convert', bg, fg, '-composite', '-crop', f'{inner}x{inner}+{off}+{off}', '+repage',
         '-resize', f'{size}x{size}', flat])
    mask = os.path.join(tmp, 'mask.png')
    if shape == 'round':
        draw = f'circle {size / 2},{size / 2} {size / 2},0'
    else:
        r = size * 0.225
        draw = f'roundrectangle 0,0 {size - 1},{size - 1} {r},{r}'
    run(['convert', '-size', f'{size}x{size}', 'xc:none', '-fill', 'white', '-draw', draw, mask])
    masked = os.path.join(tmp, 'masked.png')
    run(['convert', flat, mask, '-alpha', 'off', '-compose', 'CopyOpacity', '-composite', masked])
    webp(masked, out, 90)


ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<!-- glassGram launcher icon "{title}" (branding/gen_icon_pack.py) -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/glassgram_icon_bg_{id}" />
    <foreground android:drawable="@drawable/glassgram_icon_fg_{id}" />
    <monochrome android:drawable="@drawable/glassgram_icon_monochrome" />
</adaptive-icon>
'''


def main():
    preview = None
    if '--preview' in sys.argv:
        preview = sys.argv[sys.argv.index('--preview') + 1]
    tmp = tempfile.mkdtemp()
    columns = 8
    bgs = os.path.join(tmp, 'bgs.png')
    fgs = os.path.join(tmp, 'fgs.png')
    render_sheet([icon[3] for icon in ICONS], columns, bgs)
    render_sheet([icon[4] for icon in ICONS], columns, fgs)

    if preview:
        # bg + fg, cut to the squircle a launcher shows
        both = os.path.join(tmp, 'both.png')
        run(['convert', bgs, fgs, '-composite', both])
        run(['convert', both, '-resize', '50%', preview])
        return

    nodpi = os.path.join(RES, 'drawable-nodpi')
    anydpi = os.path.join(RES, 'mipmap-anydpi-v26')
    for i, (icon_id, title, _, _, _) in enumerate(ICONS):
        bg_png = os.path.join(tmp, f'bg_{icon_id}.png')
        fg_png = os.path.join(tmp, f'fg_{icon_id}.png')
        crop(bgs, i, columns, bg_png)
        crop(fgs, i, columns, fg_png)
        webp(bg_png, os.path.join(nodpi, f'glassgram_icon_bg_{icon_id}.webp'), 86)
        webp(fg_png, os.path.join(nodpi, f'glassgram_icon_fg_{icon_id}.webp'), 92)
        for suffix in ('', '_round'):
            with open(os.path.join(anydpi, f'glassgram_{icon_id}_launcher{suffix}.xml'), 'w') as f:
                f.write(ADAPTIVE.format(id=icon_id, title=title))
        for density, size in DENSITIES.items():
            folder = os.path.join(RES, 'mipmap-' + density)
            legacy(bg_png, fg_png, size, 'square', os.path.join(folder, f'glassgram_{icon_id}_launcher.webp'), tmp)
            legacy(bg_png, fg_png, size, 'round', os.path.join(folder, f'glassgram_{icon_id}_launcher_round.webp'), tmp)
        print('icon', icon_id)


if __name__ == '__main__':
    main()
