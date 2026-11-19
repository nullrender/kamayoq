# Restoring a backup (v1, 18/11/2026)

**Status:** decided. The product choices are the maintainer's, from the v1 interview of 18/11/2026 (`brief-2026-11-18`, item 4, in the private notes); the technical ones below were made while building it, each with its reason. Until now the app only exported (`BackupExport`, decision of 28/10/2026) and `BackupFormat.read` already read formats 1 to 4.

## 1. The maintainer's decisions

- **In v1, a restore works only on an empty app**: a new phone or a reinstalled app. Merging a backup into a library that already has works is wanted, and comes after v1.
- **Other apps' backups** (Mihon, for example) come after v1, starting with research on their format and on how to match the works.
- **The flow:** with the app empty, a "Restore backup" action opens Android's file picker. The app restores the works, the reading progress, the previous editions, the positions kept aside, the state of the sync with Kavita and the Kavita server. Then it asks for the library folder: the works found there meet their stored rows again by path, with what was read. For Kavita it shows the server and asks for the key again: the key is never in the backup (decision 6 of `kavita-step-3-2026-10-30.md`); the rest comes back by itself.

## 2. Technical choices

1. **Empty means no work and no Kavita server stored.** Every other table hangs from a work or a server, so nothing else can be left. A folder already chosen with no works in it still counts as empty: nothing would be lost.
2. **One transaction, refused unless empty.** The check runs inside the same transaction as the writing, so a reading of the folder that ends in between cannot be mixed with the backup. A backup the database refuses (a previous edition pointing to a chapter that is not there, a position kept aside in another work's chapter) leaves the database as it was: half a library would be worse than none.
3. **The identifiers come back as they were** (progress contract, section 3): works, versions and chapters keep the ids in the file, so a later backup, a Kavita agreement and a position kept aside still point to the same rows.
4. **Checked before writing, beyond what the database checks:** the origin is `local` or `kavita`; a Kavita work names a server listed in the file; a local work names none; at most one server (the app keeps one, by design); at most one chapter per work marked as where it reopens. A file that breaks one of these was not written by this app, and guessing would change the reading without a word.
5. **The local works wait for the folder.** The backup leaves out the chosen folder's address (it is not the same on another device), so the restored local versions are stored under a placeholder source, `restored`, which no folder address can be (an address always has a scheme). The first reading of a chosen folder takes them over, in the same transaction as the reading, before matching paths: from there the folder's works meet them by path, as on any reading. A restored work not found in the folder stays stored, out of the list (rule E1).
6. **Two local works at the same path.** A library that changed folder may have one work at the same path in each (the second folder copied from the first). Only one can wait for the new folder: the first in the file's order, which puts the works present in the list first. The others stay stored with their reading, under a placeholder of their own (`restored:` and the version's id), out of the list and never taken over. Nothing is deleted (rule E1).
7. **Every restored chapter with something to protect waits for its content check.** The file has no stamps (size and date are of the old device), so each chapter is stored with an empty stamp, and one with a content hash or a reading is marked to check: on its first opening here, the hash says whether it is the same content (same chapter, same reading) or another edition (rule E1). The same holds for Kavita: its chapters do not join a sync until they are opened once here, as a work that comes back after being gone. This delays sending a position queued on the old phone until its chapter opens; it never sends one for the wrong content.
8. **The order comes from the file.** The file lists works and chapters in the app's order, so the restored order is the position in the file.
9. **The open question of a changed split file does not come back.** It is not in the file (section 8 of `novel-chapters-2026-11-18.md`): the previous edition and its reading do come back, stored; only the question to apply them is gone.
10. **The file is read before the confirmation.** The picker accepts any file type (`*/*`): a backup copied by cable or by a cloud drive may arrive with another type than `application/json`, and hiding it would look like the file is gone. So the content decides: the app reads the file first and only offers to restore one it can read, saying the date of the backup and how many works it has; anything else gets a message. A file over 50 MB is refused before it is read whole: a backup of a large personal library is a few megabytes, and a wrong file picked by mistake (a video) must not run the phone out of memory.
11. **The confirmation asks even on an empty app.** Nothing is lost by restoring, but the person sees the date and the number of works before anything is written, and can still pick another file.
12. **After the restore:** with no folder chosen, the message asks for the folder; with one chosen, the app reads it again at once. With a Kavita server restored and no key saved, the Library says that Kavita needs the key again, instead of saying that Kavita could not be reached, and the Kavita screen opens with the server's address filled in. Connecting to the same account keeps the server's id, so its works stay linked; another account is refused, as before (decision 6 of 02/11/2026).
13. **Where the action is:** on the Library screen, only while the app is empty. The message of the result stays after the action leaves, and TalkBack announces it.

## 3. After the external review (18/11/2026)

The external review (Gemini 4 Neon) found these, each fixed with a failing test first:

- A split file the first reading of the new folder could not open lost its restored chapters: the reading asks for the stored chapters before the import takes the restored library over. The stored split files now include the library still waiting for its folder.
- Choice 4 is wider: an edition chain that does not end (opening would follow it forever), an earlier edition shown as present, a Kavita work or chapter without the ids a sync needs, and two positions aside for the same reason are refused. The check runs before the confirmation too, so choice 10 holds: only a backup that can be restored is offered.
- A document provider failing with an `IllegalStateException` or `IllegalArgumentException` is a file that could not be read, as an `IOException` is.
- Coming back from the Kavita screen reads Kavita again: a key saved there ends the request for it. Whether the app is empty is asked again after a restore and after the Kavita screen. What follows a restore runs once, not again on every screen made after it. The message after a restore with a folder no longer says the folder is being read.

Limits accepted, as they are:

- **Another Kavita server with the same account id.** Besides the account id (1 in every new Kavita), the app tells servers apart by the names of the libraries marked, kept in the app's preferences, not in the database or the backup. After a restore only the account id is checked, until the first import remembers the names again. The address comes filled in with the restored server's, so connecting elsewhere needs typing another address.
- **A very large file.** A file near the 50 MB ceiling is held in memory several times while it is read (bytes, text, JSON tree); on a phone with little memory that may stop the app. A backup of a personal library is a few megabytes.
- **A folder chosen before restoring takes the library over.** Choices 5 and 12 together: a folder already chosen is read at once and its reading takes the restored works over, even when the works are elsewhere. Choosing the right folder later does not find them again (they stay stored, out of the list, rule E1). The confirmation does not warn about it.
- **Two restored works whose paths differ only in letter case**, both waiting for the folder, may meet the folder's work in either order.
- **The first opening of a restored Kavita chapter** asks no conflict question: the chapter waits for its content check (choice 7), and the check runs as it opens. A position from another device is compared at the next sync.
- The confirmation counts the works present in the list when the backup was made; works gone from the folder then also come back, stored, without being counted.

## 4. Not done here

- Merging into a library that has works; other apps' backups (section 1).
- Carrying the open question of a split file in the backup (choice 9).
- Restoring the reader's settings: the backup carries none yet (`epub-step-4-design.md`, out of Milestone A).
