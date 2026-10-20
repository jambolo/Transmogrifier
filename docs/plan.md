# Transmogrifier development plan

## Goal

An Android app (Kotlin) where the user describes a browser app in plain language, an LLM of the user's choice writes it in HTML/CSS/JavaScript, and the user runs it immediately on the device in the app's own browser. Follow-up prompts modify the existing app.

Initial providers:

- **Claude** — the Anthropic Messages API (the models Claude Code uses).
- **OpenAI Codex** — the OpenAI Responses API with Codex models.

Claude Code and the Codex CLI are desktop command-line agents and do not run on Android, so the app calls the APIs directly with API keys the user enters. Subscription sign-in (Claude Pro/Max, ChatGPT Plus) is out of scope.

## Phases

| Phase | Outcome |
| --- | --- |
| 1. Architecture | Components, interfaces, and dependencies decided; SDK spike passes |
| 2. UI | All screens navigable with fake data |
| 3. Code generation | Prompt → files on disk through a real provider |
| 4. Execution | Generated app runs in the in-app browser; errors flow back to the LLM |

Each phase ends with `./gradlew build` green and its exit criteria met.

## Phase 1: Architecture and components

### Stack

- Kotlin, Jetpack Compose + Material 3 (already in the template), single activity, Navigation Compose.
- MVVM with unidirectional data flow: `ViewModel` exposes `StateFlow<UiState>`; coroutines for all I/O.
- `kotlinx.serialization` for persistence and tool JSON.
- Manual dependency injection through one `AppContainer` created in `Application` (Hilt can come later if the graph grows).
- One `app` module, organized by package; split into modules only when build times or boundaries demand it.

### Packages

| Package | Responsibility |
| --- | --- |
| `ui` | Compose screens, view models, navigation |
| `settings` | API keys, provider/model selection, limits |
| `llm` | Provider-neutral interface plus `anthropic` and `openai` implementations |
| `agent` | Generation loop, system prompt, tool definitions and executor |
| `project` | Project storage: files, metadata, conversation history |
| `runtime` | In-app browser host, asset serving, console capture |

### Core interfaces

```kotlin
interface LlmProvider {
    val id: ProviderId
    suspend fun listModels(): List<ModelInfo>
    fun runTurn(request: TurnRequest): Flow<TurnEvent>
}

sealed interface TurnEvent {
    data class TextDelta(val text: String) : TurnEvent
    data class ToolInputDelta(val callId: String, val partialJson: String) : TurnEvent
    data class ToolCall(val callId: String, val name: String, val inputJson: String) : TurnEvent
    data class Completed(val stopReason: StopReason, val usage: Usage) : TurnEvent
}

interface ProjectStore {
    fun projects(): Flow<List<ProjectSummary>>
    suspend fun create(name: String): Project
    suspend fun workspace(id: ProjectId): Workspace // sandboxed file access for one project
}
```

`GenerationEngine` (in `agent`) owns the loop: it calls `LlmProvider.runTurn`, executes tool calls against the `Workspace`, appends results, and repeats until the model finishes, a limit is hit, or the user cancels.

### Conversation history

Each provider keeps its history in its own wire format, stored per project as opaque JSON and only ever appended to. Claude requires that thinking blocks be returned unchanged and that history not be edited, so there is no lossy translation into a shared message model. Switching provider on an existing project starts a new conversation seeded with the current file list.

### Storage

- Projects: `filesDir/projects/<id>/` containing `site/` (the generated app), `project.json` (name, provider, model, timestamps), and `conversation.json`.
- Settings: Jetpack DataStore.
- API keys: encrypted with an AES-GCM key held in the Android Keystore and stored in DataStore. Keys never leave the `settings` and `llm` packages and are never visible to the in-app browser.

### Dependencies to add (version catalog)

