package app.kamayoq.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kamayoq.core.reader.ImageModeChoice
import app.kamayoq.core.reader.ImageModeSettings
import app.kamayoq.core.reader.ImageScope

@Composable
private fun ImageModeChoice.name(): String = when (this) {
    ImageModeChoice.AUTOMATIC -> stringResource(R.string.reader_image_mode_automatic)
    else -> checkNotNull(mode).label()
}

/**
 * The quick sheet of the mode (board SinteseAjustesImagem, `rapida`): four large choices, for this
 * work; a touch applies and closes, and touching another one undoes it (DESIGN, answer 5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageModeQuickSheet(settings: ImageModeSettings, onChoose: (ImageModeChoice) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { SheetHandle() },
    ) {
        ImageModeQuickContent(settings) { choice ->
            onChoose(choice)
            onDismiss()
        }
    }
}

@Composable
internal fun ImageModeQuickContent(settings: ImageModeSettings, onChoose: (ImageModeChoice) -> Unit) {
    val chosen = settings.chosen(ImageScope.THIS_WORK)
    val detected = stringResource(R.string.reader_image_detected, settings.detected.label())
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Text(stringResource(R.string.reader_image_mode_title), style = MaterialTheme.typography.titleMedium)
        Note(stringResource(R.string.reader_image_mode_note))
        // Two by two, as on the board: each choice large enough to hit without looking twice.
        ImageModeChoice.entries.chunked(2).forEach { pair ->
            Choices(Modifier.padding(top = 8.dp)) {
                pair.forEach { choice ->
                    val label = if (choice == ImageModeChoice.AUTOMATIC) "${choice.name()}\n$detected" else choice.name()
                    Choice(choice == chosen, label, Modifier.weight(1f).heightIn(min = 64.dp)) { onChoose(choice) }
                }
            }
        }
    }
}

/**
 * The image reader's settings (board SinteseAjustesImagem, `folha`), in the v1 only what the slice
 * brings: Applies to, Mode, and the restore of the scope. The tabs come back with the other groups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageSettingsSheet(settings: ImageModeSettings, onSettings: (ImageModeSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { SheetHandle() },
    ) {
        ImageSettingsContent(settings, onSettings)
    }
}

@Composable
internal fun ImageSettingsContent(settings: ImageModeSettings, onSettings: (ImageModeSettings) -> Unit) {
    // This work first, as on the board: the quick sheet and this one agree on what a touch changes.
    var scope by rememberSaveable { mutableStateOf(ImageScope.THIS_WORK) }
    val chosen = settings.chosen(scope)
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reader_settings_description), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onSettings(settings.restore(scope)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (scope == ImageScope.THIS_WORK) R.string.reader_image_restore_work else R.string.reader_image_restore_all))
            }
        }
        Label(stringResource(R.string.reader_scope))
        Choices {
            Choice(scope == ImageScope.THIS_WORK, stringResource(R.string.reader_scope_work), Modifier.weight(1f)) { scope = ImageScope.THIS_WORK }
            Choice(scope == ImageScope.ALL_IMAGE_WORKS, stringResource(R.string.reader_image_scope_all), Modifier.weight(1f)) { scope = ImageScope.ALL_IMAGE_WORKS }
        }
        Note(
            stringResource(
                when {
                    scope == ImageScope.ALL_IMAGE_WORKS -> R.string.reader_image_note_all
                    settings.own == null -> R.string.reader_image_note_work_follows
                    else -> R.string.reader_image_note_work_own
                },
            ),
        )
        Label(stringResource(R.string.reader_mode))
        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ImageModeChoice.entries.forEach { choice ->
                // What Automatic detected shows in the quick sheet only, where the choice is made.
                Choice(choice == chosen, choice.name(), Modifier.fillMaxWidth()) { onSettings(settings.choose(scope, choice)) }
            }
        }
    }
}
