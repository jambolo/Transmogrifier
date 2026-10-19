# Implementation and evidence

## Scope and status

The original delivery added branding documents and assets under `docs/branding`. The owner subsequently requested application to the Android app while retaining Transmogrifier. That integration is documented in [08-app-integration.md](08-app-integration.md). The package ID is unchanged; no page was published, domain registered, or release established.

The owner approved a bright, witty adult tone and explicitly retained Transmogrifier. The tagline, symbol, palette, and other creative choices are a completed proposed identity, not a record of separate owner approvals.

## Product evidence, inspected October 19, 2026

| Fact used in copy | Repository evidence | Implication |
| --- | --- | --- |
| Android app; describe, generate, follow up, run | [README](../../README.md), [development plan](../plan.md) | Describe small personal apps on Android |
| API key and separate usage billing | [README](../../README.md), [settings screen](../../app/src/main/java/io/github/jambolo/transmogrifier/ui/settings/SettingsScreen.kt) | Disclose setup and billing before acquisition |
| OpenAI disabled pending verification | [README](../../README.md), [development plan](../plan.md) | Current public copy names Claude only |
| Explicit manual deletion | [projects screen](../../app/src/main/java/io/github/jambolo/transmogrifier/ui/projects/ProjectsScreen.kt) | No automatic expiry claim |
| Project directory removal | [FileProjectStore](../../app/src/main/java/io/github/jambolo/transmogrifier/project/FileProjectStore.kt) | No global or secure-erasure claim |
| In-app browser blocks external requests | [SiteBrowser](../../app/src/main/java/io/github/jambolo/transmogrifier/runtime/SiteBrowser.kt) | Examples use self-contained utilities |
| Prompt requests localStorage for app state | [AgentPrompt](../../app/src/main/java/io/github/jambolo/transmogrifier/agent/AgentPrompt.kt) | Do not infer backup, synchronization, or guaranteed preservation |
| Existing public strings and recovery states | [strings.xml](../../app/src/main/res/values/strings.xml) | Proposed wording maps to known actions |

Sources reflect a working tree with existing in-progress changes. Recheck capability claims against the actual release build before publishing.

## Applying the visual system

The source values are in [tools/build-assets.cjs](tools/build-assets.cjs); [tokens.json](design-tokens/tokens.json) and [tokens.css](design-tokens/tokens.css) are generated handoff files. Edit the source and regenerate rather than letting exported values drift.

For Compose, map the `light` and `dark` semantic roles to Material color-scheme slots. Use Manrope for functional text and Bricolage for display headings. Color values are sRGB hex; Compose values need the opaque `FF` alpha prefix. Keep error and success semantics separate from decorative coral and citrus.

Current integration locations are [Color.kt](../../app/src/main/java/io/github/jambolo/transmogrifier/ui/theme/Color.kt), [Theme.kt](../../app/src/main/java/io/github/jambolo/transmogrifier/ui/theme/Theme.kt), [Type.kt](../../app/src/main/java/io/github/jambolo/transmogrifier/ui/theme/Type.kt), and [strings.xml](../../app/src/main/res/values/strings.xml). The app follows the system light/dark preference and uses the fixed brand palette instead of wallpaper-derived dynamic colors.

The files in `assets/android/` mirror resource directory names. The launcher already uses the brand symbol and citrus background, with a monochrome layer for supported launchers. Minimum SDK 26 can use adaptive icons. Do not treat the rectangular icon PNG as a premasked launcher icon; launcher masks still vary by device.

## Copy adoption

[Product copy](05-product-copy.md) records wording and interaction proposals. The HTML preview remains a design concept. The Android integration now includes example chips, distinct first-request copy, setup disclosures, and an advanced-settings group. Existing detailed generation progress remains available. Actual emulator screenshots are separately identified in the integration record.

Do not add fake progress, a nonfunctional undo, or a “Make app” action that also submits follow-up edits without changing its label. Keep placeholder parameters, pluralization, accessibility labels, and recovery-state conditions correct.

## Asset rebuild

Requires Node.js with `@napi-rs/canvas` and `sharp`. The Codex workspace runtime provided both for this delivery. Point `NODE_PATH` at an installation that contains them; no app dependencies are added.

```powershell
node docs/branding/tools/build-assets.cjs
```

This regenerates the vector logos, outlined campaign files, raster exports, Android layers, and design tokens inside this folder. It does not regenerate the AI illustration. Static Manrope instances are already bundled; rebuilding them is optional and requires Python with `fonttools`:

```powershell
python docs/branding/tools/instance-fonts.py
```

The browser preview uses local fonts and files, with no analytics or external runtime requests. Open `brand-preview.html` directly. `tools/check-package.cjs` checks assets and references; `tools/render-preview.cjs` renders desktop and phone review images using Playwright and an installed Chromium-family browser.

## Publication limits

Brand and domain clearance, product pricing, support contact, distribution URLs, and a release date remain unestablished. No material claims legal clearance. The marketing drafts disclose the current API requirement, and no social post or listing has been published.

The proposed identity avoids borrowed entertainment imagery. Original vector construction and generated artwork do not by themselves establish exclusivity. Asset provenance and font licenses are documented in [assets/README.md](assets/README.md).

## Verification

See [QA.md](QA.md) for the original package checks and [08-app-integration.md](08-app-integration.md) for Android build and UI verification. Device-specific launcher masks are not exhaustively tested.
