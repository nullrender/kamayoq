package app.kamayoq.android

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
internal fun HomeScreen() {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 12.dp).semantics { heading() },
        )
        Text(stringResource(R.string.home_later), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
