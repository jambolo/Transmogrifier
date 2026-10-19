# Visual identity

## Direction

Bright utility. Useful little objects, bold editorial type, warm space, and one sharp citrus accent. The attitude is playful and competent. Keep the composition simple enough that the task remains the most interesting thing on screen.

The [brand preview](brand-preview.html) shows the identity, type, palette, concept UI, and campaign applications together.

## Symbol and wordmark

The **pop-out tile** is a rounded app shape with a small detached corner. It suggests a small thing taking shape and moving into use. It is deliberately independent of the product's initial letter, so a future name can reuse it.

| Asset | Use |
| --- | --- |
| `logo-primary.svg` | Default horizontal lockup on light backgrounds |
| `logo-reverse.svg` | Paper-colored lockup on ink |
| `logo-black.svg` | One-color printing |
| `logo-stacked.svg` | Centered, taller compositions |
| `wordmark.svg` | Name only, when the symbol already appears nearby |
| `symbol-ink.svg` | Compact identity use |
| `symbol-paper.svg` / `symbol-citrus.svg` | Symbol on dark backgrounds |
| `logo-editable.svg` | Text-editable master; requires the supplied Bricolage font |

Files are in [assets/logos](assets/logos/). Final SVG logos use outlined type; no font installation is needed to display them. Keep the editable master for an approved future rename.

**Clear space:** at least one detached-corner width around the visible artwork. In the 96-unit symbol, that width is 20 units. The exported viewport does not include the full external clear space; add it in the layout.

**Minimum sizes:** horizontal logo 200 CSS px or 42 mm wide; stacked logo 160 px wide; standalone symbol 24 px. Use the supplied favicon below 24 px. Do not add the tagline to a small logo. On narrow app bars, use a normal text title rather than squeezing the full lockup.

Keep proportions, spacing, and the detached corner intact. Do not rotate the logo, add faces, put it inside another emblem, add shadows to it, or use the symbol as a delete icon. Rotations and hard shadows belong to decorative app cards only.

## Palette

| Name | Hex | Role |
| --- | --- | --- |
| Ink | `#252336` | Main text, controls, reverse backgrounds |
| Paper | `#FFF9F0` | Main light background |
| Citrus | `#E9F56A` | Signature accent, app icon, prominent containers |
| Coral | `#FF795E` | Warm campaign accent |
| Sky | `#B8DEF0` | Secondary utility cards |
| Lilac | `#D5C7F4` | Tertiary utility cards |
| Muted | `#615E6C` | Secondary text on light surfaces |
| Outline | `#817B89` | Meaningful light-theme borders; not small text |
| Error | `#A52D37` | Error text and destructive controls on light surfaces |
| Success | `#236247` | Success text on light surfaces |
| Dark surface | `#333044` | Raised surfaces in dark UI |

A typical light composition is roughly 65% paper, 20% ink, 10% citrus, and 5% other accents. This is a composition guide, not a per-screen formula. UI uses less color than campaign art. Use a single accent on a functional card.

## Contrast

Calculated with relative sRGB luminance; the machine-readable results are in [contrast.json](design-tokens/contrast.json).

| Text / background | Ratio | Use |
| --- | --- | --- |
| Ink / paper | 14.62:1 | Normal text |
| Ink / citrus | 12.92:1 | Normal text |
| Ink / coral | 5.94:1 | Normal text |
| Ink / sky | 10.74:1 | Normal text |
| Ink / lilac | 9.70:1 | Normal text |
| Muted / paper | 6.03:1 | Secondary text |
| Paper / dark surface | 12.18:1 | Dark UI text |
| Error / paper | 6.62:1 | Error text |
| Outline / paper | 3.91:1 | Meaningful control boundaries |
| White / coral | 2.58:1 | Do not use for text |
| White / citrus | 1.18:1 | Do not use for text |

Use ink text on every pastel and citrus surface. Color alone never communicates status. Borders must be visible against the actual adjacent color; the supplied outline is checked on paper and white, not every accent. Focus indicators use ink on light UI and citrus on dark UI with a contrasting gap.

These color calculations are not a certification of the product's accessibility. Validate actual components, font scaling, screen readers, and disabled states when implementing.

## Typography

**Bricolage Grotesque** gives headlines and the wordmark a distinctive, slightly mischievous shape. Use 700–800 weight for display work. Use **Manrope** for body text, labels, and controls. Fallback: the platform sans-serif.

| Style | Size / line height | Weight | Use |
| --- | --- | --- | --- |
| Display | 64 / 64 | 800 | Wide campaign headlines; reduce to 40 / 42 on phones |
| Heading | 40 / 44 | 700 | Section titles |
| Title | 28 / 34 | 700 | App screens and cards |
| Body | 16 / 24 | 400 | Reading text |
| Label | 14 / 20 | 700 | Controls and compact labels |
| Caption | 12 / 18 | 500 | Nonessential metadata only |

Sizes are CSS px for the preview and corresponding sp starting points in Android. Respect user font scaling. Controls expand rather than clip. Avoid long uppercase text and thin weights in small exports. Keep display letter spacing near -0.03em, and body spacing natural.

The variable fonts and original SIL Open Font License files are bundled in [assets/fonts](assets/fonts/). Static Manrope instances are included because the SVG outline converter otherwise selected the variable font's lightest weight. They preserve the font's original outlines at the specified weights.

## Shapes and layout

Use an 8-unit rhythm with 4-unit optical adjustments. Standard spaces: 4, 8, 12, 16, 24, 32, 48, and 64. Control corner radius: 16. Card radius: 24. Reserve pills for brief tags and compact actions.

In campaign compositions, tilt app cards by at most 10 degrees and use a small, crisp ink offset shadow. In working UI, keep cards level and avoid decorative shadows competing with content. Default to 16–24 units of screen padding, 48-unit minimum touch targets, and one primary action per area.

## Icons and illustration

The 12 supplied functional icons use a 24-unit grid, 2-unit strokes, and round joins and caps. They are supporting UI assets, not alternate logos. Label unfamiliar actions. Android may retain equivalent Material icons if their stroke and optical weight match.

The campaign illustration [app-tiles.png](assets/illustrations/app-tiles.png) is an original generated composition of four tactile utility tiles. Use it as a hero or editorial image, not as a screenshot, a functional control, or evidence of specific product features. Exact palette matching is required for vector UI; raster lighting naturally varies.

Suggested alt text: “Colorful tiles representing a checklist, a game, and other small useful apps.” Use empty alt text when the same meaning is fully conveyed beside a decorative image.

Do not place small text over the illustration, stretch it, or crop away the utility motifs. Keep at least the dominant citrus tile and one supporting tile visible. [tile-pattern.svg](assets/illustrations/tile-pattern.svg) and [empty-state.svg](assets/illustrations/empty-state.svg) are editable supporting graphics.

## Motion and sound

Use 120 ms for press feedback and 200 ms for ordinary transitions, with `cubic-bezier(0.2, 0, 0, 1)`. A new app card may settle from a very small offset once. Avoid looping celebration, shaking errors, or progress animations that imply a measured percentage.

Respect reduced-motion settings: use an immediate state change or a simple opacity transition. Do not add a startup jingle or default completion sound. No audio assets are part of this identity.

## Android icon

The 512 px square icon is an export master with a full-bleed citrus background; launcher masks supply the outer shape. Adaptive XML layers under [assets/android](assets/android/) keep the symbol in a conservative central safe area. Android 13 and later also have a monochrome layer. These are handoff assets; they have not been integrated into the application or tested on launchers.
