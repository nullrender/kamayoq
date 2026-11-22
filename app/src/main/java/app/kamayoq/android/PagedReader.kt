package app.kamayoq.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.kamayoq.core.reader.ImageMode
import app.kamayoq.core.reader.ImagePages
import app.kamayoq.core.reader.TapZone
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pages: one image at a time, whole on the screen over black, then the end-of-chapter block as one page
 * more. Right to left, the next page comes from the left (product design, "Leitor de imagem", 1 and 4).
 */
@Composable
internal fun PagedPages(
    chapter: OpenedChapter,
    startPage: Int,
    end: @Composable () -> Unit,
    mode: ImageMode,
    onShown: (Int) -> Unit,
    onToggleControls: () -> Unit,
    seek: Int?,
    onSought: () -> Unit,
) {
    val total = chapter.pages.size
    val pager = rememberPagerState(initialPage = startPage) { total + 1 }
    val scope = rememberCoroutineScope()
    // A page zoomed in by two fingers moves under one finger instead of turning (FEATURES, part 5).
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.settledPage) { zoomed = false }
    LaunchedEffect(seek) {
        seek?.let {
            pager.scrollToPage(it)
            onSought()
        }
    }
    LaunchedEffect(chapter) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { onShown(it) }
    }
    fun turn(zone: TapZone) {
        val target = ImagePages.turned(pager.currentPage, total, zone)
        if (target != pager.currentPage) scope.launch { pager.animateScrollToPage(target) }
    }
    val previousPage = stringResource(R.string.reader_previous_page)
    val nextPage = stringResource(R.string.reader_next_page)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // For explore by touch, switch access and keyboards, which have no side of the page to touch.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(previousPage) { turn(TapZone.PREVIOUS); true },
                    CustomAccessibilityAction(nextPage) { turn(TapZone.NEXT); true },
                )
            }
            .pointerInput(mode) {
                detectTapGestures { offset ->
                    when (val zone = ImagePages.zone(offset.x.toDouble() / size.width, mode)) {
                        TapZone.CONTROLS -> onToggleControls()
                        else -> turn(zone)
                    }
                }
            },
    ) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        HorizontalPager(
            state = pager,
            reverseLayout = mode == ImageMode.PAGES_RIGHT_TO_LEFT,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            if (index == total) {
                Box(modifier = Modifier.fillMaxSize().background(LocalPalette.current.background), contentAlignment = Alignment.Center) { end() }
            } else {
                val label = stringResource(R.string.reader_page_label, index + 1, total)
                Box(modifier = Modifier.fillMaxSize().semantics { contentDescription = label }.pinchZoom { zoomed = it }, contentAlignment = Alignment.Center) {
                    WholePage(chapter.pages[index], width, height)
                }
            }
        }
    }
}

/**
 * Two fingers zoom the page in, up to four times, and back to whole; zoomed in, one finger moves it
 * inside its own edges. A touch with no movement is left alone, so the sides still turn the page.
 */
private fun Modifier.pinchZoom(onZoomed: (Boolean) -> Unit): Modifier = composed {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val zoomedIn = stringResource(R.string.reader_page_zoomed)
    this
        .onSizeChanged { size = it }
        // Said with the page: one who zoomed in by accident knows why a drag no longer turns it.
        .semantics { if (scale > 1f) stateDescription = zoomedIn }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val fingers = event.changes.count { it.pressed }
                    if (fingers >= 2 || scale > 1f) {
                        val pan = event.calculatePan()
                        scale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                        val maxX = (scale - 1) * size.width / 2
                        val maxY = (scale - 1) * size.height / 2
                        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                        onZoomed(scale > 1f)
                        if (fingers >= 2 || pan != Offset.Zero) event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
}

private const val MAX_ZOOM = 4f

/** One page whole on the screen, decoded at most twice the screen's size, so a zoom still has detail. */
@Composable
private fun WholePage(page: PageImage, screenWidth: Int, screenHeight: Int) {
    val width = page.width
    val height = page.height
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(page) { mutableStateOf(width == null || height == null) }
    LaunchedEffect(page) {
        if (width == null || height == null) return@LaunchedEffect
        try {
            bitmap = withContext(Dispatchers.IO) { decodeFit(page, width, height, screenWidth, screenHeight) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not only IOException: access can be lost, or the file swapped after its header was read.
            Log.e("Kamayoq", "Decoding a page failed", e)
            failed = true
        }
    }
    // A page that left the pager is freed; it decodes again if it comes back.
    DisposableEffect(page) {
        onDispose { bitmap?.recycle() }
    }
    val decoded = bitmap
    when {
        failed -> Text(stringResource(R.string.reader_page_failed), style = MaterialTheme.typography.bodyMedium, color = Color.White)
        decoded != null -> Image(
            bitmap = decoded.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}

private fun decodeFit(page: PageImage, width: Int, height: Int, screenWidth: Int, screenHeight: Int): Bitmap {
    // Shown whole, the page is scaled by `fit`; decoded at twice that, a zoom keeps its detail. The
    // sample is the largest power of two that still decodes that much: a tall Strip image shown in
    // Pages is small on screen, and decoding it whole would take tens of megabytes.
    val fit = minOf(screenWidth.toDouble() / width, screenHeight.toDouble() / height)
    val wanted = minOf(1.0, 2 * fit)
    var sample = 1
    while (sample * 2 * wanted <= 1.0) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return page.open().use { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("Undecodable page")
}
