# Kamayoq

English · [Português (Brasil)](README.pt-BR.md)

A library and reading app for Android: comics (manga, manhwa, webtoons), novels and books, from your own folders, with an optional [Kavita](https://www.kavitareader.com) server.

> **Status: in early development.** Nothing here is ready for everyday reading yet. Image chapters (CBZ and folders of images) can be read today, from your folders or from your Kavita server; EPUB is next.

## Features

Working in the code today:

- Lets you choose a base folder and reads the works in its `Library` folder by a [folder convention](docs/decisions/folder-convention-2026-10-24.md): type folders (`Comics`, `Novels`, `Books`), one folder per work, chapters as CBZ/ZIP files or folders of images, ordered by volume and chapter number.
- Shows the works and their chapters, and reads image chapters in a vertical strip.
- Keeps the reading position and marks a chapter as read when its end is reached, following the [progress contract](docs/decisions/progress-contract-2026-10-24.md).
- Writes a backup of everything except the works' files to the `Backups` folder. Restoring it is not done yet.
- Connects to your own Kavita server with its address and API key: shows the libraries you include, downloads chapters to read offline and keeps the reading position in step both ways, asking you when both sides changed.

Planned, in this order: EPUB and other text formats through [foliate-js](https://github.com/readest/foliate-js), then the visual base and the app's own extensions.

## Download

Test builds only. Every change on `main` that passes CI is published as a pre-release named `r<number>`, where the number is the commit count. [Obtainium](https://github.com/ImranR98/Obtainium) can track them.

*Requires Android 12 or higher.*

## Building

The app is Kotlin and Jetpack Compose. `core` is plain Kotlin with no Android classes, so its rules are tested on the JVM; `app` is the Android app.

With JDK 21:

```
./gradlew :core:test             # rule tests (JVM)
./gradlew :data:test             # storage tests (JVM, SQLite)
./gradlew :app:testDebugUnitTest # app tests (Robolectric)
./gradlew :app:lintDebug         # Android lint
./gradlew :app:assembleDebug     # debug APK
```

CI runs the same five steps on every push and pull request.

## Project layout

| Path | What it holds |
|---|---|
| `core/` | Rules in plain Kotlin: folder convention, reading progress, backup format |
| `data/` | What the app stores and fetches: the database, the backup and the Kavita client, in plain Kotlin |
| `app/` | The Android app |
| `docs/decisions/` | Technical decisions, each with its sources and what was discarded |
| `docs/research/` | Research behind the larger steps (Kavita, EPUB, tests) |

## Contributing

Not open to code contributions yet; see [CONTRIBUTING.md](CONTRIBUTING.md). Security issues: see [SECURITY.md](SECURITY.md).

## Disclaimer

Kamayoq hosts no content. It reads files you already have and, if you set one up, your own Kavita server.

## License

Kamayoq is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. See [LICENSE](LICENSE).
