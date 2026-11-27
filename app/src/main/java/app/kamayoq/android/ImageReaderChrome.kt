package app.kamayoq.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kamayoq.core.reader.ImageMode
import kotlin.math.roundToInt

@Composable
internal fun ImageMode.label(): String = stringResource(
    when (this) {
        ImageMode.STRIP -> R.string.reader_image_mode_strip
        ImageMode.PAGES_LEFT_TO_RIGHT -> R.string.reader_image_mode_ltr
        ImageMode.PAGES_RIGHT_TO_LEFT -> R.string.reader_image_mode_rtl
    },
)

/**
 * The counter's colors over the page, which may be anything from white to black: white text on black
 * at 70 % reads at 8.5:1 over a white page and more over a dark one (CounterColoursTest).
 */
internal object CounterColors {
    val text = Color.White
    val background = Color.Black.copy(alpha = 0.7f)
}

/** The image shown, counted from 1; the end block counts as the last image. */
private fun shownNumber(page: Int, total: Int) = (page + 1).coerceAtMost(total)

/**
 * What stays on a clean screen (product design, "Leitor de imagem", 2): the 2 dp bar at the bottom edge
 * in the type's color, filling in the reading's direction, and in Pages the small counter "12 / 48".
 */
@Composable
internal fun ImageReaderTrace(page: Int, total: Int, mode: ImageMode, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val fraction = shownNumber(page, total).toFloat() / total
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (mode != ImageMode.STRIP && page < total) {
            val counter = stringResource(R.string.reader_image_counter, page + 1, total)
            Text(
                counter,
                fontSize = 11.sp,
                color = CounterColors.text,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .background(CounterColors.background, MaterialTheme.shapes.small)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        // Drawn only: the page's own label already says where the reading is.
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.18f)).clearAndSetSemantics {}) {
            Box(
                modifier = Modifier
                    .align(if (mode == ImageMode.PAGES_RIGHT_TO_LEFT) Alignment.CenterEnd else Alignment.CenterStart)
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(palette.imageType),
            )
        }
    }
}

/** The floating bar on top: back to the work, the title, and the chapter's place with the mode. */
@Composable
internal fun ImageReaderTopBar(title: String, place: String?, mode: ImageMode, onBack: () -> Unit, modifier: Modifier = Modifier) {
    FloatingBar(modifier) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp), colors = barIconColors()) {
            Icon(KamayoqIcons.back, contentDescription = stringResource(R.string.reader_back_to_work))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(
                place?.let { stringResource(R.string.reader_image_place, it, mode.label()) } ?: mode.label(),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = LocalPalette.current.secondaryText,
            )
        }
    }
}

/**
 * The floating bar below: previous chapter, the slider of images and next chapter. Right to left, the
 * slider runs from the right and the chapter buttons change sides, with the reading (DESIGN, item 3).
 * A chapter button with nowhere to go is off, not hidden, so the bar does not move under the thumb.
 */
@Composable
internal fun ImageReaderBottomBar(
    page: Int,
    total: Int,
    mode: ImageMode,
    onSeek: (Int) -> Unit,
    onPreviousChapter: (() -> Unit)?,
    onNextChapter: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val rightToLeft = mode == ImageMode.PAGES_RIGHT_TO_LEFT
    FloatingBar(modifier) {
        val previous: @Composable () -> Unit = {
            IconButton(onClick = { onPreviousChapter?.invoke() }, enabled = onPreviousChapter != null, modifier = Modifier.size(48.dp), colors = barIconColors()) {
                Icon(if (rightToLeft) KamayoqIcons.chevron else KamayoqIcons.back, contentDescription = stringResource(R.string.reader_previous_chapter))
            }
        }
        val next: @Composable () -> Unit = {
            IconButton(onClick = { onNextChapter?.invoke() }, enabled = onNextChapter != null, modifier = Modifier.size(48.dp), colors = barIconColors()) {
                Icon(if (rightToLeft) KamayoqIcons.back else KamayoqIcons.chevron, contentDescription = stringResource(R.string.reader_next_chapter))
            }
        }
        if (rightToLeft) next() else previous()
        PageSlider(page, total, rightToLeft, onSeek, Modifier.weight(1f))
        if (rightToLeft) previous() else next()
    }
}

@Composable
private fun PageSlider(page: Int, total: Int, rightToLeft: Boolean, onSeek: (Int) -> Unit, modifier: Modifier) {
    val shown = shownNumber(page, total)
    // While the thumb moves, the slider shows where it is; the reader goes there when it stops.
    var dragged by remember(page, total) { mutableStateOf<Float?>(null) }
    val value = dragged ?: shown.toFloat()
    val number = value.roundToInt()
    // The slider's own value says the image; the description says what it moves.
    val description = stringResource(R.string.reader_page_slider)
    Column(modifier = modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CompositionLocalProvider(LocalLayoutDirection provides if (rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Slider(
                value = value,
                onValueChange = { dragged = it },
                onValueChangeFinished = {
                    dragged?.let { onSeek(it.roundToInt() - 1) }
                    dragged = null
                },
                valueRange = 1f..total.coerceAtLeast(2).toFloat(),
                steps = (total - 2).coerceAtLeast(0),
                enabled = total > 1,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
            )
        }
        Text(
            stringResource(R.string.reader_image_counter, number, total),
            style = MaterialTheme.typography.bodySmall,
            color = LocalPalette.current.secondaryText,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * The quick actions under the bar below (FEATURES, part 5): Chapters, Mode and Settings, each opening
 * its sheet without leaving the reader. Chapters is there when the work's chapters are known.
 */
@Composable
internal fun ImageReaderActions(onChapters: (() -> Unit)?, onMode: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    FloatingBar(modifier) {
        if (onChapters != null) {
            TextButton(onClick = onChapters, modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = barTextColors()) {
                Text(stringResource(R.string.reader_chapters))
            }
        }
        TextButton(onClick = onMode, modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = barTextColors()) {
            Text(stringResource(R.string.reader_mode))
        }
        TextButton(onClick = onSettings, modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = barTextColors()) {
            Text(stringResource(R.string.reader_settings))
        }
    }
}
