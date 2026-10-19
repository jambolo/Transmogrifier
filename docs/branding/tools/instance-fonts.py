from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

root = Path(__file__).resolve().parents[1] / "assets" / "fonts"
for weight, label in [(400, "Regular"), (500, "Medium"), (600, "SemiBold"), (700, "Bold"), (800, "ExtraBold")]:
    font = TTFont(root / "Manrope-Variable.ttf")
    instance = instantiateVariableFont(font, {"wght": weight}, inplace=True, updateFontNames=True)
    instance.save(root / f"Manrope-{label}.ttf")