- `androidx.navigation:navigation-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose`
- `org.jetbrains.kotlinx:kotlinx-serialization-json` (+ serialization plugin), `kotlinx-coroutines-android`
- `androidx.datastore:datastore`
- `androidx.webkit:webkit` (`WebViewAssetLoader`)
- `com.anthropic:anthropic-java` (official SDK; Kotlin uses the Java SDK)
- `com.openai:openai-java` (official SDK)
- Test: `kotlinx-coroutines-test`, `app.cash.turbine:turbine`

### Spike (first task of the phase)

Verify on a device with an R8 release build that both SDKs compile at `minSdk 26`, stream a response, and survive minification. Expected friction: Jackson/reflection keep rules. `minSdk` was raised from 24 to 26 instead of enabling core library desugaring, because both SDKs use `java.time` (via Jackson). If an SDK cannot run on Android, that provider falls back to OkHttp + SSE + `kotlinx.serialization` behind the same `LlmProvider` interface.

**Result (2026-10-19): pass.** `anthropic-java` 2.73.0 and `openai-java` 4.86.0, R8 9.4.24 (AGP 9.4.1), release build on the `Medium_Phone_API_37.0` emulator:

- Both SDKs build a streaming request, send it, and surface the bogus-key 401 as their typed exception with the parsed error body.
- Canned SSE events (text delta, tool-input delta) deserialize through each SDK's Jackson mapper into `RawMessageStreamEvent` / `ResponseStreamEvent`.
- No app keep rules needed for Jackson: both SDKs ship consumer R8 rules. The only rule added is `-dontwarn com.google.auto.value.AutoValue` (OpenTelemetry via `openai-java`), in `app/src/main/keepRules/sdk.keep` (AGP 9 requires the `.keep` extension there).
- The template's `optimization { packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**") }` crashed at runtime: R8 repackaged `kotlin.text.StringsKt__StringBuilderKt` out of `kotlin.text` while its package-private superclass `StringsKt__IndentKt` stayed, giving `IllegalAccessError` on the first SDK call. Removing `packageScope` (optimize everything, the default) fixes it.
- R8 ran out of memory with the 2 GiB Gradle heap; raised to 4 GiB in `gradle.properties`.
- Release APK: 5.5 MB.

### Exit criteria

Interfaces compiled with fake implementations, spike result recorded in this document, dependencies added, unit tests for path validation pass.

**Status (2026-10-19): met.**

