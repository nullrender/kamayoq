package app.kamayoq.android

import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.library.WorkDetails
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadingPosition
import app.kamayoq.core.progress.ReadingState
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.data.EditionOffer
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.StoredAlternative
import app.kamayoq.data.StoredChapter
import app.kamayoq.data.StoredReadingState
import app.kamayoq.data.StoredWork
import app.kamayoq.data.WorkCovers
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class ChapterRow(val chapter: StoredChapter, val progress: ChapterProgress, val downloaded: Boolean = false) {
    val read get() = progress.readMark != null
}

// How far into a chapter its place is, for "Reading · 35 %".
private fun percentOf(position: ReadingPosition): Int = when (position) {
    is StripPosition -> position.page * 100 / position.totalPages
    is TextPosition -> position.fraction.value / 100
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkScreen(
    store: LibraryStore,
    covers: WorkCovers?,
    kavitaSync: KavitaSync?,
    workId: String,
    listState: LazyListState,
    progressVersion: Int,
    readingSince: Long?,
    onNoticed: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onBack: () -> Unit,
) {
    var work by remember(workId) { mutableStateOf<StoredWork?>(null) }
    var chapters by remember(workId) { mutableStateOf<List<ChapterRow>>(emptyList()) }
    var resume by remember(workId) { mutableStateOf<StoredChapter?>(null) }
    var others by remember(workId) { mutableStateOf<List<StoredAlternative>>(emptyList()) }
    var usedOther by remember(workId) { mutableStateOf(0) }
    // The questions of files that changed edition; answering one loads the work again.
    var offers by remember(workId) { mutableStateOf<List<EditionOffer>>(emptyList()) }
    var answered by remember(workId) { mutableStateOf(0) }
    // The question whose "Don't apply" waits for its confirmation.
    var dismissing by remember(workId) { mutableStateOf<EditionOffer?>(null) }
    var readingState by remember(workId) { mutableStateOf(StoredReadingState.NONE) }
    // Raised by each change of state, so the screen reads it again from the store.
    var stateVersion by remember(workId) { mutableStateOf(0) }
    var choosingState by rememberSaveable(workId) { mutableStateOf(false) }
    // Choosing chapters to mark (board SinteseObra, "Selecionar capítulos"); kept as the screen is recreated.
    var selecting by rememberSaveable(workId) { mutableStateOf(false) }
    var selection by rememberSaveable(workId) { mutableStateOf(ArrayList<String>()) }
    var menuOpen by remember(workId) { mutableStateOf(false) }
    // The bar's height, kept free at the list's end so the last chapters can come above it.
    var barHeight by remember(workId) { mutableStateOf(0.dp) }
    // The chapter a long press just chose, to keep in sight above the bar that opens with it.
    var pressed by remember(workId) { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    // Raised by each marking and its Undo, so the chapters are read again from the store.
    var marked by remember(workId) { mutableStateOf(0) }
    var downloadSheet by rememberSaveable(workId) { mutableStateOf(false) }
    // The chapters whose deletion waits for its confirmation; there is no Undo for a deleted file.
    var deleting by remember(workId) { mutableStateOf<List<StoredChapter>?>(null) }
    var details by remember(workId) { mutableStateOf(WorkDetails.NONE) }
    var cover by remember(workId) { mutableStateOf<ImageBitmap?>(null) }
    val notices = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    // The person's choice, said with Undo; the chapters read never change with the state.
    fun choose(picked: ReadingState?) {
        choosingState = false
        val before = readingState.state
        if (picked == before) return
        scope.launch {
            withContext(Dispatchers.IO) { store.setReadingState(workId, picked) }
            stateVersion++
            val message = if (picked == null) resources.getString(R.string.state_cleared)
            else resources.getString(R.string.state_changed, resources.getString(stateName(picked)))
            val answer = notices.showSnackbar(message, actionLabel = resources.getString(R.string.undo), duration = SnackbarDuration.Long)
            if (answer == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) { store.setReadingState(workId, before) }
                stateVersion++
            }
        }
    }
    fun undoAutomatic() {
        scope.launch {
            withContext(Dispatchers.IO) { store.undoAutomatic(workId) }
            stateVersion++
        }
    }
    LaunchedEffect(workId, stateVersion, progressVersion) {
        val loaded = withContext(Dispatchers.IO) { store.readingState(workId) }
        readingState = loaded
        // A change the app made while the reader was open is told once, as the reader closes; the
        // line below stays for the way back after the notice goes.
        val change = loaded.automatic
        val since = readingSince
        if (change != null && since != null && change.at.toEpochMilli() >= since) {
            onNoticed()
            scope.launch {
                val message = resources.getString(R.string.state_auto_notice, resources.getString(stateName(loaded.state)))
                val answer = notices.showSnackbar(message, actionLabel = resources.getString(R.string.undo), duration = SnackbarDuration.Long)
                if (answer == SnackbarResult.ActionPerformed) undoAutomatic()
            }
        }
    }
    fun closeSelection() {
        selecting = false
        selection = ArrayList()
    }
    // A marking said with Undo, which puts back each mark and the state it moved by itself.
    fun mark(ids: List<String>, read: Boolean, message: Int, count: Int) {
        closeSelection()
        scope.launch {
            val change = withContext(Dispatchers.IO) {
                if (read) store.markRead(workId, ids, Instant.now()) else store.markUnread(workId, ids, Instant.now())
            }
            marked++
            stateVersion++
            val answer = notices.showSnackbar(
                resources.getQuantityString(message, count, count),
                actionLabel = resources.getString(R.string.undo),
                duration = SnackbarDuration.Long,
            )
            if (answer == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) { store.undoMarks(change) }
                marked++
                stateVersion++
            }
        }
    }
    fun notify(message: String) {
        scope.launch { notices.showSnackbar(message, duration = SnackbarDuration.Long) }
    }
    // Only what is not on the device yet goes in; asking for what is there says so and does nothing.
    fun download(rows: List<ChapterRow>) {
        downloadSheet = false
        closeSelection()
        val sync = kavitaSync ?: return
        scope.launch {
            val count = withContext(Dispatchers.IO) { sync.download(workId, rows.map { it.chapter }) }
            notify(
                if (count == 0) resources.getString(R.string.download_none_new)
                else resources.getQuantityString(R.plurals.download_started, count, count),
            )
        }
    }
    fun askToDelete(rows: List<ChapterRow>) {
        downloadSheet = false
        closeSelection()
        val there = rows.filter { it.downloaded }
        if (there.isEmpty()) notify(resources.getString(R.string.selection_none_downloaded)) else deleting = there.map { it.chapter }
    }
    // The bar covers the foot of the screen: a chapter pressed there scrolls up just above it.
    LaunchedEffect(pressed, barHeight) {
        val id = pressed ?: return@LaunchedEffect
        if (barHeight == 0.dp) return@LaunchedEffect
        // One frame, so the list has the room the bar needs at its end.
        withFrameNanos { }
        val layout = listState.layoutInfo
        layout.visibleItemsInfo.firstOrNull { it.key == id }?.let { item ->
            val hidden = item.offset + item.size - (layout.viewportSize.height - with(density) { barHeight.roundToPx() })
            if (hidden > 0) listState.scrollBy(hidden.toFloat())
        }
        pressed = null
    }
    // Back closes the selection first and keeps the Work screen, as a sheet does.
    BackHandler(enabled = selecting) { closeSelection() }
    val allDownloads = kavitaSync?.downloads?.collectAsState()
    val downloading = allDownloads?.value?.get(workId)
    LaunchedEffect(workId, progressVersion, usedOther, answered, marked) {
        val loaded = withContext(Dispatchers.IO) {
            Triple(
                store.works().firstOrNull { it.id == workId },
                store.chapters(workId).map { ChapterRow(it, store.progress(it.id), kavitaSync?.isDownloaded(it) == true) },
                store.resumeChapter(workId)?.takeIf { it.present },
            )
        }
        work = loaded.first
        chapters = loaded.second
        resume = loaded.third
        others = withContext(Dispatchers.IO) { store.alternatives(workId) }
        offers = withContext(Dispatchers.IO) { store.editionOffers(workId) }
        details = withContext(Dispatchers.IO) { store.details(workId) }
        // An image that does not decode leaves the provisional cover.
        cover = withContext(Dispatchers.IO) { covers?.file(workId)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    }
    // The title is in the header, beside the cover (board SinteseObra).
    Screen(
        title = "",
        onBack = onBack,
        notices = notices,
        actions = {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(KamayoqIcons.more, contentDescription = stringResource(R.string.more_options), modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // Long press is hard with TalkBack: the menu selects too (DESIGN.md, 10/10).
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.select_chapters)) },
                        onClick = {
                            menuOpen = false
                            selecting = true
                            selection = ArrayList()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mark_all_read)) },
                        onClick = {
                            menuOpen = false
                            mark(chapters.map { it.chapter.id }, read = true, R.plurals.marked_read, chapters.size)
                        },
                    )
                }
            }
        },
    ) {
        // With nothing opened yet, chapters marked read still say where the reading is: it goes on at
        // the first one not read.
        val markedOnly = resume == null && chapters.any { it.read }
        val target = resume ?: (if (markedOnly) chapters.firstOrNull { !it.read } ?: chapters.lastOrNull() else chapters.firstOrNull())?.chapter
        val fromKavita = work?.origin == LibraryStore.KAVITA && kavitaSync != null
        // The two sides went to different chapters: where to reopen is the person's (contract, case 5).
        val crossing = others.firstOrNull { it.reason == LibraryStore.CONFLICT && resume != null && it.chapterId != resume?.id }
        val here = resume
        if (downloadSheet) {
            val resumeAt = chapters.indexOfFirst { it.chapter.id == resume?.id }.coerceAtLeast(0)
            DownloadSheet(
                // "Next" counts from where the reading is, in reading order, leaving out what is read.
                next = chapters.drop(resumeAt).filterNot { it.read },
                unread = chapters.filterNot { it.read },
                all = chapters,
                onPick = { download(it) },
                onDelete = { askToDelete(chapters) },
                onDismiss = { downloadSheet = false },
            )
        }
        deleting?.let { doomed ->
            val bytes = kavitaSync?.downloadedBytes(doomed) ?: 0L
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(pluralStringResource(R.plurals.delete_downloads_title, doomed.size, doomed.size)) },
                text = { Text(stringResource(R.string.delete_downloads_text, Formatter.formatShortFileSize(LocalContext.current, bytes))) },
                confirmButton = {
                    TextButton(onClick = {
                        deleting = null
                        scope.launch {
                            withContext(Dispatchers.IO) { kavitaSync?.deleteDownloads(doomed) }
                            marked++
                            notify(resources.getQuantityString(R.plurals.downloads_deleted, doomed.size, doomed.size))
                        }
                    }) { Text(stringResource(R.string.delete_downloads_confirm, doomed.size)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.delete_downloads_cancel)) }
                },
            )
        }
        if (choosingState) {
            StateSheet(
                current = readingState.state,
                onPick = { choose(it) },
                onDismiss = { choosingState = false },
            )
        }
        // Rule E1: nothing of a file's previous edition passes to the new one without this answer.
        fun answer(change: () -> Unit) {
            scope.launch {
                withContext(Dispatchers.IO) { change() }
                answered++
            }
        }
        // Not applying has no undo and nothing brings the question back: it asks first.
        dismissing?.let { offer ->
            AlertDialog(
                onDismissRequest = { dismissing = null },
                title = { Text(stringResource(R.string.edition_dismiss_title)) },
                text = { Text(stringResource(R.string.edition_dismiss_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        dismissing = null
                        answer { store.dismissEdition(workId, offer.path) }
                    }) { Text(stringResource(R.string.edition_dismiss_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { dismissing = null }) { Text(stringResource(R.string.edition_dismiss_keep)) }
                },
            )
        }
        // One list for the whole screen: the header scrolls away with it, as on the board. The
        // selection's bar lies over the foot of the screen instead of above the chapters, as the
        // board has it: there it pushed the chapter just pressed out from under the finger.
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = if (selecting) barHeight else 0.dp)) {
            work?.let { shown -> item(key = "header") { WorkHeader(shown, details, cover) } }
            item(key = "state") {
                // Apart from the cover above, as the board's actions are, not touching it.
                Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Download only where the origin offers it: a local file is already on the device (FEATURES, part 4).
                    if (fromKavita) {
                        OutlinedButton(onClick = { downloadSheet = true }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                            Text(stringResource(R.string.download))
                        }
                    }
                    StateButton(readingState.state, onClick = { choosingState = true })
                }
            }
            downloading?.let { progress ->
                item(key = "downloads") { DownloadProgress(progress) }
            }
            readingState.automatic?.let { change ->
                item(key = "automatic") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.state_changed_by_itself, stringResource(stateName(readingState.state))),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { undoAutomatic() }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                            val from = change.from
                            Text(if (from == null) stringResource(R.string.state_clear) else stringResource(R.string.state_back_to, stringResource(stateName(from))))
                        }
                    }
                }
            }
            // With everything here read and the state not Completed, the person decides: Completed by
            // itself needs the publication finished at the source (FEATURES, part 3).
            if (chapters.isNotEmpty() && chapters.all { it.read } && readingState.state != ReadingState.COMPLETED) {
                item(key = "all-read") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.all_read), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { choose(ReadingState.COMPLETED) }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                            Text(stringResource(R.string.mark_completed))
                        }
                    }
                }
            }
            if (details.summary.isNotEmpty()) item(key = "summary") { WorkSummary(details.summary) }
            if (details.tags.isNotEmpty()) item(key = "tags") { WorkTags(details.tags) }
            item(key = "continue") {
                if (crossing != null && here != null) {
                    Column {
                        Text(stringResource(R.string.conflict_title), modifier = Modifier.padding(16.dp))
                        OutlinedButton(onClick = { onOpenChapter(here.id) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(stringResource(R.string.conflict_here, here.name))
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { store.useAlternative(workId, crossing) }
                                    onOpenChapter(crossing.chapterId)
                                }
                            },
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(stringResource(R.string.conflict_kavita, crossing.chapterName))
                        }
                    }
                } else if (target != null) {
                    Button(onClick = { onOpenChapter(target.id) }, modifier = Modifier.padding(16.dp)) {
                        val label = if (resume != null || markedOnly) stringResource(R.string.continue_reading, target.name)
                        else stringResource(R.string.start_reading)
                        Text(label)
                    }
                }
            }
            items(others.filter { it !== crossing }) { other ->
                val place = if (other.page >= other.total) stringResource(R.string.position_end)
                else stringResource(R.string.position_page, other.page + 1, other.total)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.other_position, other.chapterName, place)) },
                    trailingContent = {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { store.useAlternative(workId, other) }
                                    usedOther++
                                }
                            },
                        ) { Text(stringResource(R.string.other_position_use)) }
                    },
                )
            }
            items(offers) { offer ->
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val lastRead = offer.lastRead
                    Text(
                        if (lastRead != null) stringResource(R.string.edition_changed, lastRead) else stringResource(R.string.edition_changed_plain),
                        modifier = Modifier.announced(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { answer { store.applyPreviousEdition(workId, offer.path) } }) {
                            Text(stringResource(R.string.edition_apply))
                        }
                        TextButton(onClick = { dismissing = offer }) {
                            Text(stringResource(R.string.edition_dismiss))
                        }
                    }
                }
            }
            items(chapters, key = { it.chapter.id }) { row ->
                // A compiled novel whose file has no table of contents is one chapter, and says why.
                val undivided = row.chapter.origin != LibraryStore.KAVITA && row.chapter.tocKey.isEmpty() &&
                    FolderConvention.splitsByContents(row.chapter.path)
                val place = row.progress.position?.takeIf { !row.read }
                val checked = selecting && row.chapter.id in selection
                ListItem(
                    headlineContent = { Text(row.chapter.name) },
                    supportingContent = if (row.read || place != null || undivided || row.downloaded) {
                        {
                            Column {
                                if (row.read) Text(stringResource(R.string.chapter_read))
                                if (row.downloaded) Text(stringResource(R.string.chapter_downloaded))
                                if (place != null) Text(stringResource(R.string.chapter_reading, percentOf(place)))
                                if (undivided) Text(stringResource(R.string.file_undivided))
                            }
                        }
                    } else {
                        null
                    },
                    leadingContent = if (selecting) {
                        { Checkbox(checked = checked, onCheckedChange = null) }
                    } else {
                        null
                    },
                    // A long press always selects; the action comes after (FEATURES, part 4).
                    modifier = Modifier
                        .combinedClickable(
                            onLongClickLabel = stringResource(R.string.chapter_select),
                            onLongClick = if (selecting) null else {
                                {
                                    selecting = true
                                    selection = arrayListOf(row.chapter.id)
                                    pressed = row.chapter.id
                                }
                            },
                            onClick = {
                                if (!selecting) {
                                    onOpenChapter(row.chapter.id)
                                } else {
                                    selection = ArrayList(if (checked) selection - row.chapter.id else selection + row.chapter.id)
                                }
                            },
                        )
                        .semantics { if (selecting) selected = checked },
                )
            }
        }
        if (selecting) {
            SelectionBar(
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                count = selection.size,
                // "Mark the earlier ones" follows the reading order, which is the list's (FEATURES, part 4).
                earlier = selection.singleOrNull()?.let { one -> chapters.indexOfFirst { it.chapter.id == one } }?.takeIf { it > 0 },
                onClose = { closeSelection() },
                onAll = { selection = ArrayList(chapters.map { it.chapter.id }) },
                onRead = { mark(selection.toList(), read = true, R.plurals.marked_read, selection.size) },
                onUnread = { mark(selection.toList(), read = false, R.plurals.marked_unread, selection.size) },
                onEarlier = { before -> mark(chapters.take(before).map { it.chapter.id }, read = true, R.plurals.marked_previous, before) },
                onDownload = if (fromKavita) {
                    { download(chapters.filter { it.chapter.id in selection }) }
                } else {
                    null
                },
                onDeleteDownloads = if (fromKavita) {
                    { askToDelete(chapters.filter { it.chapter.id in selection }) }
                } else {
                    null
                },
            )
        }
        }
    }
}

