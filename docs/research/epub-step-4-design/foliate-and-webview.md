# foliate-js and WebView for step 4 (EPUB)

Survey done on 30/10/2026 (`date`: 2026-10-30T23:02:13-03:00). Read: code of `johnfactotum/foliate-js` and `readest/foliate-js` at the commits below; `readest/readest` (excerpts); AndroidX `webkit` and Chromium `android_webview` (source); official Android reference (`WebSettings`, `WebViewClient`, `WebView`, `WebChromeClient`, `WebResourceResponse`) and guides; F-Droid (policy, metadata reference, `scanner.py`); CSP3; MDN browser-compat-data; proof-of-concept (PoC) report of 22/10/2026.

Labels: **[V]** verified in an open source just now; **[M]** measured on 30/10/2026; **[I]** inference. What could not be seen is marked "not available".

## Conclusions that change the design

1. **[M] The CSP the README recommends (`script-src 'self'`) is not enough in this app.** With it, a `<script src="/path">` inside the book ran and reached `parent`. If the book's bytes come from the same origin as the reader, the book itself can be that script. With `script-src` limited to the `/reader/` path, `/book/evil.js` was not even requested.
2. **[V] The fork loads EPUB sections through `srcdoc`, not `blob:`.** The README is out of date on this. **[M]** The page's CSP is inherited on both paths.
3. **[M] `allow-scripts` can be removed from the sandbox in Chromium:** script and `onload` did not run, with no CSP at all, and the click still reached the parent's listener. It would be a second, independent layer, at the cost of one changed line in the fork. Still to test with the real foliate in the WebView.
4. **[M] `<meta http-equiv="refresh">` is blocked by the CSP, but it kills the section:** the iframe ends up on an error page from another origin and `contentDocument` becomes null. It must be neutralized before rendering.
5. **[V] When step 3 adds `INTERNET`, `setBlockNetworkLoads` changes its default from `true` to `false`.** It must be set to `true` in code.
6. **[V] The fork's `EPUB` requires `entries` in the loader** (the original does not); and `fixed-layout.js:1` has a bare import that does not work without a build. "No build" only holds for the reflow flow.
7. **[V] Readest is not a security model:** its CSP has `'unsafe-inline' 'unsafe-eval' data: blob:` in `script-src`; Readest's defense is sanitizing with DOMPurify.

## Part 1. foliate-js

### State of the repositories [V]

| | johnfactotum/foliate-js | readest/foliate-js |
|---|---|---|
| Default branch (API) | `main` | `main` |
| HEAD | `78914aef` 2026-05-01 | `0c7a0edc` 2026-10-26 |
| License | MIT (`LICENSE`, 2022 John Factotum) | MIT, same file (not in the list of changed files) |
| Tags / releases | 0 / 0 | 0 tags |
| State | open issues: 69 (API, includes PRs) | fork, issues disabled (`has_issues: false`) |

Comparison through the API (`main...readest:foliate-js:main`): diverged, **267 ahead, 14 behind**, common base `6b11e174` (2025-11-28), 21 files. Largest: `paginator.js` +3589/−290 (4429 lines against 1130), `fixed-layout.js` +1934/−68, `pdf.js` +992/−68, `epub.js` +451/−56, `overlayer.js` +370/−25, `tts.js` +327/−35, `section-chunks.js` new (235).

Fork activity: 33 commits in October 2026 (up to the 26th), 22 in September, 23 in August; 252 commits since 2025 by Huang Xin, almost the only author. Of the 250 commits the API listed, by scope: paginator 39, fixed-layout 22, pdf 21, epub 16, opds 8, tts 8; 109 without a conventional scope. Substance for EPUB: continuous multi-view scrolling, giant sections in chunks, gestures and page turning, malformed XHTML, media types, items missing from the archive, CFI.

The 14 commits of the original missing from the fork range from 2026-03-04 to 2026-05-01 (e.g. `b74d50e` offsets in `FILTER_SKIP` wrappers, `ab5abb9` `pageBreakSource`, `9a67679` MOBI anchors, `5ba02c7` TTS, `78914ae` original hrefs on external links). In `3393b85`/`1217096`, CBZ and PDF.js updates. None was analyzed line by line.

