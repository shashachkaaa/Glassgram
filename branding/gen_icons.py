"""Regenerates Glassgram's launcher icons with the solid pointer arrow (no paper-plane fold).

Writes the adaptive foreground/monochrome vectors, the badge glyphs, and renders the icon picker
foregrounds, the legacy launcher PNGs and the branding logo through headless Chromium.
Usage: python3 gen_icons.py <repo root>
"""
import os
import subprocess
import sys
import tempfile

REPO = sys.argv[1]
RES = os.path.join(REPO, 'TMessagesProj/src/main/res')
CHROME = '/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell'
if not os.path.exists(CHROME):
    CHROME = '/opt/pw-browsers/chromium'

# The arrow in the 108 icon space: tip, left wing, notch, right wing; symmetric about the tip-back axis
TIP, LEFT, NOTCH, RIGHT = (75, 33), (33, 50), (54.35, 53.65), (58, 75)
BACK = (45.5, 62.5)  # middle of the wings, where the gradient ends
PATH = 'M%g,%g L%g,%g L%g,%g L%g,%g Z' % (TIP + LEFT + NOTCH + RIGHT)
STROKE = 5

# variant: (tip color, back color, background stops, highlight alpha)
VARIANTS = {
    '':       ('FFFFFF', 'C9C9D6', ('3A3A44', '16161C'), 0x38),
    'dark':   ('1E1F26', '6B7080', ('FFFFFF', 'E3E5EC'), 0x00),
    'orange': ('FFFFFF', 'FFE0C7', ('FFA24C', 'F0592B'), 0x38),
    'teal':   ('FFFFFF', 'CCF5EF', ('2DD4BF', '0E7490'), 0x38),
    'pink':   ('FFFFFF', 'FFD3E2', ('FF6B9C', 'C9235F'), 0x38),
    'violet': ('FFFFFF', 'E2D6FF', ('9B6BFF', '5B2BD6'), 0x38),
}
# launcher name -> foreground variant
LAUNCHERS = {'ic_launcher': '', 'icon_2_launcher': 'dark', 'icon_3_launcher': 'orange',
             'icon_4_launcher': 'teal', 'icon_5_launcher': 'pink', 'icon_6_launcher': 'violet'}
# picker foreground name -> variant
PICKS = {'dark': '', 'light': 'dark', 'orange': 'orange', 'teal': 'teal', 'pink': 'pink', 'violet': 'violet'}
DENSITIES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}


def vector_foreground(tip, back):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Glassgram launcher icon: a solid pointer arrow, lighter at the tip, inside the 66dp safe zone. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="{PATH}"
        android:strokeWidth="{STROKE}"
        android:strokeLineJoin="round">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="{TIP[0]}" android:startY="{TIP[1]}" android:endX="{BACK[0]}" android:endY="{BACK[1]}">
                <item android:offset="0" android:color="#FF{tip}" />
                <item android:offset="1" android:color="#FF{back}" />
            </gradient>
        </aapt:attr>
        <aapt:attr name="android:strokeColor">
            <gradient android:type="linear" android:startX="{TIP[0]}" android:startY="{TIP[1]}" android:endX="{BACK[0]}" android:endY="{BACK[1]}">
                <item android:offset="0" android:color="#FF{tip}" />
                <item android:offset="1" android:color="#FF{back}" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''


MONOCHROME = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Glassgram launcher icon: monochrome layer for themed icons (Android 13+). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="{PATH}"
        android:fillColor="#FFFFFFFF"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="{STROKE}"
        android:strokeLineJoin="round" />
</vector>
'''


def badge_glyph(dx, dy, extra, comment):
    # the icon arrow mapped into the 24 badge space (x' = 0.2893x - 4.13, y' = 0.2893y - 3.12)
    def m(p):
        return (round(0.2893 * p[0] - 4.13 + dx, 2), round(0.2893 * p[1] - 3.12 + dy, 2))
    pts = m(TIP) + m(LEFT) + m(NOTCH) + m(RIGHT)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Glassgram badge glyph ({comment}), drawn white on the badge's shape -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:pathData="M{pts[0]},{pts[1]} L{pts[2]},{pts[3]} L{pts[4]},{pts[5]} L{pts[6]},{pts[7]} Z"
        android:fillColor="#FFFFFFFF"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.4"
        android:strokeLineJoin="round" />{extra}
</vector>
'''


SUPPORTER_STAR = '''
    <path
        android:pathData="M7.30,13.50 L8.09,15.61 L10.34,15.71 L8.58,17.12 L9.18,19.29 L7.30,18.05 L5.42,19.29 L6.02,17.12 L4.26,15.71 L6.51,15.61 Z"
        android:fillColor="#FFFFFFFF"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="0.6"
        android:strokeLineJoin="round" />'''