- `llm/LlmProvider.kt` and `project/ProjectStore.kt` as above, with `FakeLlmProvider` (scripted events) and `FakeProjectStore` (in-memory metadata, real workspace directories).
- Interface refinements: `TurnRequest` carries model, effort, max output tokens, system prompt, tool definitions, the opaque `ProviderHistory`, and a `TurnInput` (user prompt or all tool results of the previous turn); `TurnEvent.Completed` returns the appended `ProviderHistory`, since only the provider knows its wire format.
- `project/Workspace.kt`: relative `/`-separated paths only; rejects empty or dot-prefixed segments (covers `.`, `..`, hidden files), `\`, `:`, control characters, absolute paths, symlink escapes, depth, path length, extension, per-file, file-count, and total-size limits. Results are `WorkspaceResult` values. Default limits: depth 6, 100 files, 512 KiB per file, 4 MiB total.
- `WorkspaceTest` covers validation, limits, and `edit` semantics (the symlink case is skipped on Windows without symlink privileges).
- Code style is ktlint `android_studio` (no trailing commas, 100 columns).

## Phase 2: UI

### Screens

1. **Projects** — list (name, last edited, provider), create, rename, delete.
2. **Workspace** — prompt box and conversation; a live activity log during generation ("Writing `index.html`…", streamed text, token usage); a file tree with a read-only, syntax-highlighted viewer; **Run**, **Stop**, and **Fix errors** actions.
3. **Runner** — full-screen browser with a toolbar: reload, back to workspace, console panel toggle, "Send errors to the LLM".
4. **Settings** — API key per provider (masked, with a "Test" button that calls `listModels`), default provider and model, effort level, limits (max tool iterations per prompt, max output tokens).

### Approach

- Built against fake `LlmProvider` and `ProjectStore` implementations so the whole flow is clickable before any network code exists.
- Compose previews for every screen; phone layout first, two-pane workspace on tablets later.
- First-run flow: no API key → Settings.

### Exit criteria

All screens reachable, state survives rotation and process death (saved-state handles), fake generation streams into the activity log.

**Status (2026-10-19): met** (emulator, API 37).

- Navigation Compose with type-safe routes (`ui/AppNavHost.kt`); one view model per screen, created through `AppContainer` (`TransmogrifierApplication`), which wires fakes only: `FakeProjectStore`, `FakeSettingsRepository`, `FakeLlmProvider`, and `agent/FakeGenerationEngine` (streams canned text and writes a real three-file demo site).
- Interface additions: `ProjectStore` gained `project`, `rename`, `delete`, and a provider-neutral display transcript (`TranscriptEntry`: prompts, replies, tool calls, usage, notices), because the wire-format history is opaque. Projects record provider and model at creation. `GenerationEngine.generate` emits live events (text, running tool, turn persisted); durable results go to the transcript, which the engine owns.
- Runner → workspace results go through the workspace back stack entry's `SavedStateHandle` (`RunnerResults`): the latest error log enables **Fix errors**, and **Send errors to the LLM** starts a fix immediately. The view model must be given `entry.savedStateHandle`; `createSavedStateHandle()` in the initializer is a different handle. Phase 4 replaces this with an activity result from the `:runner` process.
- `RunnerScreen` takes the page as a composable slot so Phase 4's `RunnerActivity` can host it with the `WebView`; the console is canned for now.
- Saved state: prompt draft, selected tab, open file, dialogs, and settings fields. API key drafts are deliberately not saved, so a key never lands in the saved-state `Bundle`.
- A workspace or runner whose project no longer exists navigates back instead of crashing. The in-memory fakes lose data on process death; this path also covers a project deleted elsewhere.
- Verified on the emulator: first run opens Settings; key save and test; create, rename, and delete; streaming with Stop; file tree and highlighted viewer; runner console → fix generation; rotation; process death.
- `WorkspaceViewModelTest` covers streaming, Stop, and the fix-errors handoff.

## Phase 3: Code generation

### Contract for generated apps

- Static site in `site/`, entry point `site/index.html`, relative paths only, no build step.
- Plain HTML/CSS/JavaScript (ES modules allowed). Self-contained: no remote scripts or network calls by default (setting can relax this later).
- Limits: path depth, file count, per-file and total size; allowed extensions (`.html`, `.css`, `.js`, `.mjs`, `.json`, `.svg`, `.txt`, `.md`).

### Tools exposed to the model

| Tool | Input | Effect |
| --- | --- | --- |
| `list_files` | — | Paths and sizes under `site/` |
| `read_file` | `path` | File contents |
| `write_file` | `path`, `content` | Create or overwrite |
| `edit_file` | `path`, `old_text`, `new_text` | Exact single replacement; error if not found or ambiguous |
| `delete_file` | `path` | Remove |

The `Workspace` rejects absolute paths, `..`, disallowed extensions, and over-limit writes; rejections go back to the model as error tool results, not exceptions.

### System prompt

Stable text (kept byte-identical between requests so it caches): the generated-app contract, mobile-first and touch-friendly UI, no `alert()`-based flows, keep state in `localStorage`, always leave `index.html` runnable, finish each prompt with a short summary of what changed.

The user's **Instructions for all apps** (Settings, at most 4,000 characters) are never put in the system prompt, because editing them would change the prefix of every existing conversation. Instead, `AgentEngine` puts them in front of the next user text in an `<instructions>` block whenever they differ from `Conversation.instructions`, which records the instructions last delivered in that history. If the user clears them, the next prompt says so. The transcript shows only the user's prompt.

### Generation loop

1. Append the user prompt (and, for "Fix errors", captured console errors) to the conversation.
2. Stream a turn; render text and tool-input deltas live.
3. Check the stop reason before running tools: truncated by `max_tokens` → retry or ask the user; refusal → show it.
4. Validate each tool input against its schema, execute, return **all** results of a turn in one message (failures with an error flag).
5. Repeat until the model ends its turn, the iteration limit is reached, or the user presses **Stop** (coroutine cancellation).
6. Persist conversation and usage after every turn.

### Claude provider

- Official Java SDK, streaming Messages API, client-defined tools with `strict: true` and `eager_input_streaming: true` (so `write_file` content streams; validate the parsed input before running it).
- Default model `claude-opus-5-5`; user-selectable from the Models API list.
- Adaptive thinking; effort set explicitly (default `high` for this agentic coding use; user can lower it).
- Server-side refusal fallback enabled (`fallbacks: "default"` with the `server-side-fallback-2026-07-01` beta).
- Prompt caching: top-level `cache_control`; tools and system prompt form the stable prefix. Confirm with `usage.cache_read_input_tokens`.
- `max_tokens` around 64 000 (streaming).

### OpenAI provider

- Official Java SDK, streaming Responses API with function tools mapped from the same tool definitions.
- Model list from the Models API filtered to Codex models; the default is configured in one place.
- Confirm current request shapes (reasoning items, `previous_response_id` versus stateless replay) against OpenAI's documentation at the start of the phase.

### Errors

Typed handling per SDK: invalid key (401) → Settings; rate limit / overload (429, 5xx) → backoff retry with a visible notice; network loss → resumable "Retry turn".

### Tests (JVM, `app/src/test`)

- `Workspace` path and limit validation.
- Tool executor, including error results.
- Provider mappers against recorded SSE fixtures (no network).
- `GenerationEngine` with a scripted fake provider: multi-tool turns, limits, cancellation.

### Exit criteria

"A tip calculator" produces a working `site/index.html` with each provider on a real device; follow-up "make the button blue" edits the existing files.

**Status (2026-10-19): met for Claude; OpenAI is blocked by an account with no credits.**

- Real-key check on the emulator, minified release:
  - **Claude** (`claude-opus-5-5`) wrote `index.html`, `css/style.css`, and `js/app.js` for "A tip calculator". "Make the button blue" edited `css/style.css` with `edit_file`. The history replay was accepted, and the cache hits grew from 1.4k to 6.6k tokens.
  - **OpenAI** (`gpt-6.1-sol`) accepted the request and then returned "You have no credits remaining" mid-stream. Generation is unverified, so OpenAI is disabled (`ProviderId.OPENAI`, `enabled = false`) until this check passes.
- Two bugs found and fixed:
  - Under R8, the typed `ResponseInputItem` round-trip added `phase` to user messages, and OpenAI rejected the request with a 400. Both providers now send history as a raw JSON tree (`JsonValue`), and `ProviderMapperTest` asserts that it is sent unchanged.
  - Mid-stream OpenAI errors were all retried as server errors. Now only `rate_limit_exceeded` and server-side codes are retried; anything else, such as an exhausted quota, fails at once.

- `AgentEngine` (in `agent`) runs the loop. Before each turn it saves the input it is about to send as the conversation's `pending` input, so Stop, a failure, or the iteration limit can resume with **Continue**. A new prompt keeps any unsent tool results. Only complete turns are appended, and `FileProjectStore` rejects any save that doesn't extend the stored history.
- Tool calls from a turn cut off by `max_tokens` or a refusal are never run. A `max_tokens` cut returns error results so the model retries in smaller pieces; a refusal stops with a notice.
- SDK retries are off. The engine retries rate limits, server errors, and network failures (2 s, 5 s, 15 s) and shows "Retrying in N s" in the activity log. Errors sent mid-stream are retried only when their code is transient.
- Interface changes:
  - `TurnInput` is tool results plus optional text, not a prompt-or-results union.
  - New `TurnEvent.ToolCallStarted` and `GenerationEvent.Retrying`.
  - Providers throw a neutral `LlmException`.
  - `ProjectStore` gained `conversation` / `saveConversation`.
- Claude: beta streaming Messages API through `client.beta()`.
  - `fallbacks: "default"` (beta `server-side-fallback-2026-07-01`). After a mid-output fallback, blocks before the last `fallback` marker other than text are not echoed back, and their tool calls are not run.
  - `thinking.display: "updates"` (beta `thinking-display-updates-2026-08-18`) so Opus 5.5's notes between tool calls appear in the activity log.
  - `prefix_mismatch_behavior: "error"` (beta `thinking-binding-controls-2026-08-01`): the history is append-only, so a prefix mismatch is a bug to surface.
  - Each beta is sent only to models that accept it.
  - Tools are `strict` with `eager_input_streaming`; the executor validates every input and returns invalid JSON to the model as an `INVALID_JSON` error result.
  - Top-level `cache_control`.
  - Effort default stays `high`, as planned; Opus 5.5's API default would be `medium`.
- OpenAI: streaming Responses API, replayed statelessly (`store: false`). Every input and output item is stored verbatim, including encrypted reasoning and message `phase`. Implicit prompt-cache mode is set on GPT-5.6 and later; strict function tools.
  - **Codex models are gone:** `gpt-5-codex` was shut down on 2026-07-23 and every `*codex*` ID is deprecated. The default is now `gpt-6.1-sol`, which OpenAI's Codex docs recommend for agentic coding. The model list is filtered to the coding families (`gpt-6.x-astra|sol|luna`, `gpt-5.6-*`, remaining `*-codex`) and drops models past their shutdown date.
- Settings persist in DataStore (`settings.json`). API keys are AES-GCM encrypted with an Android Keystore key; if the key is lost, the saved API key is cleared and must be entered again.
- Projects persist under `filesDir/projects/<id>/` (`project.json`, `transcript.jsonl`, `conversation.json`, `site/`).
- Tests:
  - `ProviderMapperTest` uses SSE fixtures written by hand to the documented wire format; no real key was available to record them.
  - `AgentEngineTest` covers multi-tool turns, the iteration limit, Stop and resume, truncated tool calls, retries, and auth failure.
  - Also `ToolExecutorTest` and `FileProjectStoreTest`.
- Verified on the emulator (R8 release): both SDKs send a real request with a bad key and get a typed 401; the generation shows an "Open settings" notice; keys survive restarts encrypted. A 401 comes back before the request body is validated, so the request shapes are verified only offline until a run with real keys.

## Phase 4: Execution in the in-app browser

### Hosting

- `WebView` in a dedicated `RunnerActivity` running in a separate process (`android:process=":runner"`), so a hung or crashing generated app cannot take down the editor and cannot reach the main process's memory (where API keys live).
- `WebViewAssetLoader` serves `site/` over `https://` with a per-project domain, so each project gets its own origin and its own `localStorage`.
- `allowFileAccess = false`, `allowContentAccess = false`, JavaScript and DOM storage on, no `addJavascriptInterface`.
- `WebViewClient.shouldInterceptRequest` blocks requests outside the asset domain (matches the self-contained contract); `shouldOverrideUrlLoading` opens external links in the system browser after confirmation.
- `alert`/`confirm`/`prompt` handled by `WebChromeClient` with Compose dialogs.

