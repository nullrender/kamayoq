# Skeleton step 3 (Kavita): design options

Research from 29/10/2026: three groundwork surveys (the Kavita API read from its source code, other clients, how it fits into Kamayoq) and three alternative plans (minimal path, risk first, test environment first), compared with each other. Research and design only; no code written. The decisions made from here are in [`kavita-step-3-2026-10-30.md`](../decisions/kavita-step-3-2026-10-30.md).

## 1. Summary

The recommendation starts from plan 3, which builds the test environment first and scored best in the comparison. It takes three grafts from plan 2. The first measures whether the number Kavita gives each chapter stays the same after a new scan, which is the biggest risk of losing progress. The second gives the server an identity created by the app, so that changing the address does not duplicate the works. The third is an upload queue with one row per chapter. From plan 1 comes the idea of putting a working connection screen on the test device early.

The work splits into two blocks. Block A has four pieces and touches only the CI (the automatic check GitHub runs on every push) and a new test suite. It starts a real Kavita in a container, that is, an isolated program that is created and destroyed on every run. Then it creates the account and the key without the web interface, generates test comics and pins down the server's real behavior in tests. Block A can start in parallel with the closing of step 2. Block B has ten pieces: the client, the database migration, the screens and the progress sync. It waits for step 2 to close, so that there are not two lines of work writing to the same code.

On the test device, at the end of step 3, a person connects with an address and a key and marks libraries. They also open a downloaded CBZ chapter in the Strip (Tira), and the position goes back and forth with the Kavita web reader.

There are four decisions to make before starting. The first is whether block A starts now. The second is where the Kavita that the test device will reach runs, because the development machine has neither docker nor podman. The third is what happens when both sides changed the position. The fourth is whether characterization tests without a red commit are accepted. The other questions are in section 7.

## 2. What Kavita offers

Single source for this section: the `Kareadita/Kavita` repository, read on 29/10/2026. The default branch is `develop`, at commit `3c9ddb4a6db7c918a6dba2d5d234a61dd2ed848c` (28/10/2026). The `openapi.json` at that commit says 0.9.1.16, which is a nightly (test) version. The latest stable is `v0.9.1.4`, commit `879b8b7`, from 02/09/2026. The `file:line` citations are from `3c9ddb4`. The main controllers were compared with `v0.9.1.4`: the routes are the same, except for two folder-watch routes that exist only in develop. The progress and scan logic is the same in what matters here.

An endpoint is an API address the app calls, such as `GET /api/Health`.

### Authentication

