# Local folder convention (24/10/2026)

**Status:** decided on 24/10/2026 as a minimal version, to be tested, adjusted and improved step by step. There were no local test files at the time; the convention comes from what the app needs and from Mihon's local source.

**Sources:** Mihon's local source (github.com/mihonapp/mihon, default branch `main`, commit `d001e6f210e6a077a30dd9a1392573a3225bba2f`, read on 24/10/2026: `source-local/.../LocalSource.kt`, `LocalCoverManager.kt`, `core/.../DiskUtil.kt`) and its documentation (mihon.app/docs/guides/local-source, read on 24/10/2026); the foliate-js README (github.com/johnfactotum/foliate-js, `main`, read on 24/10/2026); the product design; the progress contract (`progress-contract-2026-10-24.md`).

## Example

Revised on 27/10/2026 (see "Revision of 27/10/2026", after the rules).

```
Kamayoq/                                  ← the base folder the person picks in the app
├── Backups/                              ← the app's own, created on the first export
└── Library/                              ← works; the app creates it if missing and only reads from it
    ├── Comics/                           ← type by folder (name in English only)
    │   ├── Sakamoto Days/                ← series, one file per chapter
    │   │   ├── cover.jpg                 (optional)
    │   │   ├── ComicInfo.xml             (optional, read only)
    │   │   ├── Cap. 001.cbz
    │   │   └── Cap. 002/                 ← chapter as a folder of images
    │   │       ├── 001.jpg
    │   │       └── 002.jpg
    │   └── Gantz/                        ← series by volumes (may mix with chapters)
    │       ├── Vol. 01.cbz
    │       └── Cap. 099.cbz
    ├── Novels/
    │   ├── Jeonjijeok Dokja Sijeom (Omniscient Reader's Viewpoint)/  ← alternative title in parentheses
    │   │   ├── Cap. 0928.epub
    │   │   └── Cap. 0929.epub
    │   └── Release That Witch/
    │       └── Release That Witch.epub   ← compiled EPUB: chapters come from the table of contents
    └── Books/
        ├── Dom Casmurro.epub             ← loose file: a single-volume work
        └── Os Miseráveis/                ← book in volumes
            ├── Vol. 1.epub
            └── Vol. 5.epub
```

## Rules

1. **Type.** What decides, in this order: the person's correction; the type folder; the app's automatic metadata; the file format (images → Comics, text → Books).
   - ~~Type folders recognized in several languages, through a list of synonyms~~ (replaced on 27/10/2026): type folders with **a single name, in English**: `Comics`, `Novels`, `Books`, recognized ignoring case and accents.
   - ~~`Mangá/`, `Manhwa/`, `Webtoon/` give the Comics type and the subtype~~ (replaced on 27/10/2026): **there is no subtype folder.** The subtype (badge on the Work screen) comes only from the origin and the metadata.
   - A work outside a type folder gets its type from the metadata or from the format.
2. **Work.** A folder named after the title. A loose file is a single-volume work. **The Work screen shows two titles: the one in the person's language and the original.** Both come mainly from the metadata. The folder name may carry the second title as a hint for the search: for now, in parentheses at the end of the name; the exact separator is left to the implementation (it need not be parentheses).
3. **Chapter (reading unit).** A file, a folder of images, or an item in a file's table of contents.
   - Formats: CBZ and ZIP; EPUB, MOBI, AZW3, FB2 and PDF through foliate-js (PDF experimental, according to the README). What the Readest fork adds, and whether CBR/RAR are included: to be checked.
   - **A novel always shows chapters**, grouped by volume when known. Compiled EPUB: each table-of-contents item becomes a chapter. Volume EPUB (common for light novels): the file is the volume and the chapters come from its table of contents.
   - A book shows its table of contents inside the reader; a book in volumes shows the volumes on the Work screen.
   - **A volume is one level above the chapter, in any type, when the work has one:** it comes from the name ("Vol. 01"), from a volume subfolder, from the EPUB table of contents, or from Kavita's division and the metadata. The Work screen shows the chapters grouped by volume.
   - Chapters and volumes may be mixed in the same series.
4. **Number and order.** From the name ("Cap. 12", "Vol. 01") or from `ComicInfo.xml`. Without a number, natural order (2 before 10). "Vol."/"Volume" in the name makes the file a volume. Automatic correction of the numbering: wanted, comes with the metadata.
5. **Versions by language (reserved for phase 2).** Subfolders with a language code inside the work (`pt-BR/`, `en/`, `ja/`) are versions of the same work. Convention only: each person creates the ones they need. Read marks shared by chapter number; position kept separately per version.
6. **Writing to the folder.** The app asks for write permission and writes **only its own files**: `.nomedia` in a folder with loose images (phase 1, so they do not show up in the Gallery; Mihon does the same when it writes a cover, `DiskUtil.createNoMediaFile`), and its own metadata file when the metadata arrives. **It never changes, renames or deletes the works' files.** This keeps read-only access to the works consistent with the app writing its own files.

## Revision of 27/10/2026

