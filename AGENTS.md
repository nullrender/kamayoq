# Kamayoq · code rules

Kamayoq (`app.kamayoq.android`): a library and reading app for Android (comics, novels, books), working from the device's own folders, with an optional Kavita server. These rules apply to anyone who writes code here, person or agent.

## Stack
Decided on 23/10/2026 (`docs/decisions/stack-2026-10-23.md`): Kotlin + Jetpack Compose, Android only; `core` in Kotlin with no Android classes, tested on the JVM; text reader foliate-js in a WebView; native image reader; SQLDelight; file access only through SAF; GPL-3.0-or-later; distribution through GitHub and F-Droid.

## Code
- File, folder and code names and commit messages in English. Interface strings are born in English with Brazilian Portuguese alongside, in the same commit.
- A comment explains why, not what, and does not refer to a conversation or a review: the reason is written in the comment itself.
- Accessibility: real buttons and links, labels on icons, 44 px targets, contrast measured on the real combinations, Android Back closes the sheet and keeps the state, a destructive action asks for confirmation first and does not offer Undo when there is no way to recover.
- Sample and test data: well-known real titles are fine as sample works (Asian comics and web novels, public-domain books such as Dom Casmurro). No personal data, no real address, no real person's library.
- Secrets (Kavita key, real addresses, signing keys) never in the repository; local configuration stays outside git.

## Method
Sources: dora.dev, "Trunk-based development"; Fabio Akita, public skills in `akitaonrails/my-skills` (commit `f9c4384`).
- Every implemented rule becomes a readable automated test, named in Portuguese ("dado… quando… então…", that is, "given… when… then…"), logic before screen.
- Test before code: CI shows the test failing and then passing. The red fails on behavior, not on compilation: the new API goes in first, with no effect, together with the test.
- Every bug becomes a regression test written before the fix.
- A characterization test (it pins down what an outside service does today) goes in green: there is no honest red for it (`docs/decisions/kavita-step-3-2026-10-30.md`).
- Measurements of the reading engine go in green too: the emulator rehearsal and the hostile book's positive control (the same book with the defenses off) measure what the WebView and foliate-js do, and a red there would be staged.
- Integration tests run against the real service (Kavita in a container, in CI); never against a hand-written imitation.
- One change per commit, small enough to review; CI green before moving on.
- Work directly on `main`, in small commits. A short branch only for parallel work or for a large, risky change that spans several days (for example, a database schema migration).
- Wait for CI by the run number (`gh run list --limit 1 --json databaseId`), not by `--commit` with the short hash: the filter does not find the run.
- A repeated mistake becomes a line in this file.
- Test names in `androidTest` have no commas: DEX refuses them, and the error only shows when the emulator build runs. JVM test names may keep them.
- A documentation-only commit may carry `[skip ci]`. The GitHub documentation does not say which commit of a push with several commits is checked: push the code commit separately, before the documentation ones.

## How to check
No Java process (Gradle, Kotlin daemon) runs on the maintainer's machine: the commands below run in CI, and the result is read there by the run number. Node tests may run locally.
- `./gradlew :core:test :data:test`: rules and storage, on the JVM.
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: app (Robolectric), lint and APK.
- Tests against the real Kavita: the `kavita` CI job.
- `./gradlew :epub-fixtures:test` and `node --test third_party/upstream-check.test.mjs app/src/jsTest/*.test.mjs`: the generated EPUBs, the copy of foliate-js and the reader's own scripts.
- Text reader in a real WebView: the `Emulator` workflow (`./gradlew :app:connectedDebugAndroidTest` on an emulator).
