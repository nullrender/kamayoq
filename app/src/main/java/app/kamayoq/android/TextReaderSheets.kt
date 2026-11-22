package app.kamayoq.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A chapter of the work as the reader's list shows it; [percent] is how far a chapter begun goes. */
data class ReaderChapterRow(val id: String, val name: String, val read: Boolean, val percent: Int?)

/**
 * Go to (board SinteseLeitorPaginado): a book by the share of the book, a novel by the share of the
 * chapter, and back to the start of either. [onGo] takes a fraction from 0 to 1.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoToSheet(progress: TextProgress, onGo: (Double) -> Unit, onStart: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { SheetHandle() },
    ) {
        GoToContent(progress, onGo, onStart)
    }
}

@Composable
internal fun GoToContent(progress: TextProgress, onGo: (Double) -> Unit, onStart: () -> Unit) {
    var percent by rememberSaveable { mutableFloatStateOf(progress.percent.toFloat()) }
    val inChapter = progress.chapter != null
    val sliderDescription = stringResource(if (inChapter) R.string.reader_go_slider_chapter else R.string.reader_go_slider_book)
    val shown = percent.roundToInt()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.reader_go_to), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(if (inChapter) R.string.reader_chapter_percent else R.string.reader_book_percent, shown),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = percent,
            onValueChange = { percent = it },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = sliderDescription },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onStart, modifier = Modifier.heightIn(min = 48.dp).weight(1f)) {
                Text(stringResource(if (inChapter) R.string.reader_go_chapter_start else R.string.reader_go_book_start))
            }
            Button(onClick = { onGo(shown / 100.0) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.reader_go))
            }
        }
    }
}

/** A novel's chapters in the reader: each with its state, the current one marked; touching opens it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderChaptersSheet(rows: List<ReaderChapterRow>, currentId: String?, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { SheetHandle() },
    ) {
        ReaderChaptersContent(rows, currentId, onOpen)
    }
}

@Composable
internal fun ReaderChaptersContent(rows: List<ReaderChapterRow>, currentId: String?, onOpen: (String) -> Unit) {
    val palette = LocalPalette.current
    // Opens at the chapter being read, not at the first of hundreds.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = rows.indexOfFirst { it.id == currentId }.coerceAtLeast(0))
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.reader_chapters), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            pluralStringResource(R.plurals.chapter_count, rows.size, rows.size),
            style = MaterialTheme.typography.bodySmall,
            color = palette.secondaryText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(state = state) {
            items(rows, key = { it.id }) { row ->
                val current = row.id == currentId
                val status = when {
                    current && row.percent != null -> stringResource(R.string.reader_chapter_reading, row.percent)
                    row.read -> stringResource(R.string.reader_chapter_read)
                    row.percent != null -> stringResource(R.string.reader_chapter_reading, row.percent)
                    else -> stringResource(R.string.reader_chapter_unread)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onOpen(row.id) },
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = if (current) BorderStroke(1.dp, palette.text) else null,
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.name,
                            modifier = Modifier.weight(1f),
                            color = if (row.read && !current) palette.secondaryText else palette.text,
                            style = if (current) MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else MaterialTheme.typography.bodyLarge,
                        )
                        Text(status, style = MaterialTheme.typography.bodySmall, color = palette.secondaryText)
                    }
                }
            }
        }
    }
}