/**
 * The colour of the selection's action labels on the bar: the palette's action colour. Material's
 * outlined buttons take the grey of secondary text, which on the bar reads as disabled.
 */
internal fun selectionLabel(palette: Palette): Color = palette.action

// Where the selection's own buttons are, apart from the same words elsewhere on the screen.
internal const val SELECTION_BAR = "selection-bar"

/** The bar of the selection: how many, Select all and close, and what to do with them. */
@Composable
private fun SelectionBar(
    modifier: Modifier,
    count: Int,
    earlier: Int?,
    onClose: () -> Unit,
    onAll: () -> Unit,
    onRead: () -> Unit,
    onUnread: () -> Unit,
    onEarlier: (Int) -> Unit,
    onDownload: (() -> Unit)?,
    onDeleteDownloads: (() -> Unit)?,
) {
    val actions = ButtonDefaults.outlinedButtonColors(contentColor = selectionLabel(LocalPalette.current))
    Surface(modifier = modifier.fillMaxWidth().semantics { testTag = SELECTION_BAR }, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(KamayoqIcons.close, contentDescription = stringResource(R.string.selection_close), modifier = Modifier.size(20.dp))
            }
            Text(
                if (count == 0) stringResource(R.string.selection_none) else pluralStringResource(R.plurals.selection_count, count, count),
                modifier = Modifier.weight(1f).announced(),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onAll, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text(stringResource(R.string.selection_all)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRead, enabled = count > 0, colors = actions, modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)) {
                Text(stringResource(R.string.selection_read))
            }
            OutlinedButton(onClick = onUnread, enabled = count > 0, colors = actions, modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)) {
                Text(stringResource(R.string.selection_unread))
            }
        }
        if (onDownload != null && onDeleteDownloads != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDownload, enabled = count > 0, colors = actions, modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)) {
                    Text(stringResource(R.string.download))
                }
                OutlinedButton(onClick = onDeleteDownloads, enabled = count > 0, colors = actions, modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)) {
                    Text(stringResource(R.string.selection_delete_downloads))
                }
            }
        }
        earlier?.let { before ->
            TextButton(onClick = { onEarlier(before) }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Text(stringResource(R.string.mark_previous_read, before))
            }
        }
    }
    }
}

