from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

root = Path(__file__).resolve().parents[3]
source = root / "docs/branding/assets/fonts/BricolageGrotesque-Variable.ttf"
destination = root / "app/src/main/res/font"
destination.mkdir(parents=True, exist_ok=True)
for weight, label in [(700, "bold"), (800, "extra_bold")]:
    font = TTFont(source)
    font = instantiateVariableFont(
        font, {"wght": weight, "wdth": 100, "opsz": 24}, inplace=True
    )
    font.save(destination / f"bricolage_{label}.ttf")
