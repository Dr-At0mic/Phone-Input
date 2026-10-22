"""Render PmIcons.AppMark (24x24 viewport) to icon.png (Linux) and a multi-size icon.ico (Windows).

Usage: python make_icons.py composeApp/packaging   (needs Pillow)
"""
import sys
from PIL import Image, ImageDraw

ACCENT = (0x2D, 0xD4, 0xA7, 255)
ON_ACCENT = (0x04, 0x26, 0x1D, 255)
SS = 8  # supersampling


def render(size, margin=0.0):
    n = size * SS
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    inner = n * (1 - 2 * margin)
    off = n * margin
    u = inner / 24.0  # px per viewport unit

    def p(x, y):
        return (off + x * u, off + y * u)

    d.rounded_rectangle([p(0, 0), p(24, 24)], radius=6 * u, fill=ACCENT)

    # mic group: scale 0.66 about (12,12); stroke 2.6 scaled too
    s = 0.66

    def m(x, y):
        return p(12 + (x - 12) * s, 12 + (y - 12) * s)

    w = 2.6 * s * u
    r = w / 2

    def cap(pt):
        d.ellipse([pt[0] - r, pt[1] - r, pt[0] + r, pt[1] + r], fill=ON_ACCENT)

    def line(a, b):
        d.line([a, b], fill=ON_ACCENT, width=round(w))
        cap(a)
        cap(b)

    # capsule body x 9..15, y 2..14, r 3
    tl, br = m(9, 2), m(15, 14)
    d.rounded_rectangle([tl[0] - r, tl[1] - r, br[0] + r, br[1] + r], radius=3 * s * u + r, fill=ON_ACCENT)
    tl2, br2 = m(9, 2), m(15, 14)
    d.rounded_rectangle([tl2[0] + r, tl2[1] + r, br2[0] - r, br2[1] - r], radius=max(3 * s * u - r, 0), fill=ACCENT)
    # stand: v1 at x=5 and x=19 from y 10..11, lower semicircle r7 centered (12,11)
    line(m(19, 10), m(19, 11))
    line(m(5, 10), m(5, 11))
    a, b = m(5, 4), m(19, 18)
    d.arc([a[0] - r, a[1] - r, b[0] + r, b[1] + r], 0, 180, fill=ON_ACCENT, width=round(w))
    # stem
    line(m(12, 18), m(12, 22))

    return img.resize((size, size), Image.LANCZOS)


out = sys.argv[1]
render(512).save(f"{out}/icon.png")
sizes = [256, 128, 64, 48, 32, 24, 16]
imgs = [render(sz, margin=0.03) for sz in sizes]
imgs[0].save(f"{out}/icon.ico", sizes=[(sz, sz) for sz in sizes], append_images=imgs[1:])
