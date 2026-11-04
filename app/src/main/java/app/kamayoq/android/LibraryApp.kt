package app.kamayoq.android

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.kamayoq.core.library.IgnoreReason
import app.kamayoq.core.library.IgnoredFile
import app.kamayoq.data.BackupExport
import app.kamayoq.data.BackupResult
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.StoredAlternative
import app.kamayoq.data.StoredChapter
import app.kamayoq.data.StoredWork
import app.kamayoq.data.kavita.StoredKavitaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

private const val TAG = "Kamayoq"

private data class WorkRow(val work: StoredWork, val chapterCount: Int)

/**
 * The first percurso's screens: choose the folder, see its works, see a work's chapters and the
 * files the folder convention left out. The folder is read again each time the app opens.
 */
@Composable
fun LibraryApp(
    store: LibraryStore,
    folder: ChosenFolder,
    opening: ChapterOpening,
    backups: Backups,
    kavita: KavitaAccess,
    kavitaSync: KavitaSync,
    deviceId: String,
) {
    val scope = rememberCoroutineScope()
    var tree by remember { mutableStateOf(folder.saved()) }
    var accessLost by remember { mutableStateOf(false) }
    var accessRefused by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanFailed by remember { mutableStateOf(false) }
    var kavitaFailed by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<WorkRow>?>(null) }
    var ignored by rememberSaveable(stateSaver = IGNORED_SAVER) { mutableStateOf<List<IgnoredFile>>(emptyList()) }
    var openWorkId by rememberSaveable { mutableStateOf<String?>(null) }
    var showIgnored by rememberSaveable { mutableStateOf(false) }
    var showKavita by rememberSaveable { mutableStateOf(false) }
    var readingChapterId by rememberSaveable { mutableStateOf<String?>(null) }
    // Raised when the reader closes, so the work's chapters show the new progress.
    var progressVersion by remember { mutableStateOf(0) }
    // Kept here, above the screens: a screen that leaves takes its list position with it, and Back
    // must find it again (AGENTS.md).
    val libraryList = rememberLazyListState()
    val chapterList = rememberSaveable(openWorkId, saver = LazyListState.Saver) { LazyListState() }
    val context = LocalContext.current

    fun backUp(current: Uri) {
        // The app's context: the backup may outlive this screen.
        val app = context.applicationContext
        backups.start {
            val (place, result) = try {
                withContext(Dispatchers.IO) {
                    // The name first: once a backup is written, nothing after it may fail.
                    val place = folder.name(current)
                    place to BackupExport(store, BackupsFolder(app, folder, current))
                        .run(appVersion(app), Instant.now(), ZoneId.systemDefault())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The folder's name or the database could not be read, or a backup that did not
                // match could not be deleted.
                null to BackupResult.WriteFailed(e)
            }
            if (result is BackupResult.WriteFailed) Log.e(TAG, "Writing the backup failed", result.cause)
            place to result
        }
    }

    suspend fun refresh(current: Uri) {
        // A reading that ends after a newer one started changes nothing, on screen or in the store.
        val round = folder.readings.incrementAndGet()
        val latest = { folder.readings.get() == round }
        scanning = true
        scanFailed = false
        try {
            val (left, loaded) = withContext(Dispatchers.IO) {
                // A folder no longer chosen keeps its works and progress, out of the list (rule E1).
                store.keepOnlyFolder(current.toString(), latest)
                LibraryScan.run(folder, current, store, latest) to loadRows(store)
            }
            if (!latest()) return
            ignored = left
            rows = loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Reading the chosen folder failed", e)
            if (latest()) {
                scanFailed = true
                // The folder just left is already out of the store: a list kept from before would
                // show works that open empty.
                rows = withContext(Dispatchers.IO) { loadRows(store) }
            }
        } finally {
            if (latest()) scanning = false
        }
    }

    // The works stay as the last reading that worked: a server out of reach takes nothing away.
    suspend fun importKavita() {
        kavitaFailed = false
        try {
            rows = withContext(Dispatchers.IO) {
                kavitaSync.importNow()
                loadRows(store)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Reading Kavita failed", e)
            kavitaFailed = true
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { chosen ->
        if (chosen != null) {
            try {
                folder.choose(chosen)
            } catch (e: SecurityException) {
                // This used to close the app.
                Log.e(TAG, "Android kept no access to the chosen folder", e)
                accessRefused = true
                return@rememberLauncherForActivityResult
            }
            tree = chosen
            accessLost = false
            accessRefused = false
            scope.launch { refresh(chosen) }
        }
    }

    LaunchedEffect(Unit) {
        val stored = withContext(Dispatchers.IO) {
            opening.clearLeftovers()
            loadRows(store)
        }
        // The reading of the folder runs at the same time and may have ended first, with fresher works.
        if (rows == null) rows = stored
    }

    // At every return, not only when the screen is made: Back only sends the app to the background
    // since Android 12, and the product design wants the folder read each time the app opens.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        scope.launch { importKavita() }
        val saved = tree ?: return@LifecycleEventEffect
        if (folder.canRead(saved)) scope.launch { refresh(saved) } else accessLost = true
    }

    // A sync may finish after the screen that asked for it is gone, and it changes what any screen shows.
    val kavitaChanges by kavitaSync.changes.collectAsState()
    LaunchedEffect(kavitaChanges) {
        if (kavitaChanges > 0) {
            rows = withContext(Dispatchers.IO) { loadRows(store) }
            progressVersion++
        }
    }

    val workId = openWorkId
    val chapterId = readingChapterId
    val currentTree = tree
    when {
        workId != null && chapterId != null -> {
            val close: () -> Unit = {
                readingChapterId = null
                progressVersion++
            }
            BackHandler(onBack = close)
            ReaderHost(
                store = store,
                opening = opening,
                kavita = kavitaSync,
                tree = currentTree,
                deviceId = deviceId,
                workId = workId,
                chapterId = chapterId,
                onOpenChapter = { readingChapterId = it },
                onBack = close,
            )
        }
        workId != null -> {
            BackHandler { openWorkId = null }
            WorkScreen(
                store = store,
                workId = workId,
                listState = chapterList,
                progressVersion = progressVersion,
                onOpenChapter = { readingChapterId = it },
                onBack = { openWorkId = null },
            )
        }
        showIgnored -> {
            BackHandler { showIgnored = false }
            IgnoredScreen(ignored, onBack = { showIgnored = false })
        }
        showKavita -> {
            val closeKavita: () -> Unit = {
                kavita.keyDraft.value = ""
                kavita.addressDraft.value = ""
                showKavita = false
            }
            BackHandler(onBack = closeKavita)
            // Off the main thread: the Android docs warn that Keystore calls may take a while.
            // The address comes from the same file as the key: the two were saved as one pair.
            val saved by produceState<Pair<StoredKavitaKey, String>?>(null) {
                val stored = withContext(Dispatchers.IO) { kavita.savedForm() }
                // Filled as a pair, and only when nothing was typed: half of one with half of another
                // would send a key to an address it was not made for.
                val (key, address) = stored
                if (kavita.keyDraft.value.isEmpty() && kavita.addressDraft.value.isEmpty()) {
                    kavita.addressDraft.value = address
                    if (key is StoredKavitaKey.Present) kavita.keyDraft.value = key.key
                }
                value = stored
            }
            saved?.let { (storedKey, address) ->
                KavitaScreen(
                    initialAddress = address,
                    storedKey = storedKey,
                    connect = { address, key -> withContext(Dispatchers.IO) { kavitaSync.connect(address, key) } },
                    onBack = closeKavita,
                    keyDraft = kavita.keyDraft,
                    libraries = kavitaSync,
                    addressDraft = kavita.addressDraft,
                )
            }
        }
        else -> LibraryScreen(
            rows = rows,
            ignoredCount = ignored.size,
            hasFolder = tree != null,
            accessLost = accessLost,
            accessRefused = accessRefused,
            scanning = scanning,
            scanFailed = scanFailed,
            kavitaFailed = kavitaFailed,
            backingUp = backups.running,
            backupResult = backups.result,
            backupPlace = backups.place,
            listState = libraryList,
            onBackUp = { currentTree?.let(::backUp) },
            onChooseFolder = { picker.launch(null) },
            onOpenWork = { openWorkId = it },
            onOpenIgnored = { showIgnored = true },
            onOpenKavita = { showKavita = true },
        )
    }
}

/** For a message that appears or changes by itself: TalkBack reads it out. */
internal fun Modifier.announced(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

// Two texts per file: a recreated screen keeps the list even if the next reading fails.
private val IGNORED_SAVER = listSaver<List<IgnoredFile>, String>(
    save = { files -> files.flatMap { listOf(it.path, it.reason.name) } },
    restore = { texts -> texts.chunked(2).map { (path, reason) -> IgnoredFile(path, IgnoreReason.valueOf(reason)) } },
)

private fun loadRows(store: LibraryStore): List<WorkRow> =
    store.works().map { WorkRow(it, store.chapters(it.id).size) }

/** The installed version, such as `0.1.0-r102`, written in the backup. */
private fun appVersion(context: Context): String {
    val manager = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        manager.getPackageInfo(context.packageName, 0)
    }
    return checkNotNull(info.versionName) { "The app has no version name" }
}

@Composable
private fun LibraryScreen(
    rows: List<WorkRow>?,
    ignoredCount: Int,
    hasFolder: Boolean,
    accessLost: Boolean,
    accessRefused: Boolean,
    scanning: Boolean,
    scanFailed: Boolean,
    kavitaFailed: Boolean,
    backingUp: Boolean,
    backupResult: BackupResult?,
    backupPlace: String,
    listState: LazyListState,
    onBackUp: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenWork: (String) -> Unit,
    onOpenIgnored: () -> Unit,
    onOpenKavita: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineMedium)
            if (hasFolder && !accessLost && !accessRefused) {
                TextButton(onClick = onChooseFolder) { Text(stringResource(R.string.change_folder)) }
            }
        }
        // Here until the Settings screen exists (its place there: Settings › Sources › Kavita).
        TextButton(onClick = onOpenKavita, modifier = Modifier.padding(start = 4.dp)) {
            Text(stringResource(R.string.kavita_connect))
        }
        if (kavitaFailed) {
            Text(
                stringResource(R.string.kavita_read_failed),
                modifier = Modifier.padding(16.dp).announced(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Without a readable folder only Kavita's works can open; the local ones wait, stored.
        val kavitaRows = rows.orEmpty().filter { it.work.origin == LibraryStore.KAVITA }
        when {
            accessRefused -> {
                Notice(R.string.folder_access_refused, onChooseFolder)
                WorkList(kavitaRows, 0, listState, onOpenWork, onOpenIgnored)
            }
            !hasFolder -> {
                Notice(R.string.no_folder, onChooseFolder)
                WorkList(kavitaRows, 0, listState, onOpenWork, onOpenIgnored)
            }
            accessLost -> {
                Notice(R.string.folder_access_lost, onChooseFolder)
                WorkList(kavitaRows, 0, listState, onOpenWork, onOpenIgnored)
            }
            else -> {
                // Here until the Settings screen exists (its place there: Settings › Backup).
                BackupRow(backingUp, backupResult, backupPlace, onBackUp)
                if (scanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Text(
                        stringResource(R.string.reading_folder),
                        modifier = Modifier.padding(16.dp).announced(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (scanFailed) {
                    Text(
                        stringResource(R.string.reading_folder_failed),
                        modifier = Modifier.padding(16.dp).announced(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!scanning && rows != null && rows.isEmpty()) {
                    Text(
                        stringResource(R.string.no_works_found),
                        modifier = Modifier.padding(16.dp).announced(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                WorkList(rows.orEmpty(), ignoredCount, listState, onOpenWork, onOpenIgnored)
            }
        }
    }
}

@Composable
private fun WorkList(
    rows: List<WorkRow>,
    ignoredCount: Int,
    listState: LazyListState,
    onOpenWork: (String) -> Unit,
    onOpenIgnored: () -> Unit,
) {
    LazyColumn(state = listState) {
        if (ignoredCount > 0) {
            item {
                ListItem(
                    headlineContent = {
                        Text(pluralStringResource(R.plurals.ignored_files, ignoredCount, ignoredCount))
                    },
                    supportingContent = { Text(stringResource(R.string.see_ignored_files)) },
                    modifier = Modifier.clickable(onClick = onOpenIgnored),
                )
            }
        }
        items(rows, key = { it.work.id }) { row ->
            val chapters = pluralStringResource(R.plurals.chapter_count, row.chapterCount, row.chapterCount)
            val origin = if (row.work.origin == LibraryStore.KAVITA) stringResource(R.string.origin_kavita) else null
            ListItem(
                headlineContent = { Text(row.work.title) },
                supportingContent = {
                    Text(listOfNotNull(row.work.alternativeTitle, chapters, origin).joinToString(" · "))
                },
                modifier = Modifier.clickable { onOpenWork(row.work.id) },
            )
        }
    }
}

@Composable
private fun BackupRow(backingUp: Boolean, result: BackupResult?, place: String, onBackUp: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        TextButton(onClick = onBackUp, enabled = !backingUp) { Text(stringResource(R.string.back_up)) }
        val message = when {
            backingUp -> stringResource(R.string.backup_writing)
            result is BackupResult.Written -> result.fileName?.let { stringResource(R.string.backup_written, place, it) }
                // The folder did not say the file's name; the one asked for may not be the real one.
                ?: stringResource(R.string.backup_written_unnamed, place)
            result is BackupResult.WriteFailed -> stringResource(R.string.backup_write_failed)
            result == BackupResult.Mismatch -> stringResource(R.string.backup_mismatch)
            else -> null
        }
        if (message != null) Text(message, modifier = Modifier.announced(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Notice(@StringRes text: Int, onChooseFolder: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onChooseFolder) { Text(stringResource(R.string.choose_folder)) }
    }
}

private class ChapterRow(val chapter: StoredChapter, val read: Boolean)

@Composable
private fun WorkScreen(
    store: LibraryStore,
    workId: String,
    listState: LazyListState,
    progressVersion: Int,
    onOpenChapter: (String) -> Unit,
    onBack: () -> Unit,
) {
    var work by remember(workId) { mutableStateOf<StoredWork?>(null) }
    var chapters by remember(workId) { mutableStateOf<List<ChapterRow>>(emptyList()) }
    var resume by remember(workId) { mutableStateOf<StoredChapter?>(null) }
    var others by remember(workId) { mutableStateOf<List<StoredAlternative>>(emptyList()) }
    var usedOther by remember(workId) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(workId, progressVersion, usedOther) {
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
    }
    Screen(title = work?.title.orEmpty(), onBack = onBack) {
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
        LazyColumn(state = listState) {
            items(chapters, key = { it.chapter.id }) { row ->
                ListItem(
                    headlineContent = { Text(row.chapter.name) },
                    supportingContent = if (row.read) {
                        { Text(stringResource(R.string.chapter_read)) }
                    } else {
                        null
                    },
                    modifier = Modifier.clickable { onOpenChapter(row.chapter.id) },
                )
            }
        }
    }
}

@Composable
private fun IgnoredScreen(ignored: List<IgnoredFile>, onBack: () -> Unit) {
    Screen(title = stringResource(R.string.ignored_files_title), onBack = onBack) {
        LazyColumn {
            items(ignored, key = { it.path }) { file ->
                val reason = when (file.reason) {
                    IgnoreReason.UNSUPPORTED_FORMAT -> R.string.reason_unsupported_format
                    IgnoreReason.UNEXPECTED_PLACE -> R.string.reason_unexpected_place
                }
                ListItem(
                    headlineContent = { Text(file.path) },
                    supportingContent = { Text(stringResource(reason)) },
                )
            }
        }
    }
}

@Composable
private fun Screen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
            Text(stringResource(R.string.back))
        }
        Text(title, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall)
        content()
    }
}