### Feedback loop

- `WebChromeClient.onConsoleMessage` and a small injected `window.onerror` / `unhandledrejection` reporter capture errors (posted to the native side via `WebMessagePort`, not a JS interface).
- The console panel shows them; **Send errors to the LLM** returns to the workspace and starts a "Fix errors" generation with the captured log.

### Tests

Instrumented test in `app/src/androidTest`: load a fixture site, assert the page title and that a thrown error reaches the console capture. Run locally on an emulator; not in CI.

### Exit criteria

Run opens the generated app in under a second, edits are visible after reload, a deliberate JavaScript error round-trips to a fix.

**Status (2026-10-19): met** (emulator, API 37, minified release).

- `runtime/SiteBrowser` configures the `WebView`. It is separate from the activity, so it can be tested on its own:
  - Files are served by `WebViewAssetLoader` with a custom path handler on `https://<project id>.appassets.androidplatform.net/`. The handler sets the MIME types itself (`.mjs` included, which module scripts require) and serves `index.html` for directories.
  - Requests outside that origin get a 403, and missing files a 404. Service workers go through the same interception.
  - Every response carries a CSP (`default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; form-action 'self'; base-uri 'self'`). It covers what interception can't see: WebSockets and POST navigations.
  - Links leaving the origin open in the system browser after a confirmation. Permission and geolocation requests are denied.
  - `alert`, `confirm`, and `prompt` become Compose dialogs (`PageDialogHost`). A renderer crash shows a message, and **Reload** restarts the activity.
