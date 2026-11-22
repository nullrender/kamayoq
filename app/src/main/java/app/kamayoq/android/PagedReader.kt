package app.kamayoq.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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
    nextChapterName: String?,
    mode: ImageMode,
    onShown: (Int) -> Unit,
    onToggleControls: () -> Unit,
    onNext: () -> Unit,
) {
    val total = chapter.pages.size
    val pager = rememberPagerState(initialPage = startPage) { total + 1 }
    val scope = rememberCoroutineScope()
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
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            if (index == total) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EndOfChapter(nextChapterName, onNext) }
            } else {
                val label = stringResource(R.string.reader_page_label, index + 1, total)
                Box(modifier = Modifier.fillMaxSize().semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                    WholePage(chapter.pages[index], width, height)
                }
            }
        }
    }
}

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
