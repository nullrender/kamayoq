package app.kamayoq.android

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The hint on the first opening of a book in Pages (board SinteseLeitorPaginado, `dica`): a veil over
 * the page with its three zones named, the same as the image reader's; it never shows again after
 * Got it. Touches stop at the veil, so none turns a page behind it.
 */
@Composable
internal fun PagesHint(onDismiss: () -> Unit) {
    Box(
        // Takes the touches, so none turns a page behind it, with no node of its own for a screen reader.
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Zone(stringResource(R.string.reader_hint_previous), Modifier.weight(0.3f))
            Zone(stringResource(R.string.reader_hint_controls), Modifier.weight(0.4f))
            Zone(stringResource(R.string.reader_hint_next), Modifier.weight(0.3f))
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.reader_hint_text),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.announced(),
            )
            Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reader_hint_ok)) }
        }
    }
}

@Composable
private fun Zone(label: String, modifier: Modifier) {
    Box(modifier = modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}
