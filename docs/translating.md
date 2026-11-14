# Translating the app

The app's text lives in Android string resources, so adding a language means adding translated
copies of the XML files. No Kotlin changes are needed.

## Where the text is

Each module has its English text in `src/main/res/values/`:

| File | What it holds |
| --- | --- |
| `core/…/strings_common.xml` | Words used across the app: Cancel, Done, Retry, … |
| `core/…/strings.xml` | Shared text, plus `core_language_tag` (see below) |
| `server/…/strings.xml` | Connection and server error messages |
| `app/…/strings.xml` | The connection screen and server form |
| `feature/<name>/…/strings.xml` | That feature's screens |
| `feature/settings/…/strings_onboarding.xml` | The welcome screens and the What’s New page |
| `feature/settings/…/strings_whatsnew_entries.xml` | Every What’s New entry |

`python3 scripts/translations.py` lists every file.

## Add a language

1. For each English file, create the same file name in a `values-<code>` folder next to `values`,
   for example `feature/session/src/main/res/values-de/strings.xml`. Use the language code Android
   uses for folder names: `de`, `fr`, `ja`, `pt-rBR`, `zh-rCN`.
2. Translate the text between the tags. Keep each `name`, every placeholder (`%1$s`, `%2$d`) and
   the escaping (`\'`, `\n`, `&amp;`) as they are. Placeholders may move within the sentence.
   Leave out entries marked `translatable="false"`.
3. In `core/src/main/res/values-<code>/strings.xml`, set `core_language_tag` to the language's
   code (`de`, `pt-BR`). Dates and numbers follow it.
4. Add the code to `appLanguages` in `app/build.gradle.kts` (`"de"`, `"pt-rBR"`).
5. Run `python3 scripts/translations.py <code>`. It lists strings that are missing, strings that
   no longer exist in English, and translations whose placeholders differ from the English text.
6. Run `scripts/check.sh`, then try it: install the app and pick the language under the phone's
   Settings › Apps › OpenCode › Language (Android 13 and up), or switch the phone's language.

A string with no translation shows in English, so a language can ship partly translated and new
English text never blocks a build.

## Plurals

Counts use `<plurals>`. English has `one` and `other`; give each language the forms it needs
(`zero`, `one`, `two`, `few`, `many`, `other`). `other` is always required.

## Writing new text

- Put it in the module's `strings.xml`, never in a Kotlin literal.
- In a composable read it with `stringResource` / `pluralStringResource`. Anywhere else (reducers,
  models, enums, errors) use `localized(R.string.x)` / `localizedPlural(R.plurals.x, count, count)`
  from `ai.opencode.core.strings`.
- Call `localized` inside a function or a `get()`, never in a `const val`, a top-level `val` or an
  enum constructor argument: those run once and keep the language the app started in.
- One resource per sentence. Don't build a sentence from translated pieces, and don't compare,
  store or send translated text: use an ID or an enum for that.
- `localized` isn't checked by lint: the number and type of arguments must match the placeholders.
- Format dates and numbers a person reads with `appFormattingLocale()`.

## What stays English

Logs, model prompts and tool descriptions, text sent to a model, exported Markdown, developer-only
screens (Developer Mode, diagnostics, playgrounds) and brand names.

## Known limits

- Text already held in app state when the language changes (an open alert, a loaded list) keeps
  the old language until that state is rebuilt or the app restarts.
- Voice calls transcribe and speak English only.
- Right-to-left layout hasn't been checked.
