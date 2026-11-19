package app.kamayoq.android

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kamayoq.core.kavita.KavitaProgress
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.core.progress.strip
import app.kamayoq.core.reader.FileTooLargeException
import app.kamayoq.core.reader.SplitReading
import app.kamayoq.core.reader.TextReading
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.ProgressSaving
import app.kamayoq.data.SplitContents
import app.kamayoq.data.SplitOpening
import app.kamayoq.data.StoredAlternative
import app.kamayoq.data.StoredChapter
import app.kamayoq.data.WholeOpening
import app.kamayoq.data.kavita.KavitaSyncOutcome
import app.kamayoq.data.kavita.knownPlace
import app.kamayoq.data.kavita.placeHere
import app.kamayoq.data.splitOpening
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private sealed interface Opening {
    data object Loading : Opening
    data object Missing : Opening
    data object Failed : Opening
    data object DownloadFailed : Opening
    data object FormatNotYet : Opening
    class TooLarge(val maxBytes: Long) : Opening
    /** [turn] is a Kavita book's reading turn, and [marker] the place there it opens at; both null for a local one. */
    class ReadyText(
        val text: OpenedText,
        val title: String,
        val progress: ChapterProgress,
        val round: Long,
        val turn: Long?,
        val marker: String?,
        val type: WorkType,
        val next: StoredChapter?,
    ) : Opening

    /** A compiled novel: one file read across its chapters; [next] is the chapter after the file. */
    class ReadySplit(
        val text: OpenedText,
        val title: String,
        val split: SplitOpening,
        val round: Long,
        val type: WorkType,
        val next: StoredChapter?,
    ) : Opening

    /** In a book, [sections] says the pages are sections. */
    class Conflict(
        val chapterId: String,
        val localPage: Int,
        val conflict: KavitaSyncOutcome.Conflict,
        val turn: Long,
        val sections: Boolean = false,
    ) : Opening
    class Ready(
        val chapter: OpenedChapter,
        val title: String,
        val next: StoredChapter?,
        val previous: StoredAlternative?,
        val turn: Long,
        val round: Long,
    ) : Opening
}

/**
 * Holds what an opening returned from the moment it returns, shown on screen or not: the screen may
 * go away before it ever shows it. A newer one ends the older; once closed, anything arriving late
 * ends at once.
 */
internal class Owned<T : Any>(private val finish: (T) -> Unit) {
    private var held: T? = null
    private var heldRound = Int.MIN_VALUE
    private var closed = false

    /** [round] says which opening this is: one from an older round than the held one ends at once. */
    @Synchronized
    fun take(round: Int, opening: T) {
        if (closed || round < heldRound) {
            finish(opening)
            return
        }
        held?.let(finish)
        held = opening
        heldRound = round
    }

    @Synchronized
    fun close() {
        closed = true
        held?.let(finish)
        held = null
    }
}

/**
 * One opening at a time: an older one, even cancelled, ends its blocking work before a newer one
 * starts, so it never opens its file or takes its turn after the newer one. The app keeps one, in
 * [ChapterOpening].
 */
class OpeningTurns {
    private val mutex = Mutex()

    // Read and changed only under the mutex.
    private var latest = 0L

    /** [block] gets the round of this opening, for its saves. */
    suspend fun <T> open(block: (Long) -> T): T = mutex.withLock {
        val round = ++latest
        withContext(Dispatchers.IO) { block(round) }
    }

    // Saves run here, not in the screen's scope: leaving must not drop the last page it saw. Default,
    // not IO: the task starts on the caller's thread to take its place in line, and withContext(IO)
    // below only leaves that thread when the dispatcher it asks for differs from this one.
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Runs [block] only while the opening of [round] is the latest: a save of a screen that left,
     * held up in its blocking work, must not land on top of what a newer opening saved, and a newer
     * opening waits for a save under way before it reads the progress. Its place in line is taken
     * at the call, and it keeps it when the caller is cancelled: the screen may leave while its last
     * save waits, and that page must still be the one the chapter reopens on.
     */
    suspend fun ifLatest(round: Long, block: () -> Unit) {
        saves.async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock { if (round == latest) withContext(Dispatchers.IO) { block() } }
        }.await()
    }

    /**
     * The same as [ifLatest] without waiting for it: the reader goes on watching the pages while a
     * slow save waits, so a page reached meanwhile is asked for too and is not lost on leaving. Nobody
     * waits for [block], so it must deal with its own failures; one it throws ends the app.
     */
    fun inLine(round: Long, block: () -> Unit): Job = saves.launch(start = CoroutineStart.UNDISPATCHED) {
        mutex.withLock { if (round == latest) withContext(Dispatchers.IO) { block() } }
    }

    /**
     * Returns once every save asked before it has ended: closing reads the position after them, so it
     * never takes the server's page and then sees an older save land on top.
     */
    suspend fun waitForSaves() = mutex.withLock {}
}

