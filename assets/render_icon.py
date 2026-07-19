"""
DJProxy brand asset renderer.
Renders the shield + receding-tunnel-rings icon (same geometry as the Android VectorDrawables
in app/src/main/res/drawable/ic_launcher_{background,foreground}.xml) as flattened raster PNGs,
for: legacy pre-API26 mipmap buckets, the round-icon variant, and the 512x512 Play Store icon.

Supersamples at 4x and downsamples with LANCZOS for clean anti-aliasing at 48dp.
"""
import math
from PIL import Image, ImageDraw

SS = 4  # supersample factor
BASE = 108  # matches the 108x108 vector viewport

# geometry (same numbers as the vector drawables' pathData, on a 0..108 canvas)
SHIELD = [(54, 23), (80, 32), (80, 60), (54, 86), (28, 60), (28, 32)]
RING_CENTER = (54, 53)
RINGS = [
    # (radius, rgba, width)
    (20, (11, 18, 32, 140), 2.4),
    (15, (234, 252, 255, 191), 2.4),
    (10, (234, 252, 255, 235), 2.6),
]
CORE_RADIUS = 4

BG_TOP_LEFT = (10, 15, 30)      # #0A0F1E
BG_BOTTOM_RIGHT = (19, 28, 51)  # #131C33
SHIELD_START = (34, 211, 238)   # #22D3EE
SHIELD_END = (109, 92, 246)     # #6D5CF6
CORE_COLOR = (255, 255, 255)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(len(a)))


def render_background(size_px):
    """Diagonal navy gradient, full bleed."""
    img = Image.new("RGB", (size_px, size_px))
    px = img.load()
    for y in range(size_px):
        for x in range(size_px):
            t = (x / (size_px - 1) + y / (size_px - 1)) / 2
            px[x, y] = lerp(BG_TOP_LEFT, BG_BOTTOM_RIGHT, t)
    return img


def scale_pt(pt, size_px):
    return (pt[0] / BASE * size_px, pt[1] / BASE * size_px)


def render_composite(size_px, round_mask=False):
    ss_size = size_px * SS
    img = render_background(ss_size).convert("RGBA")
    draw = ImageDraw.Draw(img, "RGBA")

    # shield facet: approximate the diagonal gradient fill by sampling per-scanline band color
    poly = [scale_pt(p, ss_size) for p in SHIELD]
    min_y = min(p[1] for p in poly)
    max_y = max(p[1] for p in poly)

    shield_mask = Image.new("L", (ss_size, ss_size), 0)
    ImageDraw.Draw(shield_mask).polygon(poly, fill=255)

    grad = Image.new("RGBA", (ss_size, ss_size))
    gpx = grad.load()
    span = (max_y - min_y) or 1
    min_x = min(p[0] for p in poly)
    max_x = max(p[0] for p in poly)
    xspan = (max_x - min_x) or 1
    for y in range(int(min_y), int(max_y) + 1):
        for x in range(int(min_x), int(max_x) + 1):
            if 0 <= x < ss_size and 0 <= y < ss_size:
                t = ((x - min_x) / xspan + (y - min_y) / span) / 2
                gpx[x, y] = lerp(SHIELD_START, SHIELD_END, max(0.0, min(1.0, t))) + (255,)
    img.paste(grad, (0, 0), shield_mask)

    # crisp dark shield edge
    draw.line(poly + [poly[0]], fill=(11, 18, 32, 90), width=max(1, int(2 * SS / 2)))

    # tunnel rings
    cx, cy = scale_pt(RING_CENTER, ss_size)
    for radius, rgba, width in RINGS:
        r = radius / BASE * ss_size
        w = max(1, int(width * SS / 2))
        bbox = [cx - r, cy - r, cx + r, cy + r]
        draw.ellipse(bbox, outline=rgba, width=w)

    # vanishing-point core dot
    cr = CORE_RADIUS / BASE * ss_size
    draw.ellipse([cx - cr, cy - cr, cx + cr, cy + cr], fill=CORE_COLOR + (255,))

    img = img.resize((size_px, size_px), Image.LANCZOS)

    if round_mask:
        mask = Image.new("L", (size_px, size_px), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size_px, size_px], fill=255)
        out = Image.new("RGBA", (size_px, size_px), (0, 0, 0, 0))
        out.paste(img, (0, 0), mask)
        return out

    return img


if __name__ == "__main__":
    import os

    out_dir = os.path.dirname(os.path.abspath(__file__))
    res_dir = os.path.join(out_dir, "..", "app", "src", "main", "res")

    buckets = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    for folder, px in buckets.items():
        d = os.path.join(res_dir, folder)
        os.makedirs(d, exist_ok=True)
        render_composite(px, round_mask=False).save(os.path.join(d, "ic_launcher.png"))
        render_composite(px, round_mask=True).save(os.path.join(d, "ic_launcher_round.png"))
        print(f"wrote {folder} @ {px}px")

    store = render_composite(512, round_mask=False)
    store.save(os.path.join(out_dir, "djproxy_store_icon_512.png"))
    print("wrote djproxy_store_icon_512.png")
