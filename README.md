# Transmogrifier

An Android app where you describe a browser app in plain language, an LLM of your choice writes it in HTML/CSS/JavaScript, and you run it immediately on the device in the app's own browser. Follow-up prompts modify the existing app.

Supported LLM providers:

- **Claude**, through the Anthropic API.
- **OpenAI**, through the OpenAI API.

You use your own API key for each provider, and requests are billed to that account. There is no sign-in with a Claude or ChatGPT subscription.

## Requirements

- An Android phone or tablet running Android 8.0 or later.
- An API key for at least one provider:
  - Claude: [Claude Console](https://console.anthropic.com/)
  - OpenAI: [OpenAI Platform](https://platform.openai.com/), on an account with billing credits.

## Using the app

### Set up

1. On first launch, the app opens **Settings**. Paste an API key for each provider you want to use, tap **Save**, then **Test**. A passing test shows how many models the key can use.
2. Choose the **default provider** and its **default model**. New apps use these.
3. Optionally adjust:
   - **Effort**: how much the model reasons before answering. Higher is slower and costs more.
   - **Max tool iterations per prompt**: how many steps the model may take for one prompt before it pauses.
   - **Max output tokens**: the length limit for each model reply.

### Create an app

1. On the projects screen, tap **New app** and give it a name.
2. Describe what you want, for example "a tip calculator with a slider", and tap **Send**.
3. The chat shows the model's progress as it writes the files, along with the token usage for each step. The **Files** tab lists the generated files, and tapping one opens a read-only view of its code.
4. Send follow-up prompts, such as "make the button blue", to change the app.

**Stop** interrupts a generation. After a stop, an error, or the step limit, **Continue** picks up where it left off.

### Run an app

Tap **Run** (the play button) to open the app in the built-in browser. In the toolbar:

- **Reload** loads the app again, including any changes.
- The **console** button shows the app's log. A badge counts the errors.
- **Send errors to the LLM**, in the console, returns to the chat and asks the model to fix the errors.

After a run with errors, the chat's **Fix errors** button sends them to the model at any time.

### Manage apps

From the menu next to each app on the projects screen, you can rename or delete it. Each app keeps the provider and model it was created with.

## Privacy and safety

- API keys are encrypted with a key held in the Android Keystore and stored only on the device. They are sent only to the provider they belong to.
- Generated apps run in a separate process that never loads the API keys.
- Generated apps can't use the network: requests to other sites are blocked. Links to other sites open in your regular browser, after you confirm.
- Each generated app has its own storage, so apps can't read each other's saved data.

## Status

Early development. See [docs/plan.md](docs/plan.md) for the development plan and [DEVELOPMENT.md](DEVELOPMENT.md) for building from source.

## License

[MIT](LICENSE)
