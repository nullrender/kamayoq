package app.kamayoq.android

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The book's controls stay native so explore by touch can find real labeled buttons. */
@Composable
internal fun TextReaderControls(
    enabled: Boolean,
    onPrevious: () -> Unit,
    onContents: () -> Unit,
    onNext: () -> Unit,
) {
    // Contents has its own row so long translations and large fonts cannot shrink page targets.
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onContents, enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Text(stringResource(R.string.reader_contents))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onPrevious, enabled = enabled, modifier = Modifier.weight(1f).sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Text(stringResource(R.string.reader_previous_page))
            }
            TextButton(onClick = onNext, enabled = enabled, modifier = Modifier.weight(1f).sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Text(stringResource(R.string.reader_next_page))
            }
        }
    }
}
