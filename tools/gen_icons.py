
import os
from PIL import Image, ImageDraw

BASE = r"E:\dsh workspace\utv\app\src\main\res"
SS = 8  # supersample factor
V = 108.0

def draw_icon(size):
    W = int(size * SS)
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = W / V  # viewport -> pixels

    cyan = (0x38, 0xBD, 0xF8, 255)
    green = (0x10, 0xB9, 0x81, 255)

    # TV frame: rounded rect outline
    l, t, r, b = 20 * s, 22 * s, 96 * s, 80 * s
    rad = 8 * s
    stroke = max(1, int(round(6.5 * s)))
    d.rounded_rectangle([l, t, r, b], radius=rad, outline=cyan, width=stroke)

    # neck
    d.rectangle([52 * s, 80 * s, 60 * s, 87 * s], fill=cyan)
    # stand bar with rounded ends
    d.rounded_rectangle([34 * s, 87 * s, 74 * s, 93 * s], radius=3 * s, fill=cyan)

    # play triangle
    d.polygon([(45 * s, 38 * s), (69 * s, 51 * s), (45 * s, 64 * s)], fill=green)

    return img.resize((size, size), Image.LANCZOS)

for bucket, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    out_dir = os.path.join(BASE, "drawable-" + bucket)
    os.makedirs(out_dir, exist_ok=True)
    p = os.path.join(out_dir, "ic_launcher.png")
    draw_icon(size).save(p, "PNG", optimize=True)
    print("wrote", p, size)

prev = draw_icon(192)
prev.save(r"E:\dsh workspace\utv\dist\icon-preview.png")
print("preview ok")
