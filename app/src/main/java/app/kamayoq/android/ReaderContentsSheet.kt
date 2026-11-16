package app.kamayoq.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kamayoq.core.reader.ReaderContentsItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderContentsSheet(
    contents: List<ReaderContentsItem>?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Material's handle is 32 dp wide, and the sheet makes the touch target the size of what it
        // gets here; the wider box gives it 48 dp and keeps the same pill on screen.
        dragHandle = {
            Box(Modifier.sizeIn(minWidth = 48.dp), contentAlignment = Alignment.Center) {
                BottomSheetDefaults.DragHandle()
            }
        },
    ) {
        Text(stringResource(R.string.reader_contents), modifier = Modifier.padding(16.dp))
        if (contents.isNullOrEmpty()) {
            val message = if (contents == null) R.string.reader_contents_failed else R.string.reader_contents_empty
            Text(stringResource(message), modifier = Modifier.padding(16.dp).announced())
        } else {
            LazyColumn {
                items(contents, key = { it.index }) { item ->
                    TextButton(
                        onClick = { onSelect(item.index) },
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                            .padding(start = (item.depth.coerceAtMost(4) * 12).dp),
                    ) {
                        Text(item.title.ifBlank { stringResource(R.string.reader_contents_untitled) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