**Made for third parties?** [V] Not as a product: no tag, no issues, `package.json` still points to the original, README unchanged (describes the original). The consumer is Readest: submodule `packages/foliate-js` pinned at `0c7a0edc` on `main` `716aadae` (2026-10-30). The API changes as Readest needs; the original author also warns "*not* stable". PR `#43` on the original, to bring in Readest's changes, was closed; the divergence is permanent [I].

### Licenses [V]

| Component | License | Where | Goes into Kamayoq? |
|---|---|---|---|
| foliate-js | MIT | `LICENSE` | yes |
| zip.js 2.7.52 | BSD-3-Clause | README, `package-lock.json:686` | not needed (see opening) |
| fflate 0.8.2 | MIT | same `:748` | no (MOBI/KF8) |
| pdf.js (pdfjs-dist 6.2.108) | Apache-2.0 | header of `vendor/pdfjs/pdf.mjs` | no |
| pdf.js fonts | Foxit: BSD-style (PDFium); Liberation: SIL OFL | `standard_fonts/LICENSE_*` | no |
| cmaps | BSD, Adobe | `cmaps/LICENSE` | no |
| construct-style-sheets-polyfill 3.1.0 | MIT | lock `:725` | only if `fixed-layout.js` is used |
| Readest (app) | **AGPL-3.0** | API | **never copy** |

Search for `agpl|affero|GNU General` in the fork outside `vendor/`: no results. This does not prove that nothing came from the AGPL app, because the same author writes both [I]; the risk is only in copying code from the app, such as `sanitizer.ts`. The GNU list says Expat is compatible with the GPL, and Apache 2.0 with GPLv3. Nothing proprietary or incompatible copyleft was found in what would go in. The `LICENSE` (and the zip.js notice, if it stays) must go with the copy.

### (a) Embedding and opening

