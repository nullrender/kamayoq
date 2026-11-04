# Skeleton step 4 (EPUB): design options

Research done on 30/10/2026 and 31/10/2026 along three fronts (foliate-js and WebView; other readers, TalkBack and the emulator; position in Kavita and fit in Kamayoq), followed by a comparison of three approaches (minimal path, risk first, test environment first). Research and design only; no code written. The final check against the sources was done on 31/10/2026. Full notes in `docs/research/epub-step-4-design/`.

Step 3 moved forward while the research was under way. What that changes is in section 2, "What changed during the research".

## 1. Summary

The recommendation starts from plan 2, which puts risk first, and grafts in parts of the other two. From plan 3 comes the order of the security tests: each defense goes red and then green, on its own. From plan 1 come three things: the first reading on the test device before the database migration, no XML parsed in Kotlin in this phase, and the migration near the end.

The step is split into three milestones, with a stop to decide between them.

- Milestone A: an EPUB in `Books/` appears in the Library, opens, and returns to the same passage after closing, killing the app, rotating the screen or updating. A hostile book shows only text.
- Milestone B: novel. First `Cap. 0928.epub` as one chapter; then the compiled EPUB split into chapters by its table of contents.
- Milestone C: an EPUB from Kavita opens, and then the position goes back and forth.

The recommendation is to do only Milestone A now, after closing step 3, and to decide B and C with the test-device result in hand. The exception is the emulator trial, which comes first. It is a single commit and tells how much the emulator costs.

Four facts weighed most:

1. The CSP recommended by foliate-js itself is not enough here. In a test on desktop Chromium, it let a script from the book itself run. CSP is the rule that tells the page what it may load and execute.
2. The "main frame only" filter on the bridge to Kotlin does not stop a script that manages to run. What stops it is the script not running. That is why the recommendation is to remove `allow-scripts` from the fork.
3. The foliate-js fork requires WebView 117 or newer. The number 105 that was in the notes is wrong.
4. An EPUB is always a single chapter in Kavita, and the contract says a novel always shows chapters. The two do not fit together. The decision only needs to be made in Milestone B or C.

Nothing of the final setup was measured: fork, CSP, sandbox and per-entry reading have never run together in an Android WebView. The first pieces exist to find this out before spending on the rest.

There are 12 decisions to start (section 7), all with a recommendation.

## 2. Facts with sources

Marks: [V] verified in an open source on the date given; [M] measured; [I] inference. When nothing is said, the reading is from 30/10/2026. What is marked [31/10] was rechecked on 31/10/2026.

### foliate-js

- [V, 31/10] Chosen base: `readest/foliate-js`, default branch `main` per the API, commit `0c7a0edcda`, from 26/10/2026, MIT license. It is the same commit Readest uses as a submodule. The original is `johnfactotum/foliate-js`, `main`, `78914aef44`, from 01/05/2026, also MIT.
- [V] The fork is 267 commits ahead of and 14 behind the original. It has no tags or issues, and the API changes as Readest needs. The original's 14 commits were not analyzed.
- [V] The Readest app is AGPL-3.0: nothing from it may be copied. Only the fork is MIT. The `groupBy` polyfill and the sanitizer live in the app, so they stay out.
- [V] The plain EPUB flow runs as ES modules, with no build. Eight files are enough, plus the `LICENSE`: `view`, `epub`, `epubcfi`, `paginator`, `section-chunks`, `progress`, `overlayer`, `text-walker`. `vendor/`, `pdf.js` and the other formats stay out. `fixed-layout.js:1` has an import that does not work without a build. So a fixed-layout EPUB needs a clear message.
- [V] The book can arrive without zip.js: `new EPUB({ entries, loadText, loadBlob, getSize }).init()` and then `view.open(book)`. In the fork, `entries` is required (`epub.js:1270`). Kotlin opens the ZIP and serves one entry at a time.
- [V] The `relocate` event carries `fraction`, `section`, `tocItem` and `cfi` (`view.js:341-350`). A CFI is a structural address of a passage inside the book. It does not depend on font or size, but it does depend on the document being the same.
- [V] The fraction is computed by bytes, not by pages (`progress.js:175-199`). `location` counts blocks of 1500 bytes. Neither is a real page.
- [V] EPUB sections load through `srcdoc`, not through `blob:` as the README still says (`paginator.js:884-888`, `epub.js:1339`). The text changed in the `data` event is what goes into the `srcdoc` (`epub.js:949-963`).
- [V, 31/10] The iframe uses `sandbox="allow-same-origin allow-scripts"` (`paginator.js:783`). The comment cites a WebKit bug, not a Chromium one.
- [V, 31/10] An external link that is not cancelled runs `globalThis.open(href, '_blank')` outside the sandbox (`view.js:371-374`). Since `javascript:` and `intent:` count as external (`epub.js:158`), the app must always cancel.
- [V, 31/10] The WebView floor is 117: `epub.js:288`, `314`, `316` and `372` use `Object.groupBy`, and `:310` uses `Map.groupBy`. In `mdn/browser-compat-data`, branch `main`, both are Chrome 117, with `webview_android` mirroring Chrome.
- [V] With `tocItem`, foliate finds the current chapter even with several table-of-contents items in the same file (`progress.js:18-59`). It is null before the first item. "Chapter" is not the same as a spine item nor a table-of-contents item. The spine is the list of the book's files in reading order.
- [V] Switching `flow` between `paginated` and `scrolled` is an attribute, with no reload (`paginator.js:1941`).
- [V] `tests/` covers only CFI; pagination has no test in the repository.

### How to keep the copy

- [V] [`stack-2026-10-23.md`](../decisions/stack-2026-10-23.md):8 already decided: a copy in `third_party/foliate-js`, with the source commit and every change recorded.
- [V] F-Droid does not forbid submodules or JavaScript. It builds from public source. Copying only the modules avoids a minified bundle, a lockfile and PDF.js.
- [I] Procedure: an import commit with no changes at all; `UPSTREAM.md` with source, date, SHA-256 of each file and one line per change; a Node test that goes red if a file changes without a record. Updating is a deliberate act: `git diff` between commits, `git apply`, run the emulator suite.

