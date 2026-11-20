package app.kamayoq.android

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.kamayoq.core.progress.ReadingState

/** The five states in the order of the design (FEATURES, part 3). */
internal val STATES = listOf(
    ReadingState.READING,
    ReadingState.WANT_TO_READ,
    ReadingState.ON_HOLD,
    ReadingState.DROPPED,
    ReadingState.COMPLETED,
)

@StringRes
internal fun stateName(state: ReadingState?): Int = when (state) {
    null -> R.string.state_none
    ReadingState.READING -> R.string.state_reading
    ReadingState.WANT_TO_READ -> R.string.state_want_to_read
    ReadingState.ON_HOLD -> R.string.state_on_hold
    ReadingState.DROPPED -> R.string.state_dropped
    ReadingState.COMPLETED -> R.string.state_completed
}

// Each state said by what it changes, as the Work board asks; nothing about Kavita until the app
// sends Want to read there.
@StringRes
private fun stateHelp(state: ReadingState): Int = when (state) {
    ReadingState.READING -> R.string.state_help_reading
    ReadingState.WANT_TO_READ -> R.string.state_help_want_to_read
    ReadingState.ON_HOLD -> R.string.state_help_on_hold
    ReadingState.DROPPED -> R.string.state_help_dropped
    ReadingState.COMPLETED -> R.string.state_help_completed
}

/** "Reading ▾": the work's state, which opens the choice. */
@Composable
internal fun StateButton(state: ReadingState?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val name = stringResource(stateName(state))
    val label = stringResource(R.string.state_button_label, name)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.sizeIn(minHeight = 48.dp).semantics { contentDescription = label },
    ) {
        Text(name)
        // The chevron turned down: the button opens a choice.
        Icon(KamayoqIcons.chevron, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(18.dp).rotate(90f))
    }
}

/**
 * The choice of state: one tap applies and closes; Back or a tap outside closes with nothing
 * changed. "Leave without a state" shows only when there is one to leave.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StateSheet(current: ReadingState?, onPick: (ReadingState?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = {
            Box(Modifier.sizeIn(minWidth = 48.dp), contentAlignment = Alignment.Center) {
                BottomSheetDefaults.DragHandle()
            }
        },
    ) {
        // Scrolls: on a short screen the last state and the button must still be reachable.
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.state_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.state_sheet_help),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Column(modifier = Modifier.selectableGroup()) {
                STATES.forEach { state ->
                    Row(
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp)
                            .selectable(selected = state == current, role = Role.RadioButton, onClick = { onPick(state) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = state == current, onClick = null)
                        Column {
                            Text(stringResource(stateName(state)), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(stateHelp(state)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (current != null) {
                OutlinedButton(
                    onClick = { onPick(null) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).sizeIn(minHeight = 48.dp),
                ) { Text(stringResource(R.string.state_clear)) }
            }
        }
    }
}
