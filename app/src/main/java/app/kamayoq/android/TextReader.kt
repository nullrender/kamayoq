package app.kamayoq.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderEngine
import app.kamayoq.core.reader.ReaderContentsItem
import app.kamayoq.core.reader.ReaderFlow
import app.kamayoq.core.reader.ReaderLink
import app.kamayoq.core.reader.RendererCrashes
import app.kamayoq.core.reader.TextReading
import java.time.Instant
import java.util.UUID
import app.kamayoq.core.reader.ReaderMessage as PageMessage

/** What comes after the last page of a text: a book just ends; a novel's chapter leads to the next. */
sealed interface TextEnd {
    data object Book : TextEnd

    /** [nextName] is null on the last chapter of the work. */
    class Chapter(val nextName: String?, val onNext: () -> Unit) : TextEnd
}

/**
 * Shows a book in the reader's page, from where [reading] starts; [onSave] gets the progress to keep.
 * [onMessage] hears every message of the page, for the tests. The work's [type] chooses the flow,
 * and [end] what the end block offers.
 */
@Composable
fun TextReader(
    archive: EpubArchive,
    title: String,
    reading: TextReading,
    onSave: (ChapterProgress) -> Unit,
    onBack: () -> Unit,
    onMessage: (PageMessage) -> Unit = {},
    type: WorkType = WorkType.BOOKS,
    end: TextEnd = TextEnd.Book,
) {
    val save by rememberUpdatedState(onSave)
    val session = remember(reading) { WholeText(reading) { save(it) } }
    TextReader(archive, title, session, onBack, onMessage, type, end)
}

