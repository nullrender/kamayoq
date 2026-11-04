# Step 3 (Kavita): decisions

Design and numbering: `docs/research/kavita-step-3-design.md`, section 7. Record opened on 30/10/2026.

| Decision | Choice | Note |
|---|---|---|
| 1. Order of work | Block A, then block B, in sequence, on `main` | Running in parallel with step 2 no longer made sense: step 2 closed on 30/10. The migration follows decision 10 |
| 2. Kavita the test device reaches | Local container with podman and the same image as CI, reachable only over a private network; the test files folder mounted read-only | The design's premise was corrected: there is no permanent Kavita server in use |
| 3. Position changed on both sides | Does not send, and asks before opening, with no option preselected; the one not chosen is kept in the Work | Follows the product design's rule for diverging positions |
| 4. Characterization tests | Accepts the exception without a red commit, recorded in `AGENTS.md` | Applies only to these tests. The line in `AGENTS.md` goes in with piece 4 |
| 5. Kavita text works | They appear, and tapping them warns "abre no passo 4" ("opens in step 4") | Temporary notice: removed in step 4 |
| 6. Where to store the key | Encrypted with an Android Keystore key (AES-GCM), in a private app file, outside the database and the backup; no new dependency | Research below. The manifest already has `android:allowBackup="false"` |
| 7. When to sync | On opening the app, on closing the reader, and before opening a chapter | Sending in the background when the network comes back is left to revisit later |
| 8. Series or chapter that disappears from Kavita | Keep it with its progress, out of the list | Same rule as for folders (E1). Piece 4 measures whether the id comes back; the rule goes to the product design |
| 8b. Chapter that disappears and comes back with another id (changed on 01/11/2026: only with a single candidate, see the end of this file) | Automatically relinks the stored position when the Kavita series, the chapter number and the page count match; otherwise it stays stored, as in E1 | Decision of 30/10/2026, after piece 4's measurement. Applies to piece 9. Not measured: whether the whole series disappears and comes back |
| 9. `publish` waits for the `kavita` job | Not until piece 3 measures the time; after that, yes | |
| 10. First database migration | Short branch in a worktree | The signed APK is built only from `main`: the proof on the test device comes after the merge, with an in-app backup before installing. Installs over `r191` or newer, not over the design's `r120` |
| 11. HTTP client | OkHttp | Apache-2.0, checked on 30/10/2026 on GitHub; version to be checked on Maven Central when adding it |
| 13. Accept `http://` | Allowed in general, with a short notice on the connection screen when the address is `http://` | No HTTPS on a home Kavita, and no per-address rule that works for everyone. Practice of Inkita, Mihon, Kotatsu, Findroid and Immich |
| 12. Out of step 3 | Kept: Quero ler (Want to read), Marco zero (Ground zero), Continuar (Continue), download queue with "só Wi-Fi" ("Wi-Fi only"), disconnect with a warning, username and password, Buscar (Search) | They stay in phase 1 of the product, as debt |

## Research for decision 6: how Kavita clients store the key (30/10/2026)

| Client | Commit | How it stores it |
|---|---|---|
| Kamigura | `07a7fa7` | Keystore directly, AES-GCM, in DataStore (`KavitaSessionStore.kt:1-19`). Keeps the decrypted session in memory: the comment on lines 65-68 says each decryption costs several round trips to the Keystore |
| Lagrange | `018d457` | Keystore directly, AES-GCM, in SharedPreferences (`ProfileSessionStore.kt:16-39`). Swallows failures on write and on read (`runCatching`) |
| Inkita | `67c1f4a` | `EncryptedSharedPreferences` in `security-crypto:1.1.0-alpha06`, a deprecated library (`SecureStorage.kt:20-27`). A read failure becomes empty text (line 62) |
| Kover | `64f80cc` | `flutter_secure_storage` 11.2.0 (tag, commit `805638b`) with the default options: an RSA key in the Keystore wraps an AES key, and the data goes with AES-GCM into plain SharedPreferences. The AES key runs in the app process, outside the Keystore. Does not use `EncryptedSharedPreferences` |
| Mihon extension | `b0f4174` | Plain text in `preferences` (`Kavita.kt:2611`) |

