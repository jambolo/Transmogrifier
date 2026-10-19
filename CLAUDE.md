# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android app (Kotlin, Jetpack Compose): the user describes a browser app in plain language, an LLM (Claude via the Anthropic Messages API, or OpenAI Codex via the Responses API) writes it in HTML/CSS/JavaScript, and the app runs it in an in-app `WebView`. Follow-up prompts modify the existing app. API keys are entered by the user; there is no subscription sign-in.

`docs/plan.md` is the authoritative development plan (phases, package layout, interfaces, tool definitions, storage, security model). Read it before structural work and record spike/phase results there.

## Build configuration

- Release builds use R8 (`optimization { enable = true }`). Both SDKs must keep working under minification; verify with a release build on a device/emulator when changing SDK usage or keep rules.
- Keep rules go in `app/src/main/keepRules/*.keep`. Don't set `optimization.packageScope`: a partial scope made R8 break the Kotlin stdlib at runtime (see the spike result in `docs/plan.md`).
- Configuration cache is enabled (`gradle.properties`); build logic must stay configuration-cache compatible.

## Architecture

Phases 1–3 are implemented: real providers (`llm/anthropic`, `llm/openai`), file-backed projects, DataStore settings with Keystore-encrypted keys, and `AgentEngine`, wired in `AppContainer`. The fakes remain for previews and tests. The runner screen is still a placeholder until Phase 4.

To install a minified release on the emulator without changing the build, sign it with the debug keystore: pass `-Pandroid.injected.signing.store.file=<~/.android/debug.keystore>`, `...store.password=android`, `...key.alias=androiddebugkey`, and `...key.password=android` to `assembleRelease` (quote each `-P` argument in PowerShell).

Key invariants:

- Conversation history is stored per provider in that provider's own wire format as opaque, append-only JSON. Never translate between providers or edit history (Claude thinking blocks must round-trip unchanged).
- The model edits the generated site only through tools (`list_files`, `read_file`, `write_file`, `edit_file`, `delete_file`). `Workspace` rejects absolute paths, `..`, disallowed extensions, and over-limit writes; rejections are returned to the model as error tool results, not thrown.
- API keys are encrypted with an Android Keystore AES-GCM key, never leave `settings`/`llm`, and are never reachable from the `WebView`.
- The generated app runs in a separate process (`:runner`), served over `https://` with a per-project origin, no file/content access, no `addJavascriptInterface`, and network requests outside the asset domain blocked. Errors flow back via `WebMessagePort`.
- The Claude system prompt and tool definitions must stay byte-identical between requests so prompt caching hits.