/** Opens a chapter of a work for the Strip, and the next one from its end-of-chapter block. */
@Composable
fun ReaderHost(
    store: LibraryStore,
    opening: ChapterOpening,
    kavita: KavitaSync,
    tree: Uri?,
    deviceId: String,
    workId: String,
    chapterId: String,
    onOpenChapter: (String) -> Unit,
    onBack: () -> Unit,
) {
    var state by remember(chapterId) { mutableStateOf<Opening>(Opening.Loading) }
    // Raised to open the chapter again after a choice that changes where it opens.
    var round by remember(chapterId) { mutableStateOf(0) }
    // In a compiled novel the reading moves on from the chapter that was opened. A screen made again
    // (the phone turned, the process killed) opens the file at the chapter being read, not at that one.
    var readingNow by rememberSaveable(chapterId) { mutableStateOf<String?>(null) }
    // And it goes on with the same opening: where the reading is counted from, and the chapters whose
    // complete read it already counted, or a look back would become a reading and a read count twice.
    var readFromNow by rememberSaveable(chapterId) { mutableStateOf<String?>(null) }
    var countedNow by rememberSaveable(chapterId) { mutableStateOf(arrayListOf<String>()) }
    val scope = rememberCoroutineScope()
    fun thenReopen(change: () -> Unit) {
        state = Opening.Loading
        scope.launch {
            withContext(Dispatchers.IO) { change() }
            round++
        }
    }
    // Leaving the chapter, by Back, Next or the screen going away, lets its file close and its turn end.
    val owned = remember(chapterId) {
        Owned<Opening> { left ->
            when (left) {
                is Opening.Ready -> {
                    left.chapter.release()
                    kavita.finishReading(left.chapter.chapterId, left.turn, opening.turns)
                }
                is Opening.Conflict -> kavita.finishReading(left.chapterId, left.turn, opening.turns)
                is Opening.ReadyText -> {
                    left.text.release()
                    left.turn?.let { kavita.finishReading(left.text.chapterId, it, opening.turns) }
                }
                is Opening.ReadySplit -> left.text.release()
                else -> Unit
            }
        }
    }
    DisposableEffect(owned) {
        onDispose { owned.close() }
    }
    LaunchedEffect(chapterId, round) {
        val thisRound = round
        val screen = coroutineContext.job
        // Owned inside the work, before the result reaches the screen: leaving while it opens drops
        // the result with CancellationException, and leaving right after it never composes it.
        state = try {
            opening.turns.open { turnsRound ->
                openChapter(store, opening, kavita, tree, workId, readingNow ?: chapterId, turnsRound) { screen.isActive }.also { owned.take(thisRound, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChapterMissingException) {
            Opening.Missing
        } catch (e: FileTooLargeException) {
            Opening.TooLarge(e.maxBytes)
        } catch (e: ChapterDownloadException) {
            Log.e("Kamayoq", "Downloading a chapter failed", e)
            Opening.DownloadFailed
        } catch (e: Exception) {
            Log.e("Kamayoq", "Opening a chapter failed", e)
            Opening.Failed
        }
    }
    when (val current = state) {
        // With Back: a long copy must not keep the person here.
        Opening.Loading -> ReaderMessage(stringResource(R.string.reader_opening), onBack)
        Opening.Missing -> ReaderMessage(stringResource(R.string.reader_missing), onBack)
        Opening.Failed -> ReaderMessage(stringResource(R.string.reader_failed), onBack)
        Opening.DownloadFailed -> ReaderMessage(stringResource(R.string.reader_download_failed), onBack)
        Opening.FormatNotYet -> ReaderMessage(stringResource(R.string.reader_format_not_yet), onBack)
        is Opening.TooLarge -> ReaderMessage(stringResource(R.string.reader_too_large, current.maxBytes / (1024 * 1024)), onBack)
        is Opening.ReadyText -> {
            val reading = remember(current) { TextReading(current.progress, deviceId, current.marker) }
            val save = remember(current) { textSaving(store, opening.turns, current.round, current.text.chapterId) { reading.marker } }
            // A novel is read chapter after chapter; a book's volume ends where it ends.
            val end = if (current.type == WorkType.NOVELS) {
                TextEnd.Chapter(current.next?.name) { current.next?.let { onOpenChapter(it.id) } }
            } else {
                TextEnd.Book
            }
            TextReader(current.text.archive, current.title, reading, onSave = save, onBack = onBack, type = current.type, end = end)
        }
        is Opening.ReadySplit -> {
            val ids = current.split.chapterIds
            val reading = remember(current) {
                SplitReading(
                    current.split.saved,
                    current.split.opened,
                    deviceId,
                    readFrom = readFromNow?.let(ids::indexOf)?.takeIf { it >= 0 } ?: current.split.opened,
                    counted = countedNow.map(ids::indexOf).filter { it >= 0 }.toSet(),
                )
            }
            val save = remember(current) { splitSaving(store, opening.turns, current.round, ids) }
            val session = remember(current) {
                SplitText(reading, current.split.chapters) { saves ->
                    readingNow = ids[saves.last().chapter]
                    readFromNow = ids[reading.readingFrom]
                    countedNow = ArrayList(reading.countedChapters.map(ids::get))
                    save(saves)
                }
            }
            val end = TextEnd.Chapter(current.next?.name) { current.next?.let { onOpenChapter(it.id) } }
            TextReader(current.text.archive, current.title, session, onBack = onBack, type = current.type, end = end)
        }
        is Opening.Conflict -> {
            ConflictScreen(
            localPage = current.localPage,
            remotePage = current.conflict.remotePage,
            total = current.conflict.pageCount,
            sections = current.sections,
            onKeepHere = { thenReopen { kavita.keepHere(current.chapterId, current.conflict, current.sections) } },
            onUseKavita = { thenReopen { kavita.useKavita(current.chapterId, current.conflict, current.sections) } },
            onBack = onBack,
            )
        }
        is Opening.Ready -> {
            var noticeOpen by remember(current) { mutableStateOf(true) }
            Box(modifier = Modifier.fillMaxSize()) {
                StripReader(
                    store = store,
                    chapter = current.chapter,
                    turns = opening.turns,
                    round = current.round,
                    title = current.title,
                    nextChapterName = current.next?.name,
                    deviceId = deviceId,
                    onNext = { current.next?.let { onOpenChapter(it.id) } },
                    onBack = onBack,
                )
                val previous = current.previous
                if (previous != null && noticeOpen) {
                    MovedNotice(
                        // At the top: the end of the chapter keeps its button and the save warning below.
                        modifier = Modifier.align(Alignment.TopCenter),
                        onGoBack = { thenReopen { store.useAlternative(workId, previous) } },
                        onClose = {
                            noticeOpen = false
                            scope.launch { withContext(Dispatchers.IO) { store.forgetAlternative(previous) } }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Saves each progress the text reader hands out for [chapterId], in the same line as the Strip's
 * saves, so leaving never drops the last place and a late save never lands on a newer opening. A
 * failed save is logged and the reading goes on. Shared with the test that kills the process.
 * [marker] gives Kavita's marker of the same place, read when the place is handed out.
 */
internal fun textSaving(
    store: LibraryStore,
    turns: OpeningTurns,
    round: Long,
    chapterId: String,
    marker: () -> String? = { null },
): (ChapterProgress) -> Unit {
    val saving = ProgressSaving(store)
    return { progress ->
        val place = marker()
        turns.inLine(round) {
            saving.save(chapterId, progress, place)?.let { Log.e("Kamayoq", "Saving the reading position failed", it) }
        }
    }
}

/**
 * The same as [textSaving] for a file read across its chapters: each move may change the reading of
 * several of them, saved in order, so the chapter being read is the last and the work reopens in it.
 */
internal fun splitSaving(
    store: LibraryStore,
    turns: OpeningTurns,
    round: Long,
    chapterIds: List<String>,
): (List<SplitReading.Saved>) -> Unit {
    val saving = ProgressSaving(store)
    return { saves ->
        turns.inLine(round) {
            for (saved in saves) {
                saving.save(chapterIds[saved.chapter], saved.progress)?.let { Log.e("Kamayoq", "Saving the reading position failed", it) }
            }
        }
    }
}

/**
 * The two positions, neither one chosen, and no reader behind until one is (decision 3). In a book
 * ([sections]) a page is a section, and the total the book read.
 */
@Composable
internal fun ConflictScreen(
    localPage: Int,
    remotePage: Int,
    total: Int,
    onKeepHere: () -> Unit,
    onUseKavita: () -> Unit,
    onBack: () -> Unit,
    sections: Boolean = false,
) {
    @Composable
    fun place(page: Int) = when {
        sections && page >= total -> stringResource(R.string.position_book_end)
        sections -> stringResource(R.string.position_section, page + 1, total)
        page >= total -> stringResource(R.string.position_end)
        else -> stringResource(R.string.position_page, page + 1, total)
    }

    Column(
        // Scrolls: with no reader behind, a button cut off by a large font could not be chosen.
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.conflict_title), style = MaterialTheme.typography.headlineSmall)
        // A book's other place is not kept aside: the positions kept aside count images only.
        Text(stringResource(if (sections) R.string.conflict_hint_book else R.string.conflict_hint))
        OutlinedButton(onClick = onKeepHere, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.conflict_here, place(localPage)))
        }
        OutlinedButton(onClick = onUseKavita, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.conflict_kavita, place(remotePage)))
        }
    }
}

@Composable
private fun MovedNotice(modifier: Modifier, onGoBack: () -> Unit, onClose: () -> Unit) {
    Surface(modifier = modifier.fillMaxWidth().safeDrawingPadding(), tonalElevation = 3.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.moved_notice), modifier = Modifier.announced())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onGoBack) { Text(stringResource(R.string.moved_go_back)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.moved_close)) }
            }
        }
    }
}