Official sources, 30/10/2026: `developer.android.com/privacy-and-security/keystore` (page dated 23/10/2026) and the `security-crypto` `1.1.0-beta01` release note, dated 04/06/2025.

What to copy: keep the decrypted key only in memory while the app runs, like Kamigura. What to avoid: swallowed failures. If decryption fails, the connection screen asks for the key again and says why.

Reading of Kover and the library (30/10/2026; read in the code, nothing run on a device):
- With the default `resetOnError`, a decryption failure **deletes** the credential and returns empty (`FlutterSecureStorage.java:55-72`, `1336-1345`), and Riverpod's `persist()` swallows the other errors. The person sees "not connected", with no warning. Kamayoq does not delete: it keeps the file and asks for the key.
- `allowBackup="false"` is not enough: when targeting API 31 or higher, device-to-device transfer still happens on some manufacturers (`developer.android.com/about/versions/12/behavior-changes-12`, read on 30/10/2026). The Keystore key does not go along, so the copy does not decrypt. Kamayoq stores the file in `getNoBackupFilesDir()`: "always excluded even if you try to include them" (`developer.android.com/identity/data/autobackup`, read on 31/10/2026), which makes `dataExtractionRules` unnecessary for the key. The documentation does not name this folder for device-to-device transfer; that it stays out is an inference. The error handling above covers whatever gets through.
- Kover sends the key in the URL in `Reader/image`, `Reader/pdf` and `Plugin/authenticate`, and Kover's URL scrubbing in logs fails on hosts such as `kavita:5000` and `10.0.0.5` (regex reproduced in Python). Kamayoq sends the key only in `x-api-key`: it downloads the whole chapter and does not use `Reader/image`.

## Plan approved on 30/10/2026

Piece numbering in `docs/research/kavita-step-3-design.md`, section 5.

1. Real Kavita in CI (pieces 1 to 4). Once `kavitaTest` is stable, `publish` starts waiting for `kavitaTest` (decision 9).
2. Connection on the test device (pieces 5 and 6), with the research for decision 6 and the local Kavita, with podman.
3. Works and database (pieces 7 and 8), with the migration on a short branch (decision 10). If piece 4 shows that the chapter id changes, this stage is decided again before the database.
4. Reading (pieces 9 and 10).
5. Progress (pieces 11 to 14).
6. Wrap-up: review, full test on the test device, and the debts from decision 12.

## Measured facts (Kavita 0.9.1.4, CI 41155547735, 30/10/2026)

Pinned in `data/src/kavitaTest/kotlin/app/kamayoq/data/kavita/KavitaFactsTest.kt`. Timings: the server responds in 12 to 17 s, the small scan takes about 2 s and the `kavita` job about 1min30.

- The chapter id stays the same after a new scan, after replacing the file with another of the same name, and after a new chapter in the folder. The progress also stays.
- A chapter whose file disappears and comes back gets **another id and no progress**. The rule for this is open (see decision 8).
- A file replacement is only noticed if its date is a later minute than the one Kavita saw; for a folder, a later second.
- `Reader/image` without `apiKey` in the URL responds 400, even with the key in the header. `Cache-Control` of the pages: `private, max-age=0, stale-while-revalidate=0`.
- A wrong key gets 401, on `Library/libraries` and on `Plugin/version`.
- Saving the page equal to the total marks the chapter as read. The last write wins, and the date sent is ignored. Page 0 with no record does not create a record.
- An administrator without an email is accepted. Provider 3 (Mangabaka) works for the Manga type without Kavita+. `combination` 1 is `And`.

## Requests from step 4, confirmed on 31/10/2026

Source: `docs/research/epub-step-4-design.md`, sections 2 and 7. Numbering is that of the step 3 pieces.

