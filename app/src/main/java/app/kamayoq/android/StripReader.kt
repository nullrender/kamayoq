package app.kamayoq.android

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.kamayoq.core.progress.StripReading
import app.kamayoq.core.reader.StripBands
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.ProgressSaving
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/**
 * The Strip: pages one under another at the screen width, with no gap, then the
 * end-of-chapter block. The position is saved at each change of image; reaching the block marks
 * the chapter read (progress contract, section 9).
 */
@Composable
fun StripReader(
    store: LibraryStore,
    chapter: OpenedChapter,
    turns: OpeningTurns,
    round: Long,
    title: String,
    nextChapterName: String?,
    deviceId: String,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val reading = remember(chapter) { StripReading.open(chapter.progress, chapter.pages.size, deviceId) }
    if (reading == null) {
        ReaderMessage(stringResource(R.string.reader_no_pages), onBack)
        return
    }
    ImmersiveScreen()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = reading.startPage)
    var showControls by remember { mutableStateOf(false) }
    val saving = remember(store) { ProgressSaving(store) }
    // The last save failed: said on screen until a later one works; the reading goes on.
    var saveFailed by remember { mutableStateOf(false) }

    LaunchedEffect(reading) {
        snapshotFlow {
            val endVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == chapter.pages.size }
            if (endVisible) chapter.pages.size else listState.firstVisibleItemIndex
        }.distinctUntilChanged().collect { page ->
            val progress = reading.show(page, Instant.now()) ?: return@collect
            // Not waited for: a page reached while this save waits must be asked for too. The save
            // outlives the screen, so it logs and reports its own failure.
            turns.inLine(round) {
                val failure = saving.save(chapter.chapterId, progress)
                failure?.let { Log.e("Kamayoq", "Saving the reading position failed", it) }
                saveFailed = failure != null
            }
        }
    }

    val controlsAction = stringResource(if (showControls) R.string.reader_hide_controls else R.string.reader_show_controls)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The same as the tap below, for TalkBack.
            .semantics {
                onClick(label = controlsAction) {
                    showControls = !showControls
                    true
                }
            }
            .pointerInput(Unit) {
                // A tap in the middle third shows or hides the controls; the rest of the screen
                // is left for scrolling.
                detectTapGestures { offset ->
                    val middle = offset.y > size.height / 3f && offset.y < size.height * 2 / 3f
                    if (middle) showControls = !showControls
                }
            },
    ) {
        val screenWidth = constraints.maxWidth
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(chapter.pages) { index, page ->
                val label = stringResource(R.string.reader_page_label, index + 1, chapter.pages.size)
                Box(modifier = Modifier.semantics { contentDescription = label }) { StripPage(page, screenWidth) }
            }
            item { EndOfChapter(nextChapterName, onNext) }
        }
        if (saveFailed) {
            Text(
                stringResource(R.string.reader_save_failed),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .safeDrawingPadding()
                    .padding(16.dp)
                    .announced(),
            )
        }
        if (showControls) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .safeDrawingPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                Text(
                    title,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** One page at the screen width, with its final height before any pixel is decoded. */
@Composable
private fun StripPage(page: PageImage, screenWidth: Int) {
    val width = page.width
    val height = page.height
    val density = LocalDensity.current
    if (width == null || height == null) {
        Text(
            stringResource(R.string.reader_page_failed),
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val displayHeight = with(density) { StripBands.displayHeight(width, height, screenWidth).toDp() }
    var bands by remember(page) { mutableStateOf<List<Bitmap>?>(null) }
    var failed by remember(page) { mutableStateOf(false) }
    LaunchedEffect(page) {
        try {
            bands = withContext(Dispatchers.IO) { decodeBands(page, width, height, screenWidth) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not only IOException: access can be lost, or the file swapped for a smaller one
            // after its header was read.
            Log.e("Kamayoq", "Decoding a page failed", e)
            failed = true
        }
    }
    // Bands of pages that left the screen are freed; the page decodes again if it comes back.
    DisposableEffect(page) {
        onDispose { bands?.forEach(Bitmap::recycle) }
    }
    Box(modifier = Modifier.fillMaxWidth().height(displayHeight), contentAlignment = Alignment.Center) {
        val decoded = bands
        when {
            failed -> Text(stringResource(R.string.reader_page_failed), style = MaterialTheme.typography.bodyMedium)
            decoded != null -> Column(modifier = Modifier.fillMaxSize()) {
                decoded.forEach { band ->
                    Image(
                        bitmap = band.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }
        }
    }
}

/** Decodes a page shrunk to about the screen width, cut into bands that fit memory (StripBands). */
private fun decodeBands(page: PageImage, width: Int, height: Int, screenWidth: Int): List<Bitmap> {
    val sample = StripBands.sampleSize(width, screenWidth)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    // Region decoding covers JPEG, PNG, WebP and HEIF; for other formats (GIF, AVIF) it gives no
    // decoder or fails, and the page decodes whole, still shrunk. They are rarely tall.
    val decoder = try {
        page.open().use { BitmapRegionDecoder.newInstance(it) }
    } catch (e: IOException) {
        Log.w("Kamayoq", "No region decoder for a page; decoding it whole", e)
        null
    }
    if (decoder == null) {
        val whole = page.open().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Undecodable page")
        return listOf(whole)
    }
    try {
        return StripBands.bands(width, height, sample).map { band ->
            decoder.decodeRegion(Rect(0, band.first, width, band.last + 1), options)
                ?: throw IOException("Undecodable band $band")
        }
    } finally {
        decoder.recycle()
    }
}

/** The end-of-chapter block, in its minimal first version. */
@Composable
private fun EndOfChapter(nextChapterName: String?, onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.end_of_chapter), style = MaterialTheme.typography.titleMedium)
        if (nextChapterName != null) {
            Button(onClick = onNext) { Text(stringResource(R.string.next_chapter, nextChapterName)) }
        } else {
            Text(stringResource(R.string.no_next_chapter), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ReaderMessage(text: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(text, modifier = Modifier.padding(12.dp).announced(), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Full screen, into the camera cutout, with the screen kept on, while the reader is shown (5.14). */
@Composable
private fun ImmersiveScreen() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        val cutoutBefore = window.attributes.layoutInDisplayCutoutMode
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = cutoutBefore }
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
