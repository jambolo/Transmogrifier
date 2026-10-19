# Asset inventory and provenance

All paths below are relative to this directory. Assets are prepared for review and implementation; no live application or account has been updated.

## Logos

| File | Format and use |
| --- | --- |
| `logos/logo-primary.svg` | 512 × 112, outlined ink lockup, transparent background |
| `logos/logo-reverse.svg` | 512 × 112, outlined paper lockup, transparent background |
| `logos/logo-black.svg` | 512 × 112, true black one-color lockup |
| `logos/logo-stacked.svg` | 512 × 250, centered symbol and wordmark |
| `logos/wordmark.svg` | 400 × 92, outlined type only |
| `logos/logo-editable.svg` | Text-editable master; Bricolage Grotesque required |
| `logos/symbol-ink.svg` | 96 × 96 symbol |
| `logos/symbol-paper.svg` | Light symbol for dark surfaces |
| `logos/symbol-citrus.svg` | Accent symbol for dark surfaces |
| `logos/logo-primary.png` / `logo-reverse.png` | Transparent raster lockups, 1520 px wide |

Clear space is external to the SVG viewport. Refer to [visual guidelines](../04-visual-identity.md) for minimum sizes and spacing. The reverse transparent PNG can look blank in a white image viewer; place it on ink.

## Icons

`icons/app-icon.svg` is a full-bleed 512 × 512 square master. PNG variants are supplied at 512, 192, and 180 px. `icons/favicon.svg` includes an outer rounded shape for browser use, with 16, 32, and 48 px PNGs.

Twelve editable 24 × 24 functional SVGs: `plus`, `check`, `list`, `dice`, `shuffle`, `arrow-right`, `edit`, `delete`, `close`, `retry`, `play`, and `suitcase`. They have explicit ink strokes; recolor deliberately in the consuming component. Do not assume an externally loaded SVG inherits CSS `currentColor`.

`android/` contains adaptive foreground and monochrome VectorDrawables, a background color resource, and v26/v33 adaptive-icon definitions. Resource names are prefixed `brand_`. They are not copied into the app or launcher-tested.

## Illustration

| File | Origin | Use |
| --- | --- | --- |
| `illustrations/app-tiles.png` | Generated using the built-in imagegen tool, October 19, 2026 | Text-free campaign hero; 1536 × 1024 |
| `illustrations/tile-pattern.svg` | Original vector construction in this task | Decorative background; 600 × 600 |
| `illustrations/empty-state.svg` | Original vector construction in this task | Proposed empty-state illustration; 320 × 240 |

The final illustration prompt is preserved in [image-generation-prompt.txt](image-generation-prompt.txt). No reference image, existing character, stock photograph, external logo, or artist imitation was supplied. The generated image was inspected and copied into this package. It is conceptual artwork, not a product screenshot. The original generated PNG has not been retouched.

## Campaign exports

Each has an editable vector composition with outlined type and a matching full-size PNG:

| Base filename under `campaign/` | Dimensions | Intended placement |
| --- | --- | --- |
| `social-intro-1200x630` | 1200 × 630 | Landscape announcement / link-card draft |
| `social-game-night-1080` | 1080 × 1080 | Square use-case draft; explicitly labeled design concept |
| `social-lifecycle-1080` | 1080 × 1080 | Square brand-message draft |
| `feature-graphic-1024x500` | 1024 × 500 | Android store feature-graphic draft |

These dimensions are export targets, not a certification against future platform submission requirements. No official store badges, distribution links, testimonials, or release claims are embedded. Current setup and API-billing disclosures are present in each composition.

## Fonts and licenses

| Font | Source | Included license |
| --- | --- | --- |
| Manrope variable | [Google Fonts source](https://github.com/google/fonts/tree/main/ofl/manrope) | `fonts/Manrope-OFL.txt` |
| Bricolage Grotesque variable | [Google Fonts source](https://github.com/google/fonts/tree/main/ofl/bricolagegrotesque) | `fonts/BricolageGrotesque-OFL.txt` |

Downloaded October 19, 2026. Original copyright and SIL Open Font License text is retained verbatim. Keep the corresponding license with redistributed font files. Five static Manrope instances (400, 500, 600, 700, 800) were made with fontTools 4.67.0 for reliable SVG outlining. The browser preview uses the variable originals.

Vector geometry, layouts, copy, and build tools were authored for this repository. The repository's existing license remains in place; this package does not invent a different asset license or make a legal-exclusivity claim. The generated illustration's provenance is disclosed above; the font licenses govern the bundled font software.

## Source and reproducibility

[build-assets.cjs](../tools/build-assets.cjs) contains the original vector geometry, placement, copy, and palette. It regenerates outlined SVGs and PNGs from the bundled fonts. [instance-fonts.py](../tools/instance-fonts.py) regenerates static font instances. The generated raster hero is supplied directly and is not deterministically reproducible from its prompt.

Logo and campaign SVGs use outlines so they survive font substitution. Keep the editable master and build source for future changes. There are no linked remote images or web fonts in the local brand preview.
