package app.kamayoq.android

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kamayoq.core.library.LibrarySections
import app.kamayoq.core.library.StateFilter
import app.kamayoq.core.library.WorkSearch
import app.kamayoq.core.library.WorkType
import app.kamayoq.data.LibraryStore

/** Kept as names: a recreated screen keeps the sections the person folded. */
internal val COLLAPSED_SAVER = listSaver<Set<WorkType>, String>(
    save = { types -> types.map { it.name } },
    restore = { names -> names.map(WorkType::valueOf).toSet() },
)

@StringRes
internal fun sectionName(type: WorkType) = when (type) {
    WorkType.COMICS -> R.string.section_comics
    WorkType.NOVELS -> R.string.section_novels
    WorkType.BOOKS -> R.string.section_books
}

/** The Library as a list, one section per type (board SinteseBiblioteca, list view). */
@Composable
internal fun WorkList(
    rows: List<WorkRow>,
    ignoredCount: Int,
    listState: LazyListState,
    collapsed: Set<WorkType>,
    query: String,
    onToggleSection: (WorkType) -> Unit,
    onOpenWork: (String) -> Unit,
    onOpenIgnored: () -> Unit,
    stateFilter: StateFilter,
    onStateFilter: (StateFilter) -> Unit,
) {
    val searching = query.isNotBlank()
    // Only once a work has a state: before that, every chip but two would count zero. The search
    // looks in every work of the app, whatever the chip.
    val chips = !searching && rows.any { it.state != null }
    val shown = if (chips) stateFilter.keep(rows) { it.state } else rows
    val found = shown.filter { WorkSearch.matches(query, it.work.title, it.work.alternativeTitle) }
    val sections = LibrarySections.of(found, { it.work.type }, { it.work.title })
    Column {
        if (chips) StateChips(StateFilter.counts(rows.map { it.state }), stateFilter, onStateFilter)
        LazyColumn(state = listState) {
            if (chips && shown.isEmpty()) {
                item(key = "filtered-out") {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(stringResource(R.string.filter_empty), modifier = Modifier.announced(), style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = { onStateFilter(StateFilter.ALL) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.filter_clear))
                        }
                    }
                }
            }
            if (searching && found.isEmpty() && rows.isNotEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.search_none),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp).announced(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (ignoredCount > 0 && !searching) {
                item(key = "ignored") {
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenIgnored).heightIn(min = 56.dp)
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(pluralStringResource(R.plurals.ignored_files, ignoredCount, ignoredCount), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.see_ignored_files),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            sections.forEach { section ->
                // A folded section would hide what the search found.
                val open = searching || section.type !in collapsed
                item(key = "section-${section.type}") {
                    SectionHeader(section.type, section.works.size, open) { onToggleSection(section.type) }
                }
                if (open) {
                    items(section.works, key = { it.work.id }) { row -> WorkRowItem(row) { onOpenWork(row.work.id) } }
                }
            }
        }
    }
}

@StringRes
private fun chipName(chip: StateFilter): Int = when (chip) {
    StateFilter.ALL -> R.string.state_all
    else -> stateName(chip.state)
}

/** The chips by reading state, one chosen, each with how many works it keeps (board SinteseBiblioteca). */
@Composable
private fun StateChips(counts: Map<StateFilter, Int>, chosen: StateFilter, onChoose: (StateFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        items(StateFilter.entries.toList(), key = { it.name }) { chip ->
            FilterChip(
                selected = chip == chosen,
                onClick = { onChoose(chip) },
                label = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(chipName(chip)))
                        Text((counts[chip] ?: 0).toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(type: WorkType, count: Int, open: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (open) R.string.section_expanded else R.string.section_collapsed)
    val action = stringResource(if (open) R.string.section_hide else R.string.section_show)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClickLabel = action, onClick = onToggle)
            .semantics { stateDescription = state }
            .heightIn(min = 48.dp)
            .padding(start = 12.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            KamayoqIcons.chevron,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp).rotate(if (open) 90f else 0f),
        )
        Text(stringResource(sectionName(type)), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkRowItem(row: WorkRow, onOpen: () -> Unit) {
    val chapters = pluralStringResource(R.plurals.chapter_count, row.chapterCount, row.chapterCount)
    val origin = if (row.work.origin == LibraryStore.KAVITA) stringResource(R.string.origin_kavita) else null
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 72.dp).padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The initial is decoration; the title is read next to it.
        ProvisionalCover(row.work.title, initialSize = 68.sp, modifier = Modifier.size(width = 44.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.work.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(row.work.alternativeTitle, chapters, origin).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SearchButton(onOpen: () -> Unit) {
    IconButton(onClick = onOpen, modifier = Modifier.size(48.dp)) {
        Icon(KamayoqIcons.search, contentDescription = stringResource(R.string.search_open), modifier = Modifier.size(22.dp))
    }
}

/** The Library's header while searching: a way back and the field, which takes the keyboard at once. */
@Composable
internal fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(KamayoqIcons.back, contentDescription = stringResource(R.string.search_close), modifier = Modifier.size(22.dp))
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text(stringResource(R.string.search_field)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
