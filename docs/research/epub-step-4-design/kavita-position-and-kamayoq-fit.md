# Kavita's position for EPUB and how it fits Kamayoq (step 4)

Survey of 30/10/2026. Read: `Kareadita/Kavita` (`develop` and `v0.9.1.4`), `vers-one/EpubReader` `v3.3.6`, Readest's foliate-js (`0c7a0edc`), five Kavita clients, official documentation (Kavita wiki, EPUB 3.3, Project Gutenberg, Standard Ebooks, Planalto) and, in the project, the decisions, the step 3 design, the SQLDelight schema, `core/`, `data/`, `app/`, build and CI, plus the reader's product rules.

Marks: **[V]** verified in an open source; **[I]** inference; **[To measure]** only the real Kavita, the emulator or the test device can answer. **None of the EPUB facts below was measured on a running Kavita: everything is code reading.** Nothing was run against the server.

The Kamayoq repository was read as it stood on 30/10/2026, after the measured Kavita facts and step 3 decisions 6 and 13 were recorded; the files of step 3 piece 5, uncommitted at the time (`data/.../kavita/`, `core/.../kavita/`, `KavitaServer.kt`), were not read. Line numbers in the decisions may have changed.

## Conclusions that change the design

1. **[V] A Kavita "page" is the spine index, the same as foliate's section index.** `linear="no"` does not change the count in either. Only a broken EPUB falls outside this. This closes the open item in the contract (`progress-contract`, line 110), pending measurement.
2. **[V] The stored `bookScrollId` is not tied to Angular.** It is `//body/...` in lowercase, relative to the chapter body, or `id("x")...`. The server itself evaluates it against the raw XHTML. A native client can produce and consume it, with limits (section 1.6).
3. **[V] Kavita discards a page-0 save when there is no record, even with a `bookScrollId`.** In a single-spine EPUB, a partial position has nowhere to go: `pageNum` can only be 0 (discarded) or 1 (read). Step 3 of the contract's send flow (re-read and compare) would never confirm that item.
4. **[V] The web reader marks read on any scroll in the last spine item**, not at the end of the text. "Read" coming from Kavita for an EPUB does not mean the person finished.
5. **[V] Opening the chapter in the web reader rewrites the `bookScrollId`** as soon as scrolling settles, without any reading. Under the contract's rule (line 170), this counts as a Kavita change and can create a false conflict.
6. **[V] An EPUB is always one Kavita chapter. Kavita never splits by the table of contents.** A compiled novel in Kavita has a single progress record; the contract says to show chapters, but for the Kavita origin it defines chapter as "chapter id". This is the biggest gap and needs a decision.
7. **[V] For EPUB in Kavita, the folder does not give the series; the OPF does.** `Omniscient Reader's Viewpoint/Cap. 0928.epub` with different titles in the OPF would become one series per file. The contract's case 1 (931 Kavita chapters) only exists with series metadata.
8. **[I] Step 3 rule 8b ("chapter number + total pages") does not identify a standalone EPUB:** they all have Kavita's default number. It needs to be considered in step 3 before piece 9.
9. **[V] Today nothing in the app accepts EPUB.** `FolderConvention` marks it as an unsupported format; `ArchivePages.open` rejects a file with no images; the position is image-only.

## Part 1. Kavita and EPUB

### 1.1 Repository state [V]

| | Value |
|---|---|
| Default branch (API) | `develop` |
| `develop` HEAD | `3c9ddb4a6db7c918a6dba2d5d234a61dd2ed848c`, 28/10/2026, "Release changes for 0.9.1.16" (nightly) |
| Latest stable | `v0.9.1.4`, commit `879b8b73eb41d66f88dde8eb874cc99a3397eb11`, 02/09/2026 |
| EPUB library | `VersOne.Epub` 3.3.6 in both (`Kavita.Services.csproj`), Unlicense; tag `v3.3.6` read, `0fed46ce` |
| CI image | digest `31181a32…` in `ci.yml`, tag 0.9.1 = v0.9.1.4 according to the `ci.yml` comment itself |

`file:line` citations below are from **`v0.9.1.4`**, the version the CI proves, unless marked "develop"; `ParseScannedFiles.cs`, `KavitaPlusConfiguration.cs`, `FileTypeGroup` and the HTML template were read on `develop`. Compared with `develop`: identical in `saveProgress` logic, XPath generation and scoping, `KoreaderHelper` (0 lines of difference), `GetBookPage`, TOC, counting. `SaveReadingProgress` on `develop` only adds an access check and `ct`. What changed on `develop` and is **not** in 0.9.1.4: handling of `noteref` in `RewriteAnchors`; the signature of `GetBookPage`.

### 1.2 `BookController` and `BookService`

Routes in `Kavita.Server/Controllers/BookController.cs` [V]:

| Route | Line | What it does |
|---|---|---|
| `GET api/Book/{id}/book-info` | 33 | Title, `pages`, series, volume, format |
| `GET .../book-resources?file=` | 106 | One resource from inside the EPUB |
| `GET .../chapters` | 131 | Table of contents: tree of `{title, part, page, children}` (`BookChapterItem.cs`). `page` is the spine index; `part` is the anchor, without `#` |
| `GET .../book-page?page=` | 159 | One spine item, rewritten |

What `GetBookPage` does to the XHTML (`BookService.cs:1749-1809`; `ScopePage` at `:1534`) [V]:

- Picks the item by the `page` counter in reading order (`:1767-1773`).
- Fixes `<script/>` and `<title/>` with a regular expression (`EscapeTags`) and parses with HtmlAgilityPack, `OptionFixNestedTags = true` (`:1761`, `:1781`). A tolerant HTML parser, not XML.
- Moves the CSS into the body: each `<style>` and each `link` becomes a `<style>` at the start of the `body` (`InlineStyles`).
- CSS scoping: `styleContent.Replace("body", ".book-content")` (`:222`) and a `.book-content` prefix on each rule (`CssScopeClass`, `:52`). A text replace; it hits the word "body" in any selector.
- Rewrites anchors to `kavita-page`/`kavita-part` and `href="javascript:void(0)"`, images to `book-resources` with the `image-only` key, injects bookmarks and annotations.
- `PrepareFinalHtml` (`:405`) returns `<div class="html and body classes">body content</div>`. That `div` plays the role of the `body`.

**[I]** For Kamayoq, `book-page` is no use: the product rule is "Kavita EPUB downloaded and paginated in the client", and foliate wants the file. The path is `GET api/Download/chapter`.

### 1.3 What an EPUB "page" is, and the conversion to a foliate section