- Errors:
  - `onConsoleMessage` collects the console.
  - A document-start script reports `error` and `unhandledrejection` with stacks through `WebViewCompat.addWebMessageListener`, limited to the site's origin. It then cancels the event, so Chromium's own "Uncaught" line doesn't duplicate it.
  - The log sent to the LLM holds the last 20 errors, with stacks, up to 16k characters.
- `RunnerActivity` runs in `:runner` with its own WebView data directory. It handles size changes itself, so rotating keeps the page.
  - It returns the error log through the `RunApp` activity result, which replaced the `SavedStateHandle` handoff and the `Runner` route.
  - `TransmogrifierApplication.container` is lazy, so the runner process never builds it.
- Measured on the emulator:
  - Cold launch (new `:runner` process) takes about 570 ms in the release build (`Displayed`, 3 runs) and 2.5 s in the debug build. A warm launch takes about 330 ms.
  - A line that writes to a missing element was added to the Claude-built tip calculator. **Send errors to the LLM** produced a fix generation that found the line and removed it, and the next run's console was clean.
  - The external-link confirmation and a thrown error reached the console in the release build.
- `SiteBrowserTest` (`androidTest`, 3 tests) covers:
  - the page title;
  - a console log;
  - a blocked `fetch`;
  - a thrown error reported once, with its stack;
  - an edit visible after reload;
  - a missing page reported.
