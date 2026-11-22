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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kamayoq.core.home.Home
import app.kamayoq.core.library.WorkType
import app.kamayoq.data.AddedItem
import app.kamayoq.data.ArrivedItem
import app.kamayoq.data.ContinueItem
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.StoredChapter
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
                items(items.drop(1), key = { it.work.id }) { item -> StripCover(item, onOpenWork, onRead) }
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

// With two chapters in conflict the Work screen asks where to go on, before any reader opens
// (progress contract, case 5): Home takes the person there instead.
private fun goOn(item: ContinueItem, onOpenWork: (String) -> Unit, onRead: (String, String) -> Unit) {
    if (item.crossing) onOpenWork(item.work.id) else onRead(item.work.id, item.target.id)
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
        // The cover clears its letter from TalkBack: the button is named after the work.
        Box(
            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                .clickable(onClickLabel = openPage, role = Role.Button) { onOpenWork(item.work.id) }
                .semantics { contentDescription = item.work.title },
        ) {
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
                // Nothing read here to time: the card says when the chapters were marked, not a reading.
                val time = if (item.byMarks) stringResource(R.string.home_marked_ago, elapsedText(item.lastReadAt)) else elapsedText(item.lastReadAt)
                Text(time, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.size(4.dp))
            Button(onClick = { goOn(item, onOpenWork, onRead) }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
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
private fun StripCover(item: ContinueItem, onOpenWork: (String) -> Unit, onRead: (String, String) -> Unit) {
    Column(
        modifier = Modifier.width(76.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) { goOn(item, onOpenWork, onRead) },
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

/**
 * A work of Arrived (board SinteseInicio): one line per work, its chapters folded; the arrow opens
 * the newest ones, each with mark as read and, from Kavita, download or downloaded.
 */
@Composable
internal fun ArrivedGroup(
    item: ArrivedItem,
    open: Boolean,
    onToggle: () -> Unit,
    downloaded: Set<String>,
    canDownload: (StoredChapter) -> Boolean,
    onOpenWork: (String) -> Unit,
    onRead: (String, String) -> Unit,
    onMarkRead: (String, String) -> Unit,
    onDownload: (String, StoredChapter) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalPalette.current
    val title = item.work.title
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                    .clickable(onClickLabel = stringResource(R.string.home_open_page, title), role = Role.Button) { onOpenWork(item.work.id) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProvisionalCover(title, initialSize = 68.sp, modifier = Modifier.size(width = 44.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(
                            R.string.home_arrived_line,
                            pluralStringResource(R.plurals.chapter_count, item.count, item.count),
                            elapsedText(item.latest),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            // The counter in the colour of the work's type (board), read as words.
            val counted = pluralStringResource(R.plurals.home_arrived_count, item.count, item.count)
            val comics = item.work.type == WorkType.COMICS
            Box(
                modifier = Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp)
                    .background(typeLabel(item.work.type, palette), CircleShape)
                    .padding(horizontal = 8.dp)
                    .clearAndSetSemantics { contentDescription = counted },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.count.toString(),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (comics) palette.onImageType else palette.onTextType,
                )
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                Icon(
                    KamayoqIcons.expand,
                    contentDescription = stringResource(if (open) R.string.home_arrived_hide else R.string.home_arrived_show, title),
                    modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f),
                )
            }
        }
        if (open) {
            item.chapters.forEach { row ->
                val chapter = row.chapter
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 74.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { onRead(item.work.id, chapter.id) }
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(chapter.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(elapsedText(row.arrivedAt), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    IconButton(onClick = { onMarkRead(item.work.id, chapter.id) }, modifier = Modifier.size(48.dp)) {
                        Icon(KamayoqIcons.eye, contentDescription = stringResource(R.string.home_mark_read, chapter.name), modifier = Modifier.size(20.dp))
                    }
                    // Download only where the origin offers it: a local file is already on the device.
                    if (chapter.origin == LibraryStore.KAVITA) {
                        if (chapter.id in downloaded) {
                            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    KamayoqIcons.downloaded,
                                    contentDescription = stringResource(R.string.chapter_downloaded),
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else if (canDownload(chapter)) {
                            IconButton(onClick = { onDownload(item.work.id, chapter) }, modifier = Modifier.size(48.dp)) {
                                Icon(KamayoqIcons.download, contentDescription = stringResource(R.string.home_download, chapter.name), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A work of Added (board SinteseInicio): its type, its chapters and when it came; it opens the page. */
@Composable
internal fun AddedRow(item: AddedItem, onOpenWork: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button) { onOpenWork(item.work.id) }
            .padding(horizontal = 18.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProvisionalCover(item.work.title, initialSize = 68.sp, modifier = Modifier.size(width = 44.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.work.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(
                    R.string.home_added_line,
                    stringResource(sectionName(item.work.type)),
                    pluralStringResource(R.plurals.chapter_count, item.chapterCount, item.chapterCount),
                    elapsedText(item.addedAt),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
