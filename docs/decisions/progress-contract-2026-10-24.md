# Progress contract (version 3, 24/10/2026)

**Status:** version 1 approved on 24/10/2026 (see 6b). Version 2, the same day, settles seven open points, called C1 to C7 here, with three decisions (C1, C4 and C5, section 8). Version 3 settles four more, called D1 to D4 here, with one decision (D3, section 8), and adopts the definition of chapter from the folder convention (`folder-convention-2026-10-24.md`). This is not a database schema: it says **what** the app stores and why; the SQLDelight tables are derived from it.

**Sources:** the rules for work status, for the chapter state "reading, with progress" and for progress sync defined in the app design; the five sample-data cases from the design (section 4); Kavita's code (section 6).

Lines the app shows on screen are quoted in Portuguese, with the English in parentheses.

## 1. What it is for

Storing only "stopped at ch. 928, on such a date" is not enough. That position does not say which earlier chapters were read or unmarked, does not separate rereading from a first reading, and does not say whether the change happened only here, only on Kavita, or on both sides. Example from case 5: in "Release That Witch", this device is at ch. 3 (most recent) and Kavita at ch. 1430 (furthest ahead). With a single position, the app would have to choose on its own, and "furthest ahead wins" would erase the reread.

That is why the app stores **four separate things**. Each one answers a different question.

## 2. The four things

