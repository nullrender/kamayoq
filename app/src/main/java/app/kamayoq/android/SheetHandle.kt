package app.kamayoq.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/**
 * The handle of the app's sheets: Material's pill, inside a node of at least 48 × 48 dp that carries
 * the description. Material puts the description on the pill itself, 32 × 4 dp, and that is the
 * node TalkBack gets (the S24 with r686); a wider box around it only widened the merged node.
 */
@Composable
internal fun SheetHandle() {
    val description = stringResource(R.string.sheet_handle)
    Box(
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        BottomSheetDefaults.DragHandle()
    }
}
