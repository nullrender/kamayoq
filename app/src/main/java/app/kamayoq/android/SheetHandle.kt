package app.kamayoq.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The handle of the app's sheets: a pill of 32 × 4 dp, as Material's, inside a node of at least
 * 48 × 48 dp that alone carries the description. Material's own handle puts the description on the
 * pill, and that 32 × 4 dp node is what TalkBack got (the S24 with r686), whatever box surrounds it.
 */
@Composable
internal fun SheetHandle() {
    val description = stringResource(R.string.sheet_handle)
    Box(
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.shapes.extraLarge),
        )
    }
}