private fun openChapter(
    store: LibraryStore,
    opening: ChapterOpening,
    kavita: KavitaSync,
    tree: Uri?,
    workId: String,
    chapterId: String,
    round: Long,
    stillWanted: () -> Boolean,
): Opening {
    // The identifier kept by a recreated screen may be an edition that another one replaced.
    val (current, chapters) = store.openingOf(workId, chapterId)
    val chapter = chapters.firstOrNull { it.id == current } ?: return Opening.Missing
    when (chapter.format) {
        ChapterFormat.EPUB -> {
            val next = chapters.getOrNull(chapters.indexOfFirst { it.id == chapter.id } + 1)
            return if (chapter.origin == LibraryStore.KAVITA) {
                openKavitaText(store, opening, kavita, tree, workId, chapter, round, stillWanted, next)
            } else {
                // The chapter after the file: in a compiled novel the ones in between are in it.
                val afterFile = chapters.dropWhile { it.path != chapter.path }.firstOrNull { it.path != chapter.path }
                openText(store, opening, tree, workId, chapter, round, stillWanted, turn = null, next = next, afterFile = afterFile)
            }
        }
        ChapterFormat.PDF, ChapterFormat.OTHER_ARCHIVE -> return Opening.FormatNotYet
        ChapterFormat.ARCHIVE, ChapterFormat.IMAGE_FOLDER -> Unit
    }
    val turn = kavita.beforeOpening(chapter.id)
    turn.conflict?.let { conflict ->
        val position = store.progress(chapter.id).position?.strip()
        return Opening.Conflict(chapter.id, KavitaProgress.toRemote(position, conflict.pageCount) ?: 0, conflict, turn.token)
    }
    val opened = try {
        opening.open(tree, chapter, stillWanted)
    } catch (e: Exception) {
        // Nothing opened: the turn ends here.
        kavita.finishReading(chapter.id, turn.token, opening.turns)
        throw e
    }
    try {
        // Found by path: when the file changed, the chapter read now has a new identifier.
        val index = chapters.indexOfFirst { it.path == chapter.path }
        val work = store.works().firstOrNull { it.id == workId }
        val title = listOfNotNull(work?.title, chapter.name).joinToString(" · ")
        // Only while the position is still the server's: reading here makes it this device's.
        val moved = store.progress(opened.chapterId).position?.device == KavitaProgress.DEVICE
        val previous = store.alternatives(workId)
            .firstOrNull { moved && it.reason == LibraryStore.PREVIOUS && it.chapterId == opened.chapterId }
        return Opening.Ready(opened, title, chapters.getOrNull(index + 1), previous, turn.token, round)
    } catch (e: Exception) {
        opened.release()
        kavita.finishReading(opened.chapterId, turn.token, opening.turns)
        throw e
    }
}

