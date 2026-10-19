# Transmogrifier

An Android app where you describe a browser app in plain language, an LLM of your choice writes it in HTML/CSS/JavaScript, and you run it immediately on the device in the app's own browser. Follow-up prompts modify the existing app.

## Status

Early development. See [docs/plan.md](docs/plan.md) for the development plan.

## Building

Requirements:

- Android Studio (its bundled JDK is used for Gradle builds)
- Android SDK; the app targets SDK 37, minimum SDK 24

Open the project in Android Studio and run the `app` configuration, or build from the command line:

```text
./gradlew build
```

`build` compiles, runs unit tests, Android Lint, and ktlint. To check or fix formatting only:

```text
./gradlew ktlintCheck
./gradlew ktlintFormat
```

CI (GitHub Actions) runs the build and tests on every push and pull request, and ktlint on pull requests.

## License

[MIT](LICENSE)