- The API key (the "authentication key" in the project's vocabulary) can go in the `apiKey` URL parameter, in the `x-api-key` header, or in the URL path (`Kavita.Server/Middleware/AuthKeyAuthenticationHandler.cs:113-134`; header name in `Kavita.Common/Constants/Headers.cs:14`).
- When there is a key, it takes priority over the JWT (`Kavita.Server/Extensions/IdentityServiceExtensions.cs:83-90`). A JWT is a signed session token that expires. In practice, the app can send only the key on every call.
- The key has 8 to 32 characters (`Kavita.Common/Helpers/AuthKeyHelper.cs:10-33`). The user found through the key is cached on the server for 15 minutes (`AuthKeyAuthenticationHandler.cs:38-42`). An expired key is refused (`Kavita.Database/Repositories/UserRepository.cs:146,899,914`). `GET /api/Plugin/authkey-expires` says when it expires (`PluginController.cs:88-99`).
- The JWT is valid for 3 days (`Kavita.Services/TokenService.cs:52-56`) and the refresh token for 30 (`RefreshTokenProvider.cs:20`). The recommended plan does not use JWT in the app.
- `POST /api/Plugin/authenticate` carries in its own code the warning that it "may require more information in later releases" (`PluginController.cs:28`).
- Every new account is created with two non-expiring keys, `opds` and `image-only` (`Kavita.Models/Defaults.cs:426-447`). No usage restriction was found for `image-only`. This comes from searching the code and is an inference.

### Libraries, series and chapters

- `GET /api/Library/libraries` lists the libraries (`LibraryController.cs:215`). The type comes as a number: Manga=0, Comic=1, Book=2, Image=3, LightNovel=4, ComicVine=5 (`Entities/Enums/LibraryType.cs`).
- The series of a library come from `POST /api/Series/v2?PageNumber=&PageSize=`, with the filter `{"statements":[{"field":19,"comparison":0,"value":"<id>"}],"combination":1}` (`SeriesController.cs:64-74`; field 19 = Libraries in `SeriesFilterField.cs:29`). The value 1 for `combination` was not verified: it came from the enum name.
- The first page is 1. The total number of pages comes in the `Pagination` header (`HttpExtensions.cs:22-28`).
- `GET /api/Series/volumes?seriesId=` returns volumes and chapters with progress, without pagination (`SeriesController.cs:132-137`). A chapter has `id`, `number`, `sortOrder`, `pages`, `pagesRead` and `files` (`ChapterDto.cs:22-74`).
- Route to avoid: `GET /api/Series/series-detail`, which carries the warning "Do not rely on this API externally" (`SeriesController.cs:527`).

### Pages and files

- `GET /api/Reader/image?chapterId=&page=&apiKey=` serves a page (`ReaderController.cs:89-109`). Counting starts at 0. A page past the end returns the last one, not an error (`CacheService.cs:361-379`). The order is natural by file name, so "2" comes before "10" (`CacheService.cs:363-366`).
- `GET /api/Download/chapter?chapterId=` delivers the original file when the chapter is a single file, such as a CBZ (`DownloadController.cs:183-227`). It requires the Download or Admin role (line 28). `GET /api/Download/chapter-size` gives the size before downloading (line 72).
- EPUB, for step 4: `Download/chapter` also delivers the `.epub` untouched.

### Progress

- Read: `GET /api/Reader/get-progress?chapterId=` (`ReaderController.cs:652-668`). Write: `POST /api/Reader/progress` (lines 676-688).
- The server does not reject a stale write. In `ReaderService.cs:207-295` all of this can be seen:
  - the `lastModifiedUtc` sent is never read;
  - the page is capped at the total;
  - the record is overwritten unconditionally, and the last write wins (lines 248-255);
  - page 0 with no previous record does not create a record (line 229);
  - a race between writes is returned as success (lines 286-289);
  - the record's date is set by the server (`DataContext.cs:711,725`).
- The web reader's page rule is the 0-based index. On the last page, when moving forward, it sends the total number of pages, and that marks the chapter as read (`UI/Web/src/app/manga-reader/_components/manga-reader/manga-reader.component.ts:1713-1735`, checked at `v0.9.1.4`).
- Other routes that exist: `mark-chapter-read`, `continue-point`, the Want to Read list (`WantToReadController.cs`) and the "on deck" list at `POST /api/Series/on-deck` (`SeriesController.cs:359-371`).

### Version and stability

- `GET /api/Health` is anonymous and returns "Ok" (`HealthController.cs:7-19`). `GET /api/Plugin/version?apiKey=` gives the version (`PluginController.cs:73-81`).
- There is no API versioning policy: the routes have no `/v1`, and `v2` routes live alongside the old ones.

### Headless Kavita, for the CI

- The container image is `jvmilazz0/kavita`. On Docker Hub, on 29/10/2026, `latest` and `0.9.1` had the same digest `sha256:31181a32f0dd…`, from 02/09/2026, for amd64, arm64 and arm. The digest is the exact fingerprint of the image. That it is 0.9.1.4 is an inference from the date. The `ghcr.io` tags were not checked.
- The port is 5000 (`Dockerfile:36`). Configuration and database live in `/kavita/config` (`DirectoryService.cs:52-58`). The key that signs the JWT is created on the first run (`Program.cs:196-203`).
- Sequence without the web interface:
  1. `GET /api/Admin/exists` answers `false` (`AdminController.cs:19-22`).
  2. `POST /api/Account/register` creates the first administrator and returns the token and the keys (`AccountController.cs:169-209`).
  3. Optional: `POST /api/Account/create-auth-key` creates another key (`AccountController.cs:1260-1356`).
  4. `POST /api/Library/create` creates the library (`LibraryController.cs:59-147`). The `metadataProvider` must be valid for the type: for Manga only 3; for Comic, 4 or 2 (`KavitaPlusConfiguration.cs:15-45`). Otherwise the server refuses with `invalid-metadata-provider` (`LibraryController.cs:769-775`). Creation already queues a scan.
- `POST /api/Library/scan` is not called right after creating: with the scanner busy, the new scan is postponed by 3 hours (`TaskScheduler.cs:102, 593-617`). The end of the scan is detected by the change in `lastScanned` (`ScannerService.cs:668`).
- License: GPL-3.0, compatible with Kamayoq's GPL-3.0-or-later.

### Not verified (these are the first things to test against the container)

- Whether `apiKey` in the URL is required for `Reader/image`.
- Whether logging in with only `apiKey` requires empty `username` and `password`.
- Whether `Cache-Control` comes out as `max-age=0`. The code uses `TimeSpan.FromHours(1).Seconds`, which by .NET semantics is 0.
- Whether the `image-only` key has a restriction.
- The value of `combination`.
- The scanner's folder and file naming rules (`Parser.cs`).
- How long a small scan takes.
- Whether `metadataProvider` 3, 4 or 2 depends on Kavita+ (the paid service) or on the network.

## 3. How other clients do it

Code read on 29/10/2026. The default branch of each was checked through the GitHub API.

| Client | Commit | Authentication | Reading | Progress | Tests |
|---|---|---|---|---|---|
| Mihon extension (`Kareadita/tach-extension`) | b0f4174 | Exchanges the key for a JWT and never renews it (`Kavita.kt:2616-2664`, `:142`) | Page by page | Does not sync (issue #38); Mihon's tracker marks only read chapters (`mihon .../KavitaApi.kt:126-188`) | None |
| Kover (`rodonisi/kover`, Flutter) | 64f80cc | `x-api-key` on every call (`client.dart:50-61`) | Page by page; offline stores each page in the database | Local "dirty" flag plus `lastModified`; local wins if newer by the clock (`reader_dao.dart:248-279`) | Unit tests with mocks |
| Inkita (`dom-53/Inkita`, Kotlin) | 67c1f4a | `x-api-key` in an interceptor (`KavitaApiFactory.kt:50-63`) | Online page by page; offline from the downloaded CBZ | The later date wins; a 401 probably passes as success (`KavitaApi.kt:311-314`, inference) | Only the 2+2 example |
| Lagrange (`van-geaux/lagrange-reader`) | 5e7fc1c | Username and password, no renewal (`KavitaAuth.kt`) | Downloads the whole comic | Always sends index+1 (`KavitaProgressModule.kt:49-50`): probable off-by-one page relative to the web reader (inference) | Hand-written JSON |
| Kamigura (`KamiguraApp/Kamigura`) | 07a7fa7 | Renews the JWT on a 401, up to 3 attempts (`KavitaClient.kt:53-88`) | — | Local queue pushed without comparing (`OfflineIssueRepository.kt:388-428`) | Mocked server |
| kamare.koplugin (KOReader) | b85ab0a | JWT or `x-api-key` | Page by page | Sends on every page, no queue | None |

Verified in the code: none of the seven tests against a real Kavita. How Kamigura reads was not recorded.

What to copy:
- The key in the `x-api-key` header on every call, like Kover and Inkita. There is no session that expires and no renewal to get wrong.
- Download the whole CBZ (Inkita, Lagrange). This reuses the reader that already exists and prepares for reading without internet.
- The web reader's page rule: 0-based index, and the total marks the chapter as read.

What to avoid:
- Comparing the device clock with the server clock (Kover, Inkita). The [progress contract](../decisions/progress-contract-2026-10-24.md) already decided to compare with the agreed copy, without dates (D2).
- Lagrange's off-by-one page.
- The queue that pushes without re-reading (Kamigura).
- Treating a 401 as success (Inkita).
- JWT without renewal (Mihon extension, Lagrange). It shows up in the login issues.

## 4. The three proposals

### Plan 1: minimal path

On the test device, in Settings, a person connects with an address and a key and sees "Conectado · Kavita 0.9.x" (Connected · Kavita 0.9.x) or one of the two expected errors ("Não foi possível acessar o servidor…" (Couldn't reach the server…) and "O Kavita recusou o acesso." (Kavita refused access.)). The comic series from all image libraries appear in the Library with the Kavita badge. Each chapter opens in the Strip, loading page by page over the network. The position stays only on the device: nothing goes to Kavita, and the screen warns "o progresso ainda não sincroniza" (progress does not sync yet).

The pieces, in order: Kavita in the CI, connection test, connection screen, schema version 2 with the address as identity, listing, import, works in the Library, page-by-page reading and, last, the CI blocking the pre-release.

For: it is the shortest path to something useful on the device. It writes nothing to Kavita, so it does not damage the progress there. It installs over the previous version on the test device to prove the migration.

Against:
- It goes against the design, which asks for progress and Want to Read to go to Kavita.
- It accumulates local progress with no agreed copy, and the first sync will have to deal with that.
- The address becomes the identity, and changing it duplicates the works.
- The red of piece 1 is staged.
- It does not handle the page rule or reading without internet.
- It assumes docker or podman on the development machine, which are not there.

### Plan 2: risk first

On the test device: connect, mark libraries and apply Ground Zero (Marco zero: Kavita's Want to Read becomes Want to Read, started progress becomes Reading, all read becomes Read). It opens the downloaded CBZ and syncs the position. When there is a conflict, it asks before opening, and the position not chosen stays on the Work page. The queue works without network, and a changed key is handled without losing the queue. Disconnecting keeps the works.

The pieces, in order:
- P0 Kavita in the CI.
- P1 snapshot of the progress on the server.
- P2 stability of the chapter id after a new scan.
- P3 full schema version 2.
- P4 import.
- P5 page conversion.
- P6 conflict and three-step upload.
- P7 queue and failures.
- P8 expired key.
- P9 to P12, the screens.

For:
- It is the only one that measures whether the chapter id survives a new scan.
- The server identity is created by the app.
- The queue has one row per chapter, so it never resends a stale position.
- It converts the page with a round trip against the server.
- It never compares clocks.
- It is the most faithful to the design and to the progress contract.

Against:
- The first screen on the device only arrives in the ninth piece.
- It creates tables before any use.
- The characterization reds are artificial and the plan does not discuss that.
- It assumes podman on the development machine.
- `docker pause` and the key change (with the server's 15-minute cache) can make the CI flaky.
- It picks the Comic type without addressing the Kavita+ risk.

### Plan 3: test environment first

On the test device: connect, mark libraries, see Kavita works next to the local ones and open the CBZ in the Strip. The position goes to Kavita in three steps (read, send, re-read) when the reader closes and when the app opens. It comes from Kavita when only Kavita changed. Left out: the conflict screen, Want to Read, Ground Zero, Continue, queued downloads and disconnecting.

The pieces, in order: Kavita answers in the CI, setup with the key only in memory, generated comics, server facts pinned in tests, connect, libraries, chapters and pages, three-step progress, schema version 2 in a worktree, import, connection screen, Library with the Strip and round-trip position.

For:
- It checked that the development machine has neither docker nor podman.
- The test key lives only in the memory of the test process and never goes to a log or file.
- The comics are generated with known colors and 12 pages, to prove the natural order, and the comparison is by SHA-256 digest (the file's fingerprint).
- The suite fails, instead of skipping, if Kavita is missing.
- It states that a characterization test has no honest red.
- It raises the risks of Kavita+ and of the mounted folder's permissions.

Against:
- When both sides changed, it "gets recorded" without saying what the reader opens or whether the upload is blocked. Since the server accepts the last write, this can erase the reading done on another device.
- It puts the HTTP client in `core` without discussion.
- It uses `docker stop` in the middle of the suite.
- It does not measure the stability of the chapter id.

## 5. Recommendation

The skeleton is plan 3. The grafts are the following:
- from plan 2: the chapter id measurement, the server identity created by the app, the queue with one row per chapter and the page conversion as a pure function;
- from plan 1: the early connection screen, so that the test device shows something before the migration;
- to close the hole in plan 3: when both sides changed, nothing is sent and the app asks before opening (decision 3).

The HTTP client lives in `data`. Only the result types and the pure rules live in `core`.

The integration test suite is called `kavitaTest` and lives in `data`. It runs on the JVM (the Java machine of the PC and the CI, without Android) and stays outside `:data:test`, which remains without network.

A characterization test pins down what the server does today, so that the CI warns if a new Kavita version changes it.

### Block A: can start now (CI and tests only)

1. Kavita starts in the CI and answers (ci)
   - What it does: creates a `kavita` job in `ci.yml`, in parallel with `verify`, without secrets. It runs `docker run` on the image pinned by digest, waits for `/api/Health` and runs `:data:kavitaTest`. Without `KAVITA_URL`, the suite fails with a clear message.
   - Red test: "given a new Kavita in the container, when I ask for health, then it answers Ok". The red commit does not yet have the `docker run` and fails with connection refused.
   - Where to check: in the CI, with `gh run list --limit 1 --json databaseId` and then `gh run view <number>`. The job shows red and then green, with the startup time in the log.

2. Headless setup: administrator and key (data, test code)
   - What it does: in Kotlin, calls `Admin/exists`, then `register` with a username and password generated in that same run, and then `create-auth-key` with the name "ci". The key stays only in the process's memory.
   - Red tests:
     - "given a Kavita without an administrator, when the setup finishes, then the created key opens the library list with 200 and an empty list";
     - "given the setup done, when I ask whether an administrator exists, then the answer is yes";
     - "given a made-up key, when I ask for the libraries, then Kavita answers 401".
   - Where to check: in the CI, the three names in the report. Also, `gh run view <number> --log | grep -c ci-key` must give 0 lines with the key's value (the test prints only the name).

3. Test comics generated and library scanned (ci and data)
   - What it does: the `:data:kavitaFixtures` task generates the CBZs with single-color PNGs, using Java's own `javax.imageio`, with no new dependency. There are two works, and one has a 12-page chapter. The folder is mounted at `/manga` read-only. The setup creates the library with `folderWatchMode: 0` and a valid `metadataProvider`. Then it waits for `lastScanned` to change, with a time limit, and prints how long the scan took. This is where the right file name is found out, and whether the Manga type with provider 3 works without Kavita+.
   - Red tests:
     - "given the test folder with two works, when the scan finishes, then the library lists two series";
     - "given the 12-page chapter, when Kavita scans it, then the chapter has 12 pages";
     - "given the work with two chapters, when I ask for the volumes, then both come in the order 1, 2".
   - Where to check: in the CI, the line "scan took N s". At the date of this research that time is "not available".

4. Server facts pinned in tests (data, characterization)
   - What it does: pins down in tests what Kavita 0.9.1 does with authentication, pages and progress. It includes the measurement that came from plan 2: whether the chapter id survives a new scan, the replacement of the file and the arrival of a new chapter.
   - Tests:
     - "given the key only in the header, when I ask for a page without apiKey in the URL, then…" (pins the real behavior);
     - "given a wrong key, when I ask for the version, then…" (pins the code; telling a refusal apart from a server that is down depends on it);
     - "given a served page, when I read the Cache-Control, then…";
     - "given a 12-page chapter, when I write page 12, then it is read";
     - "given page 5 written, when another client writes 3 with an old date, then Kavita keeps 3";
     - "given a chapter with no progress, when I write page 0, then no record is created";
     - "given a chapter with progress, when the library is scanned again with no change, then the id and the progress stay";
     - "given a chapter with progress, when the file is replaced by another with the same name, then…" (pins the real behavior);
     - "given a new chapter in the folder, when the scan finishes, then the old ids do not change".
   - About the red: a characterization test has no honest red (decision 4). A new scan is only requested after the previous one has finished, because of the 3-hour postponement.
   - Where to check: in the CI, the green tests. Each result that differs from the code that was read becomes a line in the [progress contract](../decisions/progress-contract-2026-10-24.md) and a question to decide before piece 8.

### Block B: after step 2 closes

5. Connect (data, with the result type in core)
   - What it does: `KavitaClient` sends `x-api-key` on every call. `connect` returns one of three results: Connected (with the version), Refused (401 or 403) or Unreachable. The Unreachable test uses a closed port on the runner itself (the machine that runs the CI), without imitating anything.
   - Red tests:
     - "given the CI's Kavita and the right key, when I connect, then the result is Connected with version 0.9.1";
     - "given a wrong key, when I connect, then the result is Refused";
     - "given a port where nothing answers, when I connect, then the result is Unreachable";
     - "given an address with a trailing slash, when I normalize it, then it loses the slash" (unit test in core).
   - Where to check: in the CI.

6. Connection screen (app)
   - What it does: adds the `INTERNET` permission to the manifest. Creates the "Kavita" entry in Settings, with address, key (and where to copy it from) and the Connect button. The errors are "Não foi possível acessar o servidor…" (Couldn't reach the server…) and "O Kavita recusou o acesso." (Kavita refused access.). The texts are in English and pt-BR in the same commit. The key stays only on the device, outside the database and the backup (decision 6).
   - Red tests (Robolectric, which runs the screen on the JVM without a phone; the screen receives the result ready-made, without network):
     - "given the Refused result, when the screen receives it, then it shows 'O Kavita recusou o acesso.' and the key stays in the field";
     - "given Unreachable, then it shows 'Não foi possível acessar o servidor…'";
     - "given the screen open, when I rotate the device, then what I typed stays";
     - "given a backup exported after connecting, when I read the file, then the key is not in it".
   - On the test device, with a reachable Kavita: the right key shows "Conectado" (Connected); one changed letter shows the refusal; with Wi-Fi and mobile data off, the other error appears.

7. Libraries, works and chapters with pagination (data)
   - What it does: calls `Library/libraries`, then `Series/v2` with the filter, going through the pages via the `Pagination` header, and then `Series/volumes`.
   - Red tests:
     - "given the test library, when I list the libraries, then one comes, with the name given in the setup";
     - "given pages of size 1, when I list the works, then both come, without repeats";
     - "given the work with two chapters, when I list the chapters, then they come in Kavita's order with the total pages".
   - Where to check: in the CI.

8. Schema version 2 (data, in a short branch in a worktree)
   - What it does: it is the first migration (a change to the structure of the already-installed database). It creates the `1.sqm` file and turns on SQLDelight's migration verification in the build. A worktree is a second working copy of the repository. What goes into the schema is described below the list.
   - Red tests:
     - "given a version 1 database with local works and progress, when I migrate to 2, then every work, chapter, position and read mark stays the same";
     - "given the same Kavita chapter imported twice, when I save, then there is only one row";
     - "given a server whose address changed, when I re-import, then the works are the same";
     - "given an item in the queue, when the process is recreated, then it stays 'to send'".
   - On the test device: install the pre-release over the previous one. The local works, the read chapters and the positions stay the same.

9. Import the marked libraries and show them in the Library (data and app)
   - What it does: `LibraryStore.importKavita` saves the works and chapters of the marked libraries, in full (the design asks for the marked library to be imported in full). What disappeared from Kavita stays as absent, with its progress kept (decision 8), and the rule follows what piece 4 measures. The Library no longer requires a chosen folder. Text works appear with "abre no passo 4" (opens in step 4) (decision 5).
   - Red tests:
     - "given the test library marked, when I import, then the Library has two Kavita works with the right chapters and pages";
     - "given an import done, when I import again with no change, then no local id changes";
     - "given a series with progress that disappears from Kavita, when I re-import, then the work stays absent and the progress stays";
     - "given the local folder re-read, when I mark absent works, then the Kavita works do not disappear";
     - "given a Kavita work and no chosen folder, when I open the Library, then I see the work with the Kavita badge" (Robolectric).
   - On the test device: mark a library. The series appear with the Kavita badge, next to the local ones, with nothing pre-selected in the choice.

10. Download and open the chapter in the Strip (data and app)
    - What it does: `GET /api/Download/chapter` into the cache, opening through the existing `ArchiveOpening` and the SHA-256 in `content_hash`. `ReaderHost` no longer requires the SAF folder tree.
    - Red tests:
      - "given the 12-page chapter, when I download and open it, then the pages come out in natural order and the digest matches the generated CBZ" (integration);
      - "given an account with only Kavita, no folder, when I open a chapter, then the reader shows the first page" (Robolectric);
      - "given the chapter already downloaded and Kavita down, when I open it, then I read from the cache".
    - On the test device: open a Kavita work, scroll the Strip to the end and then open it again in airplane mode.

11. Position conversion (core, pure function)
    - What it does: converts the local page (from 0 to the total, where the total is the end block) to Kavita's `pageNum` and back.
    - Red tests:
      - "given page 0 of 5, when I convert, then pageNum is 0";
      - "given the end block of a 5-page chapter, when I convert, then pageNum is 5 and the chapter counts as read";
      - "given pageNum 5 coming from Kavita, when I convert back, then the position is the end block and the chapter is read with origin KAVITA";
      - "given I write page 3 through Kamayoq, when I read the progress in Kavita, then it returns 3" (integration).
    - Where to check: in the CI.

12. Conflict decision and three-step upload (core and data)
    - What it does: in `core`, a function receives three values: the local one, the agreed copy and the remote one. Equality is page plus `bookScrollId`, without date. In `data`, the upload reads, sends, re-reads to confirm and saves the server's date in the agreed copy.
    - Red tests:
      - "given local and remote equal to the agreed copy, when I decide, then nothing happens";
      - "given only the local one changed, when I decide, then I send the local one";
      - "given only the remote one changed, when I decide, then I adopt the remote one and keep the previous one";
      - "given both changed to different pages, when I decide, then it is a conflict and nothing is sent";
      - "given different dates and equal pages, when I decide, then it is not a conflict";
      - "given another device writes between the read and the send, when I sync, then its write is lost". This last one documents limit D1, accepted in the progress contract.
    - Where to check: in the CI. The "other device" is a second direct call to the same Kavita.

13. Upload queue (data)
    - What it does: the three states ("to send", "sent without confirmation", "confirmed"), with one row per chapter. "Kavita down" is tested with an address on a closed port, without `docker stop`.
    - Red tests:
      - "given Kavita unreachable, when I save progress, then it stays 'to send'";
      - "given two saves of the same chapter without network, when the network comes back, then only the last one goes";
      - "given an item 'sent without confirmation', when I sync, then I re-read first and do not resend if the server already has it".
    - Where to check: in the CI.

14. Round-trip position in the app, with the conflict question (app)
    - What it does: syncs when the reader closes, when the app opens and before opening a chapter (decision 7). When only Kavita changed, its position wins, with a notice and "Voltar à anterior" (Go back to the previous one). When both changed, the app asks before opening, with no option pre-selected (decision 3).
    - Red tests:
      - "given page 7 read here, when the reader closes, then Kavita keeps 7 and the queue marks confirmed";
      - "given another device that read up to page 10 and nothing changed here, when I open the chapter, then the Strip starts at 10 with the notice";
      - "given a conflict, when I tap the chapter, then I see both positions with none selected and the reader does not open before the choice".
    - On the test device: read up to page 7 and check in the Kavita web reader, on a computer. Move forward on the computer and reopen on the device. Change both sides with the device in airplane mode: on reopening, the question appears.

What goes into schema version 2 (piece 8):
- The `kavita_server` table has an id created by the app, the address (which can change), the name and the last version seen. The key does not go in.
- In `work_version`, for the `kavita` origin, `source` holds the server id and `path` holds the series id.
- In `chapter`, the `remote_id` column comes in with a unique index per version. `path` holds the chapter id, `format` is `'KAVITA_ARCHIVE'` and `stamp` holds the pages plus the server date. The `format` column has no CHECK in the SQL (checked in `Chapter.sq`).
- `progress_agreement` holds the agreed copy.
- `progress_outbox` has one row per chapter and the three states.
- `alternative_position` only comes in if decision 3 is the conflict question.

After the `kavita` job has been stable for a few pushes, `publish` gets `needs: [verify, kavita]` (decision 9).

## 6. Testing against the real Kavita

- How it runs: a `kavita` job on `ubuntu-latest`, with `contents: read` and no repository secret at all. It runs `docker run -d -p 5000:5000 -v <generated folder>:/manga:ro jvmilazz0/kavita@sha256:<full digest of 0.9.1>`. `/kavita/config` is not on a volume: database, JWT key and cache are created and destroyed with the run. The plan uses `docker run` instead of `services:` because the test folder only exists after the checkout. That `services:` starts before the checkout is an inference, not checked in the GitHub documentation. `docker logs kavita` is only kept when the job fails.
- What is seeded: only content generated in the job itself. Two CBZ works with single-color PNGs of known colors, made with `javax.imageio`, a 12-page chapter to prove the natural order and a work with two chapters. Nothing from third parties, no personal data. Public-domain text only comes in at step 4, with EPUB.
- How the key is created without the web interface: `Admin/exists` answers `false`, then `register` creates the administrator with a random username and password, and then `create-auth-key` creates the "ci" key. All of this in Kotlin, inside the test process, and the key never goes to an environment variable, file or log. Then comes `Library/create`, and the end of the scan is awaited through `lastScanned`, never with `Library/scan`.
- Updating Kavita: the version lives in a single place, the digest in `ci.yml`, with a comment giving version and date. Changing it is one commit, and the piece 4 tests say what changed.
- CI time: not available. Neither the startup time nor the scan time was measured; pieces 1 and 3 print both.
- On the development machine: there is neither docker nor podman (checked with `which` on 29/10/2026). Until there is, the proof is the CI run number.

## 7. Open decisions at the date of the research

What was decided is in [`kavita-step-3-2026-10-30.md`](../decisions/kavita-step-3-2026-10-30.md).

1. Does block A (pieces 1 to 4, CI and tests only) start now?
   1. Yes, now, in parallel with the closing of step 2 [recommended]. It touches only `ci.yml` and a new test suite, so the clash with step 2 is small. If the two lines of work touch the same code at the same time, the project rule calls for a short branch.
   2. Everything, including block B, now, in a branch in a worktree.
   3. Everything waits for step 2 to close.
2. Where does the Kavita that the test device reaches run?
   1. A container on the development machine, on the local network, with podman and the same image as the CI [recommended: same version, and it also runs the suite locally].
   2. A remote server.
   3. Only the CI until the end of step 3, with no check on the device.
3. When the position changed on both sides:
   1. The app does not send and asks before opening, with no option pre-selected, and keeps the one not chosen [recommended: follows the design, which asks for the question on conflict, and protects the progress].
   2. The app does not send, opens at the local position and warns that there is a divergence; the question is left for later.
   3. Stays as in plan 3: it "records" and decides later. Not recommended, because it can overwrite the reading done on another device.
4. Characterization tests (piece 4) without a red commit:
   1. Accept the exception and record it in the project rules [recommended].
   2. Spread each fact into the client piece that depends on it, with that piece's red.
5. Text works (EPUB, novels) from Kavita in step 3:
   1. They appear with "abre no passo 4" (opens in step 4) [recommended: the Library stays faithful to what was marked].
   2. They do not appear until step 4.
6. Where to store the key on the device: Android's current options were not checked; the research is left for before piece 6. The manifest already has `android:allowBackup="false"` (checked).
7. When to sync:
   1. When the app opens, when the reader closes and before opening a chapter [recommended].
   2. Those three plus when the connection comes back, in the background.
   3. Only when the app opens.
8. Series or chapter that disappears from Kavita:
   1. Keep it with its progress, out of the list, as in rule E1 of the [folder convention](../decisions/folder-convention-2026-10-24.md) [recommended].
   2. Delete it along with it.
9. Does `publish` wait for the `kavita` job?
   1. Not until piece 3 measures the time; after that, yes [recommended].
   2. Yes from the start.
   3. Never.
10. Short branch in a worktree for the migration (piece 8), as the project rules give as an example:
    1. Yes [recommended: it is the first migration, on a database that already has data on the test device].
    2. Directly on `main`.
11. HTTP client, a new dependency:
    1. OkHttp, used by Inkita and Kamigura [recommended, after checking the license and its presence on F-Droid].
    2. Ktor client.
    3. Java's own `HttpURLConnection`, with no dependency and more hand-written code.
12. Scope: left out of step 3 are Want to Read, Ground Zero, Continue (on-deck), the download queue with "Wi-Fi only", disconnecting with a warning, username and password, and Search.

## 8. Risks and what was not verified

Risks, from most to least serious:
1. The last write wins on the server. A reading from another device can be lost between the read and the send. This is limit D1, accepted and documented in a test. The rest is mitigated by the agreed copy, the queue with one row per chapter and the conflict question.
2. The chapter id may change after a new scan or a file replacement. Not verified; piece 4 measures it before the schema.
3. The migration from version 1 to 2 is the project's first, and the test device already has data. The defense is the preservation test, the verification in the build and installing over the previous version on the device.
4. The scanner may not recognize the generated CBZs, or the `metadataProvider` may require Kavita+ or network. Piece 3 finds this out before any app code. It is a time risk.
5. Permissions on the folder mounted in the container may give an empty library with no error. Not verified.
6. Without a reachable Kavita, pieces 6, 8, 9, 10 and 14 stop at the CI and Robolectric (decision 2). Unencrypted HTTP on the local network probably requires a network security configuration on Android. Not verified.
7. The CI may get slower or flaky (asynchronous scan, Docker Hub down). Time not available. The alternative `ghcr.io/kareadita/kavita` was not checked.
8. Two lines of work writing while step 2 closes (decision 1).

Not verified:
- In Kavita: everything in the list at the end of section 2; what `Plugin/version` answers with a wrong key; whether the administrator created by `register` has the Download role (by the policy, Admin is enough); whether `GET /api/Server/is-task-running` can be used to wait for the scan; the full digest of the image; whether `0.9.1` is 0.9.1.4; the order of specials relative to regular chapters.
- In the project facts: the fit survey read the Kamayoq code as it stood on 29/10/2026. The facts about Kavita recorded in the project come from Kavita commit `d549d5b` (24/10/2026) and were not rechecked against `3c9ddb4`, except for what is in section 2. The code of `ChapterOpening`, `ReaderHost` and `LibraryStore` was not reopened in this research.
- In Android and the CI: the license and F-Droid status of OkHttp or Ktor (Apache-2.0 from memory; not checked); the currently recommended secure key storage; whether `javax.imageio` writes PNG on a runner without a display; whether docker comes ready on `ubuntu-latest`; the CI time.
- In no client was the runtime behavior observed; everything comes from reading the code.
