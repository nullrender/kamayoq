# One backup at a time, even after the screen is recreated

Research from 29/10/2026. Question: how to make sure only one backup runs at a time, including when the screen is recreated (for example, when the phone is rotated).

## The problem in Kamayoq (checked in the code, 29/10/2026)
- `MainActivity.onCreate` creates `LibraryDatabase`, `ChosenFolder` and `ChapterOpening` every time the screen is created, including when the phone is rotated (`app/.../MainActivity.kt`).
- The "Back up" button is greyed out by screen state (`remember`), which is lost when the screen is recreated; `BackupExport` does not prevent two backups at the same time.
- The cleanup of the cached copies has the same root cause: each `ChapterOpening` creates its own `ArchiveOpening`, with its own `inUse` list, over the same cache folder (`data/.../ArchiveOpening.kt:86` and `:100`).
- `ArchiveOpening` is in the `data` module, which runs on the JVM. The cleanup problem can be reproduced in a JVM test (two instances over the same folder); it does not need an Android test.

## What the official Android documentation says (read on 29/10/2026)
- ViewModel: keeps state and work across screen recreation ("asynchronous work continues in the ViewModel"), but it is cleared when the screen closes for good, and the work is cancelled. https://developer.android.com/topic/libraries/architecture/viewmodel
- App-wide objects: create them once in a subclass of `Application`, in an "AppContainer" (not a singleton), and pass them to the screens. https://developer.android.com/training/dependency-injection/manual
- Unique work: WorkManager with `enqueueUniqueWork` and `ExistingWorkPolicy.KEEP` "guarantees that you only have one instance of work with a particular name at a time"; the repeated request is ignored. https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work

## How three apps do it (code checked on 29/10/2026)
| App | Repository, branch, commit | How the backup runs | What happens on the second tap |
|---|---|---|---|
| Mihon | `mihonapp/mihon`, `main`, `e588e04` | WorkManager, `BackupCreateWorker.startNow`: `enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.KEEP, …)`, in the foreground with a notification | ignored while the first one runs |
| Kotatsu | `KotatsuApp/Kotatsu`, `devel`, `34f6e52` | foreground service (`BackupService`, based on `CoroutineIntentService`) with a `Mutex` | waits for the first one to finish and runs afterwards |
| AntennaPod | `AntennaPod/AntennaPod`, `develop`, `047e350` | in the screen itself (`ImportExportPreferencesFragment`), with a "please wait" dialog covering the screen | there is none; and leaving the screen (`onStop`) cancels the export |

## Inference (does not come from the sources)
- The two apps that guarantee "one at a time" take the backup out of the screen: the backup belongs to the app, not to the screen. AntennaPod shows the opposite path, the one Kamayoq takes today, and pays for it with the cancellation on leaving.
- WorkManager is a new dependency (Kamayoq does not have it) and brings a notification and the notification permission; for a backup that takes less than a second (50 KB), it seems like more than the problem calls for.
- The path from the official guide that solves both problems together: create the objects once in an `Application` (the "AppContainer"), with the backup there keeping "already writing"; the recreated screen reads that state and shows "Writing the backup…". The "one at a time" rule lives in a class testable on the JVM.
- Small project: fewer pieces is better; WorkManager can come in later, if automatic backup arrives (Mihon uses the same mechanism for the periodic one).
