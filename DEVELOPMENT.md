# Development

How to build, test, and change Transmogrifier.

## Requirements

- Android Studio (its bundled JDK is used for Gradle builds)
- Android SDK; the app targets SDK 37, minimum SDK 26

## Building

Open the project in Android Studio and run the `app` configuration, or build from the command line:

```bash
./gradlew build
```

`build` compiles, runs unit tests, Android Lint, and ktlint. To check or fix formatting only:

```bash
./gradlew ktlintCheck
./gradlew ktlintFormat
```

ktlint uses the `android_studio` code style: no trailing commas, lines of at most 100 characters.

## Release builds

Release builds are minified with R8 (`optimization { enable = true }`). Both LLM SDKs must keep working under minification, so test a release build on a device or emulator after changing how an SDK is used or changing keep rules.

- Keep rules go in `app/src/main/keepRules/*.keep`.
- Don't set `optimization.packageScope`: a partial scope made R8 break the Kotlin standard library at runtime.
- R8 needs the 4 GiB Gradle heap set in `gradle.properties`.

To install a minified release without changing the build, sign it with the debug keystore:

```bash
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file="$HOME/.android/debug.keystore" \
  -Pandroid.injected.signing.store.password=android \
  -Pandroid.injected.signing.key.alias=androiddebugkey \
  -Pandroid.injected.signing.key.password=android
adb install -r app/build/outputs/apk/release/app-release.apk
```

In PowerShell, quote each `-P` argument.

### Signing and publishing

`app/build.gradle.kts` signs the release build when `keystore.properties` exists in the project root; without it, `assembleRelease` produces an unsigned APK. The file holds `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. It and `*.jks`/`*.keystore` are git-ignored, so never commit them. Back up the keystore: updates must be signed with the same key.

Pushing a `v*` tag runs `.github/workflows/release.yml`, which checks that the tag matches `versionName`, builds and verifies the signed APK, and attaches it to a new GitHub release. It needs these repository secrets:

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | The keystore, base64-encoded (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("path\to\release.jks"))`) |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

To release: bump `versionCode` and `versionName`, commit on the `release/*` branch, create an annotated tag `vX.Y.Z`, merge to `master` with `--no-ff`, and run `git push origin master --follow-tags`.

## Tests

- **Unit tests** (`app/src/test`) run with `./gradlew build` or `./gradlew testDebugUnitTest`. Provider tests replay SSE fixtures from `app/src/test/resources/fixtures`, so they don't need API keys.
- **Instrumented tests** (`app/src/androidTest`) load fixture sites in a real `WebView`. Run them locally on an emulator, not in CI.

Generate debug unit-test coverage with:

```bash
./gradlew :app:createDebugUnitTestCoverageReport
```

The HTML report is at `app/build/reports/coverage/test/debug/index.html`; the JaCoCo XML report is at `app/build/reports/coverage/test/debug/report.xml`.

