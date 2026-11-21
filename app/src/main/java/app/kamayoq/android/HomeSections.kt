package app.kamayoq.android

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kamayoq.core.home.Home
import app.kamayoq.data.ContinueItem
import java.time.Instant

/** A section's title on Home, read as a heading. */
@Composable
internal fun HomeSectionTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 10.dp).semantics { heading() },
    )
}

/**
 * Continue reading (board SinteseInicio): the work read last on a card, the others in a strip of
 * covers under it, the next one cut by the screen's edge, with no scrolling by itself.
 */
@Composable
internal fun ContinueSection(items: List<ContinueItem>, onOpenWork: (String) -> Unit, onRead: (String, String) -> Unit) {
    Column {
        HomeSectionTitle(R.string.home_continue_title)
        ContinueCard(items.first(), onOpenWork, onRead)
        if (items.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 14.dp),
            ) {
                items(items.drop(1), key = { it.work.id }) { item -> StripCover(item, onRead) }
            }
        }
    }
}

// The chapter and how far into it, or the chapter alone at its start or end.
@Composable
private fun placeOf(item: ContinueItem): String {
    val position = item.position?.takeIf { !atEnd(it) && percentOf(it) > 0 }
    return position?.let { stringResource(R.string.home_place, item.target.name, percentOf(it)) } ?: item.target.name
}

@Composable
private fun ContinueCard(item: ContinueItem, onOpenWork: (String) -> Unit, onRead: (String, String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val openPage = stringResource(R.string.home_open_page, item.work.title)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
            .background(colors.surfaceContainer, RoundedCornerShape(16.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = openPage, role = Role.Button) { onOpenWork(item.work.id) }) {
            ProvisionalCover(item.work.title, initialSize = 130.sp, modifier = Modifier.size(width = 88.dp, height = 128.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(sectionName(item.work.type)),
                style = MaterialTheme.typography.labelMedium,
                color = typeLabel(item.work.type, LocalPalette.current),
            )
            // The title opens the page, as the cover does; the button below opens the reader.
            Box(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 44.dp)
                    .clickable(onClickLabel = openPage, role = Role.Button) { onOpenWork(item.work.id) },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(item.work.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // After weeks, where the person left instead of how long ago, with no days counted (PERSONALITY).
            if (Home.leftLongAgo(item.lastReadAt, Instant.now())) {
                Text(stringResource(R.string.home_where_left, item.target.name), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            } else {
                Text(placeOf(item), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text(elapsedText(item.lastReadAt), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.size(4.dp))
            Button(onClick = { onRead(item.work.id, item.target.id) }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Icon(KamayoqIcons.play, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (item.goesOn) stringResource(R.string.home_continue, item.target.name) else stringResource(R.string.start_reading),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// A cover of the strip opens the reader where the work goes on, as the board's do.
@Composable
private fun StripCover(item: ContinueItem, onRead: (String, String) -> Unit) {
    Column(
        modifier = Modifier.width(76.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) { onRead(item.work.id, item.target.id) },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ProvisionalCover(item.work.title, initialSize = 110.sp, modifier = Modifier.size(width = 76.dp, height = 106.dp).clip(RoundedCornerShape(8.dp)))
        Text(item.work.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            item.target.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