Kavita [V]:
- `GetNumberOfPages` returns `epubBook.GetReadingOrder().Count` (`BookService.cs:909`). An exception becomes 0 and a media error (`:944-966` on develop).
- `SpineReader.GetReadingOrder` (VersOne, `Readers/SpineReader.cs:13-41`) walks **all** `itemref`s in the spine. It does not look at `linear`.
- Only items with `media-type` `application/xhtml+xml` go into `Html.Local` (`ContentReader.cs:49-67`, `:184`). A spine item of another type (SVG, `text/html`) does not find the file and, since `IgnoreMissingContentFiles` starts as false, throws. From the flow, the chapter ends up with 0 pages [I; to measure].
- `IgnoreMissingManifestItems = false` (`BookService.cs:74`, `:95`): an orphan `idref` also throws.
- Same item twice in the spine: `CreateKeyToPageMappingAsync` uses `dict.Add` (`:947`), which throws on a repeated key. Affects the table of contents and `book-page`; the count passes [I].

foliate (Readest fork, `0c7a0edc`) [V]:
- `book.sections = spine.map(...).filter(s => s)` (`epub.js:1327-1355`): keeps `linear` on each section and does not filter by it. Drops only an `idref` with no manifest item.
- `relocate` publishes the spine index even for a section cut into chunks: `detail.index = spine ?? index` (`paginator.js:3812`). The fraction is also rescaled to the whole section (`:3805-3811`).
- `linear="no"` counts zero in the fraction (`progress.js:175`) and navigation skips those sections (`paginator.js:4225`, `:4339`). The index still counts.

| Case | Kavita | foliate | Do the indexes match? |
|---|---|---|---|
| Spine with XHTML only | N pages | N sections | Yes |
| `linear="no"` | counts | counts | Yes. The fraction and "next" differ |
| Non-XHTML spine item | 0 pages, useless chapter [I] | opens | Nothing to compare |
| Orphan `idref` | 0 pages | drops it | Same. `resolveHref` uses `spine.findIndex`, so in foliate the index shifts after it |
| Repeated item | table of contents and page fail [I] | opens | Only in a broken EPUB |

Conversion [I, pure function for `core`]:

```
Kavita → Kamayoq:  page == total         → section = total - 1, and mark read with Kavita as the source
                   page <  total         → section = page
Kamayoq → Kavita:  real end of the book  → pageNum = total
                   anything else         → pageNum = section
```

`pages` for a chapter with several files is the sum (`ProcessSeries.cs:893`); the reader uses only the first. If Kavita's `pages` differs from `book.sections.length`, the conversion does not hold and must fall back to "open at the start, send nothing". The contract asks for this as a round-trip test.

### 1.4 Reader, progress

- `GET api/Reader/get-progress` (`ReaderController.cs:603`) returns `{pageNum, bookScrollId, lastModifiedUtc, ...}`; with no record, `pageNum = 0` (`develop`:652-669).
- `POST api/Reader/progress` (`:624`). `SaveReadingProgress` (`ReaderService.cs:216-230`) [V]:
  - `:219` clamps the page to `0..total`. `total` is accepted and is what marks read.
  - `:229` `if (userProgress == null && progressDto.PageNum == 0) return true;` **ignores the `BookScrollId`** and returns success.
  - `:230` same page and same `BookScrollId`: nothing changes.
  - After that it overwrites unconditionally. The `BookScrollId` text is neither validated nor normalized.

**Consequence [I]:** at spine 0 with no record, Kavita returns 200 and does not save. In the contract, the re-read returns `0` and `null`, different from what was sent; the item stays forever in "sent, unconfirmed" or becomes a false conflict. In a single-spine EPUB with no existing record, there is no way to save a partial position. Rule to propose: if `pageNum == 0` and there was no record, treat it as "nothing to send", keeping the position only in the app. Characterization test proposed in section 1.11.

### 1.5 What `bookScrollId` is, in the web reader

Production [V] (`book-reader.component.ts`, `reader.service.ts`, tag `v0.9.1.4`):
- `scroll` with `debounceTime(200)` calls `handleScrollEvent`.
- `getFirstVisibleElementXPath` (`:1829`): takes `div,o,p,ul,li,a,img,h1…h6,span,figure` inside `.book-content`, in the viewport, not children of another candidate, with text or an image; picks the first in layout order and climbs to the common top-level element.
- `getXPathTo` (`reader.service.ts:582-635`): climbs through the parents counting only **siblings with the same tag** (`p[3]`). If it finds an `element.id` on the way, it stops there: `id("x")` or `id("x")/p[3]`. All lowercase.
- `saveProgress` (`:834-842`) applies `descopeBookReaderXpath` (`reader.service.ts:410`) before sending. It strips the prefix up to `.book-content > :first-child`, that is, the `PrepareFinalHtml` `div`, and returns `//body/...`. An `id(...)` path passes through untouched.

Consumption [V]:
- `loadPage(progress.bookScrollId || undefined)` (`:1041`).
- `scrollWithinPage` (`:1514`) calls `scopeBookReaderXpath`: replaces `//body` with the real path of the `div`, with the expression in lowercase. Then `document.evaluate`.
- The `BookReaderBodyScope` constant (`BookService.cs:54`, `//BODY/APP-ROOT[1]/.../DIV[1]`) is the old form and only appears in personal bookmarks (`InjectTextBookmarks`). An old record in the database may still have that form [I].

Ancestors of the content in the reader template have no `id` (`book-reader.component.html:1-45`) [V]; so no `id()` comes out of the interface. But `id()` is evaluated against the whole `document` (`reader.service.ts`, `document.evaluate(path, document, …)`), and `app.component.html:26` has `<a id="content">`. A book with `id="content"` would resolve to the wrong element [I].

Possible forms in the field:

| Form | Who produces it |
|---|---|
| `//body/div[1]/p[12]` | web reader, Kamigura |
| `id("cap3")`, `id("cap3")/p[2]` | web reader, Kamigura, Inkita |
| `//body/div/p[40]`, without an index on each step | KOReader conversion |
| `null` or empty | KOReader conversion, clients without a bookmark |
| `idx:12` | Inkita, its own format |
| `//div[1]/div[1]/...` | Kover, see 1.8 [I] |

The server also reads the field: `GetWordCountBetweenXPaths` does `body.SelectSingleNode(xpath)` on XHTML that went only through HtmlAgilityPack, without Angular (`BookService.cs:1053`, `develop`:1141-1176). This is the most direct proof that the path is relative to the file's `body`, not to the Kavita page [V].

### 1.6 Can a native client produce and consume it?

**Yes, in the common case, with loss.** [I, based on the facts above]