- **Base folder:** the person picks a folder (`Kamayoq/`); the app reads works only from `Library/` and writes its own files to `Backups/` (and, when it downloads something, `Downloads/`), creating each one only when needed; fixed names in English, capitalized, recognized ignoring case. Code: `core/.../library/BaseFolder.kt`.
- **Types in English only, one name each:** `Comics`, `Novels`, `Books`. Reason: readers usually use the English term, and this gives a single pattern everywhere. Discarded: a list of synonyms in several languages (decision of 24/10).
- **No subtype folders** (Manga, Manhwa, Manhua, Webtoon). What the subtype provided is solved without a folder: strip or pages from the image proportions, with a per-work correction (Kotatsu does it this way: `KotatsuApp/Kotatsu`, `devel`, commit `34f6e52`, `reader/domain/DetectReaderModeUseCase.kt`, height greater than 1.8 times the width; on a test CBZ in strips, 720 × 1271, this rule gets it wrong, so Kamayoq's rule will be researched and tested with real CBZ files when the reading modes arrive); right-to-left direction from `ComicInfo.xml` (`Manga` = `YesAndRightToLeft`, schema `anansi-project/comicinfo`, commit `99e1453`; Mihon does not write this field) or from the person's choice per work. Filtering by origin is lost until there is metadata. Discarded: subtypes next to `Comics` (as it was) and inside `Comics` (approved and revised on the same day).

## Tie-breaking rules (24/10/2026)

Provisional: they hold or fall when local import is implemented with real files.

- **E1, file replaced by another edition:** each chapter has its own identity, which does not depend on its position in the table of contents (see the progress contract, section 3). When a file's content changes, the marks carry over automatically **only if the table-of-contents titles match in the same order**; otherwise the old progress is kept and the Work screen asks "Este arquivo mudou. Seu progresso na edição anterior ia até o cap. 12 · Aplicar à nova edição" ("This file changed. Your progress in the previous edition went up to ch. 12 · Apply to the new edition"). When in doubt, it never transfers or deletes.
- **E2, table of contents with levels:** only the items on the last level are chapters; the levels above are volumes. Items that point to the same passage become one chapter. A novel without a table of contents (or with an empty or invalid one) is a single chapter, the whole file, and the Work screen warns that the file does not carry the division.
- **E3, number and order:** the number comes from the person's correction > the chapter's `ComicInfo.xml` > the name (the same precedence as Mihon). Order as in Kavita: volumes by number, then loose chapters by number; ties by natural order of the name; without a number, after the numbered ones, in natural order. A volume CBZ with no known internal division is one unit.
- **E4, parentheses in the real title:** a "use the whole name as the title" correction on the Work screen, stored by the app and valid on later reads of the folder; the original folder name is kept.
- **E5, write failure:** reading never depends on writing (a read-only folder is imported); a failure on `.nomedia` produces a discreet notice ("não consegui esconder estas imagens da Galeria", "I couldn't hide these images from the Gallery"), without blocking; a failure to write the metadata file (once it exists) stays in the database as a pending write to the folder, with no silent success.

## E1 in the implementation: fingerprint and digest (25/10/2026)

**Decided on 25/10/2026**, about a problem in step 2: the same path was treated as the same content. The option chosen was "light fingerprint on import, full digest only when needed":

- **Fingerprint** (size and modification date, which SAF provides in the listing at no cost; for a folder of images, count, total size and latest date): the same fingerprint on the same path is the same chapter, with no check.
- **Digest** (SHA-256 of the whole content): computed on the first open and when the fingerprint changed or the file came back after disappearing, if there was something to protect (a stored digest or progress). Equal: the same chapter, with its progress. Different, or progress with no stored digest: the old one becomes a **previous edition**, kept with its progress and out of the lists, and a new chapter in the same place starts from zero. A chapter never opened accepts the new file without becoming an edition.
- **Accepted limit:** a file replaced by another with exactly the same size and the same date passes as the same (it only happens if forced).
- **Left for the Work screen:** the question "Este arquivo mudou… Aplicar à nova edição" ("This file changed… Apply to the new edition"); the previous edition is already kept for it.
- **Discarded:** a digest of every file on every import (re-reading the whole library; cost not measured); fingerprint only (it is a hint, by its own rule).
- **Code:** `core/.../library/ContentCheck.kt` (fingerprint and digest), `data/.../db/Chapter.sq` and `LibraryStore.confirmContent`.

## Outside this convention

- **Adaptation in another type** (a novel with a comic adaptation): these are different works, linked by the relation coming from the trackers ("Também existe como: Quadrinho (adaptação)", "Also exists as: Comic (adaptation)"), not versions. To be decided with the metadata (phase 2).
- A cloud folder as a source and per-file sync: candidates in the product design, outside phase 1.

## How to check

**Logic tests (JVM):** given `Novels/Jeonjijeok Dokja Sijeom (Omniscient Reader's Viewpoint)/Cap. 0928.epub`, then work "Jeonjijeok Dokja Sijeom", type Novels, alternative title "Omniscient Reader's Viewpoint", chapter 928; given `Comics/X/`, then Comics; given `Books/Dom Casmurro.epub`, then a single-volume work; given `Cap. 2` and `Cap. 10`, then 2 before 10; given `Vol. 01.cbz` and `Cap. 099.cbz` in the same folder, then one volume and one chapter, in order; given a work whose type the person corrected, when it is in a folder of another type, then the correction wins; given a compiled novel EPUB with a 3-item table of contents, then 3 chapters; given "Volume 1 → Cap. 1, Cap. 2", then one volume with 2 chapters; given two items with the same target, then 1 chapter; given a novel without a table of contents, then 1 chapter and a warning; given `Cap. 012.cbz` with `Number=13`, then chapter 13; given `Vol. 01.cbz` and `Cap. 099.cbz`, then the volume first; given `O retorno (parte dois)/` corrected to the whole name, then the title stays whole on the next read of the folder; given an EPUB replaced by an edition with a new preface, then the marks do not carry over automatically and the Work screen asks.

**Android test:** pick the folder through SAF, check that the app only created `.nomedia` where there were loose images and that no work file changed (hash of the full content equal before and after; date and size are only a hint); read-only folder imported; `.nomedia` already present; loss of access to the folder.

**On the test device:** build the example folder with test content (Dom Casmurro, a CBZ of free images) and check the Library.
