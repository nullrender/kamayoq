package app.kamayoq.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** What the app shows instead of closing when the library cannot be opened. */
@Composable
fun LibraryFailureScreen(failure: LibraryFailure) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.library_failed_title), style = MaterialTheme.typography.headlineSmall)
        val message = when (failure) {
            LibraryFailure.NEWER_VERSION -> R.string.library_failed_newer
            LibraryFailure.NOT_OPENED -> R.string.library_failed_not_opened
        }
        Text(stringResource(message))
    }
}