- **Precision:** XPath reaches the element, not the character. A long paragraph goes back to its start. KOReader already loses this: `/text().264` is cut off (`KoreaderHelper.cs:122`).
- **Consume:** in foliate, `goTo({ index, anchor: doc => resolver(doc) })`. A function as anchor is accepted, even in a chunked section: `#toUnitTarget` calls `split.locate(anchor)` (`paginator.js:2046-2050`). The chunk keeps empty markup elements with the same tag, "Element indices are therefore identical to the full section" (`section-chunks.js`, header), so the same path works [V on the code; to measure].
- **Produce:** from `range.startContainer`, climb to a block element, count siblings with the same tag, ignore nodes with `cfi-inert`. Recommendation: emit only plain `//body/...`, which avoids the `id()` collision; KOReader also drops `id()` (`KoreaderHelper.cs:135`, `:183`). Consume all forms.
- **DOM difference to measure:** Kavita goes through HtmlAgilityPack and then `innerHTML`; the foliate fork renders via `srcdoc`, also an HTML parser; `createDocument` uses an XML parser (`foliate-and-webview.md`, line 93). A table gains `tbody` only in the HTML parser. `<a id="x"/>` is empty in XML and opens an element in HTML. Compute the XPath on the rendered document, never on `createDocument`.
- **Evidence from clients:** Kamigura does exactly this, on top of `book-page` (1.8). No client read does it on the raw XHTML **and** foliate. The real test is a round trip with the web reader, in a browser: it cannot be automated without a browser [To measure; see 1.11].

### 1.7 Empty or invalid `bookScrollId`; when it counts as read; KOReader

Empty [V]: `progress.bookScrollId || undefined` opens the spine item at the top (`:1041`). Sending only `pageNum` is safe.

Invalid [V]: `scrollTo` exits without scrolling if the element is null (`:2143`); in production, without warning. `scroll()` then calls `handleScrollEvent`, so the web reader **overwrites** it with whatever is on screen. The XPath exception is swallowed in `getElementFromXPath`. On the server, `GetWordCountBetweenXPaths` has a `try/catch` and returns 0 (`develop`:1187-1191). A wrong string spoils the resume for whoever sent it and nothing else.

Read [V]:
- `saveProgress`: `if (this.pageNum() == this.maxPages() - 1) tempPageNum = this.pageNum() + 1;` (`:836-838`). On any scroll in the last spine item, it sends `total`.
- Single spine: every scroll counts as `pageNum = 1 = total`.
- On reopening with `pageNum >= maxPages` on the first load, it goes back to `maxPages - 1` and calls `saveProgress` again (`:1008`).
- Advancing past the last page goes to the next chapter (`nextPage`, `develop`:1764-1769); there is no other end event.
- The record only stores `PagesRead == Pages`, without telling it apart from `mark-chapter-read`.

KOReader [V] (`KoreaderHelper.cs`, identical in both versions):
- `UpdateProgressDto` (`:85`): from `/body/DocFragment[N]/body/...` it takes `PageNum = N - 1` and `//body/<rest>`, without `/text()` and without `.0`. `a` and `id()` become `null`. `#_doc_fragment_10` and a short path give only the page.
- `GetKoreaderPosition` (`:166`): `/body/DocFragment[page+1]/body/<path>.0`, or `.0` on the fragment alone, without `id()`.
- Tests in `KoreaderHelperTests.cs:9-59`. Example: `/body/DocFragment[8]/body/div/p[28]/text().264` → page 7, `//body/div/p[28]`.
- `KoreaderService.SaveProgress` goes through `SaveReadingProgress`; the same page-0 rule applies.
- `.0` at `pageNum == total` becomes `DocFragment[total+1]`, past the end [I].

This shows two things: KOReader's `DocFragment`, the spine and `pageNum` are the same number; and lossy conversion is accepted practice in Kavita itself.

### 1.8 How other clients handle EPUB and `bookScrollId` [V, 30/10/2026]

Default branch from the API, commit read:

| Client | Commit | Reads it as | `bookScrollId` | End of chapter |
|---|---|---|---|---|
| **Kamigura** (`KamiguraApp/Kamigura`, `main`) | `07a7fa7` | `book-page` in its own WebView | Produces `//body/p[n]` and `id("x")` (`assets/novel-reader.js:102-113`); resolves `id()` via `evaluate`, `//body` by hand, with `text()[n]` (`:114-133`); otherwise `null` | Going past the last spine calls `mark-chapter-read` (`NovelReaderScreen.kt:236-237`). `pageNum` is the last spine reached (`:173`, `:271`) |
| **Kover** (`rodonisi/kover`, `main`) | `64f80cc` | `book-page`, its own reflow | Generates a `scroll-id` attribute on each element (`epub_page_preprocessor.dart:85-96`), algorithm copied from the web (`element.dart`). Fragment wrapped in two `div`s, no `body`: yields `//div[1]/...` [I]. Resumes by exact text equality (`epub_reader.dart:311-316`): a web path does not match, opens without position [I] | `markChapterRead` at `page >= total - 1` (`reader.dart:133`) |
| **Inkita** (`dom-53/Inkita`, `master`) | `67c1f4a` | `book-page` in a WebView | Sends `id("x")` or `idx:N`, an index into `body *` (`ReaderScreen.kt:1202-1203`). Only understands those two (`:1312-1345`); a web XPath does not resolve | Not traced |
| **Lagrange** (`van-geaux/lagrange-reader`, `main`) | `018d457` | File in Readium | Does not send it (`kavitaProgressPayload`, without the field). `pageNum = pageIndex + 1` (`KavitaProgressModule.kt:84`), even for EPUB; not traced whether the index is a spine index | By percentage ≥ 99.5 (`:58`) |
| **kamare.koplugin** (`fpammer/kamare.koplugin`, `main`) | `b85ab0a` | Image reader | Only a comment about the format (`kavitaclient.lua:697`); no `epub` in the code | n/a |

What follows from this [I]:
- Kamigura is the closest precedent and keeps two values apart: `anchor` (XPath, from Kavita) and an exact local `location`. This matches the contract: CFI in the app, section and bookmark in Kavita.
- A polluted field is real: Inkita's `idx:N` and Kover's `//div[1]…` reach whoever reads it. Kamayoq must treat an unknown form as "open at the start of the section", never as an error.
- None uses foliate; none tests a round trip against a real Kavita. Kamigura tests against a mocked server (`androidTest`).
- Only Kamigura and Kover use `mark-chapter-read` for the end. Kamayoq has both routes; the contract calls for its own mark.

### 1.9 How Kavita represents EPUB as chapters [V]

