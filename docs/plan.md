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

Verify on a device with an R8 release build that both SDKs compile at `minSdk 24`, stream a response, and survive minification. Expected friction: Jackson/reflection keep rules and Java library desugaring. Decide from the result whether to raise `minSdk` to 26 or enable core library desugaring. If an SDK cannot run on Android, that provider falls back to OkHttp + SSE + `kotlinx.serialization` behind the same `LlmProvider` interface.

### Exit criteria

Interfaces compiled with fake implementations, spike result recorded in this document, dependencies added, unit tests for path validation pass.

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

## Later (out of scope for these phases)

- Export/share a project as a `.zip` or install it as a home-screen shortcut.
- Editing files by hand in the workspace.
- More providers behind `LlmProvider` (e.g. Gemini, local models).
- Optional network access for generated apps with a per-project allowlist.

## Verification across phases

- `./gradlew build` (compile, unit tests, Android Lint, ktlint) green on every merge; CI runs the same.
- Manual device check per phase exit criteria; record results in this document.