**Position** (used in 2a and 2b), always in the same format:
- image: page number (integer) and total pages;
- text: index of the EPUB section in reading order (integer) and foliate-js's fine locator (the CFI, an address of a passage inside the book, stored as text);
- **fraction read:** **integer from 0 to 10 000** (basis points; 100 = 1 %). Never a decimal number: 1.30 % → 2.30 % is 130 → 230, a difference of exactly 100. foliate-js delivers a decimal fraction; it is converted **on input**, rounded to the nearest integer (maximum error of half a basis point). The end of a chapter is not inferred from the fraction: "read" is a mark of its own (2b);
- **when** (the device's UTC time) and **on which device**.

### 2a. Where I reopen: "which chapter does Continue open?"
One per work and version (phase 1: one version per work).
- **Chapter to reopen:** points to the reading unit (a chapter, or the file for a book in a single EPUB) and uses the position stored in it (2b).
- **Position not chosen:** after a conflict, the losing position stays stored ("Outra posição: cap. N, data · Usar" — "Other position: ch. N, date · Use") until the person moves past both. After a change on one side only, the previous position is also kept, for "Voltar à anterior" ("Back to previous").

### 2b. Reading per chapter: "what have I read, and where did I stop in each one?"
One record per reading unit:
- **read:** yes or no;
- **when** it changed and **where the mark came from**: reading to the end, the person (one or in a batch, "mark previous"), Kavita;
- **partial position** (C3): when the chapter was started and not finished, the position where reading stopped in it. Reading half of 928, jumping to 929 and reading a passage keeps both positions; going back to 928 reopens at the halfway point. This is the chapter's "reading, with progress" state;
- **complete reads, counted here:** adds 1 only when reading reaches the end of the chapter on this device. Marking as read by hand does not add. Coming from Kavita, the number is **not available** (C6: Kavita's `TotalReads` increments when marking by hand, stays 0 on a record created already read, and is not returned by `get-progress`).

The position does not mark or unmark chapters on its own: they are two separate things. Reading ch. 3 of "Release That Witch" does not unmark chs. 4 to 1430, **nor ch. 3 itself** (C1).

### 2c. Work status: "where do I stand with this work?"
- **value:** Reading, Want to Read, On Hold, Dropped, Read, or none;
- **who changed it:** the person or one of the three automatic rules (start, resume, finish);
- **previous value and when**, for the line "Mudou sozinho para Lendo · Voltar a Quero ler" ("Changed by itself to Reading · Back to Want to Read") and for Undo. The line disappears when the person changes the status by hand, including to no status;
- **pause point** (C5, decision in section 8): when entering On Hold, the app stores the resume position at that moment. If there was no position, the point becomes the first position read after the pause. The point survives closing the app, does not change if the person goes back before it, and is erased when the work leaves On Hold.

Alongside the work, two pieces of data the rules read:
- **publication status:** Ongoing, Completed, On Hiatus, Cancelled, or **unknown**;
- **chapters missing at the source:** a number **or unknown**. Unknown never counts as zero.

### 2d. Last synced state: "what did Kavita and I have in common?"
Per work that comes from Kavita:
- **copy of the last agreement**, per chapter: the pages read, the `BookScrollId` and the date, as Kavita had them; plus whether the series was in Want to Read. Alongside, the local CFI that corresponded to that position (C2);
- **changes not yet sent:** a queue (e.g. "ch. 929 marked read at 21:02"), each item with the exact value sent and one of these states: to send, sent without confirmation, confirmed.

**Conflict** = the device changed since the agreement **and** Kavita changed since the agreement **and** the results disagree (C7). Both changing to the same result is not a conflict. If only one side changed, it wins without asking, with a notice and "Voltar à anterior" ("Back to previous").

**Sending and confirmation (C2):**
1. Before sending, the app reads the chapter's current record on Kavita. If it differs from the agreement copy, Kavita changed: the app does not send and treats it as a change on both sides (a conflict, if they disagree).
2. Sends.
3. Rereads the record. If the content is **equal to what was sent**, the agreement copy becomes that value and the item leaves the queue (confirmed). If it is **different**, another device wrote in between: the item stays, the copy does not change, and step 1 applies again. If the reread **fails** (no network), the item stays as "sent without confirmation" and the reread is retried later; the copy does not change until confirmed.

**What "equal" means (D2):** the content of a Kavita record is the page (for text, the section index) and the `BookScrollId`. The date is **not** part of equality, because Kavita always replaces the date sent with its own. After confirming, the app stores in the agreement copy the date Kavita assigned; it serves only to notice, on a later read, that the record was rewritten. Two reads with the same content and different dates are not a content change (and do not create a conflict, C7). The local CFI does not come back from Kavita: it is stored alongside the content sent, and is used when the returned content equals what was sent.

**Limit of the current API (D1):** Kavita does not reject a write made over a record that changed (there is no record version in the request). If another device writes **between step 1 and the send**, the send overwrites that write and the reread returns exactly what was sent: **the loss goes undetected**. Step 3 only detects a write of different content made **after** the send and before the reread. For a person who reads on one device at a time, the window is short and the case is rare; the contract accepts it and promises no more. The future proposal to Kavita (6b) does not solve this on its own: it would need a record version in the request, which goes into 6b as a second proposal.

**Rereading an already-read chapter (C1, decision in section 8):** the app **sends the position to Kavita**, as Kavita's own reader does. Another device resumes the reread from the right point. On Kavita and in other apps, the chapter shows as not finished until reading reaches the end again. Here the read mark stays: the agreement copy records that this "partial" was sent by the app itself during a reread, and when rereading Kavita this value does not unmark the chapter. A "partial" different from what the app sent is a change from the other side and follows the conflict rule.

## 3. How work and chapter are identified

- **Work:** an identifier generated by the app, which never changes. The source is not the work's identity.
- **Version (where the content comes from):** source + identifier in it. Kavita: server + series id. Local folder: the work's folder (exact form in the folder convention). In phase 1 there is one version per work, but **progress is stored per work and version** from now on, so that the Versions drawer in phase 2 does not require redoing everything.
- **Chapter (reading unit):** Kavita: chapter id. A Kavita chapter that disappears comes back with another id and without progress (measured on 30/10/2026): the position stays stored, out of the lists, and relinks by itself when the Kavita series, the chapter number and the total pages match; otherwise, it stays stored, as in rule E1 (decided on 30/10/2026). Local, by the folder convention: a file, a folder of images, or **an item of a file's table of contents** (compiled novel EPUB: each item in the last level of the table of contents becomes a chapter). Each chapter has **its own identity, which does not change**; the file (with the revision of its complete content) and the position in the table of contents are only where it is now. If the file changes edition, the matching follows rule E1 of the folder convention: automatic only with equal titles in the same order; otherwise, the old progress stays stored until the person decides. A novel shows chapters, grouped by volume when known. Recognizing a file that came back requires complete content, not a piece.

## 4. The five cases

| Case | Where I reopen | Reading per chapter | Work status | Last agreement with Kavita |
|---|---|---|---|---|
| 1. "Omniscient Reader's Viewpoint" (novel, Kavita) | ch. 928 | 1–927 read (assumption: the sample case does not say); 928 not read, partial position at the halfway point (section, CFI, 5 000); 929 not read, old; 930–931 not read and new | Reading; publication Ongoing, so "missing" does not enter the finish rule | per chapter, pages read, `BookScrollId` and date for the 931; Want to Read = no |
| 2. Dom Casmurro (book, local EPUB) | the file | the book: not read, partial position at 1 900 (19 %) | whatever the person chose, or Reading by the "start" rule (went past 2 %, hypothesis); publication and missing: unknown | does not exist (no Kavita) |
| 3. "Gantz" (comic, Kavita) | ch. 170 | 170 of 170 read | Reading; publication Completed; missing: 9. **Does not become Read** | copy of the 170 |
| 4. Mixed queue | — | — | — | — (the download queue is not progress; it is outside this contract) |
| 5. "Release That Witch" (novel, reread in conflict) | here: ch. 3 | 1–1430 read; ch. 3 stays read, with the reread's partial position (10/10, 20:14); complete reads coming from Kavita: not available | **Read** stays: "start" only acts on works with no status or in Want to Read | sample agreement, 05/10: ch. 1430 with 20 of 40 pages. Afterwards, Kavita changed (ch. 1430 with 40 of 40, 08/10, another device) and here changed (reread of ch. 3, 10/10): both changed and disagree on where to reopen, so it is a **conflict**, shown before opening, with no option preselected |

## 5. The automatic rules reading this data

Each rule says **which event** authorizes it (C4, decision in section 8). An event is a real change: position forward, a new read mark, or reading from another device brought by sync. Repeating a mark that already existed, a sync that brings the same progress, and a change only to publication or to missing chapters **are not events**.

| Rule | Acts on | Event that authorizes it | Fires when | Never fires |
|---|---|---|---|---|
| Start → Reading | no status, Want to Read | forward reading, or marking as read (one or batch) | progress goes **beyond** the end of the 1st chapter, or 200 basis points (2 %) in a book | unmarking; stopping exactly at the end of the 1st chapter |
| Resume → Reading | On Hold | forward reading, on this device or coming from another (D3) | since the **pause point** (2c), finished a chapter beyond it, or advanced 100 basis points (1 %), adding up several days of reading | going backwards; marking by hand done on this device; no pause point yet (the first position read becomes the point) |
| Finish → Read | any status except Read | an event that **completes the last available chapter**: finishing reading it, marking it read alone or in a batch that includes it, or that reading coming from another device | and the publication is Completed and missing = 0, known | unmarking any chapter; a batch that does not include the last one; publication Cancelled, Ongoing or unknown; missing unknown; arrival of the information that the publication ended (the Work page only shows "Publicação concluída · você leu tudo · Marcar como Lido" — "Publication completed · you read everything · Mark as Read") |

**Progress coming from another device (D3, decision in section 8):** Kavita stores finishing reading and marking as read in the same field, so the app **does not know** whether incoming progress was reading or marking by hand. The contract treats all progress that arrives through sync as reading, also for "resume". The possible error (a mark by hand on another device taking the work out of On Hold on this one) comes with a notice, Undo and the line "Mudou sozinho para Lendo · Voltar a Em pausa" ("Changed by itself to Reading · Back to On Hold").

Every automatic change records "who changed = rule" and the previous value (2c). The thresholds (end of the 1st chapter, 2 %, 1 %) remain a hypothesis to test on the test device.

**Decided on 24/10/2026:** reading done by the same person on another device and brought by sync (Kavita today; a file in a folder, if it comes) counts as the person's action; only a change to publication or to missing chapters, without reading, is left out. "Same person" = every device connected to the same Kavita account or to the same sync file.

## 6. Out of scope and pending

**Outside this contract:** full history (phase 2), statistics (phase 2), multiple versions (phase 2; only the key is ready), download queue, work metadata (decision of 24/10: the app fetches and stores the best version).

**Checked in Kavita's code** (github.com/Kareadita/Kavita, default branch `develop`, commit `d549d5b1609086c8190b9bee6eb48ebfed71e1df`, read on 24/10/2026):
- progress is **per chapter and per person**: pages read (integer), `BookScrollId`, date (`LastModifiedUtc`) and `TotalReads` (`Kavita.Models/Entities/Progress/AppUserProgress.cs`);
- chapter read = pages read equal to the total (`ReaderService.MarkChaptersAsRead`, which writes `PagesRead = chapter.Pages`); unmarking writes 0; saving progress writes the page sent **in the same field** (`SaveReadingProgress`). That is why, in Kavita, rereading and the read mark compete for the same number (C1);
- for EPUB, "page" is **the position in the book's reading order** (`BookService.GetNumberOfPages` counts `GetReadingOrder()`);
- Kavita **ignores the date the app sends**: `SaveReadingProgress` does not copy the request's `LastModifiedUtc`; the date is the server's at the moment it receives it. It also writes nothing if the page and the bookmark are the same;
- send: `POST /api/Reader/progress`; read: `GET /api/Reader/get-progress` (returns page, `BookScrollId` and date, without `TotalReads`); mark: `mark-chapter-read`, `mark-multiple-read` and others; Want to Read: `WantToReadController` (`add-series`, `remove-series`).

**Also noted for the same commit, not rechecked:** the `BookScrollId` is an XPath (a path to an element of the document), not just an HTML `id` (`UI/Web/src/app/book-reader/_components/book-reader/book-reader.component.ts:1879`); the server date is assigned in `Kavita.Database/DataContext.cs:705`; the KOReader conversion discards fine offsets (`Kavita.Services/Helpers/KoreaderHelper.cs:85`).

**Consequences (inference):** the EPUB section passes exactly in both directions; the position inside the section does not (CFI ↔ XPath loses precision). The contract stores the CFI here and sends Kavita the section and bookmark. The real precision can only be measured with the round-trip test; until then, no app text promises "page by page". A reading done offline and sent two days later shows up on Kavita with the date it was sent; the date shown in the conflict ("Neste aparelho · 10/10 20h14" — "On this device · 10/10 20:14") is the local time of the reading, stored here.

**Pending:** how Kavita represents "chapters missing" (`Ended`, not yet checked in Kavita); the exact correspondence between the section index of foliate-js (Readest fork) and Kavita's; testing sending and confirmation (2d) with a real server.

## 6b. Future proposal to Kavita

The app does not depend on any change in Kavita: it works with its format as it is. Kavita already converts KOReader progress to section + bookmark (`Kavita.Services/KoreaderService.cs`, same commit), so lossy conversion is accepted practice there.

- **What to propose (one thing only):** a "client position" field, a text that Kavita stores per chapter and returns without interpreting. Two devices with Kamayoq would return to the exact passage; nothing changes for other apps. It would also solve part of C1 (rereading and the read mark in the same field).
- **Second proposal (D1):** the progress request carries the version (or the date) of the record the app read, and Kavita rejects the write if the record changed since then. Closes the undetected loss in the window between reading and sending.
- **Principle (24/10):** every proposal or fix to Kavita must serve all Kavita clients, not only Kamayoq; nothing tailor-made for our app.
- **How (24/10):** besides suggesting, we can try to make the change ourselves and propose a PR, after talking with them.
- **What not to propose:** accepting the device's date. A wrong phone clock would make an old reading win over a new one; the contract already stores the local time here.
- **When:** after the Kavita path exists and the round-trip test measures how much is lost. Before that, look for similar requests in the issues (the search for "cfi" on 24/10 found nothing, which does not prove absence) and in their Discord; open a conversation before sending code, as their CONTRIBUTING.md asks.

Decided on 24/10/2026: the contract approved with the four choices (fraction as an integer from 0 to 10 000; progress per work and version; position separate from the read mark; rules only on real change) and this section.

## 7. How to check

**Automated logic tests (JVM, names in Portuguese):**

*Statuses and events*
- given "Omniscient Reader's Viewpoint" in Reading, when sync brings the same progress, then nothing changes in the status;
- given a work in Want to Read chosen by the person, when sync brings no progress, then it stays Want to Read;
- given a work with no status, when progress stops exactly at the end of chapter 1, then it stays with no status; when it goes past it, then it goes to Reading with a notice;
- given a book with no status, when the fraction reaches 199, then it stays with no status; at 200, it stays with no status; at 201, it goes to Reading;
- given a work On Hold with pause point at 130, when reading goes to 140, 150 … 230 over several days, then it goes back to Reading on reaching 230; at 229, it stays On Hold;
- given a work On Hold, when the person goes back before the pause point and then advances, then the count continues from the pause point;
- given a work paused with no position, when the first position arrives, then it becomes the pause point and the work stays On Hold;
- given a work On Hold, when the app is closed and reopened, then the pause point stays the same;
- given "Gantz" with 170 of 170 read and 9 missing, then it stays Reading;
- given a Completed work with everything read and missing unknown, then it stays Reading;
- given a Cancelled work with everything read, then it stays Reading and the Work page offers Mark as Read;
- given a Completed work, with no missing chapters, last chapter read and the person in Want to Read, when they unmark an earlier chapter, then it stays Want to Read;
- given the same, when they unmark the last one, then it stays Want to Read;
- given a Completed work with no missing chapters, in Reading, with the last chapter not yet read, when the person batch-marks a range that includes the last one, then it goes to Read with a notice; when the batch does not include the last one, then the finish rule does not fire;
- given a work with no status, when the person batch-marks chapters 1 to 5, then it goes to Reading by the start rule;
- given a chapter already read, when the person marks it as read again, then nothing changes;
- given a work read in full while Ongoing, when sync reports Completed and no missing chapters, then it stays Reading and the Work page shows "Publicação concluída · você leu tudo · Marcar como Lido";
- given a work that changed by itself to Reading, when the person chooses no status, then the "Mudou sozinho" ("Changed by itself") line disappears;
- given the first load from Kavita, when the series is in Want to Read with no progress, then it enters Want to Read; outside Want to Read and with no progress, then it enters with no status; with progress beyond chapter 1, then it enters Reading; with everything read, publication Completed and nothing missing, then it enters Read;
- given a work with unknown publication status and everything read, then it stays Reading;
- given a work On Hold, when one more completed chapter arrives from Kavita, then it goes back to Reading with a notice and "Voltar a Em pausa" ("Back to On Hold") (D3);
- given a work On Hold, when the person marks by hand on this device a chapter beyond the pause point, then it stays On Hold;
- given a work On Hold at chapter 200, when the person finishes reading 201, then it goes back to Reading;
- given a work that left On Hold and was paused again, then the pause point is that of the new pause;
- given a Completed work with no missing chapters, when the last chapter read on the other device arrives from Kavita, then it goes to Read; when the same sync arrives again, then nothing changes;
- given a chapter read to the end on this device, then complete reads add 1; marked by hand, then they do not add; the same sync repeated, then it does not add;

*Positions and rereading*
- given chapter 928 read halfway, when the person reads a passage of 929 and goes back to 928, then 928 reopens at the halfway point, also after closing the app;
- given "Release That Witch" Read, when the person rereads chapter 3, then it stays Read and all chapters, including 3, stay read;
- given the reread of chapter 3 sent to Kavita, when the app rereads Kavita and finds the same partial value it sent, then chapter 3 stays read here;
- given the reread of chapter 3 sent, when Kavita returns a partial different from the one sent, then it is a change from the other side;
- given a book reading with a decimal fraction coming from foliate-js, when it is converted, then it becomes the nearest integer; the start is 0, the end is 10 000, and missing or invalid input is rejected, it does not become zero;
- given an EPUB with a single section, and another with sections of different sizes, when the position goes to Kavita and back, then the section is the same and the position lands on the nearest bookmark (round-trip test with controlled content; it measures the loss, it does not hide it);
- given a compiled novel EPUB with a table of contents of 3 items, then there are 3 chapters with separate read marks.

*Sync*
- given the agreement, when both sides changed and the results disagree, then it is a conflict;
- given the agreement, when both sides changed to the same result, then it is not a conflict;
- given the agreement, when only Kavita changed, then Kavita wins without asking, with a notice and "Voltar à anterior"; when only the device changed, then the device wins; when neither changed, then nothing happens;
- given the same page on Kavita with a `BookScrollId` different from the agreement, then it is a change from Kavita;
- given a resolved conflict, then the position not chosen stays available on the Work page until the person moves past both;
- given a send, when the reread returns what was sent, then the agreement is updated and the item leaves the queue;
- given a send, when another device writes different content after the send and before the reread, then the item stays in the queue and the agreement does not change;
- given a send, when another device writes the same content after the send, then the send is confirmed;
- given a send, when another device writes between the read in step 1 and the send, then the other device's write is lost without being detected (limit D1; the test documents the limit, it does not hide it);
- given a confirmed send, when Kavita returns the same content with a date different from the one sent, then it is confirmed and Kavita's date goes into the agreement copy;
- given two reads from Kavita with the same content and different dates, then there is no content change;
- given a send received by Kavita, when the reread fails, then the item stays as "sent without confirmation" and the agreement does not change;
- given Kavita, when the read counter comes from the server, then it stays as not available.

**Android tests (required at the boundaries):** close the app in the middle of a chapter, kill the process, open and return to the same place; the same after updating the app; two partial chapters preserved after killing the process.

**On the test device:** open Dom Casmurro, read to a point, close, reopen: it returns to the same passage. When Kavita is there: round trip of a text position, measuring where it lands.

## 8. Decisions on the open points (24/10/2026)

- **C1, rereading on Kavita:** the app sends the reread position to Kavita and keeps the read mark here. Discarded: not sending the reread position (another device would not know where the reading is); asking on the first reread (one more screen and one more decision).
- **C4, what authorizes "finish":** only a reading event that completes the last available chapter; unmarking never changes the status; a publication that ends later only produces the suggestion "Marcar como Lido" ("Mark as Read"). Discarded: also changing when the new information arrives (status changed in the middle of the night, without reading); "finish" never automatic.
- **C5, where "resume" counts from:** from the point where the work went On Hold, stored. Discarded: counting per session (the defect of version 1); any reading taking it off hold (opening it to check one sentence would already do it).
- **Technical, no matter of taste:** C2 (sending and confirmation), C3 (partial position per chapter), C6 (Kavita's read counter not available), C7 (conflict requires disagreement).
- **D3, progress coming from another device:** counts as reading, also for "resume". Discarded: only reading on this device takes it off hold (ten chapters read on another device would not); only partial progress from outside counts (fails when the whole chapter is read before syncing).
- **Technical, version 3:** D1 (the overwrite before sending goes undetected, declared as a limit), D2 (what "equal" means: page/section and bookmark, without the date), D4 (test names and missing cases).
- **Folder convention (24/10):** a chapter can be an item of a file's table of contents; a novel shows chapters grouped by volume.

## 9. Tira (Strip): position and end of chapter (25/10/2026)

**Decided on 25/10/2026**, in step 2 of the skeleton. Compared with Mihon (github.com/mihonapp/mihon, `main`, commit `d001e6f210e6a077a30dd9a1392573a3225bba2f`, `app/.../ui/reader/ReaderViewModel.kt`, lines 300–316 and 651–705, read on 25/10/2026): there, the chapter becomes read when the last image is selected, and a read chapter always reopens from the start.

- **When it marks read:** only when the **end-of-chapter block** appears, not on the last image (usage report: Mihon sometimes does not mark). Closing while looking at the last image leaves the chapter not read, and it reopens on that image. Discarded: marking on the last image, as Mihon does.
- **Where it opens:** from the first image if it was never opened or if the last reading reached the end block; in any other case, read or not, where reading stopped. An interrupted reread returns to the reread's point and the chapter stays read (C1). Unlike Mihon, where a read chapter always opens from the start.
- **Position in the Strip (technical):** the image counted from 0 and the total images; position equal to the total means "at the end block". Stored on every change, also after the end. If the stored total is not the file's current one, it opens on the first image (matching with the new file is rule E1, at import).
- **Complete reads (technical):** adds 1 the first time the end block appears in each opening of the chapter; scrolling up and down at the end does not add again. The read mark that already existed (date and origin) does not change.