/** A book from Kavita takes a reading turn, as a CBZ chapter does: a conflict is asked before it opens. */
private fun openKavitaText(
    store: LibraryStore,
    opening: ChapterOpening,
    kavita: KavitaSync,
    tree: Uri?,
    workId: String,
    chapter: StoredChapter,
    round: Long,
    stillWanted: () -> Boolean,
    next: StoredChapter?,
): Opening {
    val turn = kavita.beforeOpening(chapter.id)
    turn.conflict?.let { conflict ->
        val progress = store.progress(chapter.id)
        // The same place the sync compared: the end shows only when the end block queued it.
        val here = store.syncItem(chapter.id)?.placeHere(progress.position as? TextPosition, remoteHasRecord = true)
        return Opening.Conflict(chapter.id, here?.page ?: 0, conflict, turn.token, sections = true)
    }
    return try {
        openText(store, opening, tree, workId, chapter, round, stillWanted, turn.token, next)
    } catch (e: Exception) {
        // Nothing opened: the turn ends here.
        kavita.finishReading(chapter.id, turn.token, opening.turns)
        throw e
    }
}

/** [turn] is the reading turn of a Kavita book, ended when the reader leaves it; null for a local one. */
private fun openText(
    store: LibraryStore,
    opening: ChapterOpening,
    tree: Uri?,
    workId: String,
    chapter: StoredChapter,
    round: Long,
    stillWanted: () -> Boolean,
    turn: Long?,
    next: StoredChapter?,
    afterFile: StoredChapter? = null,
): Opening {
    val text = opening.openText(tree, chapter, stillWanted)
    try {
        val stored = store.works().firstOrNull { it.id == workId }
        val work = stored?.title
        val type = stored?.type ?: WorkType.BOOKS
        // A local file to split is read across its chapters; the file itself says which they are now.
        var whole = text
        var name = chapter.name
        var nextNow = next
        if (turn == null && (chapter.tocKey.isNotEmpty() || FolderConvention.splitsByContents(chapter.path))) {
            when (val file = store.splitOpening(text.chapterId, SplitContents.of(text.archive))) {
                is SplitOpening -> return Opening.ReadySplit(text, work ?: chapter.name, file, round, type, afterFile)
                // A file that lost its division since the folder was read: one chapter took the
                // place of the ones it had, and the reading is that chapter's from here on, with its
                // name and the chapter after it as the work's chapters are now.
                is WholeOpening -> {
                    whole = OpenedText(file.chapterId, text.archive, text.release)
                    name = file.name
                    nextNow = file.next
                }
            }
        }
        // A loose book is a work of one volume with the same name: the name once is enough.
        val title = listOfNotNull(work, name.takeIf { it != work }).joinToString(" · ")
        val progress = store.progress(whole.chapterId)
        // The marker known for the saved place, queued here or agreed with Kavita: it opens a place
        // from Kavita, and goes with the place when the end block saves it with no move first.
        val marker = turn?.let { store.syncItem(whole.chapterId)?.knownPlace(progress.position as? TextPosition)?.marker }
        return Opening.ReadyText(whole, title, progress, round, turn, marker, type, nextNow)
    } catch (e: Exception) {
        text.release()
        throw e
    }
}
