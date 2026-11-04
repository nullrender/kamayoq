# Other readers, TalkBack and the emulator in CI (step 4, EPUB)

Research from 30/10/2026 (`date`: Fri 30 Oct 23:24 -03). Read: the code of readest, readest/foliate-js, foliate-js, foliate (GTK), LibreraReader, readium/kotlin-toolkit, readium/swift-toolkit, LNReader, KOReader, Myne, Book's Story, Chromium and Accessibility Test Framework; the official docs of Android, GitHub, Thorium and axe-core; public CI runs through the GitHub API; and this repository's CI.

Convention: **[V]** verified now in the cited source; **[I]** inference; **"not available"** is data that could not be obtained.

## Conclusions first

1. **The project most similar to Kamayoq is Librera, not Readest.** Librera already runs foliate-js in a native Android WebView (Java, no Tauri), has a CSP, a private https origin and 20 on-device tests with an EPUB written by the test itself. It has no emulator CI. The caveat: Librera draws the page into an offscreen bitmap, so the text does not reach TalkBack. It is a model for the bridge, the server and the tests, not for the screen.
2. **Scroll mode with a screen reader is the established practice.** Readium Swift forces scrolling with VoiceOver ("pagination breaks the screen reader"), Thorium forces scrolling to avoid problems with CSS columns, and the Readest maintainer pointed to scroll mode as the TalkBack workaround. Readium Kotlin does not detect TalkBack. Chromium exposes offscreen columns as visible, which explains the symptom.
3. **The emulator on every push costs too much.** Fifteen minutes of emulator on each push would have added, in the repository's first week (157 pushes), about 2,355 more CI minutes, well above what the whole CI spent in that period. Recommendation: a separate workflow file, triggered by `paths` and by hand, with no initial `schedule`.
4. **Nobody measured any of this on a 2 vCPU machine.** All times below come from public projects, whose machine has 4 vCPU and 16 GB; a private repository gets 2 vCPU and 8 GB. The documentation says there is acceleration on hosted Linux, 2 vCPU included, but the time and failure rate for Kamayoq are "not available".
5. **For WebView, use the `default` or `google_apis` image, not ATD.** There is public proof of a real WebView on `default` (Kiwix) and `google_apis` (Readest). For ATD there is only a hint that the view inflates (AntennaPod). The documentation does not say whether ATD has a usable WebView, and the Chromium version of each image is "not available".

## Part 1. How the readers handle EPUB

### Sources (default branch via the API, 30/10/2026)

| Project | Branch | Commit | License |
|---|---|---|---|
| readest/readest | `main` | `716aadae` | AGPL-3.0 |
| readest/foliate-js (submodule; fork, 267 ahead of and 14 behind upstream) | `main` | `0c7a0edc` | MIT |
| johnfactotum/foliate-js | `main` | `78914aef` | MIT |
| johnfactotum/foliate | `gtk4` | `67b6676d` (submodule `399248a6`) | GPL-3.0 |
| foobnix/LibreraReader | `master` | `d09b8f36` | GPL-3 |
| readium/kotlin-toolkit | `develop` | `89b96c20` | BSD-3-Clause |
| readium/swift-toolkit | `develop` | `e20d5cfc` | not checked |
| lnreader/lnreader | `master` | `dc394e85` | MIT |
| koreader/koreader | `master` | `80127739` | AGPL-3.0 |
| Pool-Of-Tears/Myne | `main` | `7ec1fdb1` | Apache-2.0 |
| Acclorite/book-story | `master` | `464891ec` | GPL-3.0 |

### Comparison [V, unless marked]