- [V] Native ES modules, no build (README). Exception: `fixed-layout.js:1` does `import 'construct-style-sheets-polyfill'`, a bare specifier the browser does not resolve without an import map. It is only loaded for `pre-paginated` EPUB (`view.js:265-267`). Under a strict CSP, an inline import map would be blocked [I]. Way out: remove the line, recorded patch (`adoptedStyleSheets` exists in WebView since 73).
- Static imports of `view.js`: `epubcfi`, `progress`, `overlayer`, `text-walker`, `section-chunks` (`view.js:1-5`). Dynamic: `paginator`, `fixed-layout`, `search`, `tts`, and other formats only in `makeBook`.
- The host only creates `<foliate-view>` (custom element with shadow DOM) and calls `open` (`view.js:243`).
- `view.open(x)`: if it is a string, Blob/File or directory, it calls `makeBook`; **any other object is treated as a ready book** (`view.js:244-246`).
- `makeBook` uses zip.js with `BlobReader` and `useWebWorkers: false` (`view.js:33-46`). Random access, but it needs a `Blob` of the whole file. The PoC downloaded everything with `fetch().blob()`.
- **Own loader** (no zip.js in JS): `new EPUB(loader).init()` (`epub.js:1269`, `:1298`), with `{ entries, loadText, loadBlob, getSize, sha1? }`. In the fork **`entries` is required** (`entries.reduce`, `epub.js:1270`), an array of `{ filename }`; in the original this parameter does not exist (`epub.js:936` there). `loadText` and `loadBlob` must return `null` for a missing entry: `#loadXML` checks this for `META-INF/encryption.xml` and similar (`:1279-1281`, `:1312`). `sha1` defaults to `crypto.subtle` (`:792-796`), which only exists in a secure context; the `https://appassets...` origin qualifies [I, issue #121 describes the failure on `http://`].

Sketch for Kotlin serving per entry (a section is only read when it opens, and `unload` revokes):

```js
const index = await (await fetch(base + 'index.json')).json() // [{filename, size}]
const size = new Map(index.map(e => [e.filename, e.size]))
const url = n => base + 'e/' + n.split('/').map(encodeURIComponent).join('/')
const get = async n => size.has(n) ? fetch(url(n)) : null
const book = await new EPUB({
  entries: index,
  loadText: async n => (await get(n))?.text() ?? null,
  loadBlob: async n => (await get(n))?.blob() ?? null,
  getSize: n => size.get(n) ?? 0,
}).init()
await view.open(book)
```

Kotlin already has a compatible path: `ArchivePages.kt` copies to a local file and opens it with `java.util.zip.ZipFile`. Each resource becomes a Blob in JS memory while the section is alive; the real consumption for a large book was not measured.

### (b) Events and data

- The view's `relocate` (`view.js:341-350`): `{ fraction, section: {current,total}, location: {current,next,total}, time: {section,total}, tocItem, pageItem, cfi, range }`. `reason` is not passed on in the view's detail.
- Total `fraction`: by uncompressed bytes of the linear sections, `(bytes before + fraction in section × section + page fraction × section) / total` (`progress.js:175-199`). It is not a page count. `location` is per 1500 bytes and `time` per 1600 (`view.js:252`): neither is a real page or a calibrated minute.
- Fraction in section: paginated `local column / section columns`; scrolled `offset / size` (`paginator.js:3780-3799`). A chunked section is rescaled (`:3805-3811`).
- `goTo(target)`: number (index), `{fraction}`, CFI or href (`view.js:516-540`). `goToFraction(frac)` (`:541-545`).
- Mode: attribute on the renderer, `view.renderer.setAttribute('flow','paginated'|'scrolled')`, without reloading (`paginator.js:1941`, `:2413`; README). In the fork scrolling is continuous across sections, with `no-continuous-scroll` and `no-preload` (`:1508-1512`).

### (c) Position and CFI

- Restore: `view.init({ lastLocation })` or `view.goTo(cfi)` (`view.js:326-337`).
- [V] A CFI is a structural path: steps through children and a character offset (`epubcfi.js`); it does not depend on font or size. It depends on the DOM being the same. Injected nodes need a filter or `cfi-inert` (`epubcfi.js:190-219`).
- [V] The fork changed what the PoC measured in the original: there `#anchor` was re-captured on every scroll (`paginator.js:956-957` of the original); in the fork, scrolling caused by the re-render itself does not move the anchor (`:3766-3775`, readest#5808). On resize, the primary goes back to `#anchor` (`:2071-2073`). This **was not measured**.
- [I] Removing elements from the body while sanitizing changes CFI indexes and invalidates positions. Prefer neutralizing an attribute over removing a node.
- [I] Risk to test: the fork renders through the HTML parser (`srcdoc`), while `createDocument` uses the XML parser (`epub.js:47-57`, `:1402-1406`). `PageProgress` and search use `createDocument`; if the two DOMs differ, CFI diverges.

### (d) Table of contents

- `book.toc`: tree `{ label, href, subitems }` (`parseNav`, `epub.js:444-473`). `subitems` is `null` when there are no children. It comes from the EPUB3 nav; the NCX is plan B if the nav has no href at all (`:1367-1381`).
- `resolveHref("cap.xhtml#x")` returns `{ index, anchor(doc) }`, or `null` if outside the manifested spine (`:1413-1420`). `href` is decoded with `decodeURI`.
- Several entries in one spine item: `TOCProgress` groups by file and picks the last one whose fragment is before the visible point (`progress.js:18-59`). Without a fragment, only the first one responds. A section without an entry inherits the previous group (`:34-37`); before the first entry, `tocItem` is null.
- **Consequence [I]:** the product's "chapter" is not the same as a spine item or a TOC item. The model must not assume 1:1.

### (e) Section loading and security

- `Loader.loadItem` builds everything recursively, replaces references with `blob:` URLs (`epub.js:949-969`, `:1011-1034`, `:1099-1185`) and reference-counts them for revocation (`:970-1009`).
- It rewrites `link[href]`, `[src]`, `[poster]`, `object[data]`, `xlink:href`, `srcset`, `<style>`, `style=` and `url()` in CSS (`:1150-1175`). Inline `<script>` **is not touched**, and an external URL stays as it is (`:144`, `:1087`).
- [V] Iframe: `sandbox="allow-same-origin allow-scripts"` (`paginator.js:783`), kept because of WebKit bug 218086 (comment `:781`; the bug is WebKit's).
- [V] `section.load()` creates a blob, but `section.loadContent()` returns the stored string (`epub.js:1035-1042`, `:961`) and `View.load` uses `srcdoc` if there is data (`paginator.js:884-888`, `:3913`, `:4162-4165`, `:3837`). Since every EPUB section has `loadContent` (`epub.js:1339`), **XHTML and HTML go through `srcdoc`**. SVG in the spine stays in a blob `src` [I]. Originating commit: not available (API search empty).
- A section above 512 KiB is cut into ~256 KiB chunks (`paginator.js:7-8`, `:1984`).
- README: scripts "not supported"; requires CSP; warning "Do NOT use this library [...] without CSP". **The fork's README is out of date:** the code has a path for book scripts (`paginator.js:767-774`, `epub.js:1340-1344`), used by Readest's `allowScript`.
- Useful hook: `transformTarget` event `load` with `detail.isScript`, `detail.allow`, `detail.url` (`epub.js:1015-1021`). `allow = false` denies a script file. The `data` event transforms text before the blob (`:951-957`).
- Unsafe point in the host: a click on an external link emits a cancelable `external-link`; **if it is not canceled, `globalThis.open(href,'_blank')` runs in the host context** (`view.js:371-373`), outside the sandbox. `isExternal` is `/^(?!blob)\w+:/i` (`epub.js:158`), so `javascript:`, `intent:`, `file:`, `content:` go through there. Issue #64 reports `javascript:` executing in another tab in the original. Kamayoq must always call `preventDefault` and decide by allowlist.
- Readest: `allowScript` default `false` (`constants.ts:401`), DOMPurify (`sanitizer.ts`), denies scripts in the `load` event (`FoliateViewer.tsx:954-955`).

### (f) Style

- `view.renderer.setStyles(css)`, string or `[before, after]`: two `<style>` in the `head` of each section, one first and the other last (`paginator.js:4368-4394`, `:3826-3829`). Requires `style-src 'unsafe-inline'` (comment in #64).
- Theme, font and hyphenation are the app's CSS. The library does not hyphenate; the example uses `hyphens:auto` and `-webkit-hyphenate-limit-*` (`reader.js:6-24`). `lang` comes from the metadata if the section has none (`view.js:353`).
- Inversion: `foliate-view::part(filter)` (README). `#transformCSS` replaces `page-break-*` with column breaks (`paginator.js:2013-2023`).
- Font, margin etc. also through attributes: `gap`, `column-gap`, `margin-*`, `max-inline-size`, `max-block-size`, `max-column-count` (`:1508-1511`). No per-property API.

### (g) What it requires from the browser

Minimum version in `webview_android`, MDN browser-compat-data 8.1.7 (2026-10-22):

| Feature | Use | WebView |
|---|---|---|
| `container-type` | `paginator.js:1583` | 105 |
| `findLastIndex` | `paginator.js:4342` | 97 |
| `Array.at` | `epub.js`, `paginator.js` | 92 |
| `Intl.Segmenter` | search, TTS | 87 |
| top-level await | if the reader page uses it | 89 |
| `::part`, `adoptedStyleSheets` | filter, fixed-layout | 73 |
| `Object.groupBy` | original `epub.js:206`; check in the fork | 117 |
| `startViewTransition` | optional, feature-tested first (`paginator.js:2432`) | 111 |

CSS columns (`column-fill: auto`, `:999`). The real floor is at least 105 [I]. The README only promises "latest version". With `minSdk` 31 the WebView is updatable, but there is no version guarantee; it is worth checking `WebViewCompat.getCurrentWebViewPackage()` and showing a clear error (issue #146: error swallowed on an old WebView). WebView version on the test device: not available.

Blob and srcdoc in an iframe require `frame-src blob:` [M, see Part 2]; `srcdoc` asked for nothing else in the test.

### (h) Broken, stalled, Android

- [V] `tests/` only covers CFI (`epubcfi-tests.js`): pagination and `epub.js` have no tests in the repository. The fork's tests live in Readest (message of `0c7a0ed`).
- [V] No mixed layout (README). Search "extrenely slow" (so, with the typo, in the README).
- [V] Original #84: screen shifts when selecting text on Android; Readest's fix cancels scrolling during selection, partly in the **app** (`useTextSelector.ts`), so it does not come for free with the fork. #157: wide iframe breaks the book's media queries. #150 and #149: paginator. #146: old WebView.
- [V] `paginator.js:1883`: "FIXME: this won't work on Android WebView", disables automatic page turning while dragging a selection.
- [V] Readest #5924: blank pages in the middle of a chapter (closed; cause not read). #6288: selection jitter on Android 16 (closed; not read).
- [V] `view.js:290` has `x.index = resolved.index` (assignment instead of comparison), in media overlay; out of scope.

### How to maintain the copy

The form is already decided: a copy in `third_party/foliate-js` ([`stack-2026-10-23.md`](../../decisions/stack-2026-10-23.md), line 8). The evidence below does not call for reopening it.

| Project | Form | Note |
|---|---|---|
| Foliate (GPL-3.0, `gtk4`) | submodule of the original | `.gitmodules` |
| Readest (AGPL) | submodule of the fork | `.gitmodules` |
| Anx Reader (MIT, `develop`) | **modified copy** in `assets/foliate-js`, with `src/`, webpack and `dist/bundle.js` (614,700 bytes) committed | advertises F-Droid; metadata in `fdroiddata` |
| Komelia (F-Droid) | submodules, EPUB readers with npm | `submodules: true`, `scandelete: third_party`, `gradle buildEpubReaders` |

The foliate README recommends a submodule. Pros of the submodule: updating means changing a pointer. Cons: it requires a separate public repository and `submodules: true`.

F-Droid [V]: the Inclusion Policy mentions neither submodules nor JavaScript; it requires FLOSS, building from source, and dependencies from source or from a trusted Maven (Google Maven included). `submodules: true` runs `git submodule update --init --recursive`. `scanner.py` (`cdfdce83`, 2026-10-27) flags binaries, `.jar`, `.so`, `.apk`, and has `DEPFILE` linking `package.json` to a lockfile (`:64-68`, `:1123-1127`). `grep` for `minif`/`.min.js` found no rule; JS is text. F-Droid builds only public source.

Procedure recommendation [I]:
- Copy only: `view, epub, epubcfi, paginator, section-chunks, progress, overlayer, text-walker, footnotes, LICENSE`; `search`, `tts` and `fixed-layout` when they come in. **Left out:** `vendor/`, `pdf.js`, `mobi.js`, `fb2.js`, `comic-book.js`, `opds.js`, `reader.*`, `ui/`, `tests/`, `rollup*`, `package*.json`. This way there is no minified bundle and no PDF.js (12 MB), and no lockfile trigger. The `import()` calls for missing files only happen in `makeBook`, which the app does not call.
- `third_party/foliate-js/UPSTREAM.md`: repository, branch, commit, date, list of copied files, and one line per change with reason and local commit.
- An isolated import commit, with no changes; each patch in its own commit with a prefix in the subject.
- Updating: in the fork's clone, `git diff <old-commit>..<new> -- <files>`, apply with `git apply --directory=third_party/foliate-js`, run the checklist and record it. Otherwise, do not update. `git subtree` is available here (git 2.56), but it would bring the history and the excluded files.
- Watch the original's fixes manually: 14 commits not in the fork.

## Part 2. WebView with file content

### Platform facts [V]

| Item | Fact | Source |
|---|---|---|
| `setAllowFileAccess` | default `false` when targeting Android 11 or higher; targetSdk 36 falls into this | WebSettings |
| `setAllowContentAccess` | "The default is enabled": **call with `false`** | WebSettings |
| `setBlockNetworkLoads` | default `false` with `INTERNET`, `true` without. Setting `false` without the permission throws `SecurityException` | WebSettings |
| Blocking and interception | blocking becomes `LOAD_ONLY_FROM_CACHE`, and `shouldInterceptRequest` is still called | `net_helpers.cc:86-94`, `aw_proxying_url_loader_factory.cc:488-514`, Chromium `d0fbc69a` |
| `shouldInterceptRequest` | not called for `javascript:`, `blob:`, `android_asset`; on redirect, only for the initial URL; runs off the UI thread | WebViewClient |
| `shouldOverrideUrlLoading` | not called for the app's own `loadUrl()` nor for POST; may be called for subframes and non-HTTP schemes | WebViewClient |
| Windows | `setSupportMultipleWindows` default `false`: `window.open` and `_blank` become top-level navigation in the same WebView. `onCreateWindow` default: `false` | WebSettings, WebChromeClient |
| `addJavascriptInterface` | injects into all frames, without checking origin | WebView; guide "insecure-webview-native-bridges" |
| `WebViewCompat.addWebMessageListener` | injects only into frames whose origin matches `allowedOriginRules`; callback carries `sourceOrigin` and `isMainFrame`; only string or ArrayBuffer | `WebViewCompat.java:574-770` |
| `WebMessagePort` / `createWebMessageChannel` | requires pinning the target origin; "*" accepts any sender | guide above |
| `WebViewAssetLoader` | default domain `appassets.androidplatform.net`, always https; `http` only with `setHttpAllowed` | `WebViewAssetLoader.java:106`, `:452-457`, `:509` |
| Loader with no match | returns `null`; the WebView "falls back to loading content from the internet" | guide load-local-content |
| `InternalStoragePathHandler` | forbids `databases/`, `shared_prefs/` etc.; everything in the directory is reachable by the origin (`:266-268`, `:287`) | source |
| Intercepted headers | `WebResourceResponse(..., responseHeaders, data)` and `setResponseHeaders` | reference; `aw_proxying...cc:334-340` |
| Safe Browsing | on by default; the documentation recommends keeping it; intercepted URLs still go through the check | guides, WebViewClient |
| Renderer | `onRenderProcessGone`: the `WebView` cannot be reused; recreate it. If not handled, it brings down the app (the guide shows `return false` letting it crash) | managing-webview |
| `androidx.webkit` | Apache-2.0, 1.18.0 latest on Google Maven; `androidx-main` `31434e7d`. The PoC used 1.17.1 | Maven, source |

A `meta` CSP does not apply `frame-ancestors`, `report-uri` or `sandbox`, and does not cover what came before it (CSP3 §3.3). Local-scheme documents inherit the policy (CSP3 §7.8: srcdoc, blob:, data:, about:blank). An additional policy adds restrictions, never loosens them (§3.3).

Process isolation: I only confirmed that there is a separate renderer process with its own lifecycle. Whether same-origin frames share a process in WebView, and site isolation guarantees: not available; here everything is the same origin anyway [I].

`androidx.webkit` is a new dependency and, under the project's rule, needs approval before it comes in. The app has nothing from webkit today. It would use: `WebViewAssetLoader`, `WebViewCompat`, `WebViewFeature`.

### What I measured today [M]

Own test pages, in desktop Chromium 152.0.7977.167, on `127.0.0.1`. **This is not Android WebView, and foliate was not run** in this measurement. They count as evidence about the engine; confirmation happens on the device.

1. **README CSP.** Header with `script-src 'self'` and `allow-same-origin allow-scripts` iframes, blob and srcdoc. Blocked in both: inline script, `body onload`, `img onerror`, `<svg><script>`, remote image, `data:` iframe. **Ran:** `<script src="/gadget.js">`, with access to `parent`.
2. **Path-based CSP.** `script-src http://.../reader/`, without `'self'`, via header and via `<meta>`, same result. The page's `import()` worked. `/book/evil.js` and a `blob:` script did not run; the server did not even receive the request for `evil.js`. Remote server with 0 requests for `link stylesheet`, `@import`, `@font-face`, `background`, `audio`, `object`, `embed`, `preconnect`, `prefetch`. Inconclusive: `dns-prefetch` (the test does not see DNS) and `ping` (click by script).
3. **Meta refresh.** Blocked by `frame-src`, but the iframe was left with a null `contentDocument`.
4. **Sandbox without `allow-scripts`, no CSP.** `sandbox="allow-same-origin"`: script and `onload` did not run. From the parent these worked: click listener with a real click, injecting `<style>`, `ResizeObserver`, `getSelection`.

### Proposed CSP [I, based on M]

Sent as a header on the intercepted response for `/reader/index.html`:

```
default-src 'none';
script-src https://appassets.androidplatform.net/reader/;
style-src 'unsafe-inline' blob:;
img-src blob: data:; font-src blob:; media-src blob:;
connect-src https://appassets.androidplatform.net/book/;
frame-src blob:;
base-uri 'none'; form-action 'none'; object-src 'none'
```

No `'self'`, no `'unsafe-inline'` for scripts, no `'unsafe-eval'`. `/reader/` only has the app's files, fixed in the APK. A font bundled with the app may require one more origin in `font-src`. The reader's stylesheet needs checking with the real foliate: this policy has never run with foliate.

### Proposed arrangement [I]

- Own interceptor that denies by default: `/reader/**` from assets; `/book/<token>/**` from Kotlin; **everything else, a synthetic 404, never `null`**.
- Random `<token>` per opening; only the open book responds. No listing.
- Book response: `application/octet-stream`, `X-Content-Type-Options: nosniff`. The JS uses `fetch`, and the type comes from the manifest, so a script via `src` fails twice.
- Do not use `InternalStoragePathHandler` for the book.
- WebSettings: `javaScriptEnabled = true`, `allowFileAccess = false`, `allowContentAccess = false`, `blockNetworkLoads = true` (fixed), `domStorageEnabled` at the default `false`, `supportMultipleWindows` at the default `false`.
- `shouldOverrideUrlLoading`: always deny the main frame. In a subframe, allow only `about:srcdoc` and `blob:` (canceling everything would kill the section; **exact behavior to be tested**).
- Bridge: `addWebMessageListener` with the exact rule `https://appassets.androidplatform.net` and **`isMainFrame` required**. From the method's text, the object also shows up in same-origin iframes [I]; hence the filter. Only typed outgoing messages (position, table of contents, error), validated. Never the Kavita key, a file path, or a write command. Requires `WebViewFeature.isFeatureSupported(WEB_MESSAGE_LISTENER)`.
- Kavita: bytes come in through Kotlin. The WebView never needs the network or the key.
- Fork patches: remove `allow-scripts` at `paginator.js:783` and, when used, in `fixed-layout.js`; neutralize `http-equiv` in the `data` event, without removing the element; `detail.allow = false` for scripts.

### Attacks and the layer that stops them

| Attack | First layer | Backup |
|---|---|---|
| `<script>` in the XHTML, inline SVG | sandbox without `allow-scripts` | CSP |
| `onerror`, `onload` | same | CSP |
| `<script src>` from the book itself or `/book/` | sandbox | path-based CSP, `octet-stream` + nosniff |
| `<script src>` of an app file | sandbox | passes the CSP: only app code runs, no payload from the book [I] |
| `javascript:` in a link | `external-link` with `preventDefault` | sandbox; scheme allowlist |
| `intent:`, `file:`, `content:` | same, only `https`/`http` with confirmation | `shouldOverrideUrlLoading`; access settings off |
| Remote image, CSS, font, audio | CSP | `blockNetworkLoads`, interceptor |
| Meta refresh | neutralize in the `data` event | CSP prevents leaving, but the section dies |
| Remote or `data:` `<iframe>` | `frame-src blob:` | network blocked |
| Blob `<iframe>` from the book itself | inherits CSP and sandbox [I, CSP3 §7.8] | test nested |
| `<form>` | sandbox without `allow-forms` (`paginator.js:783`) | `form-action 'none'` |
| `<object>`, `<embed>` | `object-src 'none'` | network blocked |
| `<base>` | `base-uri 'none'` | foliate rewrites before |
| `fetch` to other books | no script, nothing runs | `connect-src`, token, one book at a time |
| Fake interface inside the book | nothing technical; confirmation before leaving the app, showing the real URL | Consequences rule |
| Zip bomb | limits in Kotlin: entries, per entry, total; count bytes read, not the header | `onRenderProcessGone` |
| Heavy HTML/CSS | limits above | `onRenderProcessGone`: recreate and inform |
| `../` path in an entry | only exact lookup in the name map; nothing extracted to disk with the zip's name | reject `..`, absolute, `\` |
| XXE in Kotlin | parser without external DTD | do not parse in Kotlin what the JS already does |

XXE in detail: OWASP prescribes `disallow-doctype-decl = true` and `FEATURE_SECURE_PROCESSING`; it does not cover `XmlPullParser` or Android. EPUB 2 NCX and XHTML usually carry a DOCTYPE [I], and rejecting DOCTYPE may refuse legitimate books; not checked, for lack of a sample EPUB in the survey. Alternative: disable external DTD and external entities, with an XXE test and a "billion laughs" test. The JS side goes through `DOMParser` and `sanitizeXMLEntities` (`epub.js:1260-1263`, `:1279-1287`).

### Effect of step 3

Today the manifest has no `INTERNET`, and the PoC did not have it either (manifest without permissions): **everything the PoC saw was with network blocked by default**. When step 3 adds the permission, the WebView can reach the network, unless `setBlockNetworkLoads(true)`. The loader's `null` going to the internet also starts to apply. That is why the network test must run on the build with `INTERNET`, and the order between steps 3 and 4 matters.

### Test with a malicious EPUB

Required by the stack decision ([`stack-2026-10-23.md`](../../decisions/stack-2026-10-23.md), line 8). Robolectric does not run WebView, so the test is instrumented on the CI emulator, which comes in with this step, plus a checklist on the test device.

**File:** generated by test code, from text in the repository, no binary. Fictional text or Dom Casmurro. Backup: `johnfactotum/epub-test` (CC0-1.0, with `epub-test.epub`), cited in the README; record the commit if downloaded.

**Sentinels:** each attack tries to report through its own named channel:
- `parent.kamayoqBridge.postMessage('PWNED:<case>')`
- a request to `/book/<token>/PWNED-<case>` and to another valid token
- a request to the test's own local server (`127.0.0.1`), which counts hits
- a change to `parent.document.title`

**Assertions (given… when… then…):**
1. Given the malicious book, when opening it and going through all sections, then no `PWNED` message reaches Kotlin.
2. Then every URL seen by the interceptor is in the expected list.
3. Then the local server received 0 requests, **with `INTERNET` in the manifest of the tested build**.
4. When tapping `javascript:`, `intent:`, `file:`, `content:` links, then no Activity opens and no top-level navigation happens.
5. Given a `meta refresh`, then the section stays readable and `relocate` still fires.
6. Given XHTML with all of the above, then normal text appears and the saved position comes back (the defense did not break the reader).

**Positive control, required:** the same book in a test-only control page, without CSP and with the original sandbox, must trigger the sentinels. Without it the test could pass because the payload does not work. The control must not exist in the production APK.

**JVM (before the UI):** exact CSP text; link classifier; interceptor decision; zip limits (bomb and lying header); names with `..`; XXE. This part is pure logic and fits the "test first" method.

The emulator is heavy on CI; the time was not measured. Option: run the instrumented test only when the reader or `third_party` changes.

## Part 3. What the PoC measured

Source: the report of the PoC done before the stack was chosen (separate repository, not published). Other documents only summarize it.

**Conditions:** test phone, Android 16, builds 6 and 7, 22/10/2026. **Original** foliate-js `78914aef` (`assets/foliate/VERSION`). Paginated mode. No CSP, with `addJavascriptInterface`, without `INTERNET`, whole book as a Blob.

| Book | Open and parse | 1st page |
|---|---|---|
| short, 20 chapters | 79 ms | 247 ms |
| real, 248 spine items | 92 ms | 228 ms |
| long, 3,000 chapters | 178 ms | 290 ms |

- pt-BR hyphenation worked in the test phone's WebView ("in-termináveis", "burocráti-cos", "al-gum", "pa-lácio").
- Position, first test: it came back in the short book; in the real and long ones, it did not. Second test (real book): without an anchor it came back; with a CFI anchor, 150% and 80% correct, but re-capturing on every change accumulated drift and 100% ended up somewhere else. The PoC report calls this a test design error.
- PoC conclusion: the stable anchor belongs to the app, fixed until the person navigates; the right metric is "the passage being read stays visible".
- In the visual assessment, reading and formatting were judged good.

Limits of this evidence: script blocking and a minimal bridge are missing; the evidence is from the original engine and does not hold for the fork; for images the PoC measured strip mode, not paginated; Android tests should be mandatory at the boundaries, with a real WebView; TalkBack and hyphenation still need checking on the test device.

Validation still to do (not measured): with Dom Casmurro and three font changes.

**Left open (none of this was measured):**
- the `readest/foliate-js` fork, in any number;
- section via `srcdoc`, per-entry loader, active CSP, sandbox without `allow-scripts`;
- page-turn time, memory, dropped frames;
- scroll mode and switching Páginas/Rolar (Pages/Scroll) while keeping the passage;
- correct anchor in an automated test; orientation; closing and resuming;
- giant section; real EPUB in Portuguese; `ZipFile` via SAF copy;
- tablet or mid-range device; TalkBack; embedded and obfuscated fonts;
- CFI round trip with Kavita.

Discrepancy to record: an earlier decisions record says "already fixed for the Android WebView", but that sentence comes from reading code, not from measurement; the "0.3 s" cited in the same record is from the original foliate.

## Pending decisions

1. Remove `allow-scripts` from the sandbox in the fork (recommended, if it passes in the WebView).
2. Add `androidx.webkit` 1.18.0, a new dependency.
3. Safe Browsing: keep the default, as the documentation prescribes (recommended). With the network blocked the gain is small [I], and the cost was not measured.

## Changes during the survey

The repository changed during the survey; lines cited from `docs/decisions` may have changed. The test pages used in the measurements were not committed.