def svg_arrow(tip, back, gid):
    return (f'<defs><linearGradient id="a{gid}" gradientUnits="userSpaceOnUse" x1="{TIP[0]}" y1="{TIP[1]}" x2="{BACK[0]}" y2="{BACK[1]}">'
            f'<stop offset="0" stop-color="#{tip}"/><stop offset="1" stop-color="#{back}"/></linearGradient></defs>'
            f'<path d="{PATH}" fill="url(#a{gid})" stroke="url(#a{gid})" stroke-width="{STROKE}" stroke-linejoin="round"/>')


def svg_background(stops, hl, gid):
    return (f'<defs><linearGradient id="b{gid}" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="108" y2="108">'
            f'<stop offset="0" stop-color="#{stops[0]}"/><stop offset="1" stop-color="#{stops[1]}"/></linearGradient>'
            f'<radialGradient id="h{gid}" gradientUnits="userSpaceOnUse" cx="30" cy="18" r="70">'
            f'<stop offset="0" stop-color="#FFFFFF" stop-opacity="{hl / 255:.3f}"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient></defs>'
            f'<rect width="108" height="108" fill="url(#b{gid})"/><rect width="108" height="108" fill="url(#h{gid})"/>')


def render(svg_body, view_box, size, out, clip=None):
    """Renders an SVG of size x size px to out (transparent outside the clip)."""
    clip_css = ''
    if clip == 'round':
        clip_css = 'border-radius:50%;'
    elif clip == 'square':
        clip_css = f'border-radius:{size * 0.225:.2f}px;'
    html = (f'<!doctype html><html><head><style>html,body{{margin:0;padding:0;background:transparent;overflow:hidden}}'
            f'svg{{display:block;width:{size}px;height:{size}px;{clip_css}}}</style></head><body>'
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view_box}">{svg_body}</svg></body></html>')
    with tempfile.NamedTemporaryFile('w', suffix='.html', delete=False) as f:
        f.write(html)
        page = f.name
    try:
        subprocess.run([CHROME, '--headless', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
                        '--default-background-color=00000000', f'--window-size={size},{size}',
                        f'--screenshot={out}', 'file://' + page],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
    finally:
        os.unlink(page)


def main():
    v26 = os.path.join(RES, 'drawable-anydpi-v26')
    for variant, (tip, back, _, _) in VARIANTS.items():
        name = 'glassgram_icon_foreground' + ('_' + variant if variant else '') + '.xml'
        open(os.path.join(v26, name), 'w').write(vector_foreground(tip, back))
    open(os.path.join(v26, 'glassgram_icon_monochrome.xml'), 'w').write(MONOCHROME)

    drawable = os.path.join(RES, 'drawable')
    open(os.path.join(drawable, 'glassgram_badge_arrow.xml'), 'w').write(badge_glyph(0, 0, '', 'arrow'))
    open(os.path.join(drawable, 'glassgram_badge_supporter.xml'), 'w').write(
        badge_glyph(0.6, -0.6, SUPPORTER_STAR, 'supporter: the Glassgram arrow with a small star'))

    # Icon picker foregrounds: the whole 108 space at 324 px, on transparency
    for pick, variant in PICKS.items():
        tip, back, _, _ = VARIANTS[variant]
        render(svg_arrow(tip, back, 'p'), '0 0 108 108', 324,
               os.path.join(RES, 'drawable-nodpi', f'glassgram_icon_pick_fg_{pick}.png'))

    # Legacy launcher icons (before Android 8): the visible middle of the adaptive icon
    for launcher, variant in LAUNCHERS.items():
        tip, back, stops, hl = VARIANTS[variant]
        body = svg_background(stops, hl, 'l') + svg_arrow(tip, back, 'l')
        for density, size in DENSITIES.items():
            folder = os.path.join(RES, 'mipmap-' + density)
            render(body, '17 17 74 74', size, os.path.join(folder, launcher + '.png'), 'square')
            render(body, '17 17 74 74', size, os.path.join(folder, launcher + '_round.png'), 'round')

    # Branding: the logo for avatars and the store, 1024 px, square
    tip, back, stops, hl = VARIANTS['']
    body = svg_background(stops, hl, 'x') + svg_arrow(tip, back, 'x')
    branding = os.path.join(REPO, 'branding')
    open(os.path.join(branding, 'glassgram-logo.svg'), 'w').write(
        '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="14 14 80 80">\n'
        '  <!-- Glassgram launcher icon (glassgram_icon_background + glassgram_icon_foreground), cropped for an avatar -->\n'
        f'  {body}\n</svg>\n')
    render(body, '14 14 80 80', 1024, os.path.join(branding, 'glassgram-logo.png'))


if __name__ == '__main__':
    main()
