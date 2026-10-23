"""Kukátko's banner and icon, drawn from scratch (no third party logo).

Banner: "kukátko" in Outfit over a gold sunrise arc, on a red sky. Icon: a peephole (white ring, a
sunrise seen through it). Same red sky as the sister app Dželi Beli.

Renders HTML with headless Chrome and writes app/src/main/res/drawable-xxxhdpi/banner.png
(1280x720, = 320x180 dp) and icon.png (192x192, = 48 dp).
Run: python -I tools/logo/make_logo.py

To use other pictures only on your own machine, put banner.png / icon.png into
local-branding/res/drawable-xxxhdpi/ (ignored by git); local builds then use them.
"""
import subprocess
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res/drawable-xxxhdpi"
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

SKY = "radial-gradient(120% 60% at 50% 115%, #f0a040 0%, #b0402a 18%, #8a0f14 45%, #4a0508 75%, #240204 100%)"
GOLD = '<linearGradient id="gold" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#FFE9A0"/>' \
       '<stop offset=".5" stop-color="#FFC83D"/><stop offset="1" stop-color="#E69B00"/></linearGradient>'


def arc(width):
    # A thin gold crescent, like a sun rising over the horizon.
    return (f'<svg width="{width}" viewBox="0 0 300 40"><defs>{GOLD}</defs>'
            f'<path d="M4 34 Q 150 -18 296 34 Q 150 8 4 34Z" fill="url(#gold)"/></svg>')


def peephole(size):
    # The view through the hole: a low sun over a dark horizon, a small glint on the glass.
    return f"""<svg width="{size}" height="{size}" viewBox="0 0 100 100">
<defs>
<radialGradient id="view" cx="50%" cy="88%" r="85%">
<stop offset="0" stop-color="#fff0b0"/><stop offset="0.2" stop-color="#ffc24d"/>
<stop offset="0.5" stop-color="#e0662a"/><stop offset="1" stop-color="#7a1018"/></radialGradient>
<clipPath id="hole"><circle cx="50" cy="50" r="38"/></clipPath>
</defs>
<circle cx="50" cy="50" r="38" fill="url(#view)"/>
<g clip-path="url(#hole)"><path d="M0 74 Q 22 66 40 72 T 100 70 V100 H0Z" fill="#240204" opacity=".85"/></g>
<ellipse cx="36" cy="33" rx="9" ry="5" transform="rotate(-35 36 33)" fill="#fff" opacity=".35"/>
<circle cx="50" cy="50" r="42" fill="none" stroke="#fff" stroke-width="8"/>
</svg>"""


def page(w, h, content):
    return f"""<!doctype html><html><head><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@500&display=block" rel="stylesheet"><style>
html,body{{margin:0;width:{w}px;height:{h}px;overflow:hidden}}
body{{background:{SKY};display:flex;flex-direction:column;align-items:center;justify-content:center;
font-family:Outfit,sans-serif;font-weight:500;color:white;line-height:1}}
</style></head><body>{content}</body></html>"""


def render(content, w, h, scale):
    with tempfile.TemporaryDirectory() as tmp:
        page_file = Path(tmp) / "tile.html"
        page_file.write_text(page(w, h, content), encoding="utf-8")
        shot = Path(tmp) / "tile.png"
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--virtual-time-budget=8000",
                        f"--force-device-scale-factor={scale}", f"--window-size={w},{h}", f"--screenshot={shot}",
                        page_file.as_uri()], check=True, capture_output=True, timeout=60)
        return Image.open(shot).convert("RGB")


RES.mkdir(parents=True, exist_ok=True)
banner = render('<div style="font-size:112px;letter-spacing:-1px">kukátko</div>'
                f'<div style="margin-top:-4px">{arc(330)}</div>', 640, 360, 2)
banner.save(RES / "banner.png", optimize=True)
icon = render(peephole(400), 512, 512, 1).resize((192, 192), Image.LANCZOS)
icon.save(RES / "icon.png", optimize=True)
print(f"banner {banner.size}, icon {icon.size} -> {RES}")