/** How the downloads asked here go: a bar while they run, and what failed once they end. */
@Composable
private fun DownloadProgress(progress: WorkDownloads) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp).announced()) {
        if (progress.running) {
            Text(
                stringResource(R.string.download_progress, minOf(progress.done + progress.failed + 1, progress.total), progress.total),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { (progress.done + progress.failed).toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        } else if (progress.failed > 0) {
            Text(
                pluralStringResource(R.plurals.download_failed, progress.failed, progress.failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The Download sheet (board SinteseObra): each option says how many would come, leaving out what
 * is already here; the deletion, in the error colour, asks before it deletes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(
    next: List<ChapterRow>,
    unread: List<ChapterRow>,
    all: List<ChapterRow>,
    onPick: (List<ChapterRow>) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.download_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.download_sheet_help),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (next.isNotEmpty()) DownloadOption(stringResource(R.string.download_next, 5), next.take(5), onPick)
            // Next 10 only when it brings more than Next 5 (board, correction of 11/10).
            if (next.size > 5) DownloadOption(stringResource(R.string.download_next, 10), next.take(10), onPick)
            if (unread.isNotEmpty()) DownloadOption(stringResource(R.string.download_unread), unread, onPick)
            DownloadOption(stringResource(R.string.download_all), all, onPick)
            val there = all.count { it.downloaded }
            if (there > 0) {
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                    Text(pluralStringResource(R.plurals.download_delete, there, there), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun DownloadOption(label: String, rows: List<ChapterRow>, onPick: (List<ChapterRow>) -> Unit) {
    val missing = rows.count { !it.downloaded }
    Column(
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp).clickable(role = Role.Button) { onPick(rows) }.padding(vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(
            if (missing == 0) stringResource(R.string.download_nothing_new) else pluralStringResource(R.plurals.download_to_get, missing, missing),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