- **One file, one `Chapter`.** `ProcessSeries.UpdateChapters` (`:757-790`) creates or finds one chapter per `ParserInfo`, and each EPUB produces one `ParserInfo`. `Chapter.Pages` = sum over the files.
- **Never splits by the table of contents.** Searching for `GetChapterTitleFromToC` and TOC in the code: only in bookmarks and `ReaderController`, for the title. The table of contents is only navigation inside the chapter.
- **Several table-of-contents items in the same file:** same `page`, different `part`. The progress record is still a single one.
- `BookParser.Parse` with `enableMetadata` (`BookParser.cs:15-19`) uses `ParseInfo` (`BookService.cs:1370`):
  - `calibre:series` + `calibre:series_index`, or `belongs-to-collection` + `group-position`: `Series`, `Volumes = index`, `Chapters = DefaultChapter`, title in `Title`.
  - Without that: `Series = dc:title`, `Volumes = LooseLeafVolume`, `Chapters = DefaultChapter`.
  - The chapter number never comes from the OPF: the default stays. With metadata on, an EPUB's `ChapterNumber` is always the default (`-100000`, `DefaultChapterNumber`).
  - A title like "Overlord Vol. 3" without a series: `Series = Overlord`, volume 3 (`BookParser.cs:53-58`).
- `IsSpecialInfo` = `IsSpecial` or (loose volume and default chapter) (`ParserExtensions.cs:30`). A standalone EPUB comes in as a "special", with `Range` = file name and `Title` = title (`ChapterBuilder.cs:35-47`). Two EPUBs with the same series and volume and no number become separate chapters, by file.
- Library: Book and LightNovel use `ParseMangaVolume`/`ParseMangaChapter` (`Parser.cs:890-912`), identical. Both go through `BookParser` for `.epub` (`ReadingItemService.cs:196`).
- Official wiki, read on 30/10/2026: "EPUB are primarily parsed by metadata within the epub file (opf)", metadata "overrides everything", and "eBooks do not fall back to folders for parsing". https://wiki.kavitareader.com/guides/scanner/epub/

Translated into the use cases [I]:

| File | How it shows up |
|---|---|
| Compiled novel, no series in the OPF | Series = `dc:title`, 1 special chapter, pages = spine |
| Volumes with `calibre:series` and index | One series, volumes N, each with 1 chapter |
| Volumes without metadata, title without "Vol." | One series **per file** |
| `Cap. 0928.epub` with distinct `dc:title`s | One series **per file**, unless there is metadata |
| Library with `enableMetadata: false` | Series and numbers from the file name (`BookParser.cs:20-34`), without falling back to the folder; an empty series rejects the file in 0.9.1.4 (`return string.IsNullOrEmpty(info.Series) ? null : info`) |

`Download/chapter` [V]: a single file in a non-image format returns the original (`DownloadController.cs:207-216`), `application/epub+zip`, original name (`DownloadService.cs:21-44`). A chapter with several files becomes a ZIP. Requires `DownloadPolicy` (`:27`). This answers an open item in the product design: the unit of an EPUB in volumes is the file.

As for rule 8b (`kavita-step-3`, "series, chapter number and total pages"): for EPUB the number is the default; two loose volumes with the same page count would be indistinguishable [I]. A natural tie-breaker is the file name in `files[0]`, which `Series/volumes` provides.

### 1.10 Library with EPUB in CI [V on the code; To measure]

- `Library/create`: `type` 2 (Book) or 4 (LightNovel), `fileGroupTypes: [2]` (`FileTypeGroup.Epub = 2`; today the test sends `[1]`, Archive, `KavitaServer.kt:169`). The extension filter comes from there (`ParseScannedFiles.cs:155`).
- `metadataProvider`: for Book and LightNovel, Hardcover and Mangabaka apply (`KavitaPlusConfiguration.cs:28-42`). 3 (Mangabaka) was measured only for Manga: test it.
- **Use `enableMetadata: true`.** The current setup sends `false` (`KavitaServer.kt:164`); for EPUB that would ignore the OPF.
- Folder: `Series/file.epub`. A third-party guide says a loose file at the root is skipped; not checked in the scanner. Using a subfolder avoids the doubt.
- Format returned in `Files[].format`: `Epub = 3` (`MangaFormat.cs:32`).