| | Engine | How the file arrives | Position | When it saves |
|---|---|---|---|---|
| **Readest** | foliate-js (fork) in Tauri/WebView | `rangefile` scheme, range in the URL (`src-tauri/src/range_file.rs:1-17`, `services/nativeAppService.ts:341-352`) | CFI in `config.location` (`store/readerStore.ts:484-546`) | 1 s debounce + 500 ms, and immediately on `visibilitychange`/`pagehide` (`useProgressAutoSave.ts:57-64, 84-105`) |
| **Librera** | foliate-js (copy in assets) in WebView, rendered to a bitmap | `shouldInterceptRequest` on `https://librera.foliate`; `fetch` + `blob()` of the whole book (`FoliateServer.java:31`, `librera-bridge.js:640-645`) | CFI (`FoliateDocument.java:310-390`), plus a cached page index (`FoliateIndex.java`) | not checked |
| **Foliate GTK** | foliate-js in WebKitGTK | its own schemes (`foliate:`) (`webview.js:6-30`); the book becomes a `blob:` | CFI in `lastLocation` (`book-viewer.js:864-865`) | on each `relocate`, with a 1 s debounce on storage (`utils.js:92`) |
| **Readium** | its own HTML + Readium CSS in WebView | `shouldInterceptRequest` on `https://readium_package/`, streaming from the container and injecting CSS/JS (`WebViewServer.kt:50-55, 103-133`) | `Locator` JSON: href, fragment, progression, position, total progression (`Locator.kt:43-68`) | the app observes `currentLocator` and saves (`docs/guides/navigator/navigator.md:270-294`) |
| **LNReader** | sanitized HTML in react-native-webview | extracts to disk on import (`services/epub/import.ts`); HTML goes through `source.html` | chapter percentage | end of scroll/page turn, via `postMessage` (`assets/reader/js/core.js:143-146, 626-629`) |
| **KOReader** | crengine (C++), no WebView | its own engine with a disk cache (`credocument.lua:1509-1522`) | xpointer, plus `percent_finished` and `last_epubcfi` (`readerrolling.lua:42-54, 291, 340-343`) | in `onSaveSettings`, on close and at a configurable interval (`readerui.lua:850-877`; the interval is `auto_save_settings_interval_minutes`) |
| **Myne** | Compose (`LazyColumn`), no WebView | reads the zip in memory and loads chapter bodies on demand (`EpubParser.kt:121-188`) | chapter index + offset (`ReaderProgress.kt:36-37`) | on scroll (`ReaderActivity.kt:136-149`, `ReaderViewModel.kt:247-279`) |
| **Book's Story** | its own Compose, no WebView | parses the EPUB into nodes and keeps a cache (`EpubContentParser.kt`) | node + inner offset (`Book.kt:36-37`) | 200 ms debounce on scroll and on exit (`ReaderLayoutContinuous.kt:240-262`, `ReaderModel.kt:380-388`) |

### Chapters from the table of contents, with one large file holding several chapters

This is the case of the compiled EPUB, where each table-of-contents item points to `file.xhtml#id`.

- **foliate-js solves it.** `TOCProgress` groups the items by spine file and, with the visible `Range`, compares each fragment with `range.comparePoint` to find the current item (`progress.js:19-56`). This requires the document to be loaded. Foliate GTK and Readest use this `tocItem`.
- **KOReader solves it through xpointer precision.** `getAccurateTocIndexByXPointer` breaks ties between items on the same page, `validateAndFixToc` fixes an out-of-order table of contents, and there is an alternative table of contents built from headings (`readertoc.lua:161-200, 408-440`).
- **Myne slices the content:** it stores `fragmentId` and `nextFragmentId` and reads only the stretch between them (`EpubParser.kt:163-184, 479-499`). But its heuristic for a trustworthy table of contents requires more than 3 items and **more than one distinct file** (`:304-307`); the single-file EPUB fails it and falls back to the spine, becoming one chapter. [I, reading of the code, not run]
- **LNReader does not solve it:** it strips the `#fragment` and keeps one label per file (`EpubParser.cpp:255-259`); a chapter is a spine item.
- **Book's Story:** the content comes from the spine (`EpubContentParser.kt:193-213`) and chapters derive from node headings (`SaveBookContentUseCase.kt:160-178`). I did not see the EPUB's table of contents used in the parser. [I]
- **Readium:** the title comes from the table of contents' href map (`EpubNavigatorFragment.kt:1100-1110`), and the key does not include a position inside the file. I did not check how it handles several items in the same file.

### Paginated or scroll; the rest