`connectedAndroidTest` uninstalls the app afterwards, which erases its data, including saved API keys. To keep the data, install and run the tests yourself:

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w io.github.jambolo.transmogrifier.test/androidx.test.runner.AndroidJUnitRunner
```

## CI

GitHub Actions (`.github/workflows/ci.yml`) runs the build and tests on every push to `master`, `develop`, and `release/**` and on every pull request. ktlint runs on pull requests.

A separate release workflow publishes signed APKs for `v*` tags; see [Signing and publishing](#signing-and-publishing).

CI also generates debug unit-test coverage and uploads the XML report to Codecov. Configure the repository in Codecov and save its upload token as the GitHub Actions secret `CODECOV_TOKEN`. Upload failures fail the build job. Instrumented-test coverage is not included.

## Architecture

| Package | Responsibility |
| --- | --- |
| `ui` | Compose screens, view models, navigation |
| `settings` | API keys, provider and model selection, limits |
| `llm` | Provider-neutral `LlmProvider` interface, with `anthropic` and `openai` adapters |
| `agent` | Generation loop (`AgentEngine`), system prompt, tool definitions and executor |
| `project` | Project storage: files, metadata, conversation history |
| `runtime` | In-app browser (`SiteBrowser`, `RunnerActivity`) and console capture |

Dependencies are wired by hand in `AppContainer`. Fake implementations (`FakeLlmProvider`, `FakeProjectStore`, `FakeGenerationEngine`, `FakeSettingsRepository`) back the Compose previews and tests.

Key invariants:

- Conversation history is stored per provider in that provider's own wire format, as opaque append-only JSON. Never translate it between providers or edit it: Claude's thinking blocks must round-trip unchanged.
- The model changes the generated site only through tools (`list_files`, `read_file`, `write_file`, `edit_file`, `delete_file`). `Workspace` rejects absolute paths, `..`, disallowed extensions, and over-limit writes, and these rejections go back to the model as error tool results.
- API keys are encrypted with an Android Keystore AES-GCM key and never leave `settings` and `llm`.
- The generated app runs in the `:runner` process, which never builds `AppContainer`. It is served over `https://` on a per-project origin, with no file or content access and no `addJavascriptInterface`. Requests outside that origin are blocked, both by request interception and by a Content Security Policy header. With "Let apps use the network" on (Advanced settings, passed to the runner as an intent extra), https `fetch`/WebSocket/image/media requests pass; remote scripts, styles, fonts, and plain http stay blocked. Uncaught errors and speech requests reach the app through web message listeners limited to the site's origin.
- The system prompt and tool definitions must stay byte-identical between requests so prompt caching hits.

### Design notes

- **Generated apps:** a static site in `site/` with the entry point `index.html`, relative paths, and no build step. `Workspace` allows `.html`, `.css`, `.js`, `.mjs`, `.json`, `.svg`, `.txt`, and `.md`, with limits of depth 6, 100 files, 512 KiB per file, and 4 MiB in total.
- **Storage:** each project lives in `filesDir/projects/<id>/` (`project.json`, `transcript.jsonl`, `conversation.json`, `site/`). Settings are in DataStore (`settings.json`). If the Keystore key is lost, the saved API key is cleared and must be entered again.
- **The system prompt never changes.** Editing it changes the prefix of every existing Claude conversation, which `prefix_mismatch_behavior: "error"` rejects. Instructions for all apps, the network setting, and the app's name ride on the next user text instead, and `Conversation` records what was last delivered. This is why the prompt still describes network access as blocked.
- **Generation loop:** `AgentEngine` saves the input it is about to send as `pending` before each turn, so Stop, a failure, or the step limit can resume with **Continue**. Only complete turns are appended. Tool calls from a turn cut off by `max_tokens` or a refusal are never run. SDK retries are off; the engine retries rate limits, server errors, and network failures after 2, 5, and 15 seconds.
- **Claude:** each beta header is sent only to models that accept it. `pause_turn` maps to `StopReason.PAUSED`, and the engine resumes with `TurnInput(resume = true)`.
- **OpenAI:** requests replay the history statelessly (`store: false`) as a raw JSON tree, because a typed round-trip adds fields under R8. `OpenAiRequests.params` drops null fields from the copy it sends: output items carry `status: null`, which OpenAI rejects as input. The stored history keeps them. `*codex*` model IDs are deprecated, so the model list is filtered to the coding families.
- **SDKs:** `minSdk` is 26 instead of using core library desugaring, because both SDKs use `java.time`. Both ship consumer R8 rules. The only app rule they need is the `-dontwarn` in `sdk.keep`.
- **Provider fixtures:** the SSE fixtures are written by hand to the documented wire format, so they can miss what the live API really sends. After changing a provider, run a real generation on a minified build.

### Adding an LLM provider

Providers are adapters behind `LlmProvider`, and each SDK stays inside its own `llm` subpackage. To add one:

1. Add a constant, with its default model, to `ProviderId`.
2. Implement `LlmProvider` in a new `llm/<name>/` package. Map the SDK's errors to `LlmException`, and store history in the provider's own wire format.
3. Register the adapter in `AppContainer`.
4. Add its display name in `ui/Labels.kt`; the compiler flags it if it's missing.

A provider constructed with `enabled = false` keeps its code and tests but isn't offered in Settings, isn't registered, and existing projects that use it get a "provider unavailable" notice instead of generating.
