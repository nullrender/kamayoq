package app.kamayoq.android

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.kamayoq.core.reader.FooterContent
import app.kamayoq.core.reader.PageColors
import java.util.Date
import kotlinx.coroutines.delay

/** A chapter's place among its work's: the [number] counts from 1. */
data class ChapterPlace(val number: Int, val total: Int)

/**
 * Where the reader is, as the bars and the footer say it: a book by how much of it was read; a novel
 * by its chapter and how much of that chapter (product design, "contagem por tipo").
 */
data class TextProgress(val fraction: Float, val chapter: ChapterPlace?) {
    val percent: Int get() = (fraction.coerceIn(0f, 1f) * 100).toInt()
}

@Composable
private fun TextProgress.place(): String =
    chapter?.let { stringResource(R.string.reader_chapter_of, it.number, it.total) } ?: stringResource(R.string.reader_book_percent, percent)

@Composable
private fun TextProgress.inChapter(): String? = chapter?.let { stringResource(R.string.reader_chapter_percent, percent) }

/** The floating bar on top: back to the work, the title and the place (board SinteseLeitorPaginado). */
@Composable
internal fun TextReaderTopBar(title: String, progress: TextProgress, onBack: () -> Unit, modifier: Modifier = Modifier) {
    FloatingBar(modifier) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp), colors = barIconColors()) {
            Icon(KamayoqIcons.back, contentDescription = stringResource(R.string.reader_back_to_work))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(
                progress.place(),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = LocalPalette.current.secondaryText,
            )
        }
    }
}

/**
 * The floating bar below: previous chapter, the contents, the place and next chapter. A chapter
 * button with nowhere to go is off, not hidden, so the bar does not move under the thumb.
 */
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
) {
    FloatingBar(modifier) {
        IconButton(onClick = { onPreviousChapter?.invoke() }, enabled = enabled && onPreviousChapter != null, modifier = Modifier.size(48.dp), colors = barIconColors()) {
            Icon(KamayoqIcons.back, contentDescription = stringResource(R.string.reader_previous_chapter))
        }
        TextButton(onClick = onContents, enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), colors = barTextColors()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(KamayoqIcons.contents, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(contentsLabel, fontSize = 10.sp, maxLines = 1)
            }
        }
        ProgressArea(progress, Modifier.weight(1f))
        end()
        IconButton(onClick = { onNextChapter?.invoke() }, enabled = enabled && onNextChapter != null, modifier = Modifier.size(48.dp), colors = barIconColors()) {
            Icon(KamayoqIcons.chevron, contentDescription = stringResource(R.string.reader_next_chapter))
        }
    }
}

@Composable
private fun ProgressArea(progress: TextProgress, modifier: Modifier) {
    val palette = LocalPalette.current
    Column(modifier = modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(palette.line, RoundedCornerShape(2.dp))) {
            Box(modifier = Modifier.fillMaxWidth(progress.fraction.coerceIn(0f, 1f)).height(4.dp).background(palette.action, RoundedCornerShape(2.dp)))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(progress.place(), style = MaterialTheme.typography.bodySmall, color = palette.secondaryText, maxLines = 1)
            progress.inChapter()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.secondaryText, maxLines = 1) }
        }
    }
}

// The bars take the app's palette in full, whatever theme is around them: a reader shown by itself
// keeps the contrast measured for that palette (slice 1).
@Composable
private fun barIconColors() = LocalPalette.current.let {
    IconButtonDefaults.iconButtonColors(contentColor = it.text, disabledContentColor = it.secondaryText)
}

@Composable
internal fun barTextColors() = LocalPalette.current.let {
    ButtonDefaults.textButtonColors(contentColor = it.text, disabledContentColor = it.secondaryText)
}

@Composable
private fun FloatingBar(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = LocalPalette.current.bar,
        contentColor = LocalPalette.current.text,
        shadowElevation = 4.dp,
    ) {
        Row(modifier = Modifier.height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/**
 * The line at the foot of the page, in the page's own colors: the place, and with [FooterContent.ALL]
 * the battery and the time, which full screen hides (his choice of 07/10/2026).
 */
@Composable
internal fun TextReaderFooter(content: FooterContent, progress: TextProgress, colors: PageColors, modifier: Modifier = Modifier) {
    if (content == FooterContent.NOTHING) return
    val color = Color(colors.secondary)
    val place = listOfNotNull(progress.place(), progress.inChapter()).joinToString(" · ")
    Row(
        modifier = modifier.fillMaxWidth().height(FOOTER_HEIGHT).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(place, fontSize = 11.sp, color = color, maxLines = 1)
        if (content == FooterContent.ALL) {
            val (battery, time) = batteryAndTime()
            val line = if (battery != null) stringResource(R.string.reader_battery_time, battery, time) else time
            Text(line, fontSize = 11.sp, color = color, maxLines = 1, modifier = Modifier.clearAndSetSemantics { contentDescription = line })
        }
    }
}

internal val FOOTER_HEIGHT = 28.dp

/** The battery's level, null when the system does not say, and the time in the system's own format. */
@Composable
private fun batteryAndTime(): Pair<Int?, String> {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<Int?>(null) }
    var time by remember { mutableStateOf(DateFormat.getTimeFormat(context).format(Date())) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                battery = levelOf(intent)
            }
        }
        // A sticky broadcast: the current level comes back at once.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { battery = levelOf(it) }
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(context) {
        while (true) {
            time = DateFormat.getTimeFormat(context).format(Date())
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    return battery to time
}

private fun levelOf(intent: Intent): Int? {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else null
}
