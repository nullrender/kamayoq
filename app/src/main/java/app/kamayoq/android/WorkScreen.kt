package app.kamayoq.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.progress.ReadingState
import app.kamayoq.data.EditionOffer
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.StoredAlternative
import app.kamayoq.data.StoredChapter
import app.kamayoq.data.StoredReadingState
import app.kamayoq.data.StoredWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class ChapterRow(val chapter: StoredChapter, val read: Boolean)

@Composable
internal fun WorkScreen(
    store: LibraryStore,
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
    LaunchedEffect(workId, progressVersion, usedOther, answered) {
        val loaded = withContext(Dispatchers.IO) {
            Triple(
                store.works().firstOrNull { it.id == workId },
                store.chapters(workId).map { ChapterRow(it, store.progress(it.id).readMark != null) },
                store.resumeChapter(workId)?.takeIf { it.present },
            )
        }
        work = loaded.first
        chapters = loaded.second
        resume = loaded.third
        others = withContext(Dispatchers.IO) { store.alternatives(workId) }
        offers = withContext(Dispatchers.IO) { store.editionOffers(workId) }
    }
    Screen(title = work?.title.orEmpty(), onBack = onBack, notices = notices) {
        val target = resume ?: chapters.firstOrNull()?.chapter
        // The two sides went to different chapters: where to reopen is the person's (contract, case 5).
        val crossing = others.firstOrNull { it.reason == LibraryStore.CONFLICT && resume != null && it.chapterId != resume?.id }
        val here = resume
        if (crossing != null && here != null) {
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
        } else if (target != null) {
            Button(onClick = { onOpenChapter(target.id) }, modifier = Modifier.padding(16.dp)) {
                val label = if (resume != null) stringResource(R.string.continue_reading, target.name)
                else stringResource(R.string.start_reading)
                Text(label)
            }
        }
        StateButton(readingState.state, onClick = { choosingState = true }, modifier = Modifier.padding(horizontal = 16.dp))
        readingState.automatic?.let { change ->
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
        // Completed by itself needs the publication finished, which the app does not know yet: with
        // everything here read, the person decides (FEATURES, part 3).
        if (chapters.isNotEmpty() && chapters.all { it.read } && readingState.state != ReadingState.COMPLETED) {
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
        if (choosingState) {
            StateSheet(
                current = readingState.state,
                onPick = { choose(it) },
                onDismiss = { choosingState = false },
            )
        }
        others.filter { it !== crossing }.forEach { other ->
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
        offers.forEach { offer ->
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
        LazyColumn(state = listState) {
            items(chapters, key = { it.chapter.id }) { row ->
                // A compiled novel whose file has no table of contents is one chapter, and says why.
                val undivided = row.chapter.origin != LibraryStore.KAVITA && row.chapter.tocKey.isEmpty() &&
                    FolderConvention.splitsByContents(row.chapter.path)
                ListItem(
                    headlineContent = { Text(row.chapter.name) },
                    supportingContent = if (row.read || undivided) {
                        {
                            Column {
                                if (row.read) Text(stringResource(R.string.chapter_read))
                                if (undivided) Text(stringResource(R.string.file_undivided))
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.clickable { onOpenChapter(row.chapter.id) },
                )
            }
        }
    }
}
