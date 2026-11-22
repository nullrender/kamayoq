package app.kamayoq.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * The block after a chapter's last image (DESIGN, "Leitor de imagem", 5): which chapter finished and
 * which comes next, or, after the last one there is, that the reading reached it. In the reader's own
 * colors, so Pages' black around it never takes its text.
 */
@Composable
internal fun ImageEndBlock(
    chapterName: String?,
    workTitle: String?,
    nextChapterName: String?,
    onNext: () -> Unit,
    onChapters: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(
        modifier = modifier.fillMaxWidth().background(palette.background).padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (chapterName != null) {
            Text(
                stringResource(R.string.reader_image_chapter_finished, chapterName).uppercase(Locale.getDefault()),
                color = palette.imageType,
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (nextChapterName != null) {
            Text(stringResource(R.string.reader_image_up_next), color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
            Text(nextChapterName, color = palette.text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Dash()
            Button(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reader_image_read_next)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onChapters?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reader_chapters)) } }
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reader_back_to_work)) }
            }
        } else {
            Text(
                stringResource(R.string.reader_image_last_available),
                color = palette.text,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            workTitle?.let { Text(it, color = palette.secondaryText, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
            Dash()
            // Filled and neutral, as on the board: no type color for leaving.
            Button(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.text, contentColor = palette.background),
            ) { Text(stringResource(R.string.reader_back_to_work)) }
            onChapters?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reader_chapters)) } }
        }
    }
}

@Composable
private fun Dash() = Box(modifier = Modifier.width(44.dp).height(2.dp).background(LocalPalette.current.imageType))
