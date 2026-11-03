"""Package the approved icon with Python 3 and ImageMagick 7 (no pip packages).

Run: python tools/generate_icons.py
Preserves the artwork; removes its backdrop and sizes it for Android's safe circle.
"""
import json
import math
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BRANDING = ROOT / "assets/branding"
RESOURCES = ROOT / "HyperFCMLive/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "dist/icon-preview"
BACKGROUND = "#C6E9FC"
SIZE = 432  # 108dp at xxxhdpi; XML wrappers fill the adaptive layer.
SAFE_RADIUS = 33 * 4
ART_RADIUS = 31.5 * 4  # Antialiasing margin inside the safe circle.


def magick(*args):
    return subprocess.run(["magick", *map(str, args)], check=True, stdout=subprocess.PIPE).stdout


def rgba(path):
    return magick(path, "-depth", "8", "rgba:-")


def visible_radius(data, size):
    center = (size - 1) / 2
    return max(math.hypot(i % size - center, i // size - center)
               for i, alpha in enumerate(data[3::4]) if alpha > 8)


def main():
    if not shutil.which("magick"):
        raise SystemExit("ImageMagick 7 is required; nothing is installed automatically.")
    RESOURCES.mkdir(parents=True, exist_ok=True)
    PREVIEW.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hyperfcm-icons-") as temporary:
        temp = Path(temporary)
        source = BRANDING / "icon-source.png"
        cutout = temp / "cutout.png"
        width, height = map(int, magick(source, "-format", "%w %h", "info:").split())
        if width != height:
            raise ValueError("The approved artwork must be square.")
        # Flood only the connected blue backdrop; preserve the blue sticker layer.
        magick(source, "-alpha", "set", "-fuzz", "4%", "-fill", "none",
               "-draw", "color 0,0 floodfill", cutout)
        source_rgba = rgba(cutout)
        scaled_size = math.floor(width * ART_RADIUS / visible_radius(source_rgba, width))
        foreground = RESOURCES / "ic_launcher_artwork.png"
        magick(cutout, "-filter", "Lanczos", "-resize", f"{scaled_size}x{scaled_size}",
               "-gravity", "center", "-background", "none", "-extent", f"{SIZE}x{SIZE}",
               "-depth", "8", "-strip", foreground)
        # Keep lettering and outline in themed mode, rather than a blank silhouette.
        mono = bytearray(len(source_rgba))
        for offset in range(0, len(source_rgba), 4):
            r, g, b, alpha = source_rgba[offset:offset + 4]
            saturation = (max(r, g, b) - min(r, g, b)) / max(1, r, g, b)
            ink = max(0, min(1, (saturation - 0.40) / 0.08))
            mono[offset:offset + 4] = bytes((255, 255, 255, round(alpha * ink)))
        raw = temp / "monochrome.rgba"
        raw.write_bytes(mono)
        monochrome = RESOURCES / "ic_launcher_themed.png"
        magick("-size", f"{width}x{height}", "-depth", "8", f"rgba:{raw}",
               "-filter", "Lanczos", "-resize", f"{scaled_size}x{scaled_size}",
               "-gravity", "center", "-background", "none", "-extent", f"{SIZE}x{SIZE}",
               "-depth", "8", "-strip", monochrome)
        report = {"layer_px": SIZE, "layer_dp": 108, "safe_radius_dp": 33,
                  "source_resize_px": scaled_size, "background": BACKGROUND}
        for name, path in (("color", foreground), ("themed", monochrome)):
            radius = visible_radius(rgba(path), SIZE)
            if radius >= SAFE_RADIUS:
                raise ValueError(f"{name} artwork exceeds the adaptive safe circle")
            report[f"{name}_radius_dp"] = round(radius / 4, 3)
        # At rest, AdaptiveIconDrawable shows the middle 72dp of the 108dp layer.
        full = temp / "full.png"
        magick("-size", f"{SIZE}x{SIZE}", f"xc:{BACKGROUND}", foreground,
               "-composite", "-crop", "288x288+72+72", "+repage", "-resize", "512x512", full)
        themed_full = temp / "themed-full.png"
        magick(monochrome, "-channel", "RGB", "-fill", "#274A68", "-colorize", "100",
               "+channel", "-background", "#D8E9F6", "-alpha", "remove", "-alpha", "off",
               "-crop", "288x288+72+72", "+repage", "-resize", "512x512", themed_full)
        cards = []
        for name, label, source_image in (
            ("rounded-square", "Rounded square", full),
            ("circle", "Circle", full),
            ("themed", "Themed", themed_full),
        ):
            mask = temp / f"{name}-mask.png"
            shape = "circle 255.5,255.5 255.5,0" if name == "circle" else "roundrectangle 0,0 511,511 120,120"
            magick("-size", "512x512", "xc:black", "-fill", "white", "-draw", shape, mask)
            output = PREVIEW / f"{name}.png"
            magick(source_image, mask, "-alpha", "off", "-compose", "CopyOpacity", "-composite",
                   "-depth", "8", "-strip", output)
            if name == "rounded-square":
                shutil.copyfile(output, BRANDING / "icon.png")
            small = temp / f"{name}-small.png"
            magick(output, "-resize", "48x48", small)
            card = temp / f"{name}-card.png"
            magick("-size", "280x368", "xc:#F5F7FA", "(", output, "-resize", "232x232", ")",
                   "-geometry", "+24+16", "-composite", small, "-geometry", "+116+266", "-composite",
                   "-font", "Arial", "-pointsize", "18", "-fill", "#26354B", "-gravity", "South",
                   "-annotate", "+0+14", label, card)
            cards.append(card)
        magick(*cards, "+append", PREVIEW / "launcher-preview.png")
        (PREVIEW / "verification.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
