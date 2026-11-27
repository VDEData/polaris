#!/usr/bin/env python3
"""The Android launcher icon's star, cut from the iOS app icon.

Reads Polaris/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png, keeps the gold star
(by colour: the backdrop's greys have no gold in them) and writes it as the adaptive icon's
foreground at each density, 58 dp tall in the 108 dp canvas so every mask shape keeps it whole.
The backdrop (paper, rings and spokes) is drawn by drawable/ic_launcher_background.xml at the
same scale. Needs Pillow. Run from the repo root:

    python3 scripts/make-android-icon.py
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "Polaris/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
RES = ROOT / "android/app/src/main/res"
CANVAS_DP, STAR_DP = 108, 58
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def star(source: Image.Image) -> Image.Image:
    """The star alone: its own gold, with how gold each pixel is as its alpha."""
    rgb = source.convert("RGB")
    red, _, blue = rgb.split()
    # Gold is far redder than blue; the paper, rings and dots are grey-blue (red at or under blue).
    gold = max(rgb.getdata(), key=lambda p: p[0] - p[2])
    span = gold[0] - gold[2]
    alpha = Image.eval(Image.merge("RGB", (red, blue, blue)), lambda v: v).convert("RGB")
    mask = Image.new("L", rgb.size)
    mask.putdata([max(0, min(255, round((r - b) * 255 / span))) for r, b in zip(red.getdata(), blue.getdata())])
    del alpha
    out = Image.new("RGBA", rgb.size, gold + (0,))
    out.putalpha(mask)
    return out.crop(mask.point(lambda v: 255 if v > 8 else 0).getbbox())


def main() -> None:
    cut = star(Image.open(SOURCE))
    print(f"star {cut.width}x{cut.height} px of {SOURCE.name}")
    for name, scale in DENSITIES.items():
        canvas = round(CANVAS_DP * scale)
        height = round(STAR_DP * scale)
        width = round(cut.width * height / cut.height)
        layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        layer.alpha_composite(cut.resize((width, height), Image.LANCZOS), ((canvas - width) // 2, (canvas - height) // 2))
        folder = RES / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)
        layer.save(folder / "ic_launcher_foreground.png", optimize=True)
        print(f"{name}: {canvas} px, star {width}x{height}")


if __name__ == "__main__":
    main()
