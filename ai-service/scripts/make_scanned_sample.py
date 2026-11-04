"""Generate corpus/warranty-card-scanned.pdf: an IMAGE-ONLY PDF (no text layer) that imitates a scanned paper form.

Because the PDF contains only page images, text extraction without OCR returns (almost) nothing; only OCR can read it.
That makes it a real test of the Tika + Tesseract path. The content is a SAMPLE written for the demo corpus.

Needs the dev dependencies (pillow):  uv run python scripts/make_scanned_sample.py
"""
from __future__ import annotations

import random
import textwrap
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(__file__).resolve().parents[1] / "corpus" / "warranty-card-scanned.pdf"
FONT_CANDIDATES = ["C:/Windows/Fonts/arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                   "/Library/Fonts/Arial.ttf"]

PAGES = [
    ("DSPORTS WARRANTY CARD",
     ["SAMPLE DOCUMENT - written for the DSports demo knowledge base. Not manufacturer documentation.",
      "",
      "Cricket bats: the limited warranty lasts 90 days from the delivery date and covers manufacturing defects.",
      "Footwear: the limited warranty lasts 30 days from the delivery date and covers sole separation and stitching defects.",
      "",
      "Not covered: damage from misuse, use with a hard tennis ball, water damage, normal wear and tear, and cracks "
      "caused by using a bat that was not knocked in."]),
    ("HOW TO MAKE A WARRANTY CLAIM",
     ["1. Keep your order number. It is needed to find your purchase.",
      "2. Report the defect within 7 days of finding it.",
      "3. Send clear photos of the defect.",
      "4. Your claim gets a reference that starts with DSW and a decision is made after inspection.",
      "",
      "A warranty claim is not a return. For returns see the Return Policy: returns can be requested within "
      "7 days of delivery."]),
]


def _font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    raise SystemExit("No TrueType font found; set FONT_CANDIDATES")


def render(title: str, lines: list[str], seed: int) -> Image.Image:
    rng = random.Random(seed)
    img = Image.new("L", (1240, 1754), 245)
    draw = ImageDraw.Draw(img)
    draw.text((90, 110), title, font=_font(50), fill=20)
    y = 240
    body = _font(34)
    for line in lines:
        for wrapped in textwrap.wrap(line, width=58) or [""]:
            draw.text((90, y), wrapped, font=body, fill=30)
            y += 52
        y += 14
    img = img.rotate(rng.uniform(-0.8, 0.8), expand=False, fillcolor=245, resample=Image.BICUBIC)
    noise = Image.effect_noise(img.size, 14).point(lambda v: v - 128 if v > 128 else 0)
    img = Image.composite(img, Image.blend(img, noise.convert("L"), 0.06), Image.new("L", img.size, 255))
    return img.filter(ImageFilter.GaussianBlur(0.6))


def main() -> None:
    images = [render(title, lines, seed=i) for i, (title, lines) in enumerate(PAGES, start=1)]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    images[0].save(OUT, "PDF", resolution=150.0, save_all=True, append_images=images[1:])
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes, {len(images)} pages, image-only)")


if __name__ == "__main__":
    main()