### WebView with file content

WebView is the Android component that shows a web page inside the app. The bridge is the channel between that page and Kotlin.

- [V] `setAllowContentAccess` starts on: it must be turned off. `setAllowFileAccess` already starts off with `targetSdk` 36.
- [V] `setBlockNetworkLoads` starts `true` without the `INTERNET` permission and `false` with it. Step 3 adds `INTERNET` in piece 6, so the page would gain network access if the app does not pin the value to `true`.
- [V] `WebViewAssetLoader` uses the domain `appassets.androidplatform.net`. An interceptor that returns `null` lets the request go to the internet, so the denial has to be a synthetic 404.
- [V] `addJavascriptInterface` injects the object into every frame, without checking the origin; the official guide recommends `WebViewCompat.addWebMessageListener`, with an exact origin.
- [I] `isMainFrame` is not a defense against script. An iframe that runs script reaches `parent.<object>`, and the message leaves from the main frame's object. This was not executed, but the tests should treat it that way.
- [V] CSP3 §7.6: after a redirect, the path stops counting in the rule. The interceptor must never answer with a redirect.
- [V] `detail.allow = false` depends on the type the book itself declares (`epub.js:1015-1021`). It does not work as a barrier.
- [V] Cleartext is off by default, with an implicit exception for localhost only on Android 17. Step 3's decision 13 allows `http://` in the app; whether that reaches the WebView was not checked.
- [M, desktop Chromium 152, without foliate, 30/10/2026] With `script-src 'self'`, a `<script src="/gadget.js">` ran and reached `parent`. With `script-src` limited to the `/reader/` path, the book's script was not even requested. Without `allow-scripts`, neither script nor `onload` ran, and the click still reached the page's listener. `meta refresh` is blocked, but leaves the section empty.
- [V] `<script src=/reader/...>` passes the CSP by path. Nobody proves that running the reader shell inside the iframe is harmless. One more reason to remove `allow-scripts`.

### Kavita and EPUB

Source: `Kareadita/Kavita`, default branch `develop` (`3c9ddb4`), read at tag `v0.9.1.4` (`879b8b7`), the CI version. None of this was measured with an EPUB: it is code reading.

- [V] An EPUB "page" is the index in the spine: `epubBook.GetReadingOrder().Count` (`BookService.cs:909`). foliate counts the same way, including `linear="no"`. This closes the open item in the [progress contract](../decisions/progress-contract-2026-10-24.md) (line 110), pending measurement.
- [V] An EPUB is always one Kavita chapter. It never splits by the table of contents (`ProcessSeries.UpdateChapters`, `:757-790`). Several table-of-contents items in the same file have the same `page` and a different `part`.
- [V] An EPUB's chapter number never comes from the file: it stays at the default, `-100000`. The folder does not give the series; the OPF does (official Kavita wiki, EPUB scanner guide). `Cap. 0928.epub` with different titles becomes one series per file. The OPF is the EPUB's metadata file.
- [V, 31/10] `ReaderService.cs:229`: with no record and `PageNum == 0`, the server answers success and does not save, ignoring the `bookScrollId`. In an EPUB with a single spine item, the partial position has nowhere to live. What was measured in run 41155547735 applies to an image chapter without a marker, not to EPUB.
- [V, 31/10] The web reader sends the total on any scroll in the last spine item (`book-reader.component.ts:836-838`). "Read" coming from Kavita for an EPUB does not mean the person finished.
- [V] Opening the chapter in the web reader rewrites the `bookScrollId` without reading. Under the contract's rule, this would create a false conflict.
- [V] The `bookScrollId` is not tied to Angular: it is `//body/...` in lowercase, or `id("x")...`. The server itself evaluates it against the XHTML (`BookService.cs:1053`). XPath is a path to an element of the document. A native client can produce and consume it, losing the position within the paragraph.
- [V] The field arrives polluted by other clients: `idx:12` from Inkita, `//div[1]…` from Kover. An unknown form must open at the start of the section, never raise an error.
- [V] `GET /api/Download/chapter` delivers the original `.epub` when the chapter is a single file (`DownloadController.cs:207-216`).

### What defines step 4

- Skeleton criterion (22/10/2026): "open a CBZ and an EPUB → close and come back at the same place".
- [`stack-2026-10-23.md`](../decisions/stack-2026-10-23.md):8: "Before the first reader: blocking of the book's scripts (CSP) and a minimal-reach bridge, tested with a deliberately malicious EPUB".
- Scope recommendation made when the stack was proposed: "local and Kavita EPUB, Pages/Scroll, stable passage and general/per-book settings".
- [Progress contract](../decisions/progress-contract-2026-10-24.md), tests on Android: close in the middle, kill the process and come back; the same after updating.
- [`kavita-step-3-2026-10-30.md`](../decisions/kavita-step-3-2026-10-30.md), decision 5: the "abre no passo 4" ("opens in step 4") notice is removed in step 4.
- Project plan: visual foundation after EPUB; TalkBack test with the app finished; emulator in CI in this step.

### In today's code

- [V] `FolderConvention` rejects `.epub`. `ArchivePages.open` rejects a file without images. `OpenedChapter` only has image pages. `ReaderHost` requires `tree` and calls `StripReader` directly.
- [V] `chapter_progress` only has an image position. In Strip, `page == total` is the end block. `BasisPoints` exists and nobody uses it.
- [V] `chapter_current_path` is unique per `(version_id, path)`. Several chapters in the same file do not fit without changing that index.
- [V] `format` has no CHECK in SQL, but `ChapterFormat.valueOf` throws for an unknown value.
- [V] There is no backup restore: what exists is the export. Step 3's decision 10 ("backup in the app before installing") gives no way back if the migration fails. The migration runs in a transaction, so the database stays intact, but the app would crash on opening, with no screen.
- [V] The published build is the debug one (`ci.yml:172`). So whatever is in `app/src/debug` reaches the test device.
- [V] There is no work state in the code: the contract's 2 % rule has nowhere to act yet.
- [V] There are no reading preferences and no settings table.

### What changed during the research

