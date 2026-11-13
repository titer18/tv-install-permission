from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


SIZE = 512
SCALE = 4
W = SIZE * SCALE
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "res" / "drawable" / "ic_launcher.png"


def s(v):
    return int(round(v * SCALE))


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

# Background rounded square with an indigo-to-blue gradient.
margin = s(30)
radius = s(104)
top = (91, 113, 255)
bottom = (22, 78, 190)
for y in range(margin, W - margin):
    t = (y - margin) / (W - 2 * margin - 1)
    draw.line([(margin, y), (W - margin - 1, y)], fill=lerp(top, bottom, t))

bg_mask = Image.new("L", (W, W), 0)
ImageDraw.Draw(bg_mask).rounded_rectangle(
    [margin, margin, W - margin, W - margin], radius=radius, fill=255
)
img.putalpha(bg_mask)

# Soft top-left light to give the square some depth.
glow = Image.new("RGBA", (W, W), (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse(
    [s(30), s(20), s(330), s(300)], fill=(255, 255, 255, 52)
)
glow = glow.filter(ImageFilter.GaussianBlur(s(60)))
img = Image.alpha_composite(img, glow)

# Shadow behind the central badge.
shadow = Image.new("RGBA", (W, W), (0, 0, 0, 0))
ImageDraw.Draw(shadow).ellipse(
    [s(104), s(104), s(408), s(408)], fill=(5, 16, 46, 120)
)
shadow = shadow.filter(ImageFilter.GaussianBlur(s(16)))
img = Image.alpha_composite(img, shadow)

# Central white badge.
badge = Image.new("RGBA", (W, W), (0, 0, 0, 0))
ImageDraw.Draw(badge).ellipse(
    [s(118), s(118), s(394), s(394)], fill=(246, 250, 255, 255)
)
img = Image.alpha_composite(img, badge)

# Thin inner ring.
ring = Image.new("RGBA", (W, W), (0, 0, 0, 0))
ImageDraw.Draw(ring).ellipse(
    [s(132), s(132), s(380), s(380)], outline=(37, 99, 235, 70), width=s(3)
)
img = Image.alpha_composite(img, ring)

# Green rounded check mark.
check = Image.new("RGBA", (W, W), (0, 0, 0, 0))
cd = ImageDraw.Draw(check)
width = s(30)
p1 = (s(176), s(258))
p2 = (s(226), s(310))
p3 = (s(340), s(196))
cd.line([p1, p2, p3], fill=(22, 163, 74, 255), width=width, joint="curve")
for p in (p1, p3):
    r = width // 2
    cd.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=(22, 163, 74, 255))
img = Image.alpha_composite(img, check)

img = img.resize((SIZE, SIZE), Image.LANCZOS)
OUT.parent.mkdir(parents=True, exist_ok=True)
img.save(OUT)
print("saved", OUT)
