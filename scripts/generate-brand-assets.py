#!/usr/bin/env python3
"""Render the Autunn Play geometric A mark. Requires Pillow; no downloaded artwork."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
BACKGROUND = "#DE2910"  # China red
ACCENT = "#F5F5F2"  # Mist white
INK = BACKGROUND


def mark(size, *, background=None, color=ACCENT, adaptive=False, round_icon=False):
    scale = 4
    image = Image.new("RGBA", (512 * scale, 512 * scale))
    draw = ImageDraw.Draw(image)
    if background:
        box = (0, 0, 512 * scale - 1, 512 * scale - 1)
        if round_icon:
            draw.ellipse(box, fill=background)
        else:
            draw.rounded_rectangle(box, radius=108 * scale, fill=background)
    # An A built from straight strokes. Adaptive launchers have a smaller safe zone.
    factor = 0.72 if adaptive else 1.0
    def point(x, y):
        return (round((256 + (x - 256) * factor) * scale),
                round((256 + (y - 256) * factor) * scale))
    draw.line([point(139, 379), point(256, 133), point(373, 379)],
              fill=color, width=round(51 * factor * scale), joint="curve")
    draw.line([point(192, 287), point(320, 287)], fill=color, width=round(43 * factor * scale))
    return image.resize((size, size), Image.Resampling.LANCZOS)


def save(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, lossless=True)


def generate(server):
    res = ROOT / "shared/src/main/res"
    for path in sorted(res.glob("mipmap-*/*.webp")):
        if not path.name.startswith("ic_launcher"):
            continue
        with Image.open(path) as old:
            size = old.width
        foreground = "foreground" in path.name or "monochrome" in path.name
        image = mark(size, background=None if foreground else BACKGROUND,
                     color="white" if "monochrome" in path.name else ACCENT,
                     adaptive=foreground, round_icon="round" in path.name)
        save(image, path)
    save(mark(512, background=BACKGROUND), ROOT / "shared/src/main/ic_launcher-playstore.png")
    for variant, color in [("drawable-nodpi", INK), ("drawable-night-nodpi", ACCENT)]:
        save(mark(512, color=color), ROOT / f"common/src/main/res/{variant}/ic_autunn_logo.png")
    save(mark(256, background=BACKGROUND), ROOT / "common/src/main/res/raw/ic_car_home.png")
    common_res = ROOT / "common/src/main/res"
    for path in sorted(common_res.glob("drawable*/ic_diplay_notification.png")):
        with Image.open(path) as old:
            size = old.width
        save(mark(size, color="white"), path)
    for path in sorted(common_res.glob("drawable*/ic_carplay.png")):
        with Image.open(path) as old:
            size = old.width
        save(mark(size, background=BACKGROUND), path)
    if server:
        server = Path(server)
        for name in ("app-icon.png", "icon.png"):
            save(mark(512, background=BACKGROUND), server / "site/assets" / name)
        for name, color in [("logo-light.png", INK), ("logo-dark.png", ACCENT)]:
            save(mark(256, color=color), server / "site/assets" / name)
        save(mark(128, background=BACKGROUND),
             server / "authorization-server/src/main/resources/static/admin/brand.png")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-root", help="Optional sibling server source root")
    generate(parser.parse_args().server_root)