- Step 3 moved forward during the research. Piece 5 is almost done, and the `kavita` job is red on purpose, with piece 5's failing test. The next piece, 6, adds `INTERNET`.
- Step 3's decision 13: `http://` allowed in general, with a warning. Step 4's network proof has to run with that final manifest.
- Step 3's decision 8b: a chapter that disappears is relinked by series, number and total pages. In a standalone EPUB, the number is always the default, so this rule identifies nothing [I]. It applies to step 3's piece 9, and the warning is urgent.
- The product design changed one line about the same rule.

### Corrections to the notes

The notes stay as they were written. These corrections apply:

- [`foliate-and-webview.md`](epub-step-4-design/foliate-and-webview.md): "floor of at least 105" is wrong; it is 117.
- [`other-readers-and-emulator.md`](epub-step-4-design/other-readers-and-emulator.md): "tag `v2` at `4c44018e`" is the tag object. The commit of `v2.38.0` is `a421e43855164a8197daf9d8d40fe71c6996bb0d`.
- `other-readers-and-emulator.md`: the URL of the `default` images is `sys-img/android`.
- Readest runs foliate on `google_apis` 34, but with its own `groupBy` polyfill. That does not prove the image has Chromium 117.
- The claim that a child table is the only way to have a CHECK across fields is wrong: measured on local SQLite, `ADD COLUMN` accepts a CHECK that refers to another column. The Android SQLite version was not tested.

## 3. How other readers do it

Code read on 30/10/2026; default branch of each one per the API. More detail and file:line in [`other-readers-and-emulator.md`](epub-step-4-design/other-readers-and-emulator.md).

| Project | Commit | Engine | How the book arrives | Position | Several chapters in one file | WebView in tests |
|---|---|---|---|---|---|---|
| Librera | `d09b8f36` | foliate-js in WebView, drawn to a bitmap | `shouldInterceptRequest`, whole book as a Blob | CFI | foliate | Yes, on a device; no CI |
| Readest | `716aadae` | foliate fork, Tauri | `rangefile` scheme | CFI | `tocItem` | Nightly, 13 failures in 40 runs |
| Foliate GTK | `67b6676d` | foliate-js, WebKitGTK | own schemes, `blob:` | CFI | `tocItem` | No |
| Readium Kotlin | `89b96c20` | own HTML in WebView | `shouldInterceptRequest`, streaming | `Locator` | Not verified | No `androidTest` |
| LNReader | `dc394e85` | sanitized HTML | extracts to disk | % of chapter | Not handled | No |
| KOReader | `80127739` | crengine, no WebView | own engine | xpointer | Resolves precisely | Does not use one |
| Myne | `7ec1fdb1` | Compose | zip in memory | index + offset | Cuts by fragment, but rejects a single file [I] | No |
| Book's Story | `464891ec` | Compose | parses into nodes | node + offset | Uses headings [I] | No |

On the Kavita side, none of the five clients read (Kamigura, Kover, Inkita, Lagrange, kamare) uses foliate or tests the round trip against a real Kavita. The closest is Kamigura, which keeps two values, like the contract: Kavita's marker and the exact local position.

On security, Librera is the closest model: `FoliateServer.java` and `FoliateRenderer.java` deny what is not from the book, refuse main navigation and let subframes through. It still uses `addJavascriptInterface` and `script-src 'self'`, which we do not copy. Readest is not a model: its CSP has `'unsafe-inline' 'unsafe-eval'`, and the defense is sanitizing with DOMPurify.

