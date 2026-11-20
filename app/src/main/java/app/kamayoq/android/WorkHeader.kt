package app.kamayoq.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kamayoq.core.library.Publication
import app.kamayoq.core.library.PublicationStatus
import app.kamayoq.core.library.WorkDetails
import app.kamayoq.data.StoredWork

// Test tags of the Work screen's cover: the real one, and the provisional one a work without a
// cover gets (DESIGN.md, "Capa provisória").
internal const val COVER_IMAGE = "work-cover-image"
internal const val COVER_PROVISIONAL = "work-cover-provisional"

// Dark tones for the provisional cover, so the light initial on top reads in either theme
// (DESIGN.md, "Capa provisória"). The title picks one, so a work always gets the same.
private val coverTones = listOf(0xFF2E3B30, 0xFF3B3026, 0xFF26324A, 0xFF3A2A38, 0xFF2D3A3B, 0xFF3A3526).map(::Color)

/**
 * The block of colour with the title's initial, enormous and almost transparent, cut by the edges,
 * that stands for a work with no cover. Decoration: the title is always read next to it.
 */
@Composable
internal fun ProvisionalCover(title: String, initialSize: TextUnit, modifier: Modifier = Modifier, tag: String? = null) {
    Box(
        modifier = modifier
            .background(coverTones[Math.floorMod(title.hashCode(), coverTones.size)])
            .clearAndSetSemantics { tag?.let { testTag = it } },
    ) {
        Text(
            title.take(1).uppercase(),
            // The same cut at every size: about a twentieth of the letter to the left, an eighth up.
            modifier = Modifier.offset(x = (-initialSize.value * 0.044f).dp, y = (-initialSize.value * 0.13f).dp),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = initialSize, lineHeight = initialSize, fontWeight = FontWeight.ExtraBold),
            color = Color.White.copy(alpha = 0.1f),
            maxLines = 1,
        )
    }
}

/**
 * The top of the Work screen (board SinteseObra): the cover, 112 × 166 dp, or the provisional one;
 * the type, title, authors and how the publication stands, each only when known. The header keeps
 * the app's palette: the cover colours only itself (DESIGN.md, 07/10).
 */
@Composable
internal fun WorkHeader(work: StoredWork, details: WorkDetails, cover: ImageBitmap?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val shape = Modifier.size(width = 112.dp, height = 166.dp).clip(RoundedCornerShape(12.dp))
        if (cover != null) {
            Image(
                cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = shape.clearAndSetSemantics { testTag = COVER_IMAGE },
            )
        } else {
            ProvisionalCover(work.title, initialSize = 172.sp, modifier = shape, tag = COVER_PROVISIONAL)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(sectionName(work.type)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(work.title, style = MaterialTheme.typography.titleLarge)
            work.alternativeTitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (details.authors.isNotEmpty()) {
                Text(details.authors.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            }
            publicationLine(details.publication)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Nothing when the status is unknown: a work with no details shows only what it has.
@Composable
private fun publicationLine(publication: Publication): String? {
    val status = when (publication.status) {
        PublicationStatus.ONGOING -> R.string.publication_ongoing
        PublicationStatus.HIATUS -> R.string.publication_hiatus
        PublicationStatus.COMPLETED -> R.string.publication_completed
        PublicationStatus.CANCELLED -> R.string.publication_cancelled
        null -> return null
    }
    val missing = publication.missing
    // Kavita's "Ended": completed, with chapters this source does not have (FEATURES, part 3).
    return if (publication.status == PublicationStatus.COMPLETED && missing != null && missing > 0) {
        pluralStringResource(R.plurals.publication_missing, missing, stringResource(status), missing)
    } else {
        stringResource(status)
    }
}

/** The summary in three lines, with More when it does not fit and Less once open. */
@Composable
internal fun WorkSummary(summary: String) {
    var open by rememberSaveable(summary) { mutableStateOf(false) }
    var overflows by remember(summary) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (open) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!open) overflows = it.hasVisualOverflow },
        )
        if (open || overflows) {
            // A plain clickable line, not a TextButton: the button's own padding and minimum width
            // pushed the word in from the summary's margin.
            Box(
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(role = Role.Button) { open = !open },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    stringResource(if (open) R.string.summary_less else R.string.summary_more),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * The work's tags, to read. On the board a tap filters the Library; the Library has no filter by
 * tag yet, so these are plain labels, not buttons that would do nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkTags(tags: List<String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    tag,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
