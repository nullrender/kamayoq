package app.kamayoq.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
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
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderEngine
import app.kamayoq.core.reader.ReaderContentsItem
import app.kamayoq.core.reader.ReaderFlow
import app.kamayoq.core.reader.ReaderLink
import app.kamayoq.core.reader.RendererCrashes
import java.util.UUID
import app.kamayoq.core.reader.ReaderMessage as PageMessage

/** Shows a book in the reader's page. [onMessage] hears every message of the page, for the tests. */
@Composable
fun TextReader(archive: EpubArchive, title: String, onBack: () -> Unit, onMessage: (PageMessage) -> Unit = {}) {
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
    val flow = ReaderFlow.forAccessibility(touchExploration)
    var webView by remember(archive) { mutableStateOf<WebView?>(null) }
    var ready by remember(archive) { mutableStateOf(false) }
    var contents by remember(archive) { mutableStateOf<List<ReaderContentsItem>?>(null) }
    var contentsOpen by remember(archive) { mutableStateOf(false) }
    // Kept only during this screen: changing accessibility mode must not jump to the start.
    // Persisting it across closing the book belongs to the storage migration.
    var cfi by remember(archive) { mutableStateOf<String?>(null) }

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
            key(renderer, flow) {
                // New at each page, so an address from an earlier page stops answering.
                val token = remember { UUID.randomUUID().toString().replace("-", "") }
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            webView = this
                            ready = false
                            // AndroidView leaves a view in wrap-content height, and a WebView in that
                            // mode gives the page a viewport of height 0: the book became a white strip.
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            val client = ReaderClient(viewContext.assets, ReaderBook(archive, token, position = cfi, flow = flow)) {
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
                                        readContents { items -> if (webView === this) contents = items }
                                    }
                                    is PageMessage.Relocated -> if (ready && message.reason.byPerson) cfi = message.cfi
                                    is PageMessage.Failed -> problem = failureText(message.code)
                                    is PageMessage.Link ->
                                        (ReaderLink.classify(message.href) as? ReaderLink.AskFirst)?.let { leaving = it.url }
                                    else -> Unit
                                }
                            }
                        }
                    },
                    onRelease = {
                        if (webView === it) {
                            webView = null
                            ready = false
                        }
                        it.destroy()
                    },
                )
            }
            TextReaderControls(
                enabled = ready,
                onPrevious = { webView?.goToPreviousPage() },
                onContents = { contentsOpen = true },
                onNext = { webView?.goToNextPage() },
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
