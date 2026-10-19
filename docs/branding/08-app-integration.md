# Android brand integration

Applied October 19, 2026. The product name remains **Transmogrifier**.

## Applied system

| Area | Implementation |
| --- | --- |
| Colors | Paper and ink foundations; citrus primary app action; coral, sky, and lilac app accents; explicit light and dark Material color schemes |
| Typography | Bundled Bricolage Grotesque 700/800 for headings; Manrope 400/500/700 for body text and controls; system monospace retained for code and logs |
| Shapes | Rounded cards, fields, and controls using an 8–28 dp shape scale |
| Icons | Original brand symbol on the shelf and app cards; matching add, open, close, rename, delete, and reload vectors; existing matching adaptive launcher icon retained |
| App shelf | “Little apps. For right now.” heading, colored app tiles, useful empty state, explicit manual-deletion message |
| Creation | Editable example requests for game night, packing, and lunch; “Make app” accessibility label for the first request and “Send” for follow-ups |
| Setup | Plain-language key explanation and billing disclosure; model, thinking effort, and limits grouped under Advanced settings |
| Recovery | Clear failure and retry messages, “Try to fix it,” and disclosure of what is sent for repairs |
| Deletion | Named confirmation, clear permanent removal wording, and “Keep app” cancellation |

System dark mode still selects the theme. Wallpaper-derived colors are disabled so the product retains its palette. Fonts are packaged locally and require no download. Native window backgrounds match the selected light or dark surface.

Generation, provider adapters, stored projects, API-key handling, and generated-app rendering are unchanged. Examples only fill a draft; the user must submit it. Apps remain until manually deleted. Existing detailed generation and token information remains available.

## Source and maintenance

Theme code lives in [ui/theme](../../app/src/main/java/io/github/jambolo/transmogrifier/ui/theme/Theme.kt). Interface wording lives in [strings.xml](../../app/src/main/res/values/strings.xml). All replacement format arguments and existing key-test plurals are preserved; retry delays now also use plurals.

Android fonts are static instances to support the full minimum-SDK range. [prepare-android-fonts.py](tools/prepare-android-fonts.py) produces the Bricolage 700/800 instances from the bundled variable source, with optical size 24 and width 100. Manrope resources use the existing static brand instances. Font licenses are included in the APK under `assets/licenses`.

```powershell
python docs/branding/tools/prepare-android-fonts.py
```

The HTML brand book remains a concept and is not an Android screenshot.

## Review images

These images render the actual Compose screens with synthetic test content. They contain no saved keys or personal app data.

- [Light app shelf](previews/app-projects-light.png)
- [Dark app shelf](previews/app-projects-dark.png)
- [Setup](previews/app-settings.png)
- [Workspace](previews/app-workspace.png)
- [Enlarged text](previews/app-large-text.png)

## Verification

The full Gradle `build` passes, including debug and minified release assembly, unit tests, Android lint, and ktlint. All 28 unit tests pass. Five Compose UI tests pass on the API 37 phone emulator:

- App opening, rename, cancellation of deletion, and explicit deletion call the expected callbacks.
- Example requests fill the draft without submitting; first requests, follow-up edits, opening the generated app, and stopping generation retain their actions.
- Advanced settings remain accessible and editable behind the simplified setup.
- Creation works with the enlarged-text layout.
- The primary action remains visible and accessible in dark mode.

The UI checks exposed a missing merged accessibility label on the floating action button. It now explicitly announces “New app.” Retry messages use correct singular and plural forms. Native theme resources pass minimum-SDK checks for Android 8.0.

Test APKs were installed with `adb install -r` and executed with `am instrument`; no uninstall, data clearing, or `connectedAndroidTest` task was used. UI tests use synthetic state and callbacks, with no live AI requests. The asset check also passes: 93 files, 93 local references, and no failures.

Screenshots cover light mode, dark mode, and a 2× font-scale setting. They do not establish accessibility certification or exhaustive coverage of devices and launcher masks. The minified release was built; these UI tests ran against the debug app.
