#!/usr/bin/env python3
"""Renders the TV banner PNGs from docs/brand/banner-art.svg plus the Inter wordmark.

    python3 tools/render-brand.py          # needs rsvg-convert and Pillow

The square launcher icon is a vector adaptive icon (res/drawable/ic_launcher_*.xml) and needs no
rendering; docs/brand/icon.svg is its source of truth for the website and README.
"""
import os
import subprocess
import tempfile

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "docs/brand/banner-art.svg")
FONT = os.path.join(ROOT, "app/src/main/res/font/inter.ttf")
RES = os.path.join(ROOT, "app/src/main/res")


def banner(scale):
    w, h = 320 * scale, 180 * scale
    with tempfile.NamedTemporaryFile(suffix=".png") as tmp:
        subprocess.run(["rsvg-convert", "-w", str(w), "-h", str(h), ART, "-o", tmp.name], check=True)
        img = Image.open(tmp.name).convert("RGBA")
    font = ImageFont.truetype(FONT, 50 * scale)
    font.set_variation_by_axes([32, 650])  # optical size (display), weight
    draw = ImageDraw.Draw(img)
    text = "Aerial"
    left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
    x = 132 * scale
    y = (h - (bottom - top)) / 2 - top + 2 * scale
    draw.text((x, y), text, font=font, fill=(255, 255, 255, 255))
    return img


if __name__ == "__main__":
    for density, scale in (("xhdpi", 2), ("xxhdpi", 3)):
        out = os.path.join(RES, f"drawable-{density}", "banner.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        banner(scale).convert("RGB").save(out, optimize=True)
        print(out)