Minimal EPUB [V, EPUB 3.3, https://www.w3.org/TR/epub-33/ , read on 30/10/2026]: `mimetype` first and uncompressed ("MUST NOT compress or encrypt", 4.3.3); `META-INF/container.xml`; package with `dc:identifier`, `dc:title`, `dc:language`; exactly one `dcterms:modified` (5.5.5); exactly one `item` with `nav` (5.6.2.1); `spine`. VersOne is more tolerant (`IgnoreMissingToc = true`), but the fixture must be valid: foliate reads the same file. In Java, `mimetype` requires `ZipEntry.STORED` with size and CRC ready. The default value of `linear` was not in the part of the specification read: not available here.

Proposed fixtures [I], generated like today's CBZs:

| Fixture | Proves |
|---|---|
| 3 spine items of different sizes, `nav` with 3 | `pages == 3`, table of contents, `Download` equal to what was generated |
| With `linear="no"` at the start | `pages` counts it; index equal to foliate |
| Single spine with anchors in the `nav` | Same `page`, different `part`; page 0 with no record |
| Two volumes with `calibre:series` | One series, two volumes |
| Two files with distinct `dc:title`s in the same folder | Two series |
| SVG item in the spine | Pins down what Kavita does |

### 1.11 Characterization tests for `kavitaTest` [I]

They go in green (step 3 decision 4): page 0 with `bookScrollId` and no record does not save; page `total - 1` with `bookScrollId` comes back identical, byte for byte; `id("x")` comes back identical; `//body/p[1]` in uppercase is not normalized; `Book/{id}/chapters` gives `page` equal to the generated spine. A round trip with the web reader needs a real browser: it stays out of `kavitaTest`, as a manual script with the local Kavita in podman (step 3 decision 2).

### 1.12 Dom Casmurro as EPUB: where it comes from

Legal basis [V]: Machado de Assis died in 1908 (Gutenberg: 1839-1908). Law 9.610/98, art. 41, Planalto, read on 30/10/2026: "Os direitos patrimoniais do autor perduram por setenta anos contados de 1º de janeiro do ano subseqüente ao de seu falecimento, obedecida a ordem sucessória da lei civil." (The author's economic rights last seventy years counted from 1 January of the year after their death, following the order of succession of civil law.) https://www.planalto.gov.br/ccivil_03/leis/l9610.htm . Calculation: following year 1909; 1909 + 70 = 01/01/1979; reference date 30/10/2026: term expired. This covers the 1899 work, not a modern edition with its own notes or preparation; a 1998 law is not the one in force in 1979 [I].

| Source | State of the file | Usable? |
|---|---|---|
| **Project Gutenberg 55752** https://www.gutenberg.org/ebooks/55752 | Portuguese. "Public domain in the USA.", EPUB3 217 kB. License: do not remove the license from the file (1.E.4), or "strip the Project Gutenberg license and all references to Project Gutenberg" (https://www.gutenberg.org/policy/license.html). Outside the USA, check (https://www.gutenberg.org/policy/permission.html). Site "intended for human users only", with IP blocking for automation (https://www.gutenberg.org/policy/robot_access.html) | Good for manual testing on the test device, downloaded by hand. Not in CI nor in the repository |
| **Standard Ebooks** | CC0 in each file (https://standardebooks.org/about/standard-ebooks-and-the-public-domain). Search "machado de assis": 0 results, 30/10/2026 | Does not exist |
| **Domínio Público (MEC)** | The work's page returned 403 when read; terms of use: not available | Not recommended until the terms are read |
| **EPUB generated in the build, from plain text** | No third-party file; the text comes from a small excerpt stored in the repository | **Recommended for CI** |

No EPUB was downloaded, so the real structure (how many spine items, table of contents) of 55752 is not available. The design's sample data says 148 chapters.

**Recommendation [I]:** CI only with generated files, following `kavita-step-3-design.md:351` ("No third parties"). Text: mostly fictional; a Dom Casmurro excerpt only if real accents are needed, transcribed from a public-domain edition and without any reference to Gutenberg. On the test device: the 55752 EPUB downloaded by hand, outside the repository. This also meets the project rule for reading text: "public domain (Dom Casmurro) or fictional".

## Part 2. The fit

### (1) What step 4 is, according to the records

| Source | Text |
|---|---|
| Product design, skeleton | Pick a folder on the device → list of works → open a CBZ and an EPUB → close and come back to the same place |
| Product design, broad scope for text | "Text path with the fork actually chosen: local and Kavita EPUB, Pages/Scroll, stable passage and general/per-work settings. The EPUB position-matching test with the server must happen early, before promising progress equivalence; the typographic criteria remain acceptance criteria." |
| Product design, skeleton | Text with the chosen fork → expand up to phase 1 |
| Product design, skeleton | Before starting: exact commit of the foliate-js fork and protection of the bridge |
| `stack-2026-10-23.md:8` | "Before the first reader: blocking of the book's scripts (CSP) and minimal-reach bridge, tested with a deliberately malicious EPUB" |
| `progress-contract:181` | "close the app mid-chapter, kill the process, open and come back to the same place; the same after updating the app; two partial chapters preserved after killing the process" |
| `progress-contract:183` | "open Dom Casmurro, read to a point, close, reopen: it comes back to the same passage. When there is Kavita: round trip of the text position, measuring where it lands." |
| Product design | The emulator in CI comes in at the EPUB step |
| Product design | Visual foundation before the first final screen, after EPUB |
| Product design | TalkBack testing on the device waits until the app is ready |
| `kavita-step-3`, decision 5 | "Provisional notice: removed in step 4" |

Phase 1 of the reader, as designed, is much larger than the minimum: two modes, tap zones, full typography, five themes, footer, counting by type, table of contents, automatic advance, *Só esta obra*/*Todas* (This work only/All) scope.

**In, no doubt:** `third_party/foliate-js` with a recorded commit; CSP and bridge with a malicious test; WebView; open a local EPUB in `Books/`; position (section, CFI, fraction in basis points) saved and reopened, including killing the process; remove the "opens in step 4" notice; emulator in CI; texts in English and pt-BR.

**Out, no doubt:** visual foundation (after EPUB); TalkBack test on the device; Voice, search in text, bookmark, highlights (phases 2 and 3); MOBI, AZW3, FB2, PDF; tablet; Knuth-Plass comparison.

**Ambiguous, to be decided:**

| Item | Minimum scope | Broad scope |
|---|---|---|
| Kavita EPUB | does not say | in |
| Table of contents becomes chapters (novel) | does not say | does not say; contract tests "3 chapters" |
| Scroll and Pages | one is enough | both |
| General and per-work settings | out | in |
| Typographic criteria | out | "acceptance" |
| Minimal formatting panel | out | partial |
| Table of contents inside the reader | out | does not say |
| End of chapter in a novel | out | does not say |

The step 2 precedent cut the image reader down to "one mode only", with settings out. That favors a similar cut [I].

### (2) Rules step 4 must honor

From the stack and the project rules:
- "foliate-js, own fork in `third_party/foliate-js` from the Readest fork (MIT), origin commit and each change recorded" (`stack:8`).
- Kotlin core without Android classes; SAF only; "no proprietary dependency" (`stack:12`).
- Every implemented rule becomes a readable automated test in Portuguese; test before code; every bug becomes a regression test.
- Integration tests against the real service; "The app never changes the work's files" (`stack:11`).
- Real buttons and links, labels on icons, 44 px targets, measured contrast; Android Back closes the sheet and preserves state.
- Risky migration: short branch in a worktree (step 3 decision 10).

From the contract:
- Position "text: index of the EPUB section in reading order (integer) and foliate-js's fine locator (the CFI...)" (line 17).
- "**integer from 0 to 10 000**"; "converted **on input**"; "The end of a chapter is not deduced from the fraction: 'read' is its own mark" (line 18).
- "The position does not mark or unmark chapters by itself" (line 33).
- "The contract stores the CFI here and sends Kavita the section and bookmark" (line 108).
- "no app text promises 'page by page'" (line 108).
- Book threshold: 200 basis points, strict (line 85, tests line 133).

From the folder convention:
- E1: "the marks carry over on their own **only if the table-of-contents titles match in the same order**"; "When in doubt, never transfer or delete."
- E2: "only the items at the last level are chapters; the levels above are volumes. Items pointing to the same passage become one chapter. A novel with no table of contents (or with an empty or invalid one) is a single chapter, the whole file, and the Work warns".
- Rule 3: "A book shows the table of contents inside the reader".

From the product (reader design rules):
- Default mode by type (book Pages, novel Scroll); can be changed per work.
- Justify always turns on hyphenation, with no separate button. This replaces the earlier design, which had a separate Hyphenate button.
- Defaults: 18 px, line height 1.5, margins 24, indent 1.5 em, left-aligned, no hyphenation; default typeface to be decided.
- Theme on the page only.
- Counting by type, not by mode.
- Advance to the next chapter automatically on reaching the end (phase 1).
- A paragraph is never left without markup.
- The implementation checks the measures: 35 to 50 characters per line on a phone; type of 16 px or more; line height of 1.4 to 1.6; hyphen with two letters before and three after. The person's setting beats the book's CSS only on the chosen property. Position by passage, not by page number.
- Typographic acceptance criteria, among them: changing font, size or orientation preserves position, selection and access to content.
- The criterion "justified only with pt-BR hyphenation active on Android **and** Linux" lost the Linux part when the stack dropped desktop (`stack-2026-10-23.md`) [I].
- Reference typographic measurement: Noto Serif gives 47, 41 and 37 characters per line at 16, 18 and 20 px, at a width of 328 px; criterion 35-50.

Sample-data discrepancy: the design's sample data gives 148 as the total number of **chapters** of Dom Casmurro and, elsewhere, "148 pages at the default size". The contract's case 2 treats the book as one file (contract line 74).

### (3) A compiled EPUB becomes chapters

**What the contract defines.** Section 3: "one table-of-contents item of a file (compiled novel EPUB: each item at the last level of the table of contents becomes a chapter). Each chapter has **its own identity, which does not change**; the file (with the revision of its full content) and the position in the table of contents are only where it is now."

**Today's schema** [V] (`Chapter.sq`):

```
path TEXT NOT NULL,                  -- :5
format TEXT NOT NULL,                -- :11, comment "'ARCHIVE' or 'IMAGE_FOLDER'.", no CHECK
stamp TEXT NOT NULL,                 -- :16
content_hash TEXT,                   -- :18
replaced_by TEXT REFERENCES chapter(id) DEFERRABLE INITIALLY DEFERRED,  -- :24
UNIQUE (version_id, id)              -- :26
CREATE UNIQUE INDEX chapter_current_path ON chapter (version_id, path) WHERE replaced_by IS NULL;  -- :30
```

`chapter.id` is a generated UUID: the identity is already there. The obstacle is the unique index `chapter_current_path`: several chapters in the same file have the same `path`. `format` has no CHECK in SQL, but `ChapterFormat.valueOf(format)` throws for an unknown value (`LibraryStore.kt:369`, `:336`, and `Backup.kt:183`).

Options for storing it [I]:

| Option | How | For | Against |
|---|---|---|---|
| A. `toc_key` column | `ADD COLUMN toc_key TEXT NOT NULL DEFAULT ''`; index becomes `(version_id, path, toc_key)` | `path` stays the file; `FolderWalk.find`, `PathMatch` and editions keep working | The migration recreates the index |
| B. Composite `path` | `file.epub#item` | No migration | `#` is valid in a file name; `FolderWalk.find(library, chapter.path)` and `movePath` break; `ChapterOpening` and `ReaderHost` compare `path` |
| C. Separate `toc_chapter` table | Child of the file | Keeps it apart | Duplicates the whole progress, resume and backup path, which point to `chapter` |

Recommendation: A. Likely new columns: `toc_key`, `toc_href` (current target, with fragment), `volume_label` or `volume_key` (E2; today there is only `kind`, with no level above). `name` holds the item's title; `sort_index` the order. **`toc_key` is not identity:** it is "where it is now", exactly like `path`; it can be the item's index in the flattened list.

**When to read the table of contents.** Options [I]:

| Option | Cost | Testable on the JVM | Correct list before opening |
|---|---|---|---|
| 1. At import, in Kotlin, only when the fingerprint changes | Copy or read each new or changed EPUB | Yes, in `core` | Yes |
| 2. On first opening the Work, in Kotlin | One work at a time | Yes | Only after opening |
| 3. In the WebView, through foliate | Needs to load the book | No | Only after opening |

Recommendation: 1, only for `Novels/`. The rule requires chapters in the Work and the test "given a compiled novel EPUB with a 3-item table of contents, then there are 3 chapters" on the JVM (contract line 164). A book does not need it: "A book shows the table of contents inside the reader". The parser in `core` reads `container.xml`, OPF, `nav` and, failing that, NCX. `javax.xml.parsers` exists on the JVM and on Android, with no new dependency, with `DOCTYPE` turned off since it is a hostile file. The implementation has to map `href` to a spine index, like foliate's `book.resolveHref`, and merge items with the same target (E2).

Divergence to avoid: if the Kotlin parser and foliate flatten the table of contents differently, chapter and `tocItem` do not match. foliate prefers `nav` and falls back to NCX if the `nav` has no navigable link (`epub.js:1358-1383`). Copy that order and test with a fixture of both [I].

**Cost of the central directory through SAF.** The end of the ZIP needs random access [V]: `ArchivePages.kt:27-31` documents that the index at the end opens Mihon's downloads. `ArchivePages` works on `java.util.zip.ZipFile(File)`, which is why `ArchiveOpening` copies the whole file to the cache. Alternatives [I]:
- Whole copy: simple, already exists, cost proportional to the file. Time on the test device per MB: not available.
- Sequential `ZipInputStream`: no copy needed, reads until it finds it; fails on a ZIP whose sizes come only after the data, the reason for the comment above.
- `ParcelFileDescriptor` with a seekable channel: reads only the end, but depends on the SAF provider and needs its own ZIP reader or a new dependency.

A known open item with CBZ also applies to EPUB: Android 14+ rejects certain entry names, and the message becomes "Not a ZIP archive". `ChosenFolder` and `FolderWalk` already provide size and date without opening the file. The fingerprint stays cheap; the cost is only for a new or changed EPUB.

**Boundary rule still unanswered.** `Cap. 0928.epub` and `Release That Witch.epub` are both in `Novels/`. The convention says the first is chapter 928 (test, `folder-convention:90`) and that the compiled one is split. A `Cap. 0928.epub` with a two-part table of contents would become two chapters under E2 [I]. Proposal: a name with a chapter number = a single chapter; `Vol.` or a name without a number = split. See question 3.

**E1 with several chapters in the file.** Today `confirmContent` acts on a single chapter, on opening (`LibraryStore.kt:233-250`). With a compiled file, replacing the file has to act on all its chapters, in one transaction: same titles in the same order → all keep id and progress and get the new hash; different titles → all become a previous edition and new ones are created, with no transfer. None of this exists. `content_hash` and `stamp` repeat on every row of the file. `insertEdition` copies one row at a time. The SHA-256 comes out on opening, but the titles need to be read again.

**Progress with several chapters in one file and one spine** [I]:
- Chapter fraction: foliate gives the fraction of the book and of the section, not of the table-of-contents item. A single spine with 1430 chapters: it must be computed between the anchors of the item and the next one. Contract case 1: "(section, CFI, 5 000)".
- `ReaderHost` reloads when `chapterId` changes (`remember(chapterId)`, `ReaderHost.kt:38`). In a compiled file, reading crosses chapters without reloading: `relocate` brings `tocItem`, and saving needs to switch `chapterId` from inside. `work_resume` goes along with it (`LibraryStore.kt:223`).
- `tocItem` is null before the first entry, and a section without an entry inherits the previous one (`foliate-and-webview.md`, line 99).
- **"Read" in a continuous flow:** the Strip only marks at the end block (section 9). In a scrolled novel within one file, there is no block between chapters. The contract does not say what marks 928 as read when reading moves on to 929. See question 4.
- Read counter: "adds 1 only when reading reaches the end of the chapter on this device" (line 31).

**Clash with Kavita, where the whole EPUB is one record.**

What the contract says: section 3, "Kavita: chapter id"; section 2d, copy of the agreement "per chapter"; "the content of a Kavita record is the page (for text, the section index) and the `BookScrollId`"; and the case 1 test deals with 931 Kavita chapters, each with pages.

What it leaves open [I]:
1. Whether a compiled EPUB coming from Kavita shows table-of-contents chapters ("A novel always shows chapters") or a single chapter ("Kavita: chapter id").
2. If it shows them: the agreement is per Kavita chapter, but local marks are per table-of-contents item. Marking chapter 3 of 1430 as read has no equivalent there; only the position travels. Does marking read in Kavita mark all of them?
3. Several items in the same spine: the page does not tell chapters apart; the bookmark does, if it resolves.
4. Light novel EPUB volume: same thing, per volume.
5. `Series` and `Volume` group differently: the "grouped by volume" rule has to come from Kavita or from the table of contents.

Recommendation for phase 1 [I]: a file downloaded from Kavita shows chapters per table-of-contents item only after it is downloaded; `Book/{id}/chapters` allows listing them without downloading. One Kavita record ↔ the position of the current item; the read mark per item stays local, and the Kavita mark is only sent when the last item is read. This remains an open decision.

### (4) What changes, and where step 3 collides

**`ReaderHost` and opening.** [V]
- `ReaderHost(tree: Uri, ...)` (`ReaderHost.kt:31`) and `LibraryApp` only shows it with `currentTree != null`.
- `Opening.Ready` carries `OpenedChapter`, with `pages: List<PageImage>` (`ChapterOpening.kt:28-33`): images only. The `is Opening.Ready` branch calls `StripReader` directly (`ReaderHost.kt:65`).
- `ChapterOpening.open` does `when (chapter.format)` with two cases (`:57-60`).
- `ArchivePages.open` throws when there are no images (`ArchivePages.kt:55`); the copy is named `chapter-*.cbz` (`ArchiveOpening.kt:128`).
- `FolderConvention` rejects `.epub` (`:95`, `:130`) and `typeByFormat` is always `COMICS` (`:23`).
- `UnitContent` has only `Archive` and `ImageFolder`.

**Step 3 piece 10** changes the same place: "`ReaderHost` no longer requires the SAF folder tree", with `Download/chapter` to the cache and opening through `ArchiveOpening`.

| File | Step 3 | Step 4 | Risk |
|---|---|---|---|
| `Chapter.sq` | `remote_id`, format `KAVITA_ARCHIVE` | `toc_key`, index, text formats | High |
| `ChapterProgress.sq` | not planned | text position | Medium |
| `.sqm` migration | `1.sqm` | `2.sqm` | High, ordering |
| `LocalLibrary.kt` (`ChapterFormat`, `UnitContent`) | yes | yes | Medium |
| `ChapterProgress.kt` | not planned | position type | Medium |
| `LibraryStore.kt` | `importKavita`, missing items | import table of contents, E1 per file, position | High |
| `ArchiveOpening.kt` | reuses it, download cache | EPUB variant | Medium |
| `ChapterOpening.kt` | Kavita branch | EPUB branch | High |
| `ReaderHost.kt` | removes `tree` | chooses the reader | High |
| `LibraryApp.kt` | Library without a folder, "step 4" notice | removes the notice | Medium |
| `Backup.kt` | formats, Kavita origin | position, format 2 | High |
| `FolderConvention.kt` | no | yes | Low |
| `AndroidManifest.xml` | `INTERNET` | activity or nothing | Low. `setBlockNetworkLoads` in `foliate-and-webview.md` |
| `ci.yml` | `kavita` job | emulator; `/books` folder | Medium |
| `KavitaFixtures.kt`, `KavitaServer.kt` | yes, in progress | EPUB | Medium |

Suggested shape [I]: introduce a sealed type for an opened chapter (`Images`, `Text`) and have `ReaderHost` decide by type. Let step 3 do the source switch (`ChapterSource`, folder or cache) and step 4 only add the reader. **Request to step 3:** reserve the format name by content, so that `KAVITA_ARCHIVE` does not later require `KAVITA_EPUB` without a rule; and decide what piece 9 stores for EPUB (decision 5), since the chapter row will need the format, the number and the file name.

**`chapter_progress`.** Image-only columns [V] (`ChapterProgress.sq:9-23`):

```
position_page INTEGER, position_total INTEGER, position_at INTEGER, position_device TEXT,
CHECK ((... all null) OR (... all filled)),
-- Page equal to the total is the end-of-chapter block (contract, section 9).
CHECK (position_total >= 1 AND position_page BETWEEN 0 AND position_total)
```

`save` is `INSERT OR REPLACE`. `StripPosition` has `page`, `totalPages`, `at`, `device`; `BasisPoints` exists and **nobody uses it** outside its own file (search in `core`, `data`, `app`).

Contract: "text: index of the section... and the CFI" and a fraction in every position, "**when**... and **on which device**". The convention "page == total is the end block" belongs to the Strip; for text, "read" is its own mark and the section runs from 0 to `total - 1` [I].

| Option | How | Note |
|---|---|---|
| A. Reuse | `position_page` = section, `position_total` = sections, plus `position_cfi` and `position_fraction` via `ADD COLUMN` | No table rebuild. SQLite accepts `ADD COLUMN` with a column CHECK (checked on the local `sqlite3` 3.54.0; Android's SQLite version is different). Mixes meanings in columns named `page` |
| B. Child table `chapter_text_position` | `chapter_id` references `chapter(id)`, not `chapter_progress`: `INSERT OR REPLACE` would delete the parent row | Clean; two reads per position |
| C. Rebuild `chapter_progress` | Copy into a new table | The only way to change the table CHECKs. The first migration is already risky; not recommended |

Recommendation: A with a sealed type in Kotlin (`StripPosition`, `TextPosition`), with `kind` implied by the chapter format, if generic columns are acceptable. If not, B.

`StripReading` does not work for text: an analogous rule (`TextReading`) in `core`: opens where it stopped; if the sections changed, opens from the start with the edition following E1; the fraction goes through `BasisPoints.fromFraction`; `null` is rejected. The end of a book needs its own event, not deduced from the fraction (contract line 18). A book in Pages: foliate has no end block; the app has to decide what "reaching the end" is.

**`work_resume`** [V]: `(version_id, chapter_id)`, saved in `saveProgress`. Its shape does not change. Its frequency changes in a compiled file: it switches with each item crossed.

**Backup** [V]: `BackupFormat.VERSION = 1`, `read` rejects a different version (`Backup.kt:68`, `:84`). `PositionEntry(page, totalPages, at, device)` (`:219`). `ChapterFormat.valueOf` and `kind` throw if unknown. A text position needs format 2 and reading of 1. Restore does not exist yet. `BackupChapter` needs `tocKey` and whatever else the schema gains. If step 3 bumps the format, coordinate so there are not two 2s.

**Migration order** [I]:
- Version 2 is step 3's, in a worktree (decision 10); it includes `kavita_server`, `remote_id`, `progress_agreement`, `progress_outbox`, `alternative_position`.
- Version 3 is step 4's, after 2 is merged. Doing the reverse forces step 3 to rebase the first migration, which is the riskiest.
- SQLDelight's automatic migration verification comes in with version 2. Step 4 inherits it.
- **`progress_agreement`** needs to store `page`, `book_scroll_id` and the server date, and the contract asks for "the local CFI that corresponded to that position". Asking piece 8 for a nullable `local_cfi` column right now costs one line and avoids another migration on the same file.
- If step 4 starts before step 3 ends, there will be parallel work on the same code, which calls for a short branch. The files in the table above are almost all the core ones. Recommendation: sequential.

**Surviving recreation** [V]: `LibraryApp` keeps `openWorkId` and `readingChapterId` in `rememberSaveable` (`:82-84`); `ReaderHost` reopens and `StripReader` uses the saved position. A WebView loses state on recreation; rotation and theme require saving the position **before** going down and reopening by CFI [I]. `android-tests-for-recreation.md` says Robolectric does not run WebView; so a recreation test for the reader is instrumented. `StripReader` saves on every change; for text, `relocate` fires much more often: it needs an interval, with a guaranteed save in `ON_STOP`. `ProgressSaving` already handles failure [I].

### (5) Reading preferences

**What exists** [V]: none. Only `SharedPreferences` for the folder (`ChosenFolder.kt:24`) and the device identity (`DeviceId.kt:12`). No DataStore. `allowBackup="false"`. No settings table. The product rule says the backup stores preferences, and the backup does not have them yet.

What the product asks for: a general default for "all text works", per-work settings, Mode always per work, page theme, footer, typeface, and two restore commands. The key is the **work**, not the version [I; the design does not distinguish].

| Option | Good for | Against |
|---|---|---|
| SQLDelight table `reader_settings(scope, key, value)` or columns | Goes into the backup, testable on the JVM, per-work scope comes naturally | One more migration |
| `SharedPreferences` only for the general ones | No migration | Outside the backup; two places; harder to test |
| DataStore | Modern | New dependency |

Recommendation: a table, in the same migration 3, **with a single settings row in the minimum scope**: Mode per work, if Scroll and Pages are both in. The skeleton's real minimum: no saved preferences; fixed CSS with the product defaults (18 px, 1.5, margins 24, indent 1.5 em) applied through `renderer.setStyles`, since `setStyles` exists (`foliate-and-webview.md`, line 116). The default typeface is to be decided; embedded fonts have licenses to check, and that point was not looked at.

Reminder of the measures rule: "the person's setting beats the book's CSS only on the chosen property". With no setting, the book's CSS stays. This weighs on the design of the injected CSS, before there is a panel.

### (6) Open questions

Format: options, recommendation marked.

1. **Size of step 4.** (a) Skeleton minimum: local EPUB in `Books/`, one mode, return to the same place, no Kavita [recommended: it is what the skeleton promised, and each extra item depends on a migration]. (b) Plus chapters from the table of contents in `Novels/`. (c) Broad scope: Kavita, two modes, general and per-work settings.
2. **Kavita EPUB in step 4?** (a) Download and open, no position; (b) with a round trip of section and bookmark [recommended, in separate pieces: the EPUB position-matching test with the server must happen early, and step 3 will have pieces 11-14 done]; (c) after the local text phase.
3. **`Novels/`: which files are split by the table of contents?** (a) A name with a chapter number = one chapter; `Vol.` or no number = split [recommended]; (b) every EPUB in `Novels/` is split; (c) the Work asks. Needs testing with real files, not yet available.
4. **What marks a chapter as read in a continuous novel?** (a) Passing the start of the next chapter [recommended: it is the closest equivalent to the end block without interrupting reading]; (b) an end block in each chapter, like the Strip, which stops the flow; (c) only at the end of the file. Worth testing on the device. In Kavita, mark only on the last one.
5. **Compiled novel coming from Kavita.** (a) Shows table-of-contents chapters, with one record in Kavita and local marks per item [recommended: "A novel always shows chapters"]; (b) a single chapter, like Kavita. The contract changes either way.
6. **Position at spine 0 with no record in Kavita.** Server limit: it does not save. (a) Stays only in the app, without a notice, and the next section saves [recommended]; (b) send `pageNum = 1` to force the record, which lies; (c) a proposal to Kavita, which goes under 6b of the contract, under the principle of serving all clients. A and C together are possible.
7. **Read coming from Kavita for EPUB.** The web marks it on scrolling in the last spine. (a) Accept it as read, and reopen at the bookmark [recommended: follow the contract]; (b) ignore it if there is a bookmark.
8. **Send the last spine like the web does?** (a) Only `total` on really finishing [recommended]; (b) same as the web, marking read on getting there. (a) leaves Kavita with the chapter unread for longer.
9. **False conflict from opening in the web.** A change only to `bookScrollId`, in the same section. (a) Keep the current rule and ask; (b) same section counts as a one-sided change and does not ask [recommended: opening the web reader is not reading]. Changes the test at line 170 of the contract.
10. **Dom Casmurro for manual testing.** Use the Gutenberg 55752 EPUB, downloaded by hand, outside the repository.
11. **Settings table now or later?** (a) Minimum: fixed defaults, no saved preference [recommended for size (a) in question 1]; (b) table already with Mode per work.
12. **Visual foundation:** the product design puts the foundation after EPUB. With a new reader and no palette, the text screen is born provisional. Keep the order?

## Not verified

- Everything that depends on the real Kavita: page 0 with a bookmark, `linear="no"`, SVG in the spine, `metadataProvider` on Book and LightNovel, a loose EPUB at the root.
- XPath round trip with foliate and the web reader; size of the loss.
- foliate's DOM in `srcdoc` against Kavita's DOM.
- Time of `ZipFile` with a copy through SAF on the test device; memory with a large EPUB.
- Real structure of Gutenberg 55752 (not downloaded).
- Domínio Público terms (403).
- Default value of `linear` in the specification (outside the part read).
- Inkita at end of chapter; Lagrange, the unit of `pageIndex` for EPUB.
- Step 3 piece 5, uncommitted at the time.
- Licenses of the reading fonts.
