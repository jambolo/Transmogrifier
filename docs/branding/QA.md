# Branding QA

Verified October 19, 2026. The checks below cover the original documents and assets. Subsequent Android integration and its verification are recorded in [08-app-integration.md](08-app-integration.md).

## Visual review

Inspected the generated four-tile illustration, rendered desktop and phone brand book, light and dark app concepts, app icon, and campaign exports. The artwork uses abstract utility tiles. The scorekeeper is explicitly labeled a design concept. No borrowed characters, quotations, entertainment logos, or comic imagery appear in the reviewed artwork.

A font-export issue was found and corrected: the SVG converter initially used Manrope's lightest variable-font instance. Static 400/500/600/700/800 instances now supply the intended body and label weights. Final wordmarks and campaign typography are outlined.

## Browser checks

The local HTML preview was rendered in headless Chromium at 1440, 390, and 320 px viewport widths. All three loaded their fonts and images without horizontal page overflow. No JavaScript errors, failed requests, or external HTTP requests occurred. The light/dark UI toggle updated its visible label, appearance, and `aria-pressed` state. Reduced-motion mode sets scrolling to `auto`.

Evidence: [browser-check.json](previews/browser-check.json), [desktop](previews/brand-desktop.png), [phone](previews/brand-mobile.png), [light UI](previews/ui-light.png), and [dark UI](previews/ui-dark.png).

## Asset and document checks

All 29 SVG and 5 Android XML documents were parsed. The package check passed with 80 local references and no failures across 86 files, including 19 PNGs. Checks cover local Markdown/HTML/CSS references, JSON syntax, intended PNG dimensions, outlined final logo text, absence of active SVG content and remote SVG references, heading spacing, and selected color-contrast pairs. The reproducible check is [check-package.cjs](tools/check-package.cjs).

The color table uses calculated sRGB contrast. Intended normal-text pairs exceed 4.5:1. White on citrus and white on coral are documented as unsuitable. The outline color is for meaningful boundaries on paper or white, not small text.

The store title contains 27 characters; the short description contains 80. Export sizes and character counts do not establish compliance with all current store requirements.

## Boundaries

These package checks are not an accessibility certification. Android compilation and emulator checks belong to the subsequent integration record. Device-specific launcher masks still need review for each distribution target. No pricing, legal clearance, release date, domain registration, store submission, or publication was performed.

Product claims were grounded in the repository inspected for this task. The app's existing working-tree changes were not modified by the branding work. Recheck those claims against the release being marketed.
