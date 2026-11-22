package app.kamayoq.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.kamayoq.core.reader.FooterContent
import app.kamayoq.core.reader.PageColors

/** A chapter's place among its work's: the [number] counts from 1. */
data class ChapterPlace(val number: Int, val total: Int)

/**
 * Where the reader is, as the bars and the footer say it: a book by how much of it was read; a novel
 * by its chapter and how much of that chapter (product design, "contagem por tipo").
 */
data class TextProgress(val fraction: Float, val chapter: ChapterPlace?) {
    val percent: Int get() = (fraction.coerceIn(0f, 1f) * 100).toInt()
}

/** The floating bar on top. Not drawn yet. */
@Composable
internal fun TextReaderTopBar(title: String, progress: TextProgress, onBack: () -> Unit, modifier: Modifier = Modifier) = Unit

/** The floating bar below. Not drawn yet. */
@Composable
internal fun TextReaderBottomBar(
    enabled: Boolean,
    contentsLabel: String,
    progress: TextProgress,
    onPreviousChapter: (() -> Unit)?,
    onContents: () -> Unit,
    onNextChapter: (() -> Unit)?,
    modifier: Modifier = Modifier,
    end: @Composable () -> Unit = {},
) = Unit

/** The line at the foot of the page. Not drawn yet. */
@Composable
internal fun TextReaderFooter(content: FooterContent, progress: TextProgress, colors: PageColors, modifier: Modifier = Modifier) = Unit