/** The same screen for any text: [session] says where the page opens and saves what each move changes. */
@Composable
fun TextReader(
    archive: EpubArchive,
    title: String,
    session: TextSession,
    onBack: () -> Unit,
    onMessage: (PageMessage) -> Unit = {},
    type: WorkType = WorkType.BOOKS,
    end: TextEnd = TextEnd.Book,
) {
    val context = LocalContext.current
    // Checked before any WebView is made: below the floor the fork fails later, with no clear reason.
    val supported = remember {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            ReaderEngine.supports(WebViewCompat.getCurrentWebViewPackage(context)?.versionName)
    }
    var problem by remember(archive) { mutableStateOf(if (supported) null else R.string.reader_webview_old) }
    var leaving by remember(archive) { mutableStateOf<String?>(null) }
    val crashes = remember(archive) { RendererCrashes() }
    // A dead renderer leaves a WebView that cannot be used again: a new number makes a new one.
    var renderer by remember(archive) { mutableIntStateOf(0) }
    val touchExploration = touchExplorationEnabled()
    val flow = ReaderFlow.of(type, touchExploration)
    var webView by remember(archive) { mutableStateOf<WebView?>(null) }
    var ready by remember(archive) { mutableStateOf(false) }
    var contents by remember(archive) { mutableStateOf<List<ReaderContentsItem>?>(null) }
    var contentsOpen by remember(archive) { mutableStateOf(false) }
    // Where a new page of this screen opens (a crash, a change of mode): the last place kept.
    var cfi by remember(archive) { mutableStateOf(session.startCfi) }
    var section by remember(archive) { mutableStateOf(session.startSection) }
    // A place from Kavita opens by its marker until the person moves; then the CFI read here leads.
    var marker by remember(archive) { mutableStateOf(session.startMarker) }
    // The end block shows over the last page until Previous page or another move leaves it.
    var atEnd by remember(archive) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        }
        val shown = problem
        if (shown != null) {
            Text(
                stringResource(shown),
                modifier = Modifier.fillMaxWidth().padding(16.dp).announced(),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            key(renderer, flow) {
                // New at each page, so an address from an earlier page stops answering.
                val token = remember { UUID.randomUUID().toString().replace("-", "") }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            webView = this
                            ready = false
                            // AndroidView leaves a view in wrap-content height, and a WebView in that
                            // mode gives the page a viewport of height 0: the book became a white strip.
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            val book = ReaderBook(
                                archive, token, position = cfi, flow = flow, section = section, marker = marker,
                                sections = session.startSections.takeIf { cfi == null },
                                start = session.startHref.takeIf { cfi == null },
                                chapters = session.chapters,
                            )
                            val client = ReaderClient(viewContext.assets, book) {
                                when (crashes.afterCrash()) {
                                    RendererCrashes.Decision.REOPEN -> renderer++
                                    RendererCrashes.Decision.STOP -> problem = R.string.reader_renderer_gone
                                }
                            }
                            loadReaderPage(client) { message ->
                                onMessage(message)
                                when (message) {
                                    PageMessage.Restored -> {
                                        ready = true
                                        session.restored()
                                        readContents { items -> if (webView === this) contents = items }
                                    }
                                    // Only once this page restored: a new page lays out before it, and
                                    // the guard already left its own opening behind.
                                    is PageMessage.Relocated -> if (ready) {
                                        session.relocated(message, Instant.now())?.let { place ->
                                            atEnd = false
                                            cfi = place.cfi
                                            section = place.section
                                            marker = null
                                        }
                                    }
                                    PageMessage.End -> if (ready) {
                                        atEnd = true
                                        session.ended(Instant.now())
                                    }
                                    is PageMessage.Failed -> problem = failureText(message.code)
                                    is PageMessage.Link ->
                                        (ReaderLink.classify(message.href) as? ReaderLink.AskFirst)?.let { leaving = it.url }
                                    else -> Unit
                                }
                            }
                        }
                    },
                    // Hidden under the end block: an invisible view takes no touch and no TalkBack focus,
                    // and keeps its page for Previous page.
                    update = { it.visibility = if (atEnd) View.INVISIBLE else View.VISIBLE },
                    onRelease = {
                        if (webView === it) {
                            webView = null
                            ready = false
                        }
                        it.destroy()
                    },
                )
            }
            if (atEnd) EndOfText(end)
            }
            TextReaderControls(
                enabled = ready,
                // From the end block, Previous page goes back to the last page, which is still there.
                onPrevious = { if (atEnd) atEnd = false else webView?.goToPreviousPage() },
                onContents = { contentsOpen = true },
                onNext = { if (!atEnd) webView?.goToNextPage() },
            )
        }
    }

    if (contentsOpen) {
        ReaderContentsSheet(
            contents = contents,
            onSelect = { index ->
                contentsOpen = false
                webView?.goToContents(index)
            },
            onDismiss = { contentsOpen = false },
        )
    }

    leaving?.let { url ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text(stringResource(R.string.reader_link_title)) },
            // The real address, never the link's text: the book chooses the text.
            text = { Text(url) },
            confirmButton = {
                TextButton(onClick = {
                    leaving = null
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: ActivityNotFoundException) {
                        // The book stays open: only the way out is missing.
                        Toast.makeText(context, R.string.reader_link_no_browser, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.reader_link_open)) }
            },
            dismissButton = {
                TextButton(onClick = { leaving = null }) { Text(stringResource(R.string.reader_link_stay)) }
            },
        )
    }
}

/**
 * The end block, over the last page (decision 9), in its minimal first version. A novel's chapter
 * offers the next one, with the Strip's own words.
 */
@Composable
private fun EndOfText(end: TextEnd) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (end) {
                TextEnd.Book -> Text(
                    stringResource(R.string.end_of_book),
                    modifier = Modifier.announced(),
                    style = MaterialTheme.typography.titleMedium,
                )
                is TextEnd.Chapter -> {
                    Text(
                        stringResource(R.string.end_of_chapter),
                        modifier = Modifier.announced(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (end.nextName != null) {
                        Button(onClick = end.onNext) { Text(stringResource(R.string.next_chapter, end.nextName)) }
                    } else {
                        Text(stringResource(R.string.no_next_chapter), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun touchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        enabled = manager.isTouchExplorationEnabled
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

@StringRes
private fun failureText(code: String): Int =
    if (code == "fixed-layout") R.string.reader_fixed_layout else R.string.reader_failed
