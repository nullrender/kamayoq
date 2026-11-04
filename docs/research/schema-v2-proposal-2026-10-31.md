# Step 3, piece 8: schema version 2 proposal

Written on 31/10/2026. **Approved on 01/11/2026:** the 6 decisions stay as they are; not yet turned into a migration.

Verified while writing: reading of `.sq`, `LibraryStore.kt`, `Backup.kt`; a dry run of an earlier version of the schema on desktop SQLite 3.54 (identical data, clean `foreign_key_check` and `integrity_check`); `:core:test` and `:data:test` with 190 tests, 0 failures. Read in source code only, not run: Kavita `v0.9.1.4` (`879b8b7`), SQLDelight `2.4.1`, AOSP. Nothing ran on Android. The final schema was not dry-run.

## Tables

Additions only: no table is recreated.

| Table | New columns | Why |
|---|---|---|
| `work_version` | nullable `remote_library_id` | Sending to Kavita requires the number (`ProgressDto.cs:8-12`); lets works from an unchecked library be hidden without network |
| `chapter` | `page_count`, `remote_file`, `remote_volume_id`, all nullable | Rule 8b; tie-break by name (smallest `filePath`, name only; the API gives no name and `files[0]` has no guaranteed order); sending to Kavita |
| `kavita_server` | `id`, `address`, `last_version`, `account_id` | `account_id`: progress in Kavita is per user. No `name`, which has no source. The key never goes in |
| `kavita_library` | `server_id`, `library_id` | Checked libraries. Missing from the design; piece 9 would have nowhere to store them |
| `progress_agreement` | `chapter_id`, `page`, `book_scroll_id`, `server_at`, `own_reread`, `local_cfi` | Contract 2d. `server_at` is Kavita's text, only for equality, null when there is no record. `own_reread` is rule C1 |
| `progress_outbox` | `chapter_id`, `page`, `book_scroll_id`, `local_cfi`, `state`, `queued_at` | Contract 2d. The CFI does not come back from Kavita, so it keeps the value that was sent |
| `alternative_position` | `version_id`, `reason`, `chapter_id`, `page`, `total`, `at`, `device` | Key `(version_id, reason)`: the one not chosen and the previous one coexist. Same rules as `chapter_progress`; from Kavita, `device` = `kavita` |

Left out: "Quero ler" (Want to read) in the agreement (decision 12); CFI and fraction in `alternative_position`, which come in version 3 together with `chapter_progress`; the Kavita installation identifier, which only an administrator can read and can be added later at no cost.

`insertEdition` now copies the new `chapter` columns.

## What does not match the design

- `remote_id` is redundant: `path` holds the chapter id, and the `chapter_current_path` index rejected the second row in the dry run.
- `KAVITA_ARCHIVE` does not go in: the format says the content, `origin` says the origin. Consequence: piece 10 decides by origin before format; today `ChapterOpening.kt:54-59` would go to the local folder.
- `verifyMigrations` does not trigger the verification: CI needs a step with `:data:verifyMainKamayoqDatabaseMigration`, with a red run proven.
- The "address changed" and "reimport" tests depend on `importKavita`: in piece 8 they stay in SQL; the real proof moves to piece 9.
- The test "the key is not in the backup", planned in piece 6, does not exist. It goes into piece 9.
- `core` and `data` run on the development machine, without the Android SDK, and not only in CI.

## Migration safety

- `1.db` generated before any change to `.sq`: with `1.sqm` present the file would come out as `2.db`, and the verification would pass without migrating anything.
- Main test: `1.db` plus `user_version = 1` and a frozen `.sql` of fictional rows, calling `schema.migrate` on the raw driver.
- Copy with `VACUUM INTO` on a read-only connection. In the dry run, a plain file copy lost data from the WAL; `VACUUM INTO` did not. Name `kamayoq-v<n>.db`, in `noBackupFilesDir`, under a temporary name, checked and renamed; an existing good copy is reused.
- Missing or version 0: create. Same version: open. Older version: copy and migrate. Newer version: screen, without copying.
- `onCorruption` overridden in both openings: corruption becomes a screen, never deletes the file.
- Before installing, compare the real schema on the test device with `1.db`, with the copy outside the repository.
- Not tested: `VACUUM INTO` on Android. If it fails, the app stops at the screen without migrating and the database stays intact.

## Approved decisions (01/11/2026)

1. Remove `remote_id` and do not create `KAVITA_ARCHIVE`.
2. Store `remote_library_id` and `remote_volume_id` (alternative: fetch them before each send). Whether these ids change after a new scan was not measured.
3. The item leaves the queue on confirmation, as in the contract; `state` with two states, and the text of piece 14 changes.
4. Rule 8b only with a single candidate (one missing and one new with the same series, number and pages). This changes the earlier decision: the number repeats in specials, in volumes that restart and in ranges.
5. Piece 8 does not bump `BackupFormat.VERSION`. The first commit of piece 9 bumps it to the complete format 2, before storing any Kavita work, with servers, checked libraries, `source` and the new columns. Step 4 becomes format 3, with piece 22 before 21.
6. Failure screen without export for now; the copy comes out through `adb`, and exporting from the app becomes a condition for the first public release.

## Findings for the next pieces (they do not change the schema)

They come from reading code, without running against a real Kavita.

- Piece 9: `Series/volumes` sorts the loose volume first; Kavita's reader puts it last (`ReaderService.cs:539-540`). Only mark items missing after listing all libraries. Decide what is shown for CBR, PDF and a chapter with several files (the download becomes a ZIP of CBZs). Define Kavita's `stamp`. Clear the agreement when relinking through 8b. Reconnecting with a new key: same account and same names continue; a mismatch asks.
- Piece 10: `confirmContent` treats progress without a hash as a new edition and loses the position that came from Kavita (`LibraryStore.kt:240`, measured on the real `LibraryStore`). Decide whether a Kavita chapter with the same id becomes an edition; if it does, agreement and queue must follow. The cache cannot stay in the folder that `clearLeftovers` deletes.
- Piece 11: Kavita and Kamayoq count pages by different rules (hidden folders and `@recycle`).
- Pieces 12 and 13: `SENT_UNCONFIRMED` written before the POST and immutable; the pending item is `chapter_progress` against the agreement; cap the page at `page_count`, because the server reduces it to the total; a remote 0 over a larger agreement requires checking whether the chapter exists; case 5 of the contract is a conflict between chapters, and detecting it requires scanning `Series/volumes`.
- Step 4: a compiled novel (B-3) may revisit the agreement and queue key.