- **Both paginated and scroll modes exist** in Readest, Librera, Foliate, Readium and LNReader. KOReader has pages and scrolling. Book's Story has `ReaderLayoutPaged` and `ReaderLayoutContinuous`.
- **Rotation:** Readest declares `configChanges` with orientation, so the Activity is not recreated (`AndroidManifest.xml:51`). Readium keeps the `Locator` in the `Bundle` (`EpubNavigatorFragment.kt:590-592`) and recreates the page.
- **Process death:** the official documentation says `saveState` fits within a shared 1 MB limit, and does not say it preserves the main frame's scroll position or the JavaScript state ([manage-state](https://developer.android.com/develop/ui/views/layout/webapps/manage-state)). In the Readest, Readium and Librera files I read, I did not see the WebView's `restoreState` used to reopen the book (in Readium, only a page adapter's `saveState`); what comes back is the saved position. LNReader not checked. A `blob:` origin would not survive either. [I]
- **Death of the renderer only:** the official guide says the WebView cannot be reused and that reloading the same page may repeat the failure ([managing-webview](https://developer.android.com/develop/ui/views/layout/webapps/managing-webview)). I found no `onRenderProcessGone` in the Librera files read (`grep` in `FoliateRenderer.java` and `FoliateDocument.java`). Foliate GTK only logs the failure (`webview.js:77-90`).

### Book scripts and security

- **The foliate-js README is categorical:** it does not support book scripts, `allow-scripts` on the iframe defeats the sandbox, and it is "imperative" to use a CSP blocking everything except `'self'` (`README.md:75-84`). The iframe has `sandbox="allow-same-origin allow-scripts"` (`paginator.js:244`).
- **Foliate GTK and Librera follow this:** `script-src 'self'` (`reader/reader.html:4`; `librera.html`, CSP meta). Librera also denies every URL outside the origin, without relying on `blockNetworkLoads`, refuses main-frame navigation and turns off `allowFileAccess` and `allowContentAccess` (`FoliateServer.java`, `FoliateRenderer.java:204-205, 217-232`).
- **A precaution worth copying:** `shouldOverrideUrlLoading` must not refuse subframes, or it cancels the paginator's `blob:` URLs and the book does not appear (`FoliateRenderer.java:218-223`).
- **Readest is looser:** a CSP with `'unsafe-inline'` and `'unsafe-eval'` (`tauri.conf.json:25`), offset by DOMPurify (`transformers/sanitizer.ts:39`) and `allowScript: false` by default (`constants.ts:401`). It has more points of failure. [I]
- **Readium runs JS with the `Android` interface injected** (`R2EpubPageFragment.kt:171, 182`) and I found no blocking of book scripts in the files checked. Do not copy.
- **Bridge:** Librera and Readium use `addJavascriptInterface`. The official guide advises against it: no origin control and present in every frame, iframes included; it recommends `WebViewCompat.addWebMessageListener` with `allowedOriginRules`, WebView 82+ and Jetpack Webkit 1.3.0+ ([JSBridge](https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge)). With the book in a same-origin `blob:`, the origin rule does not separate the book from the shell; what protects is the CSP, and the received payload must be validated. [I]
- **ES modules and `file://`:** Librera records that `import` fails on `file://`, hence the https origin (`FoliateServer.java:22-27`). The official guide also advises against `file://` ([load-local-content](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)).

### Memory and large files

- **Range bug:** Readest records that, on Android, the WebView re-applies the offset to an intercepted response, corrupting reads that start at a non-zero offset (Chromium 40739128, `range_file.rs:4-10`). The way out was to put the range in the URL query. If Kamayoq reads in parts through `shouldInterceptRequest`, this needs a test before it can be trusted. [I]
- **Import in JS blows up:** `V8 javascript OOM` with EPUBs above 100 MB (readest#553). Readest moved zip opening to Rust (`utils/tauriEpubBridge.ts:1-32`). In Kamayoq, the equivalent is reading metadata and cover in Kotlin. [I]
- **Librera** loads the whole book into a `Blob`; the maximum size it handles is "not available".
- **Readium:** position calculation has a slowness complaint on `.lcpl` (#744), with a cause I did not analyze.
- **Paginating the whole book is expensive:** foliate only knows a section's pages after rendering it, and Librera stores that pass in an index keyed by book, size, font and CSS (`FoliateIndex.java:16-25`). foliate estimates location by bytes (`SectionProgress`, 1,500 bytes per location, `view.js:242`).

### Tests

| Project | How it tests a real WebView | CI |
|---|---|---|
| Librera | `FoliateEngineDeviceTest.java` (AndroidX, device), EPUB generated in the test, `FoliateHostActivity` only in the debug build | no emulator (only `issue-title.yml`) |
| Readest | `*.android.test.ts` lane: Vitest on the computer, CDP + `adb input` on the installed app (`vitest.android.config.mts`, `docs/testing.md:87-136`) | nightly, by label or manual; does not block PRs |
| Readium | no `androidTest` in the repository (empty `find`) | no emulator |
| LNReader | Jest, no real WebView | no emulator |
| Foliate-js | `tests/epubcfi-tests.js`, `tests.html` in the browser | ESLint |
| Myne, Book's Story | only a sample on-device test, or none | no emulator |
| KOReader | Lua tests (`spec/unit`) | does not use WebView; its "emulator" is KOReader's own [I] |

### What to copy and what to avoid

**Copy:**
1. From Librera: shell with CSP, private https origin, everything that is not the book denied, `FoliateHostActivity` in debug, test EPUB generated in the test, and the `aLocationFindsItsPageAgain` test (the same CFI points to the same words with another font, `FoliateEngineDeviceTest.java:751-793`).
2. From Readest: save immediately on `visibilitychange`/`pagehide`, because Android freezes the WebView before the debounce (`useProgressAutoSave.ts:84-105`, issue #5859); do not save on the first `relocate`, so as not to overwrite the position (#4222, `:18-23`).
3. From foliate: `TOCProgress` for chapters.
4. From KOReader: store the position only on an explicit page turn, so that rotation and mode changes do not make the point drift (`readerrolling.lua:42-54`).
5. From Readest (#1983): prevent a tap during loading from sending the book back to the first page.

**Avoid:**
1. A loose CSP relying only on a sanitizer.
2. `canvas`/bitmap as the reading screen, because it erases the text for TalkBack (Librera is the warning; KOReader is also opaque to TalkBack, #5958).
3. Myne's heuristic, which rejects the single-file case.
4. Relying on `restoreState`.

**Open foliate-js bugs that a fork inherits:**
- #149: `#locked` stays `true` forever if `snap()` calls `#goTo()` in the middle of a page turn; Android Chrome.
- #84: layout shifts when selecting text in Chrome Android. Readest has a workaround, which according to the maintainer works only in the WebView.
- #146: needs `Array.prototype.at`, and the error is swallowed in old WebViews.
- #157: a large iframe width interferes with `media queries`.

## Part 2. TalkBack in the WebView

### How it works [V, unless marked]

- **The iframe enters the tree:** the TalkBack report in Readest (#2276) walks through the book's text, which is in an iframe, by swiping through the content. On Linux, with WebKitGTK, Orca could not reach the reading area (foliate#1518, readest#932). Android and Linux are different engines; what matters for Kamayoq is Android. [I]
- **Offscreen columns remain accessible:** in Chromium, `IsVisibleToUser()` is just `!IsInvisibleOrIgnored()` (`content/browser/accessibility/browser_accessibility_android.cc:541-543`, `chromium/chromium` `main` `296a15b5`). There is a separate `IsOffscreen` (`ui/accessibility/platform/browser_accessibility.cc:934`), and I did not see that check tied to visibility here. A column clipped by `overflow` stays exposed. [I, combining the two readings]
- **Observed effect:** in #2276, the screen changes as the TalkBack focus moves, but the page indicator stays still. Something scrolls the container without the paginator knowing. Chromium has `scrollToMakeNodeVisible` (`WebContentsAccessibilityImpl.java:1501`), although the stretch read is about focus restoration. Exact cause in #2276: "not available"; the issue was closed on 05/02/2026 and I did not identify the fix.
- **Screen reader focus may not generate an event:** foliate listens to `focusin` to scroll to the element (`readest/foliate-js paginator.js:1893-1898`). The Thorium documentation says screen readers use a separate buffer, the page falls out of sync, and the app only knows the position if there is a standard web event ([Thorium](https://thorium.edrlab.org/en/docs/300_accessibility/_print/)). That is desktop; on Android, the consequence is Readest's #3864: position not saved when reading with a screen reader without scrolling.
- **`aria-hidden` in the book:** a report that it did not work in Readest (#6206), closed without a clear conclusion. Test it.

### Who works with paginated mode

| Project | Behavior |
|---|---|
| Readium Swift | forces scrolling with VoiceOver: "pagination breaks the screen reader" (`EPUBNavigatorViewModel.swift:420-424`) |
| Thorium | forces scrolling "in order to avoid issues with CSS columns" ([th3](https://thorium.edrlab.org/en/th3/500_accessibility/_print/)) |
| Readest | paginated mode partly handled; the maintainer pointed to scroll mode as a workaround (#2276) and says they test with TalkBack and VoiceOver (#3477) |
| Readium Kotlin | does not detect TalkBack (`isTouchExplorationEnabled` with no search results); #352, inaccessible decorations, open |
| Foliate upstream | paginator without `aria`; Orca does not read |
| Librera (foliate) | bitmap; text not accessible through the WebView [I] |
| KOReader | inaccessible to TalkBack (#5958) |

### What Readest did, to copy the idea

In `readest/foliate-js paginator.js` and `src/utils/a11y.ts`:
- `aria-hidden` on preloaded offscreen sections, so swiping does not enter them (`paginator.js:2085-2106`). It does not use `inert`, which would block the pointer.
- A "skip to last position" link as the first element of the body (`a11y.ts:54-88`).
- A "continue to the next section" link at the end of the section (`a11y.ts:89+`), taking care not to create an empty column (#4126, #5924).
- `aria-live` with "Page N" (`PageNavigationButtons.tsx:88-90`).
- Page and section buttons always present, even with `opacity-0`, with a label (`:101-181`). Screen reader users asked for this (#3036, #3290).
- `cfi-inert` on injected nodes, so they do not shift the CFI (`paginator.js:486-490`).

There is a cost: `sr-only` in the WebView is a workaround for not having a native button there. Kamayoq has Compose around the WebView.

### The accessibility rule in front of a WebView

Proposal [I]:
- **Real buttons and labels:** every control stays in Compose, outside the WebView. Inside the WebView, only the book's text.
- **Tap zones:** in the WebView they have no accessibility node, and screen taps do not exist for someone using explore by touch. With TalkBack on, replace them with Compose buttons labeled "Previous page", "Next page", "Previous chapter", "Next chapter". Readest shows that swiping can close menus (#3035): custom gestures must give way.
- **44 px:** see the divergence below. In Readest, a real bug came from a control with the right visual height and a 32 x 32 touch area, measured only in the real WebView (#5401, `header-touch-targets.android.test.ts`).
- **Back:** with sheets in Compose, `BackHandler` handles this. The WebView must not have history: refuse main navigation and handle internal links through foliate. Readium had the problem of an internal link swapping the whole context (#27).
- **Mode with TalkBack:** forcing scroll when `isTouchExplorationEnabled` is true would align with Readium Swift and Thorium. It is a product decision; Thorium, since 3.3, requires ticking a box instead of detecting on its own. **Open decision.**
- **Hyphenation:** the product design already plans a TalkBack test. Readium CSS advises against a JS polyfill because of screen reader problems ([CSS19-api](https://readium.org/css/docs/CSS19-api.html), via search, page not opened).

### What can be tested automatically and what needs a person

| Tool | Good for | Limit | Status |
|---|---|---|---|
| Compose `enableAccessibilityChecks` + `tryPerformAccessibilityChecks`, artifact `ui-test-junit4-accessibility`, Compose 1.8+ ([doc](https://developer.android.com/develop/ui/compose/accessibility/testing)) | labels, contrast, target, order in the Compose shell | does not see the book's text; only "some" checks | **[V]** Kiwix uses it (`HelpScreenRouteTest.kt:60, 81`). Working under Robolectric: not tested |
| Espresso `AccessibilityChecks.enable()`, `setRunChecksFromRootView(true)` ([doc](https://developer.android.com/training/testing/espresso/accessibility-checking)) | Views; the shell is Compose | the doc does not mention WebView | [V] |
| Accessibility Test Framework | basis of the two above | in front of a WebView it lowers severity and recommends manual testing (`TouchTargetSizeCheck.java:248-251`); `SpeakableTextPresentCheck` has its own result for web (`RESULT_ID_WEB_CONTENT`, `:49-50`) | [V] Apache-2.0, `master`; last push to the public mirror on 03/04/2024 |
| Espresso-Web (`androidx.test.espresso:espresso-web`, `onWebView()`) | real DOM in the WebView | requires JS; the doc does not mention iframes or accessibility; the doc itself asks for care with `forceJavascriptEnabled` | [V] Kiwix uses it on a `default` emulator |
| CDP through the `webview_devtools_remote_<pid>` socket + `adb input` | geometry, selection, touch area, everything in the real WebView | debug build; Node in CI | [V] Readest's method |
| UI Automator (accessibility tree) | check whether an offscreen column node is exposed | the doc mentions neither WebView nor `dumpWindowHierarchy` | **not verified**; a small trial settles it |
| axe-core in Chromium | the shell's HTML and injected nodes | finds "on average 57%" of WCAG issues; must be in each iframe; `script-src 'self'` hinders injection | [V] MPL-2.0, `v4.14.0` from 05/10/2026. Low return: the content belongs to the book's author [I] |

**Needs a person with TalkBack:** whether the speech makes sense, the real swipe order, entering and leaving the iframe, end of section, page announcement, and whether the saved position follows what was read. The official guide says the framework "can't detect issues that occur when the app is running on a device" and asks for manual testing ([testing](https://developer.android.com/guide/topics/ui/accessibility/testing)). Scripting TalkBack in the emulator: I found no project that does it. It is left for manual testing with the finished app (decided on 30/10/2026); suggested script, six cases: open a book; swipe to the next column and the next section; close and reopen at the position; open and close a sheet with Back; go to a chapter through the table of contents; rotate.

**Target divergence to decide:** the product design asks for 44 px, the ATF check uses 48 dp (32 at the edge) (`TouchTargetSizeCheck.java:161-163`). If automated tests come in, either the design goes up to 48 or the validator is adjusted. Keep 44 or go up to 48?

## Part 3. Emulator in CI

### android-emulator-runner [V]

- `ReactiveCircus/android-emulator-runner`, branch `main`, `ed009f53` (26/08/2026). Latest release `v2.38.0` (05/07/2026), tag `v2` at `4c44018e`. Runs on `node24`, Apache-2.0, 99 open items. Maintained: commits in August, updating command-line tools.
- **KVM:** udev rule `KERNEL=="kvm", GROUP="kvm", MODE="0666"...` + `udevadm` (`README.md:15-22`). Without it, the runner user cannot open `/dev/kvm`; a third-party vendor says the emulator then falls back to software (unofficial source).
- **Snapshot cache:** `actions/cache` of the `~/.android/avd/*` folder, one step that creates the snapshot and another that tests (`README.md:150-190`).
- Accepts `aosp_atd`, `google_atd`, `default`, `google_apis`, `*_ps16k` (`action.yml:14-15`).
- Real network risk: a bad image download failed the job; Readest installs the image beforehand with 3 attempts (`android-e2e.yml:64-74`).

### GitHub: KVM on hosted runners [V, 30/10/2026]

- Official doc: "GitHub-hosted Linux runners support hardware acceleration for Android SDK tools" ([runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)). Changelog from 02/04/2024: 2 vCPU also got it ([changelog](https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/)). Neither distinguishes public from private. On the same page, the `ubuntu-latest` machine is **4 vCPU/16 GB in public and 2 vCPU/8 GB in private**, 14 GB of SSD.
- Nobody has confirmed `/dev/kvm` in this repository: "not available". An `ls -l /dev/kvm` step settles it in seconds.
- Cache: 10 GB per repository ([billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions)).

### System images [V, official XML read on 30/10/2026]

Source: `https://dl.google.com/android/repository/sys-img/<tag>/sys-img2-3.xml`. x86_64, stable channel, zip size:

| API | `aosp_atd` | `google_atd` | `default` | `google_apis` | `google_apis_playstore` |
|---|---|---|---|---|---|
| 31 | 550 MB | 654 | 657 | 1471 | 1424 |
| 33 | 582 | 679 | 694 | 1708 | 1670 |
| 34 | 615 | 700 | 721 | 1564 | 1511 |
| 35 | 690 | 813 | 782 | 1739 | 1762 |
| 36 | 746 | 860 | 844 | 1895 | 1925 |

API 32 exists in all of them. `default` means an AOSP image without Google services.

**The Gradle Managed Devices page is out of date:** it still says "ATDs currently support only API level 30" ([GMD](https://developer.android.com/studio/test/gradle-managed-devices)), but the official repository has ATD from 30 to 37.0.

**ATD and WebView:**
- [V] The doc lists Chrome and Browser2 among the removed apps, says it turns off hardware rendering, and does not mention WebView.
- [I] A hint in favor: AntennaPod's `TextOnlyFeedsTest` (`develop` `047e3507`) opens an episode; the screen uses `ShownotesWebView` (`feeditem_fragment.xml:213`) and runs on `aosp_atd` 30 and 36 (`checks.yml:113-126`). Inflating a WebView without a provider throws a known exception, so the view exists. This proves neither rendering nor iframes.
- I did not open the image (the zip is hundreds of MB). I looked for a public project with Espresso-Web on ATD and found none; the search was not exhaustive.
- Chromium version of any image: "not available". The image carries a fixed version; a real test device has the current one. [I] As a diagnostic, record `adb shell dumpsys webviewupdate` on the first run (command not tested here).

**GMD or runner:** GMD is Gradle configuration (`testOptions.managedDevices`), with `aosp-atd`/`google-atd`, sharding, and needs `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect` on a server. Now in Android defines GMD devices (`GradleManagedDevices.kt:30-32`) but uses the runner in CI. The five measured projects use the runner. The AGP version here is 9.4.1; GMD with it not tested.

### Public measurement (30/10/2026)

Method: script `gh api repos/<r>/actions/workflows/<wf>/runs?per_page=50&status=completed[&event=...]`, then `/actions/runs/<id>/jobs?filter=latest`; job duration `completed_at - started_at`; **median only of successful jobs**; failure rate = failures / (successes + failures), cancelled ones counted apart. `run_attempt` > 1 counts as a retry.

| Project, job | Sample | Window | Ok / fail / canc. | Failure | Median | Range | Image |
|---|---|---|---|---|---|---|---|
| AntennaPod, `Emulator Test`, 5 variants (all events) | 40 runs, 185 jobs | 09–31/10 | 185 / 0 / 0 | 0.0% (**with 3 internal attempts**) | 10.6 | 6.4–17.0 | ATD 30, 36; `default` 23 |
| AntennaPod, `push` only | 40 runs, 178 jobs | 08/09–29/10 | 178 / 0 / 0 | 0.0%; 2 runs retried | 11.5 | 6.2–17.0 | same |
| Now in Android, `androidTest` (`push`) | 40 runs, 80 jobs | 15/09/2025–20/10/2026 | 62 / 1 / 17 | 1.6% | 18.7 | 5.9–25.0 | no `target`, hence the default `default`; API 26, 34 |
| AnkiDroid, `merge_group` | 40 jobs | 27–30/10 | 39 / 0 / 1 | 0.0% | 22.6 | 17.7–29.1 | `google_apis` 36 |
| AnkiDroid, `pull_request` | 40 jobs | 29–30/10 | 35 / 1 / 4 | 2.8% | 23.8 | 18.1–28.5 | same |
| AnkiDroid, weekly | 3 runs, 12 jobs | 12–26/10 | 12 / 0 / 0 | 0.0% (small sample) | 35.9 | 17.6–41.5 | 36, 37, 16k, tablet |
| Readest, `android-e2e` (nightly) | 40 runs | 21/09–30/10 | 27 / 12 / 1 | **30.8%** | 17.1 | 13.2–19.4 | `google_apis` 34 |
| Kiwix, `Automated tests` (`push`, six APIs) | 40 runs, 228 jobs | 03–28/10 | 60 / 35 / 133 | 36.8% | 44.5–107.5 per API | up to 125.7 (broad sample) | `default` |
| Kiwix, `on Tablet`, `Branded`, `PlayStore` | same, 684 jobs | 03–28/10 | 644 / 7 / 33 | 1.4%, 0.0%, 1.9% | 8.8–16.0 per variant | | `default` |

Notes:
- **AntennaPod:** the 0% hides the flakiness. `runEmulatorTests.sh` does `runTests || runTests || runTests` ("Retry tests to make them less flaky"). How many retries happened: "not available".
- **AnkiDroid:** wraps the image download in `nick-fields/retry`, citing the runner's bug #496 (`tests_emulator.yml:199-200`), and uses `adb_retry.sh`. So AnkiDroid's rate also has some built-in infrastructure retrying.
- **Now in Android, PRs:** the unfiltered sample gave 20 failures and 20 cancellations, all in Renovate PRs; I did not use it as the emulator rate, because they reflect a dependency update. I did not check the cause.
- **Readest is the closest analogue** (real WebView, real book, real touch) and the least stable. Of the 12 failures, 2 were in AVD creation (one, `Error on ZipFile`), and 10 in the lane. In the 5 logs I read: 4 were `timed out waiting for reader page` in `selection.android.test.ts`, and 1 was a `double-click` assertion (`expected 'Chapter' to be 'Rabbit'`). It is probably more flakiness than regression. [I] Its own configuration has `retry: 1`.
- **Readest time per step:** APK 8.3–11.3 min (Rust), AVD creation 0.9–1.1, image 0.8–1.0, lane 2.8–3.3 min. The emulator itself costs little; the build is what weighs.
- **Kiwix, per API, `Automated tests`:** 25: 20 ok/3 failures/15 canc., median 44.5; 30: 6/4/28, 79.8; 33: 17/4/17, 46.1; 34: 11/0/27, 60.9; 35: 3/13/22, 107.5; 36: 3/11/24, 88.8. With `fail-fast: true`, one red cancels the rest. API 35 and 36 concentrate the failures and the times. Cause: not investigated.

**What is NOT measured:** time, failure rate and memory use of the emulator in a 2 vCPU private repository; any Kamayoq number (emulator, WebView, foliate, boot, snapshot size); the WebView version of the images. All "not available".

### This repository [V]

- `.github/workflows/ci.yml`: `on: push, pull_request, workflow_dispatch`; jobs `verify`, `kavita`, `publish` (push on `main` only, after `verify`).
- Last 60 runs (30–31/10 UTC): `verify` n=60, median 3.01 min (1.53–5.38), 20 red; `kavita` n=12, median 1.38, 6 red; `publish` n=40, median 1.83. The reds include the deliberate ones from the method.
- **Whole history:** 157 runs (25–31/10 UTC), 551.3 job minutes in total.
- **Cache:** 10,606,932,747 bytes in 174 entries, already above the 10 GB per-repository limit. Largest: `gradle-dependencies` up to 596 MB. One more AVD competes for the same space; its size is "not available".

### CI time per trigger policy

Inference from the numbers above, assuming 15 min per run (public range of 8 to 24 min, on a machine larger than the 2 vCPU one):

| Policy | Extra minutes |
|---|---|
| Today, no emulator | 551 in 7 days (reference) |
| On every push | + 2,355 in 7 days |
| Nightly, every day | + 450 per month |
| Only when the reader changes (`paths`) and by hand | proportional to step 4's work; cannot be estimated |

The pace of the measured week is that of building the CI and step 3, so the projection is pessimistic. In any case, checking real usage before turning the emulator on is the first step.

### Projects' policy

| Project | Trigger |
|---|---|
| AntennaPod | `pull_request` + `push` on `master`/`develop` (`checks.yml:3-7`) |
| Now in Android | `push` on `main`, PR, manual (`Build.yaml:3-8`) |
| Kiwix | `push` on `main` + PR, six APIs (`ci.yml:3-7, 25-26`) |
| AnkiDroid | PR, push and `merge_group` with one API; weekly and manual with a broad matrix (`tests_emulator.yml:3-6, 67-78`) |
| Readest | nightly, `e2e-android` label, manual; "Not PR-blocking" (`android-e2e.yml:3-13`) |

**Official guide:** it does not prescribe when to run instrumented tests. The CI pages say they require provisioning and waiting, suggest GMD, an emulator plugin or Firebase Test Lab, and say to track flakiness instead of relying on retries. Only benchmarks are pointed to as nightly runs ([automation](https://developer.android.com/training/testing/continuous-integration/automation), [features](https://developer.android.com/training/testing/continuous-integration/features)). Firebase Test Lab is a proprietary cloud service; it would serve only for testing, without going into the app, but it would require an account and was not evaluated.

### Recommendation

Practice observed in five projects, adjusted for a single-maintainer project [I]. The project's method requires seeing the test red and then green in CI, and a nightly run does not show this per commit, while running on every push costs too much. The `paths` filter serves both.

1. **Measure before deciding.** A first push with `workflow_dispatch` only, a job with a Hello page with `ls -l /dev/kvm`, boot, `dumpsys webviewupdate` and one test with a WebView. It gives the real time on 2 vCPU and the image's Chromium.
2. **Separate file**, for example `.github/workflows/emulator.yml`, with `on: push: paths:` (the reader code, foliate assets, `androidTest`) and `workflow_dispatch`. GitHub says one matching path is enough for it to run ([syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)). The page's warning about pending checks applies to PRs with required checks; Kamayoq works directly on `main`, so it should not matter, but branch protection was not checked. `publish` stays out of this file.
3. **No `schedule` at first.** Scheduled runs only run on the default branch and may be delayed under load ([events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)). The emulator only changes when the code changes, and the cost of a nightly run buys no information at this stage. If a reason appears, it comes back.
4. **A single API at first:** `google_apis` or `default` on 34 or 36. API 31 (minimum) is added by hand if we want the old WebView. No matrix: Kiwix shows the cost of six APIs. `timeout-minutes` on the job.
5. **Flakiness:** the guide says to track it. Retry only the emulator boot; none of AntennaPod's `|| runTests || runTests` on the Method's tests, because it hides bugs and the project's rule forbids weakening tests. Install the image with retries, like Readest.
6. **Before turning it on:** check CI usage; look at artifact retention and the cache above 10 GB, which the emulator would share with `verify` and `publish`.

### WebView without an emulator

| Path | Proves | Does not prove |
|---|---|---|
| `node --test` | pure JS of the glue: CFI, position conversion, `TOCProgress`, parsing. foliate has `tests/epubcfi-tests.js` | real DOM: paginator, layout, iframe. `DOMParser` in Node would require a new dependency |
| Playwright with headless Chromium | real paginator, columns, iframes, CSP, stable CFI with another font, `TOCProgress` on the compiled EPUB. Apache-2.0. `ubuntu-24.04` already ships Chrome 156 and Chromium (`Ubuntu2404-Readme.md:148-150`). Readest does this in `vitest.browser` | `shouldInterceptRequest` and Range, Kotlin bridge, `addWebMessageListener`, touch, insets, Android accessibility tree, lifecycle, the device's WebView version |
| `connectedDebugAndroidTest` on a test device over USB | real, current WebView, including a vendor's build, no CI cost | does not run in CI; depends on someone remembering to run it; needs the SDK on the development machine |
| Emulator on the development machine | `/dev/kvm` exists (`crw-rw-rw- root kvm`); what is missing is the SDK (no `sdkmanager`, no `emulator`, `ANDROID_HOME` empty) | installation not done; open decision |
| Robolectric | lifecycle | does not run WebView ([doc](https://developer.android.com/training/testing/local-tests/robolectric), `docs/research/android-tests-for-recreation.md:7`) |

The test in desktop Chromium is not a test against an imitation: it is the real engine, without the Android layer. Still, it does not replace the WebView; the rule of testing against the real service was written for Kavita, and whether it applies here remains open.

**Proposed pyramid [I]:** `node --test` and Playwright on every push (seconds to a few minutes, no emulator); `androidTest` in the real WebView via `paths`; a test device by hand for what only a real device shows; TalkBack with a person, on the finished app.

## Points for decision (as of 30/10/2026)

1. Measure first with a test `workflow_dispatch`? I recommend yes.
2. Keep the `paths` + manual policy, with no nightly run? I recommend keeping it.
3. With TalkBack on, force scroll mode like Readium Swift and Thorium, or offer it as an option? I recommend forcing it, to be confirmed in the on-device test.
4. 44 px target from the product design or the test framework's 48 dp?

## External sources opened on 30/10/2026

Android: [WebView](https://developer.android.com/develop/ui/views/layout/webapps/webview), [JSBridge](https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge), [manage-state](https://developer.android.com/develop/ui/views/layout/webapps/manage-state), [load-local-content](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content), [managing-webview](https://developer.android.com/develop/ui/views/layout/webapps/managing-webview), [testing](https://developer.android.com/guide/topics/ui/accessibility/testing), [Compose a11y](https://developer.android.com/develop/ui/compose/accessibility/testing), [Espresso a11y](https://developer.android.com/training/testing/espresso/accessibility-checking), [Espresso-Web](https://developer.android.com/training/testing/espresso/web), [UI Automator](https://developer.android.com/training/testing/other-components/ui-automator), [GMD](https://developer.android.com/studio/test/gradle-managed-devices), [CI](https://developer.android.com/training/testing/continuous-integration).

GitHub: [billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions), [runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners), [changelog 2024](https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/), [`actions/runner-images`](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md).

Others: [Thorium](https://thorium.edrlab.org/en/th3/500_accessibility/_print/), [axe-core](https://github.com/dequelabs/axe-core) (`develop`), ATF ([repository](https://github.com/google/Accessibility-Test-Framework-for-Android), `master`), issues cited in their respective repositories.
