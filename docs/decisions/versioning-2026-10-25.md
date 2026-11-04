# Versioning policy (25/10/2026)

**Decided on 25/10/2026:** the policy below, with test builds published in this same repository.

## Release
- Format **MAJOR.MINOR.PATCH** (Semantic Versioning, semver.org). PATCH goes up with a bug fix; MINOR, with a new feature that does not break what existed; MAJOR, with a change that cannot be undone (for example, a backup that no longer opens in an older version). While MAJOR is 0, the app is still taking shape.
- First release: `0.1.0`, tag `v0.1.0`. The tag is created only **with CI green** and is **never rewritten**.
- `versionCode` (the integer Android compares to accept an update) goes up by one with each release, by hand, in `app/build.gradle.kts`.
- `CHANGELOG.md` in the Keep a Changelog format from the first release on.

## Test (debug build)
- Its own identifier, `app.kamayoq.android.debug`, name "Kamayoq Dev": it lives alongside the release on the same device, and the numbering does not mix.
- Number = **commit count** of `main` (`git rev-list --count HEAD`). It only goes up, because published history is never rewritten. CI fetches the full history, and Gradle refuses to build from a shallow clone, which would give a wrong count.
- `versionCode` = the count; displayed name = `<next release>-r<count>`, for example `0.1.0-r27`.
- Each push to `main` publishes a **pre-release** with tag `r<count>` and the APK. Obtainium compares the tag name to know whether there is a new version (Obtainium code, commit `fcd1807`, `lib/app_sources/github.dart`, lines 735–738).

## Evidence (checked on 24 and 25/10/2026)
- **Mihon** (`app/build.gradle.kts`, commit `d001e6f`): `versionName = "0.21.2"`, `versionCode = 38` by hand, tags `v0.21.2`; test builds in the `mihon-preview` repository, tags `r8192` (commit count). Komikku (`komikku-preview`, `r10617`) and Yōkai (`yokai-nightly`, `r6433`) do the same.
- **Akita**: MAJOR.MINOR.PATCH tags in `ai-memory` (v3.2.3), `ai-jail` (v2.11.2), `FrankYomik` (v2.0.3), `FrankMD` (v1.10.0), `ai-usagebar` (v1.43.0); the `ai-memory` `CHANGELOG.md` declares Semantic Versioning and Keep a Changelog; publishing triggered by tag (`FrankYomik`, `.github/workflows/release.yml`); "only creates a tag with CI green" and never rewrites a published tag (ailair.akitaonrails.com/pt-br/workflow). These repositories do not publish test builds.

## Checked on a test device (25/10/2026)
- `r21` installed through Obtainium; `r23` installed over it, without uninstalling: the fixed debug key works.

## Rejected
- CI run number as the version: it also goes up on failures and re-runs, so it does not track the code.
- Pre-release with a fixed name (`dev`): Obtainium would not see an update without changing its version option.
- A separate repository for test builds (the practice of Mihon, Komikku and Yōkai): one more repository to maintain, and a token that can see both.