- Piece 8: nullable column `local_cfi` in `progress_agreement` (contract, 2d), so the same file is not migrated again.
- Piece 8: private copy of the database before migrating, already in version 2, with a screen instead of a crash if the migration fails. Replaces "in-app backup before installing" from decision 10: the app does not restore backups.
- Pieces 8 and 9: format name by content, so that `KAVITA_ARCHIVE` does not require `KAVITA_EPUB` without a rule.
- Piece 9: rule 8b does not identify a standalone EPUB, because an EPUB's chapter number stays at the default, -100000 (inference from the code, not measured). Tie-breaker: the file name in `files[0]`, returned by `Series/volumes`. Measure before writing the rule.
- Piece 9: store format and file name in text chapters (decision 5).
- Piece 10: opened chapter as a sealed type (images or text), if the piece touches `OpenedChapter` or `ReaderHost`.
- To be agreed: who bumps the backup format. `BackupFormat.VERSION` is 1 and reading requires the same version: there cannot be two format 2s.

## Piece 8: decisions on schema version 2 (01/11/2026)

Source: `docs/research/schema-v2-proposal-2026-10-31.md`. All six proposals were approved.

1. No `remote_id`: `path` stores the chapter id, and the index `chapter_current_path` rejects the second row. No `KAVITA_ARCHIVE`: the format says the content, `origin` says the origin.
2. `remote_library_id` and `remote_volume_id` are stored instead of fetched before each send. Whether these ids change with a new scan was not measured.
3. The item leaves the queue on confirmation, as in the contract; `state` has two states, and the text of piece 14 changes.
4. **Changes decision 8b:** it only relinks with a single candidate (one disappeared and one new with the same series, number and pages), because the number repeats in specials, in volumes that restart, and in ranges.
5. Piece 8 does not bump `BackupFormat.VERSION`. The first commit of piece 9 bumps it to the full format 2, before storing any Kavita work, with servers, checked libraries, `source` and the new columns. Step 4 becomes format 3, with piece 22 before 21.
6. Failure screen without export for now; the copy is taken out via `adb`, and exporting from the app becomes a condition for the first public release.

Decided during implementation: `verifyMigrations` stays off, because it rejects `CHECK (page_count >= 0)` in `ALTER TABLE` ("No column found with name page_count"). The rule stays, and the migration is checked by the CI step `:data:verifyMainKamayoqDatabaseMigration`, which failed as it should with a column removed from the migration.

Also decided: `PRAGMA quick_check` on every open (the time is measured on the test device); responses in the wrong format become `KavitaListingFailed`; an existing good copy is reused, and it is the one from the first attempt. The failing tests for schema version 2 went in together with the tables, on purpose: that is what made CI show red in the migration check too. It departs from "test before code".

## Piece 9: decisions (02/11/2026)

Record of 02/11/2026. Measurement: Kavita 0.9.1.4, same image as CI, disposable container, fictitious files; `data/src/kavitaTest/kotlin/app/kamayoq/data/kavita/KavitaReturnFactsTest.kt` pins it in CI.

All six recommendations were approved:

1. A whole series that disappears comes back with another id, so 8b also applies to the series: single candidate by the same library and same title.
2. In 8b, the file name breaks ties between chapters with a number; without a number (EPUB, special) the name is required. A renamed EPUB stays stored, as in E1.
3. No library comes checked on connecting. Diverges from the product design, which expected all of them on: take to the design.
4. Backup format 2 also carries agreement, queue and alternative position, empty for now; `source` only on a Kavita work. The local folder stays out (rule of 28/10/2026).
5. CBR, PDF and a chapter with several files appear and warn when tapped.
6. A key from another account, or different library names: warns and does not import. The account id is 1 on every new Kavita, so the id alone proves nothing.

Decided during implementation: reading the backup still accepts only the current format, because there is no restore; the future one will have to read format 1. Local works come before Kavita ones in the list. Chapter order: volumes, loose chapters, specials, like the Kavita reader.

Also decided:
- A position on the first image is not sent to Kavita, because every opening starts there and sending 0 would unmark the read chapter. Deliberately going back to the beginning is not sent either.
- A chapter whose content is in doubt stays out of sync until the summary confirms it.
- Server recognized by a checked library with the same id and name, stored in `SharedPreferences` to avoid migrating the database. Limit: a Kavita reinstalled with the same names and ids is accepted as the same one.
- Conflict between chapters (contract, case 5): each chapter follows its own rule and the Work asks where to reopen, with two options and without Continue. The contract says "does not send"; here the send happens, because it touches another chapter and no position is lost. Take to the product design.
