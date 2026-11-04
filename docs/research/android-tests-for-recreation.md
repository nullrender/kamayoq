# Android tests for screen recreation

Research from 29/10/2026. With option 1 (app objects created once, `docs/research/one-backup-at-a-time.md`), the fix lies in where the opener is created, and that can only be tested by running Android. The question: Robolectric or an emulator in CI, according to the official documentation, what other apps do, and Akita.

## Official Android documentation (read on 29/10/2026)
- Local test (on the computer or in CI, no device) vs. instrumented test (device or emulator): "the most important distinction for app developers". Robolectric appears as "Big local test: You can use an Android simulator that runs locally". https://developer.android.com/training/testing/fundamentals
- Robolectric: "a simulated Android environment inside a JVM, without the overhead and flakiness of an emulator"; it is for "simulate the launch of an Activity and test the logic inside it, calling all the lifecycle methods". Limits: "not a complete replacement for an emulator"; cases that need a device include "unsupported features, like `WebView`". For unit tests, "only use Robolectric ... as a last resort: with legacy code or when using APIs that depend on Android classes". https://developer.android.com/training/testing/local-tests/robolectric
- Strategy: "consider the lowest layer of the pyramid that can give the team the right level of feedback"; unit and component tests run in CI on every commit; application tests run later. https://developer.android.com/training/testing/fundamentals/strategies
- Recreating the screen in a test: `ActivityScenario.recreate()`; "`ActivityScenario` is a cross-platform API that you can use in local unit tests and on-device integration tests alike". https://developer.android.com/guide/components/activities/testing

## What three apps do (code checked on 29/10/2026)
| App | Repository, branch, commit | Tests |
|---|---|---|
| Mihon | `mihonapp/mihon`, `main`, `e588e04` | JVM only: CI runs `./gradlew testDebugUnitTest`; no files in `androidTest` |
| Kotatsu | `KotatsuApp/Kotatsu`, `devel`, `34f6e52` | 6 files in `androidTest` (for example `AppBackupAgentTest.kt`); no CI workflow in the repository runs them (there are only moderation and website workflows) |
| AntennaPod | `AntennaPod/AntennaPod`, `develop`, `047e350` | both: Robolectric 4.16 in `libs.versions.toml` and an "Emulator Test" job in CI (`checks.yml`, `reactivecircus/android-emulator-runner`) |

## Akita (sources of the project's working method)
- `ailair.akitaonrails.com/pt-br/workflow` (read on 29/10/2026): talks about "a regression test written before the fix" and green CI before the tag; it says nothing about test types, UI, emulators or simulators.
- Fabio Akita's book (analysis from 15/10/2026): nothing about Android testing.
- The method rule (`AGENTS.md`: "Integration test against the real service (Kavita in a container, in CI); never against a hand-written imitation") is about the service (Kavita). Robolectric is an Android simulation maintained by Google, not written in the project; even so, it is a simulation, not real Android. The sources read do not say where Akita stands on this.

## Inference
- The current case (recreate the screen and check that the opener and the backup state are the same) is Activity lifecycle, exactly the use the guide gives Robolectric, and it runs in CI on every push without an emulator.
- The emulator will be needed anyway in the EPUB step: the text reader is foliate-js in a WebView, and Robolectric does not run WebView. Adding the emulator now would bring that cost forward (extra minutes on every CI run) for a test that Robolectric covers.
- A project maintained by one person, with CI on every commit: fast CI matters more than maximum fidelity in this case.
