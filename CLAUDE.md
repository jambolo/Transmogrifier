# CLAUDE.md

## Project

`docs/plan.md` is the authoritative development plan (phases, package layout, interfaces, tool definitions, storage, security model). Read it before structural work and record spike/phase results there.

## Build configuration

- Release builds use R8 (`optimization { enable = true }`). Both SDKs must keep working under minification; verify with a release build on a device/emulator when changing SDK usage or keep rules.
- Keep rules go in `app/src/main/keepRules/*.keep`. Don't set `optimization.packageScope`: a partial scope made R8 break the Kotlin stdlib at runtime (see the spike result in `docs/plan.md`).
- Configuration cache is enabled (`gradle.properties`); build logic must stay configuration-cache compatible.

## Architecture

Providers are adapters behind `LlmProvider`: everything outside `llm/` depends only on its provider-neutral types, and each SDK is confined to its subpackage. The steps for adding a provider are in the `ProviderId` KDoc.

See `DEVELOPMENT.md` for installing minified releases and running instrumented tests. Never run `connectedAndroidTest`: it uninstalls the app, which erases its saved API keys.

Key invariants:

- Conversation history is stored per provider in that provider's own wire format as opaque, append-only JSON. Never translate between providers or edit history (Claude thinking blocks must round-trip unchanged).
- The model edits the generated site only through tools (`list_files`, `read_file`, `write_file`, `edit_file`, `delete_file`). `Workspace` rejects absolute paths, `..`, disallowed extensions, and over-limit writes; rejections are returned to the model as error tool results, not thrown.
- API keys are encrypted with an Android Keystore AES-GCM key, never leave `settings`/`llm`, and are never reachable from the `WebView`.
- The generated app runs in a separate process (`:runner`), served over `https://` with a per-project origin, no file/content access, no `addJavascriptInterface`, and network requests outside the asset domain blocked (interception plus a CSP header). Uncaught errors and `speechSynthesis` requests (spoken by `TextToSpeech` in `SpeechBridge`) flow back through web message listeners limited to the site's origin. The `:runner` process never builds `AppContainer`, so it never loads API keys.
- The Claude system prompt and tool definitions must stay byte-identical between requests so prompt caching hits.