- The system prompt still tells the model not to use `alert()`. The dialogs exist for apps that use it anyway.

### Sound and speech (2026-10-19)

- `mediaPlaybackRequiresUserGesture = false`, so Web Audio and media elements play without a tap first. Sounds are synthesized (Web Audio, or `data:`/`blob:` URLs, which the CSP allows); the tools write text only, so there are no audio file types.
- WebView's `speechSynthesis` doesn't work, so a document-start script (`res/raw/speech_synthesis.js`) replaces it with a standard-shaped `speechSynthesis` / `SpeechSynthesisUtterance`. It talks to `runtime/SpeechBridge`, which wraps Android `TextToSpeech`, through a second web message listener limited to the site's origin; still no JavaScript interface.
  - Supported: `speak` (queued), `cancel`, `getVoices` with `voiceschanged`, `voice`, `lang`, `rate`, `pitch`, `volume`, and `start`, `end`, `error`, and word `boundary` events. `pause` and `resume` do nothing, because `TextToSpeech` can't pause.
  - Speech stops when the page navigates or reloads and when the runner is paused; the page gets `error` events with `interrupted`.
  - The manifest declares a `TTS_SERVICE` query so the engine is visible on Android 11+.
- The system prompt is unchanged: models already use the standard APIs, and editing the prompt would change the prefix of every existing Claude conversation, which `prefix_mismatch_behavior: "error"` rejects.
- `SiteBrowserTest` gained two tests (speech start/end with voices and a running `AudioContext`; `cancel`). Passed on a physical device (debug build).

## Later (out of scope for these phases)

- Export/share a project as a `.zip` or install it as a home-screen shortcut.
- Editing files by hand in the workspace.
- More providers behind `LlmProvider` (e.g. Gemini, local models).
- Optional network access for generated apps with a per-project allowlist.

## Verification across phases

- `./gradlew build` (compile, unit tests, Android Lint, ktlint) green on every merge; CI runs the same.
- Manual device check per phase exit criteria; record results in this document.