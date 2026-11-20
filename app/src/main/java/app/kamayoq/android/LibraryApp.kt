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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.library.IgnoreReason
import app.kamayoq.core.library.IgnoredFile
import app.kamayoq.core.library.StateFilter
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.ReadingState
import app.kamayoq.data.BackupExport
import app.kamayoq.data.BackupRestore
import app.kamayoq.data.BackupResult
import app.kamayoq.data.EditionOffer
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.RestoreResult
import app.kamayoq.data.StoredAlternative
import app.kamayoq.data.StoredChapter
import app.kamayoq.data.StoredReadingState
import app.kamayoq.data.StoredWork
import app.kamayoq.data.kavita.StoredKavitaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val TAG = "Kamayoq"

internal data class WorkRow(val work: StoredWork, val chapterCount: Int, val state: ReadingState? = null)

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
    restores: Restores,
) {
    val scope = rememberCoroutineScope()
    var tree by remember { mutableStateOf(folder.saved()) }
    var accessLost by remember { mutableStateOf(false) }
    var accessRefused by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanFailed by remember { mutableStateOf(false) }
    var kavitaFailed by remember { mutableStateOf(false) }
    // The address of a restored server whose key must be typed again.
    var kavitaKeyFor by remember { mutableStateOf<String?>(null) }
    // A restore is offered only while nothing is stored (docs/decisions/backup-restore-2026-11-18.md).
    var empty by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<WorkRow>?>(null) }
    var ignored by rememberSaveable(stateSaver = IGNORED_SAVER) { mutableStateOf<List<IgnoredFile>>(emptyList()) }
    var openWorkId by rememberSaveable { mutableStateOf<String?>(null) }
    var showIgnored by rememberSaveable { mutableStateOf(false) }
    var showKavita by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable(stateSaver = COLLAPSED_SAVER) { mutableStateOf(emptySet<WorkType>()) }
    // Kept above the screens, so a work opened from the results comes back to them.
    var searching by rememberSaveable { mutableStateOf(false) }
    // The app opens on the Library until Home has content of its own (slice 4).
    var tab by rememberSaveable { mutableStateOf(AppTab.LIBRARY) }
    var query by rememberSaveable { mutableStateOf("") }
    var stateFilter by rememberSaveable { mutableStateOf(StateFilter.ALL) }
    var readingChapterId by rememberSaveable { mutableStateOf<String?>(null) }
    // When the reader last opened, in milliseconds: a change of state the app made since then is
    // told on the Work screen as the reader closes (FEATURES, part 3: always with a notice).
    var readingSince by rememberSaveable { mutableStateOf<Long?>(null) }
    // Raised when the reader closes, so the work's chapters show the new progress.
    var progressVersion by remember { mutableStateOf(0) }
    // Kept here, above the screens: a screen that leaves takes its list position with it, and Back
    // must find it again (AGENTS.md).
    val libraryList = rememberLazyListState()
    // Where the list was before a search: the results move it, and closing the search brings it back.
    var listIndexBefore by rememberSaveable { mutableStateOf(0) }
    var listOffsetBefore by rememberSaveable { mutableStateOf(0) }
    var listToRestore by remember { mutableStateOf(false) }
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
                LibraryScan.run(folder, current, store, latest) { opening.contents(current, it) } to loadRows(store)
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
            val waiting = withContext(Dispatchers.IO) { kavitaSync.waitingForKey() }
            kavitaKeyFor = waiting
            // Nothing can be read without the key: the Library asks for it instead of saying Kavita failed.
            if (waiting != null) return
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

    // Any type: a backup copied by cable or a cloud drive may not come as JSON, and the content decides (choice 10).
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { file ->
        if (file == null) return@rememberLauncherForActivityResult
        val resolver = context.applicationContext.contentResolver
        restores.open {
            withContext(Dispatchers.IO) {
                BackupRestore(store).open { resolver.openInputStream(file) ?: throw IOException("No input for $file") }
            }
        }
    }

    val restoreResult = restores.result
    // Any change to the works may end the empty app, or begin it: a restore whose works are all
    // out of the list and a Kavita server connected change no row.
    LaunchedEffect(rows, restoreResult, showKavita) {
        empty = withContext(Dispatchers.IO) { store.isEmpty() }
    }

    LaunchedEffect(restoreResult) {
        when (val taken = restores.takeResult()) {
            is RestoreResult.Failed -> Log.e(TAG, "Restoring the backup failed", taken.cause)
            is RestoreResult.Unreadable -> Log.e(TAG, "The backup picked could not be read as one", taken.cause)
            is RestoreResult.ReadFailed -> Log.e(TAG, "Reading the backup picked failed", taken.cause)
            is RestoreResult.Restored -> {
                rows = withContext(Dispatchers.IO) { loadRows(store) }
                importKavita()
                // A folder already chosen is read again at once: its works meet the restored ones by path.
                tree?.let { if (folder.canRead(it)) refresh(it) }
            }
            RestoreResult.NotEmpty, null -> Unit
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
                readingSince = readingSince,
                onNoticed = { readingSince = null },
                onOpenChapter = {
                    readingSince = System.currentTimeMillis()
                    readingChapterId = it
                },
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
                // A key saved there ends the request for it, and the restored works can be read now.
                scope.launch { importKavita() }
            }
            BackHandler(onBack = closeKavita)
            // Off the main thread: the Android docs warn that Keystore calls may take a while.
            // The address comes from the same file as the key: the two were saved as one pair.
            val saved by produceState<Pair<StoredKavitaKey, String>?>(null) {
                val stored = withContext(Dispatchers.IO) {
                    val (key, address) = kavita.savedForm()
                    // After a restore nothing is saved yet: the address is the restored server's, and
                    // only the key is asked again (choice 12).
                    key to address.ifEmpty { store.kavitaServer()?.address.orEmpty() }
                }
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
        else -> {
            // Back from Home or Settings goes to the Library, the place the app opens on.
            BackHandler(enabled = tab != AppTab.LIBRARY) { tab = AppTab.LIBRARY }
            // Back closes the search first, as it closes a sheet (AGENTS.md).
            BackHandler(enabled = tab == AppTab.LIBRARY && searching) {
                searching = false
                query = ""
                listToRestore = true
            }
            // After the screen has the whole list again, not before: scrolling the list of results
            // would stop at its last one.
            LaunchedEffect(searching) {
                if (!searching && listToRestore) {
                    listToRestore = false
                    libraryList.scrollToItem(listIndexBefore, listOffsetBefore)
                }
            }
            val folderReadable = tree != null && !accessLost && !accessRefused
            // The content ends above the bar instead of passing under it: on a short screen the
            // bar would cover a work, and a tap meant for the work would reach the bar.
            Column(modifier = Modifier.fillMaxSize()) {
                // The bar takes the navigation bar's inset; the screens above it must not add it again.
                Box(modifier = Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
                    when (tab) {
                        // The empty app shows its ways in here too; what they lead to happens in the
                        // Library, where the folder is read and a restore is confirmed.
                        AppTab.HOME -> HomeScreen(
                            firstOpening = empty && tree == null,
                            restoreBusy = restores.reading || restores.restoring,
                            onChooseFolder = {
                                tab = AppTab.LIBRARY
                                picker.launch(null)
                            },
                            onOpenKavita = { showKavita = true },
                            onPickBackup = {
                                tab = AppTab.LIBRARY
                                backupPicker.launch(arrayOf("*/*"))
                            },
                        )
                        AppTab.SETTINGS -> SettingsScreen(
                            hasFolder = tree != null,
                            folderReadable = folderReadable,
                            backingUp = backups.running,
                            backupResult = backups.result,
                            backupPlace = backups.place,
                            onChooseFolder = { picker.launch(null) },
                            onOpenKavita = { showKavita = true },
                            onBackUp = { currentTree?.let(::backUp) },
                        )
                        AppTab.LIBRARY -> LibraryScreen(
                            rows = rows,
                            ignoredCount = ignored.size,
                            hasFolder = tree != null,
                            accessLost = accessLost,
                            accessRefused = accessRefused,
                            scanning = scanning,
                            scanFailed = scanFailed,
                            kavitaFailed = kavitaFailed,
                            kavitaKeyFor = kavitaKeyFor,
                            canRestore = empty,
                            restores = restores,
                            listState = libraryList,
                            collapsed = collapsed,
                            onToggleSection = { type -> collapsed = if (type in collapsed) collapsed - type else collapsed + type },
                            onPickBackup = { backupPicker.launch(arrayOf("*/*")) },
                            onConfirmRestore = { restores.confirm { backup -> withContext(Dispatchers.IO) { BackupRestore(store).apply(backup) } } },
                            onChooseFolder = { picker.launch(null) },
                            onOpenWork = { openWorkId = it },
                            onOpenIgnored = { showIgnored = true },
                            onOpenKavita = { showKavita = true },
                            searching = searching,
                            query = query,
                            onOpenSearch = {
                                listIndexBefore = libraryList.firstVisibleItemIndex
                                listOffsetBefore = libraryList.firstVisibleItemScrollOffset
                                searching = true
                            },
                            onQueryChange = { query = it },
                            stateFilter = stateFilter,
                            onStateFilter = { stateFilter = it },
                            onCloseSearch = {
                                searching = false
                                query = ""
                                listToRestore = true
                            },
                        )
                    }
                }
                AppBar(current = tab, onSelect = { tab = it })
            }
        }
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
    kavitaKeyFor: String?,
    canRestore: Boolean,
    restores: Restores,
    listState: LazyListState,
    collapsed: Set<WorkType>,
    onToggleSection: (WorkType) -> Unit,
    onPickBackup: () -> Unit,
    onConfirmRestore: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenWork: (String) -> Unit,
    onOpenIgnored: () -> Unit,
    onOpenKavita: () -> Unit,
    searching: Boolean,
    query: String,
    onOpenSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    stateFilter: StateFilter,
    onStateFilter: (StateFilter) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        if (searching) {
            SearchBar(query, onQueryChange, onCloseSearch)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                SearchButton(onOpenSearch)
            }
        }
        if (kavitaFailed) {
            Text(
                stringResource(R.string.kavita_read_failed),
                modifier = Modifier.padding(16.dp).announced(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (kavitaKeyFor != null) {
            Text(
                stringResource(R.string.kavita_key_again, kavitaKeyFor),
                modifier = Modifier.padding(16.dp).announced(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // The empty app with no folder offers the restore among its ways in, below.
        val firstOpening = canRestore && !hasFolder
        // Here until the Settings screen exists (its place there: Settings › Backup).
        RestoreRow(canRestore && !firstOpening, restores, hasFolder, onPickBackup, onConfirmRestore)
        // Without a readable folder only Kavita's works can open; the local ones wait, stored.
        val kavitaRows = rows.orEmpty().filter { it.work.origin == LibraryStore.KAVITA }
        when {
            accessRefused -> {
                Notice(R.string.folder_access_refused, onChooseFolder)
                WorkList(kavitaRows, 0, listState, collapsed, query, onToggleSection, onOpenWork, onOpenIgnored, stateFilter, onStateFilter)
            }
            firstOpening -> FirstOpening(
                restoreBusy = restores.reading || restores.restoring,
                onChooseFolder = onChooseFolder,
                onOpenKavita = onOpenKavita,
                onPickBackup = onPickBackup,
            )
            !hasFolder -> {
                Notice(R.string.no_folder, onChooseFolder)
                WorkList(kavitaRows, 0, listState, collapsed, query, onToggleSection, onOpenWork, onOpenIgnored, stateFilter, onStateFilter)
            }
            accessLost -> {
                Notice(R.string.folder_access_lost, onChooseFolder)
                WorkList(kavitaRows, 0, listState, collapsed, query, onToggleSection, onOpenWork, onOpenIgnored, stateFilter, onStateFilter)
            }
            else -> {
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
                WorkList(rows.orEmpty(), ignoredCount, listState, collapsed, query, onToggleSection, onOpenWork, onOpenIgnored, stateFilter, onStateFilter)
            }
        }
    }
}

@Composable
internal fun BackupRow(backingUp: Boolean, result: BackupResult?, place: String, onBackUp: () -> Unit) {
    Column {
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
private fun RestoreRow(
    offered: Boolean,
    restores: Restores,
    hasFolder: Boolean,
    onPick: () -> Unit,
    onConfirm: () -> Unit,
) {
    val result = restores.result
    val busy = restores.reading || restores.restoring
    if (offered || busy || result != null) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            if (offered) TextButton(onClick = onPick, enabled = !busy) { Text(stringResource(R.string.restore_backup)) }
            val message = when {
                restores.reading -> R.string.restore_reading
                restores.restoring -> R.string.restore_running
                result is RestoreResult.Restored -> if (hasFolder) R.string.restore_done else R.string.restore_done_choose_folder
                result == RestoreResult.NotEmpty -> R.string.restore_not_empty
                result is RestoreResult.Unreadable -> R.string.restore_unreadable
                result is RestoreResult.ReadFailed -> R.string.restore_read_failed
                result is RestoreResult.Failed -> R.string.restore_failed
                else -> null
            }
            if (message != null) Text(stringResource(message), modifier = Modifier.announced(), style = MaterialTheme.typography.bodyMedium)
        }
    }
    // Asked even on an empty app: the date and the number of works say whether it is the right file.
    restores.offer?.let { backup ->
        val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
            .format(backup.exportedAt)
        AlertDialog(
            onDismissRequest = restores::cancel,
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { Text(stringResource(R.string.restore_confirm_text, date, backup.works.count { it.present })) },
            confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.restore_confirm)) } },
            dismissButton = { TextButton(onClick = restores::cancel) { Text(stringResource(R.string.restore_cancel)) } },
        )
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
private fun Screen(title: String, onBack: () -> Unit, notices: SnackbarHostState? = null, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
                Text(stringResource(R.string.back))
            }
            Text(title, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall)
            content()
        }
        // Over the screen's foot, where a notice with Undo does not move what is under the finger.
        notices?.let { SnackbarHost(it, modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding()) }
    }
}