What to copy:
- From Librera: a private https origin, deny by default, a test Activity, an EPUB written by the test itself, and the test that the same CFI points to the same words with another font.
- From Readest: save immediately on `visibilitychange` and `pagehide`, because Android freezes the WebView before the interval ends (#5859). Do not save on the first `relocate`, so as not to overwrite the position (#4222).
- From KOReader: the position only changes through explicit navigation, never through rotation or a layout change.
- From foliate: `tocItem` to know the current chapter.

What to avoid:
- A loose CSP that depends on a sanitizer (Readest).
- A bitmap as the reading screen, which erases the text for TalkBack (Librera).
- The WebView's `restoreState`: the official guide does not promise position, and in the readers studied what comes back is the saved position.
- Repeating tests until they pass (AntennaPod's `|| runTests`): it hides bugs and goes against the project rule.

### Accessibility

- [V] With TalkBack, column pagination is the weak point. Readium Swift forces scrolling ("pagination breaks the screen reader"), so does Thorium, and the Readest maintainer pointed to scrolling as a workaround (#2276).
- [I] Chromium exposes off-screen columns as visible, which explains the symptom.
- [I] Everything that is a control lives in Compose; only the book's text lives in the WebView. Tap zones do not exist for people using explore by touch, so, with TalkBack on, labeled buttons come in.
- [V] Checks can be automated in Compose (`enableAccessibilityChecks`), which use 48 dp as the minimum target. Speech, swipe order and section end need a person, in the test left for the finished app.

## 4. The three proposals

### Plan 1: minimal path (15 pieces)

On the test device: Dom Casmurro in `Books/` opens in Pages, with the book's own CSS, and returns to the same passage. No Scroll, no table of contents, no read mark, no Kavita.

For:
- Kotlin does not parse XML, which removes XXE, an XML attack, in this phase.
- Migration as the last piece, so reading on the test device happens even if step 3 is delayed.
- Commands from Kotlin to JS are constants, and the CFI comes back through `index.json`.
- The hostile test's red is real, before any user route exists.
- First reading on the test device at piece P10.

Against:
- It uses the wrong floor (105) precisely in the trial that should protect the rest.
- Only the CSP stops the book's script, with no zip limits, and `meta refresh` leaves the section empty.
- The network control as written proves nothing, because the interceptor already denies.
- It has no table of contents in the reader, against rule 3 of the [folder convention](../decisions/folder-convention-2026-10-24.md), and no read mark.
- Process death is only tested by hand.

### Plan 2: risk first (27 pieces)

On the test device, in three milestones with a stop between them, as described in the summary.

For:
- It found the 117 floor and the fact that there is no backup restore.
- Checker with SHA-256 per fork file.
- Copy of the database before migrating.
- The only one that says how to really kill the process (`am force-stop` between two instrumentation runs).
- Compares Kotlin's chapter list with foliate's.
- Measures CFI with XHTML where the two parsers diverge (empty `<a/>`, table without `tbody`).
- Rejects `Novels/` until there is a correct identity, so as not to save marks on the wrong chapter.
- Milestones with stops.

Against:
- The proof Activity in `app/src/debug` would reach the test device before the hostile test, against the letter of the stack decision.
- It adds `INTERNET` to debug before step 3.
- The main defense never shows red.
- The `isMainFrame` filter appears as a layer.
- Reading through the real path only arrives at piece 23.
- The argument for the child table is partly wrong.
- Piece 25 mixes subjects.

### Plan 3: test environment first (32 pieces)

On the test device: the same as Milestone A, with a fixed mode per type and Kavita text works opening without sending position. The rest is conditional.

For:
- The best test sequence: the control proves the attack, the CSP goes red and green, and the sandbox is tested with a shell without CSP.
- A log of what the bridge rejects, to tell rejected apart from never attempted.
- Trial with two images.
- Kavita facts early.
- The only one that fulfills decision 5 (the notice is removed).
- Runner pinned to the right commit.

Against:
- About 19 pieces with nothing new on screen.
- Highest CI cost.
- 25 decisions.
- A test bench installed via `adb` on the test device.
- `detail.allow` treated as a defense.
- The piece that kills the process does not say how.
- It places pieces outside the characterization exception so they enter green.

### What no plan covered

Gaps common to all three, which went into the recommendation:
- `isMainFrame` is not a defense, and that needs to be in the tests.
- A rule and a test for never answering with a redirect.
- `<script src=/reader/...>` inside the book.
- Network control with the interceptor turned off, otherwise "0 requests" proves nothing.
- `unhandledrejection` in the JS glue, because the fork's opening errors are asynchronous.
- A limit before the SAF copy, which today copies with no ceiling.
- Names with `%2F` and `%2e%2e`, and duplicate entries in the ZIP.
- `save` with `INSERT OR REPLACE` preserving CFI and fraction on every write path.
- `publish` does not wait for the emulator (decision 8).
- `Books/Os Miseráveis/Vol. 1.epub` and `Novels/Cap. 0928.epub`, which the plans did not decide.
- An amendment to the contract before the code, for a compiled book coming from Kavita.

## 5. Recommendation

The skeleton is plan 2, with the grafts:
- from plan 3: the security test sequence, the rejection log, the trial with two images, the Kavita facts early;
- from plan 1: Kotlin without XML in Milestone A, CFI through `index.json`, constant commands, migration near the end, reading on the test device before it;
- the gaps above.

The test bench on the test device and the Activity in `app/src/debug` are dropped. With the work done in sequence after step 3, the first reading comes through the real path right after the security tests. The bench stays as a fallback if the emulator turns out too expensive.

"Enters green" means with no red commit before it, per decision 7. The other pieces go red and then green.

### Block A: before any reader (15 pieces)

Nothing here has a route in the interface. Only piece 1 may come in before step 3 is closed.

1. Emulator trial (ci, androidTest)
   - What it does: creates `.github/workflows/emulator.yml`, with only `workflow_dispatch`, and `app/src/androidTest`. Runs `ls -l /dev/kvm`, `dumpsys webviewupdate` and a WebView test, twice on each image: `default` 36 and `google_apis` 34. Rejects an image by function, not by number.
   - Tests: "given a WebView from the image, when I ask for `Object.groupBy` and `Map.groupBy`, then both exist"; "given a page with JavaScript, when it loads, then it answers the test". Enters green.
   - Where to check: in CI, with `gh run list --limit 1 --json databaseId`: minutes, failures, Chromium of each image. The file has to be on `main`, because `workflow_dispatch` only triggers that way (GitHub documentation).

2. Pure reader rules (core)
   - What it does: exact CSP text, interceptor decision, link classifier and bridge messages.
   - Tests:
     - "given the CSP, then `script-src` has no `'self'`, `unsafe-inline` or `unsafe-eval`";
     - "given any URL outside `/reader/` and the open book, then the response is 404, never null nor a redirect";
     - "given a name with `%2F` or `%2e%2e`, then there is no entry";
     - "given a `javascript:`, `intent:`, `file:` or `content:` link, then it does not open";
     - "given a fraction of 0.5, then it is 5,000 basis points; an invalid fraction is rejected, it does not become zero";
     - "given a message of unknown type or a huge CFI, then it is rejected and logged".
   - Where to check: in CI, Core tests step.

3. Hostile file (core)
   - What it does: reads the EPUB as an exact map of entries, with limits on count, per entry and total, counting bytes read. Nothing is extracted with a name coming from the ZIP. Default values come from the measurement on the test device.
   - Tests:
     - "given an entry whose header lies about its size, then reading stops at the limit";
     - "given a ZIP with a repeated name, then it is rejected";
     - "given a name with `..`, then there is no entry";
     - "given a file that is not a ZIP, then it is rejected".
   - Where to check: in CI, Core tests step.

4. Untouched copy of the fork (third_party, ci)
   - What it does: an isolated commit with the eight modules and the `LICENSE`, plus `UPSTREAM.md` with repository, branch, commit, date, SHA-256 and a table of changes.
   - Tests (Node): "given `UPSTREAM.md`, when I check the files, then each one exists, every relative import points to a copied file and none differs without a line in the table".
   - Where to check: in CI; `git show --stat` shows only `third_party/foliate-js`.

5. EPUB generator (`epub-fixtures` module, plain Kotlin)
   - What it does: generates EPUBs in code, with no binary: short, compiled with a single spine item above 512 KiB, and EPUB 2 with NCX. Fictional text. Three consumers justify the module: `core`, `androidTest` and `kavitaTest`.
   - Tests: "given the generator, when I create an EPUB, then `mimetype` is the first entry, uncompressed, and the package has exactly one `nav` and one `dcterms:modified`".
   - Where to check: in CI.

6. Shell and bridge on the real engine (app, no route)
   - What it does: `androidx.webkit`, WebView with file and content access off, `blockNetworkLoads` pinned to `true`, piece 2's interceptor, `addWebMessageListener` bridge with exact origin and a log of what is rejected. `window.onerror` and `unhandledrejection` become errors. No CSP on purpose, so piece 9 has a true red.
   - Tests (emulator): "given the reader page, when it loads, then `foliate-view` exists and the `ready` message reaches Kotlin"; "given a request outside the routes, then the interceptor answers 404".
   - Where to check: in CI, `emulator.yml`.

7. EPUB opened per entry (app, no route)
   - What it does: `index.json` with entries, sizes and last position. `new EPUB(loader).init()`, `view.open(book)`, `relocate` becomes a message. Commands from Kotlin are constants; book data is never concatenated into `evaluateJavascript`.
   - Tests (emulator): "given an EPUB with three sections, when I open it, then the first one's text appears and `relocate` carries section, CFI and fraction"; "given the next command, then the section changes".
   - Where to check: in CI. It is the first measurement of the fork in an Android WebView, with `srcdoc` and per-entry reading.

8. Hostile book and positive control (epub-fixtures, androidTest)
   - What it does: one case per attack: inline script, `onerror`, SVG, `<script src>` to `/book/` and to `/reader/`, `meta refresh`, iframes, `base`, `form`, `object`, remote resources and links. Sentinels: `parent.kamayoqBridge.postMessage('PWNED:<case>')`, a request to `/book/<token>/PWNED-<case>`, another token, `parent.document.title` and the test's local server. The control lives only in `androidTest`, never in `debug`.
   - Tests: "given the control page, with no defenses, when I go through the book, then every sentinel fires" (green: it measures the engine; if it does not fire, the test is worthless); "given the reader without CSP, when I go through the book, then no sentinel fires" (real red).
   - Where to check: in CI, red with the list of cases that fired.

9. CSP by header (core, app)
   - What it does: applies piece 2's CSP to the `index.html` response, with `script-src` only on `/reader/`. It has never run with foliate, and any adjustment has a recorded reason.
   - Tests: piece 8's red turns green; "given the normal book, when I open it, then the text appears and `relocate` arrives" (the defense did not break the reader).
   - Where to check: in CI.

10. Sandbox without `allow-scripts` (third_party, if approved)
    - What it does: a patch to `paginator.js:783`, in its own commit, with a line in `UPSTREAM.md`.
    - Tests (emulator, with a test shell without CSP): "given the hostile book, when I go through it, then no script runs"; "given the text, when I tap and select, then page turning and selection work".
    - Where to check: in CI. If it breaks tapping or selection, the work stops and goes back to the decision with the evidence.

11. Network and navigation closed (app, with `INTERNET` in the manifest)
    - What it does: proves `blockNetworkLoads` with step 3's final manifest. Denies main navigation; in a subframe allows only `about:srcdoc` and `blob:`.
    - Tests:
      - "given the control with the same network configuration and no interceptor, then the local server receives requests";
      - "given the reader, then the server receives 0 requests";
      - "given a tapped `javascript:` link, then nothing opens and there is no navigation".
    - Where to check: in CI.

12. Links and `meta refresh` (app, assets)
    - What it does: `external-link` always cancelled; the URL goes to Kotlin, and `http`/`https` ask for confirmation showing the real URL. `http-equiv` is neutralized in the `data` event, without removing the element, so as not to shift the CFI.
    - Tests (emulator): "given `meta refresh` in a section, then it stays readable and `relocate` fires"; "given an external link, when I tap it, then I see the real URL before leaving the app".
    - Where to check: in CI.

13. Renderer crash and old WebView (core, app)
    - What it does: `onRenderProcessGone` discards the WebView and reopens once; on the second crash, it explains and stops, without erasing the position. A WebView below the floor and a fixed-layout EPUB become a clear message, in English and pt-BR.
    - Tests: "given version 116, then the decision is to refuse with a message; given 117, then it opens" (JVM); "given the renderer killed, then the app does not crash" (emulator; how to bring it down is still to be checked).
    - Where to check: in CI.

14. CFI on the real engine (core, androidTest)
    - What it does: `TextPosition` with section, total, CFI, basis points, date and device. Pure state machine: opening, restored, reading. Nothing is accepted before the restore is confirmed, and the anchor only changes through the person's navigation.
    - Tests:
      - "given the first `relocate`, then the saved position is not overwritten" (JVM);
      - "given a saved CFI, when I reopen, then the same words are visible" (emulator);
      - the same with a larger font, with a section cut into chunks, and in Scroll;
      - "given `<a id="x"/>` and a table without `tbody`, then the CFI points to the same passage".
    - Where to check: in CI. If it fails, the work stops before the schema.

15. Kavita facts for EPUB (data, `kavitaTest`)
    - What it does: a Book library with `fileGroupTypes: [2]` and `enableMetadata: true`, with generated EPUBs. Enters green, per step 3's decision 4.
    - Tests:
      - "given an EPUB with three sections, then `pages` is 3";
      - "given page 0 with a marker and no record, then…" (pins down the real behavior);
      - "given page 1 with `bookScrollId`, then it comes back the same, byte for byte";
      - "given `id("x")`, then it comes back the same";
      - "given `Book/{id}/chapters`, then `page` is the spine index";
      - "given two distinct titles in the same folder, then they are two series".
    - Where to check: in CI. Each divergence becomes a line in the contract.

### Block B: Milestone A through the real path (9 pieces)

16. EPUB in `Books/` in the Library (core, data)
    - What it does: a loose `.epub` and `Books/Work/Vol. 1.epub` become a work and a volume, with `ChapterFormat.EPUB`. No migration needed: `format` has no CHECK. The title comes from the file name. Outside `Books/`, it stays rejected until Milestone B.
    - Tests: "given `Books/Dom Casmurro.epub`, then there is a work with one volume, of type Books"; "given `Books/Os Miseráveis/Vol. 1.epub`, then there is a volume in the work"; "given `Novels/Cap. 0928.epub`, then it is still unsupported".
    - On the test device: the work appears in the Library.

17. Open in the reader (app, data)
    - What it does: an opened chapter becomes a sealed type (images or text), and `ReaderHost` picks the reader. Copies through SAF with a ceiling, without a `.cbz` name, with SHA-256 and cleanup after a crash. New token per opening.
    - Tests: "given an EPUB larger than the ceiling, when I open it, then the copy stops and I see the message" (JVM); "given an EPUB chapter, when I open it, then the text reader appears" (emulator).
    - On the test device: first real reading. Swipe and check the text. Note the WebView version, which today is not available.

18. Reading defaults and mode per type (core, app)
    - What it does: fixed CSS from the product design (18 px, line height 1.5, margins 24, indent 1.5 em, left-aligned, no hyphenation), which does not override the book's CSS beyond that. Book in Pages. No saved preference.
    - Tests (emulator): "given the book, when I open it, then the applied size is 18 px"; "given a book with its own color CSS, then its color stays".
    - On the test device: compare at real size. Characters per line only count on the device, not on the emulator.

19. Controls, table of contents and accessibility (app)
    - What it does: every control in Compose, 48 dp, with a label. Back, Previous page, Next page and Table of contents in a simple sheet. With TalkBack on, Scroll (decision 11). The WebView keeps no history. Texts in English and pt-BR in the same commit.
    - Tests:
      - "given the reader open, when I run the Compose accessibility checks, then no control fails";
      - "given the table-of-contents sheet, when I press Back, then it closes and the passage stays";
      - "given explore by touch on, then the mode is Scroll".
    - On the test device: open and close the table of contents. The TalkBack test is left for the finished app.

20. Schema version 3 (core, data, short branch in a worktree)
    - What it does: `position_cfi` and `position_fraction` in `chapter_progress`, via `ADD COLUMN`, with a CHECK from 0 to 10,000. `position_page` stores the section and `position_total` the total number of sections. Sealed types `StripPosition` and `TextPosition` in Kotlin. The choice between this and a child table waits for piece 14's measurement; the child table requires revisiting `hasProgress`.
    - Tests:
      - "given a version 2 database with works and progress, when I migrate, then everything stays the same";
      - "given fraction 10,001, then the database rejects it";
      - "given a saved text position, when I mark it read or apply E1, then CFI and fraction stay".
    - On the test device: with the database copy from decision 6, install over the existing app. The positions stay.

21. Save and resume (app, core)
    - What it does: connects piece 14's guard to the database. Saves on every accepted page turn, immediately on `visibilitychange` and `pagehide`, and Kotlin saves on `ON_STOP`. A CFI that does not resolve opens at the start of the section.
    - Tests (emulator): "given a saved position, when I rotate the screen, then the same words are visible"; "given a tap during loading, then the book does not go back to the first page".
    - On the test device: read to a point, close and reopen.

22. Backup format 3 (core, data), before piece 21 (decided on 01/11/2026: step 3's piece 9 moves up to format 2)
    - What it does: carries the text position and reads the earlier formats. `Backup.kt` today requires an equal version.
    - Tests: "given a format 2 backup, when I read it, then it comes in without loss"; "given a text position, when I export, then CFI and fraction are in the file".
    - Where to check: in CI.

23. End of the book (core, app)
    - What it does: going past the last page shows the end block in Compose (decision 9), with the read mark and completed reads. The fraction never decides this.
    - Tests: "given 10,000 basis points without the block, then it is not read"; "given the block showing, then it is read and reads add up to 1"; "given scrolling up and down at the end, then it does not add again".
    - On the test device: go to the end, go back, and check the mark.

24. Kill the process and update (ci, androidTest)
    - What it does: in `emulator.yml`, `installDebug`, one instrumentation run saves, `am force-stop`, another instrumentation run checks. The same with an install over the existing app. A test inside the process cannot kill itself.
    - Tests: "given a saved position, when I kill the process and reopen, then the same words are visible"; the same after updating the app; "given two partial chapters, when I kill the process, then both stay".
    - Where to check: in CI and, finally, with Dom Casmurro on the test device.

### Milestone B: novel (to be decided at the end of Milestone A)

- B1. `Novels/Cap. 0928.epub` as one chapter, with novels in Scroll. It costs only the rule and the mode, and depends on decision B-1. Test: "given `Cap. 0928.epub`, then chapter 928".
- B2. Amendment to the [progress contract](../decisions/progress-contract-2026-10-24.md) and the [folder convention](../decisions/folder-convention-2026-10-24.md), before the code, answering questions B-1 to B-3. Includes the test on line 163 of the contract, which does not pass as written because of `ReaderService.cs:229`.
- B3. Table of contents in Kotlin (`core`): `container.xml`, OPF, `nav` and NCX, in foliate's order (`epub.js:1358-1383`), without an external DTD. Handling DOCTYPE in NCX needs a real EPUB. Test: "given 'Volume 1 → Cap. 1, Cap. 2', then one volume with 2 chapters"; "given two items with the same target, then 1 chapter"; "given an empty table of contents, then 1 chapter and a warning".
- B4. Kotlin and foliate agree (emulator): "given the compiled book, then Kotlin's list, `book.toc` and `tocItem` match".
- B5. Migration: `toc_key`, `chapter_current_path` per `(version_id, path, toc_key)`. `chapter.id` remains the identity.
- B6. E1 on all chapters of the file in one transaction. Test: "given an EPUB replaced by an edition with a new preface, then the marks do not carry over on their own and the Work screen asks".
- B7. Reading crosses chapters without reloading, with the chapter fraction between anchors and `work_resume` following along.

### Milestone C: EPUB from Kavita (to be decided at the end of Milestone A)

- C1. Downloads and opens, without sending position. The "abre no passo 4" ("opens in step 4") notice is removed. Text chapters stay out of step 3's send queue until C2. Test: "given a Kavita text chapter read here, when I sync, then nothing is sent".
- C2. Pure conversion of section and page (`page == total` is read; `total` only at the true end) and a `//body/...` marker, computed on the rendered DOM, never on `createDocument`. An unknown form opens at the start of the section. Test: "given `idx:12`, then it opens at the start of the section"; "given `pages` different from the number of sections, then it opens at the start and does not send".
- C3. Real round trip, measuring the loss. With the web reader it is a manual script, with the podman setup from step 3's decision 2.

## 6. Test environment

### What runs where

| Where | What it proves | When |
|---|---|---|
| JVM (`core`, `data`) | rules, limits, migration, backup | every push |
| Node | fork integrity | every push |
| Robolectric | Compose shell | every push |
| `kavitaTest` | Kavita facts | every push |
| Emulator | real WebView, real fork, security, CFI | when the reader changes, and by hand |
| Test device (Android 14+) | manufacturer's WebView, typography, human evaluation | end of a piece with a screen |
| Person using TalkBack | speech, order, position | finished app (decided on 30/10/2026) |

### Emulator

- How it runs: `.github/workflows/emulator.yml`, separate from `ci.yml`, with `workflow_dispatch` and `push` filtered by `paths`: `third_party/foliate-js/**`, reader assets, reader code, `app/src/androidTest/**` and the file itself. No `schedule`, no matrix, with `timeout-minutes`. Build before starting the emulator. Runner: `ReactiveCircus/android-emulator-runner`, `v2.38.0`, pinned to `a421e43855164a8197daf9d8d40fe71c6996bb0d`.
- [V] GitHub documents hardware acceleration on hosted Linux, without distinguishing public and private. A private repository gets 2 vCPU and 8 GB; a public one, 4 and 16.
- Images: [V] `default` and `google_apis` have a real WebView in public projects (Kiwix, Readest). ATD stays out: the documentation does not say whether it works, and there is only a hint that the view inflates. [I] According to the AOSP `chromium-webview` log, Android 14 ships 113 to 119, Android 15 ships 124, and Android 16 is between 133 and 134. If the `default` image uses that WebView, 34 may fall below the floor and 35 and 36 pass. API 31, the `minSdk`, is well below, and the `minSdk` does not guarantee the floor.
- Flakiness: retry only the boot and the image download, never the tests. A red becomes an investigation.

### Cost

[V] Public measurements, on a machine larger than a private repository's, with the median of successful jobs:

| Project | Sample | Failure | Median |
|---|---|---|---|
| AntennaPod, `push` | 178 jobs | 0 %, with 3 built-in retries | 11.5 min |
| Now in Android | 80 jobs | 1.6 % | 18.7 min |
| AnkiDroid, PR | 40 jobs | 2.8 % | 23.8 min |
| Readest, nightly | 40 runs | 30.8 % on 30/10; 32.5 % on the recount | 17.1 min |

Readest is the closest analog, with real WebView, book and touch, and also the least stable. Almost all failures were timeouts.

Estimate for Milestone A [I]: about 15 pieces touch the emulator, with red and green, plus 4 trial runs and 15 % slack, giving about 40 runs, of 8 to 24 min each. Expect it to be slow on 2 vCPU. Real time in Kamayoq: not available, and that is what piece 1 is for.

Fallbacks, if the emulator turns out too expensive: reduce artifact retention; run by hand only what changed; run the emulator on a local machine with `/dev/kvm`.

### Seeding

- Only EPUBs generated in code, with fictional text, no binary. Nothing third-party in CI, and the local test folder has no EPUB.
- [V] Standard Ebooks does not have Machado de Assis. Domínio Público returned 403. Project Gutenberg 55752 exists, but forbids automation and is not suitable for the repository.
- On the test device, Gutenberg 55752 is downloaded by hand, outside the repository. On copyright: Lei 9.610/98, art. 41, "Os direitos patrimoniais do autor perduram por setenta anos contados de 1º de janeiro do ano subseqüente ao de seu falecimento" (the author's economic rights last seventy years counted from 1 January of the year following their death) (https://www.planalto.gov.br/ccivil_03/leis/l9610.htm, read on 30/10/2026). Machado died in 1908; the count starts on 01/01/1909 and the term ended in 1979, base date 31/10/2026. This applies to the work, not to a modern edition with notes.
- Known limit: a generated file only proves what the generator writes. Obfuscated fonts, DOCTYPE, heavy CSS and malformed XHTML only show up with a real book, on the test device.

## 7. Decisions

With all recommendations accepted, the step is defined.

### Now

1. Order relative to step 3:
   1. Step 3 closes and only then block A, with the trial (piece 1) before it, in a single commit [recommended: both touch `ReaderHost`, `Chapter.sq`, `ChapterProgress.sq`, migration and backup; step 3 brings `INTERNET` and the version 2 this one depends on; step 3's decision 1 was sequential].
   2. Block A in parallel, on a short branch. It moves forward without touching the core, but runs the emulator on both at the same time.
   3. Everything waits, including the trial.
2. Size of step 4:
   1. Milestone A; B and C are decided with the test device in hand [recommended: it is what the skeleton promised, and step 2 made the same cut].
   2. Milestones A and B.
   3. A, B and C, closer to the scope recommendation made when the stack was proposed.

   If C stays out, the "abre no passo 4" ("opens in step 4") notice changes to a text with no step number, against the letter of step 3's decision 5.
3. Emulator: manual trial, then `paths` and manual, no nightly, one image. If it goes past 25 min per run, the emulator stays manual only, and that weakens "CI shows red and green" during that period.
4. New things in the project: `androidx.webkit` 1.18.0 (Apache-2.0, needed for the safe bridge); `androidx.test` runner 1.7.0 and `ext-junit` 1.3.0, test only; the emulator action; the `epub-fixtures` module.
5. Remove `allow-scripts` from the fork, if it passes in the WebView [recommended: without it, only the CSP stops the book's script, and the bridge filter does not help].
6. Copy of the database before migrating: since there is no restore, keep a private copy before the migration, and a screen instead of a crash if it fails [recommended, already for step 3's version 2].
7. Method: the trial (piece 1) and the positive control (piece 8) enter green, because they measure the engine and a red there would be staged. The current exception applies only to characterization of an outside service; the proposal is to extend it to these two cases.
8. `publish` does not wait for the emulator, and there is no branch protection. An APK with a red defense could be published:
   1. Rule: only install on the test device after the emulator is green, by run number [recommended: zero cost].
   2. Move the emulator into `ci.yml`, with `publish` waiting. More minutes and a slower APK.
9. End of the book: going past the last page shows an end block, as in Strip [recommended]. The alternatives are marking at the last section or only by hand.
10. Dom Casmurro: Gutenberg 55752 is downloaded by hand into `Books/` on the test device, when piece 17 arrives.
11. With TalkBack on, the reader forces Scroll, like Readium Swift and Thorium [recommended, to be confirmed in the test with the finished app]. The target for the reader's controls stays at 48 dp, which meets the 44 px of the project's accessibility rule and the automatic check.
12. Requests to step 3, cheap now and expensive later:
    - Rule 8b does not identify a standalone EPUB: the natural tie-breaker is the file name in `files[0]` (piece 9).
    - Store format and file name in text chapters.
    - Name formats by content, so `KAVITA_ARCHIVE` does not require `KAVITA_EPUB` without a rule.
    - A nullable `local_cfi` column in `progress_agreement`, in piece 8.
    - Opened chapter as a sealed type, if piece 10 touches that.
    - Agree on who bumps the backup format.

### No change, unless decided otherwise

- Out of Milestone A: formatting panel, per-work settings, themes, footer, counting, auto-advance, Scroll with a button, cover and the two OPF titles, preferences in the backup. It is phase 1 debt.
- Default typeface: still "to be decided" in the product design. Provisional in Milestone A, to be chosen with the tests on the test device. The font licenses were not looked at.
- Visual foundation: still after EPUB (order of 28/10). The text screen starts out provisional.
- Safe Browsing: stays at the default, as the documentation says.
- Fork: stays at `0c7a0edc`, without chasing a newer commit.

### When reaching Milestones B and C

Preliminary recommendations, no need to decide now:

- B-1. In `Novels/`, which files split by table of contents? A name with a chapter number is one chapter; `Vol.` or a name without a number splits [recommended]. The alternatives are all splitting, or the Work screen asking.
- B-2. What marks a chapter as read in a continuous novel? Passing the start of the next chapter [recommended]; an end block in each chapter; only at the end of the file.
- B-3. Compiled novel coming from Kavita: chapters from the table of contents, with one record there and per-item marks here [recommended]; or a single chapter. The contract changes in both cases.
- C-1. Position in the first spine item, with no record in Kavita: stays only here [recommended]. Sending `pageNum` 1 would be lying. A proposal to Kavita can come along, under the principle of serving all clients.
- C-2. Read coming from Kavita for an EPUB: accept it and reopen at the marker [recommended].
- C-3. Send read only at the true end [recommended], or on reaching the last item, like the web reader.
- C-4. A marker-only change within the same section does not ask [recommended]. It changes the test on line 170 of the contract.

### Open items in the design

- There are two conflicting indications about Dom Casmurro's number 148: chapters or pages.
- The description of the Hyphenate button still appears, superseded by the decision of 10/10.
- The product design asks for "book by page", and foliate does not give a real page. What to show is left for when the footer comes in.

## 8. Risks and what was not verified

Risks, from most to least serious:

1. The fork may fail with the narrow CSP, the sandbox or per-entry reading in the WebView. Pieces 6 to 10 find this out early. If it fails, the work stops; changing the base is a separate decision.
2. Security: the whole defense was measured only on desktop Chromium, without foliate. The test only counts with the positive control firing, which is why piece 8 fails if it does not fire. A fake interface inside the book has no technical defense; there is only the confirmation before leaving the app.
3. Position: in the proof of concept of 22/10, done with the original foliate, the return failed on the first round with the real book. There may be a CFI divergence between `srcdoc` and `createDocument`. Piece 14 exists for this.
4. Emulator CI time: not measured in Kamayoq, with both steps running. Piece 1 and decision 3.
5. Emulator flakiness on 2 vCPU may cost minutes and trust.
6. Migration: it is the project's second, and there is no restore. Decision 6. The real data today is small.
7. A green emulator does not prove the test device: the WebView is different, without the real fonts and hyphenation. The test on the device remains mandatory.
8. Bugs inherited from the fork: #149 (`#locked` stuck), #84 (selection on Android), Readest #5924 (blank pages). If they show up, they become a test before the fix.
9. Memory with a large EPUB: each resource becomes a Blob in JS. Readest #553 records running out of memory above 100 MB when opening everything in JS. Kotlin serving per entry reduces this, but it was not measured.
10. WebView Range bug on an intercepted response (Chromium 40739128, cited by Readest): relevant only if Kotlin serves parts of an entry.
11. Old WebView on a device with `minSdk` 31: the message is tested with a simulated version. There is no real coverage.
12. In Pages, TalkBack is poor in the other readers. Only a person can confirm.

Not verified:

- Fork: none of it has run anywhere. The proposed CSP has never run with foliate. Same for `srcdoc`, the sandbox without `allow-scripts` and `isMainFrame` in `srcdoc`. The exact behavior of `shouldOverrideUrlLoading` with `srcdoc`. The original's 14 commits.
- Android: WebView version on the test device and on each image; `/dev/kvm` in this repository; whether the WebView honors the cleartext policy; how to bring down the renderer in a test; `ZipFile` with rejected names on Android 14+ in EPUB; time of the SAF copy on the test device; APK size with the fork; how to package `third_party` in AGP 9.4.1; whether CHECK in `ADD COLUMN` works on Android's SQLite version; the database copy in relation to WAL.
- CI: emulator time and failure rate in Kamayoq.
- Kavita with EPUB: everything. It is code reading; nothing was measured on the server. Including page 0 with a marker, `linear="no"`, an SVG item in the spine, `metadataProvider` in Book, a loose EPUB at the root, and the marker round trip with the web reader.
- Content: no real EPUB was opened, not even Gutenberg 55752. Domínio Público's terms (403). License of the reading fonts. Whether a real EPUB 2 has DOCTYPE in NCX.
- `FolderConvention` with `Books/Work/Vol. 1.epub`: the assumption is that it follows the series-folder path, without checking the code with EPUB.
- Of the external sources in `other-readers-and-emulator.md`, the public measurements are a snapshot from 30/10/2026 and change with the window.
- The final check of 31/10/2026 covered the facts marked [31/10]. The rest of the notes was not rechecked.
