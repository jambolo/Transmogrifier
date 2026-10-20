# Development

[docs/plan.md](docs/plan.md) is the authoritative development plan: phases, package layout, interfaces, tool definitions, storage, and security model. Record spike and phase results there.

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
- The generated app runs in the `:runner` process, which never builds `AppContainer`. It is served over `https://` on a per-project origin, with no file or content access and no `addJavascriptInterface`. Requests outside that origin are blocked, both by request interception and by a Content Security Policy header. Uncaught errors and speech requests reach the app through web message listeners limited to the site's origin.
- The system prompt and tool definitions must stay byte-identical between requests so prompt caching hits.

### Adding an LLM provider

Providers are adapters behind `LlmProvider`, and each SDK stays inside its own `llm` subpackage. To add one:

1. Add a constant, with its default model, to `ProviderId`.
2. Implement `LlmProvider` in a new `llm/<name>/` package. Map the SDK's errors to `LlmException`, and store history in the provider's own wire format.
3. Register the adapter in `AppContainer`.
4. Add its display name in `ui/Labels.kt`; the compiler flags it if it's missing.

A provider constructed with `enabled = false` keeps its code and tests but isn't offered in Settings, isn't registered, and existing projects that use it get a "provider unavailable" notice instead of generating. OpenAI is disabled this way until it has been verified with a real key; to re-enable it, remove `enabled = false` from `ProviderId.OPENAI`.
