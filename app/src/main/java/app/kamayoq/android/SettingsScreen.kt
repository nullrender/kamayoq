package app.kamayoq.android

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.kamayoq.data.BackupResult
import app.kamayoq.data.HomeContent
import app.kamayoq.data.StoredChapter

/**
 * Settings as far as the first slice goes: the folder, Kavita and the backup, out of the Library's
 * header. Slice 7 of the v1 interface gives this screen the structure of the AjustesApp board.
 */
@Composable
internal fun SettingsScreen(
    hasFolder: Boolean,
    folderReadable: Boolean,
    backingUp: Boolean,
    backupResult: BackupResult?,
    backupPlace: String,
    onChooseFolder: () -> Unit,
    onOpenKavita: () -> Unit,
    onBackUp: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 16.dp)),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 8.dp).semantics { heading() },
        )
        Group(R.string.settings_folder) {
            OutlinedButton(onClick = onChooseFolder) {
                Text(stringResource(if (hasFolder) R.string.change_folder else R.string.choose_folder))
            }
        }
        Group(R.string.settings_kavita) {
            OutlinedButton(onClick = onOpenKavita) { Text(stringResource(R.string.kavita_connect)) }
        }
        // A backup is written into the chosen folder, so it needs one that can be read.
        if (folderReadable) {
            Group(R.string.settings_backup) {
                BackupRow(backingUp, backupResult, backupPlace, onBackUp)
            }
        }
    }
}

@Composable
private fun Group(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** Home until slice 4 brings Continue reading, new chapters and recently added works. */
@Composable
internal fun HomeScreen(
    firstOpening: Boolean,
    restoreBusy: Boolean,
    onChooseFolder: () -> Unit,
    onOpenKavita: () -> Unit,
    onPickBackup: () -> Unit,
    content: HomeContent?,
    // Null until the Library's rows are read: Home does not say the app is empty before it knows.
    hasWorks: Boolean?,
    // False when something is stored (a Kavita server with no work marked): a restore would refuse it.
    canRestore: Boolean,
    onOpenLibrary: () -> Unit,
    onOpenWork: (String) -> Unit,
    onRead: (workId: String, chapterId: String) -> Unit,
    downloaded: Set<String>,
    canDownload: (StoredChapter) -> Boolean,
    onMarkRead: (workId: String, chapterId: String) -> Unit,
    onDownload: (workId: String, chapter: StoredChapter) -> Unit,
    notices: SnackbarHostState,
) {
    // With no work at all, Home shows the ways in, as the first opening does: a folder chosen may be empty.
    if (firstOpening || (content != null && content.isEmpty && hasWorks == false)) {
        FirstOpening(restoreBusy, onChooseFolder, onOpenKavita, onPickBackup, canRestore, Modifier.fillMaxSize().safeDrawingPadding())
        return
    }
    // The works whose new chapters are open; Arrived starts folded (FEATURES, part 2).
    var opened by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header") {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp).semantics { heading() },
            )
        }
        // A section with nothing in it is left out (FEATURES, part 2).
        val continueReading = content?.continueReading.orEmpty()
        val arrived = content?.arrived.orEmpty()
        if (continueReading.isNotEmpty()) {
            item(key = "continue") { ContinueSection(continueReading, onOpenWork, onRead) }
        }
        if (arrived.isNotEmpty()) {
            item(key = "arrived-title") { HomeSectionTitle(R.string.home_arrived_title) }
            items(arrived, key = { "arrived-${it.work.id}" }) { group ->
                val open = group.work.id in opened
                ArrivedGroup(
                    item = group,
                    open = open,
                    onToggle = { opened = ArrayList(if (open) opened - group.work.id else opened + group.work.id) },
                    downloaded = downloaded,
                    canDownload = canDownload,
                    onOpenWork = onOpenWork,
                    onRead = onRead,
                    onMarkRead = onMarkRead,
                    onDownload = onDownload,
                )
            }
        }
        val added = content?.added.orEmpty()
        if (added.isNotEmpty()) {
            item(key = "added-title") { HomeSectionTitle(R.string.home_added_title) }
            items(added, key = { "added-${it.work.id}" }) { AddedRow(it, onOpenWork) }
        }
        // Every section empty: Home says why and what to do (FEATURES, part 2). Nothing before the
        // first reading of the store and of the Library's rows, so the screen never says empty about
        // what it has not read.
        if (content != null && content.isEmpty && hasWorks == true) {
            item(key = "empty") {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.announced())
                    Text(stringResource(R.string.home_empty_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onOpenLibrary, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text(stringResource(R.string.home_go_library)) }
                }
            }
        }
    }
    // Over the screen's foot, where a notice with Undo does not move what is under the finger.
    SnackbarHost(notices, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
