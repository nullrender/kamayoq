# Novels by chapter (milestone B of step 4, 18/11/2026)

**Status:** decided. Questions B-1 to B-3 of `docs/research/epub-step-4-design.md` (section 7) were accepted as recommended on 18/11/2026; B-1 was shown again before the code and kept the same day ("at least for now": it is to be revisited with a large sample library). This amends the [folder convention](folder-convention-2026-10-24.md) (rules 3 and E2) and the [progress contract](progress-contract-2026-10-24.md) (sections 2b, 3 and 7). Where they disagree, this text wins.

## 1. Which files split (B-1)

In `Novels/`, an EPUB is read by its **name** first:

- A name with a **chapter number** (`Cap. 0928.epub`, `Chapter 12.epub`, a bare `928.epub`, by the same rule 4 that numbers comics) is **one chapter**, with that number. Its own table of contents is shown only inside the reader, as for a book.
- A name with `Vol.` and no chapter number, or with **no number**, is **split by its table of contents** (E2): each item of the last level becomes a chapter. A `Vol. N` file is that volume, and its chapters belong to it.

The name is enough to choose; nothing is opened to decide. Discarded: splitting every file (a one-chapter file with "Prefácio" and "Capítulo" in its contents would become two chapters and lose its number); the Work screen asking for each work (one more question per novel).

`Books/` does not change: a book is one reading unit, and its contents stay inside the reader.

## 2. The chapters of a split file (E2, made exact)

The table of contents is read in Kotlin (`core`), in the same order as foliate-js (`epub.js:1358-1383`): the EPUB 3 `nav` of type `toc` first; the NCX when the `nav` has no item with a destination. No external DTD is ever fetched.

- **Chapters:** the items of the last level that have a destination inside the spine. An item whose destination is outside the spine, or that has none, is not a chapter. A level above is a volume, as E2 says.
- **Same destination:** two items that point to the same place become **one chapter**, with the first one's title.
- **Empty, invalid or missing contents:** the file is **one chapter**, the whole file, and the Work screen warns "Este arquivo não traz a divisão em capítulos" ("This file does not carry the chapter division").
- **Number:** from the item's title, by rule 4 ("Cap. 3", "Chapter 3"); without one, the chapter has no number and keeps the table's order.
- **Order:** the files in rule E3's order; inside a file, the order of its table of contents.
- **Key:** each chapter keeps the item's destination, resolved to a path inside the EPUB the way foliate-js resolves it (`OEBPS/Text/c3.xhtml#ch3`). This **contents key** is where the chapter is now, like the path; it is **not** its identity (section 3 of the contract). A file read as one chapter has an empty key.
- **When:** the table is read at import, only for a file to split that is new or whose stamp changed; the result is kept with the chapters.

Kotlin's list and foliate-js's `book.toc` must agree: an emulator test compares them on a compiled book (piece B4).

## 3. Position and read mark in a split file (B-2)

The whole file opens once, and reading crosses its chapters with no reload (piece B7).

- **Position:** stored in the chapter being read, in the existing text form: the file's section and CFI, so it reopens on the same passage. The **fraction** of that position is the chapter's own, from 0 at the chapter's start to 10 000 at the next chapter's start (or the file's end), measured on foliate-js's fraction of the book.
- **Which chapter is being read:** the one whose item foliate-js reports for the page (`tocItem`); a page before the first item belongs to the first chapter.
- **Read mark (B-2):** a chapter becomes read, by reading to the end, when the person **pages or scrolls from it into the next chapter** of the same file. Jumping by the contents, or going back, marks nothing. The last chapter of the file becomes read at the end block, as a book does. A complete read counts once per opening of the file, per chapter.
- **Where the work reopens:** the chapter being read, as soon as reading enters it (section 2a); `work_resume` follows.
- **Leaving a chapter forward:** its position becomes its end (fraction 10 000, with the CFI of the first page past it), as the Strip keeps "at the end block".
- **Opening a chapter from the Work screen:** never opened, at its item's destination; when its last reading reached its end, at its start again (as the Strip does); otherwise at its saved position, also in a reread (C1).

Discarded for the read mark: an end block in every chapter (it breaks continuous reading); only at the end of the file (a novel of 1 400 chapters in one file would show none read).

## 4. A file that is one chapter

- Novels open in **Scroll** (product design: "book Pages, novel Scroll"). TalkBack keeps forcing Scroll for every type (decision 11 of step 4).
- Past the end, the end block of the chapter offers the **next chapter**, as the Strip does (contract, section 9); the chapter becomes read there.

## 5. A file that changes edition (E1, B6)

The chapters of a split file are compared **as a whole**, in one transaction, when the file's content changes:

- The same titles in the same order: each chapter keeps its identity and progress, and only its key moves.
- Anything else (a new preface, a title changed, one chapter more or less): the old chapters stay stored with their progress, out of the lists; the new ones start with none, and the Work screen asks "Este arquivo mudou. Seu progresso na edição anterior ia até o cap. N · Aplicar à nova edição" ("This file changed. Your progress in the previous edition went up to ch. N · Apply to the new edition"). Nothing is moved or deleted without that choice.

## 6. A compiled novel from Kavita (B-3)

Kavita sees a file as **one chapter, one record** (milestone C). For a Kavita novel whose file name splits by rule 1, the app shows the chapters of the table of contents, each with its own read mark here, and Kavita keeps the one record:

- The position sent is the file's, as in milestone C: section and marker of the page being read.
- The file is sent as read only at the true end (C-3), when its last chapter is read.
- A read file coming from Kavita marks every chapter of it read here (origin Kavita); a position from Kavita reopens the chapter that holds it.
- The per-chapter marks are not sent: Kavita has nowhere to keep them.

The contract changes in two places: a chapter of a Kavita work is no longer always one Kavita record (section 3), and a Kavita record may stand for several chapters (section 2d). Discarded: showing the compiled file as a single chapter (a long novel would be one entry).

## 7. Tests that change

In the contract's section 7, the round-trip test "given an EPUB with a single section … the section is the same" does not pass as written: Kavita answers success to page 0 with no record and stores nothing, marker included (`ReaderService.cs:229`, Kavita commit `d549d5b1`, read on 31/10/2026; measured in `KavitaEpubFactsTest`). It becomes two tests: "given an EPUB with a single section and no record on Kavita, then the position stays only here and nothing is sent" (decision C-1), and "given sections of different sizes, when a position past the first section goes to Kavita and back, then the section is the same and the position lands at the start of the element the marker names" (measured in milestone C: 0 to 37 words back, never forward).

New logic tests (JVM): given `Novels/X/Cap. 0928.epub`, then one chapter, number 928; given `Novels/X/Vol. 1.epub` or `Novels/X/X.epub`, then it splits; given "Volume 1 → Cap. 1, Cap. 2", then one volume with 2 chapters; given two items with the same destination, then 1 chapter; given empty contents, then 1 chapter and a warning; given an item outside the spine, then it is not a chapter; given reading paged from chapter 3 into 4, then 3 is read; given a jump by the contents from 3 to 10, then 3 is not read; given an EPUB replaced by an edition with a new preface, then the marks do not carry over on their own and the Work screen asks; given the same titles in the same order, then the marks carry over.
