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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderEngine
import app.kamayoq.core.reader.ReaderContentsItem
import app.kamayoq.core.reader.ReaderChapters
import app.kamayoq.core.reader.ReaderFlow
import app.kamayoq.core.reader.PageLook
import app.kamayoq.core.reader.PageThemes
import app.kamayoq.core.reader.ShownLook
import app.kamayoq.core.reader.TapZone
import app.kamayoq.core.reader.TapZones
import app.kamayoq.core.reader.TextSettings
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
    chosenFlow: ReaderFlow? = null,
    chapter: ChapterPlace? = null,
    onPreviousChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    settings: TextSettings = TextSettings.DEFAULT,
    onSettings: ((TextSettings) -> Unit)? = null,
    onFlow: ((ReaderFlow) -> Unit)? = null,
    chapterRows: List<ReaderChapterRow>? = null,
    chapterId: String? = null,
    onOpenWorkChapter: ((String) -> Unit)? = null,
    pagesHint: Boolean = false,
    onPagesHintSeen: () -> Unit = {},
) {
    val save by rememberUpdatedState(onSave)
    val session = remember(reading) { WholeText(reading) { save(it) } }
    TextReader(
        archive, title, session, onBack, onMessage, type, end, chosenFlow,
        chapterOf = { chapter }, onPreviousChapter = onPreviousChapter, onNextChapter = onNextChapter,
        settings = settings, onSettings = onSettings, onFlow = onFlow,
        chapterRows = chapterRows, chapterIdOf = { chapterId }, onOpenWorkChapter = onOpenWorkChapter,
    )
}

/**
 * The same screen for any text: [session] says where the page opens and saves what each move changes.
 * [chosenFlow] is the mode the person chose for this work. A novel's [chapterOf] says which chapter
 * of the work the page is in, from the chapter of a split file the page names (null for a file that
 * is one chapter). The [settings] are the panel's as the work opens; [onSettings] and [onFlow] keep
 * what the panel changes, and without [onSettings] there is no panel. A novel's [chapterRows] are
 * its chapters for the Chapters sheet, [chapterIdOf] the one the page is in (from the chapter of a
 * split file), and [onOpenWorkChapter] opens another; without rows, Chapters shows the file's contents.
 * The [pagesHint] and [onPagesHintSeen] are not used yet.
 */
@Composable
fun TextReader(
    archive: EpubArchive,
    title: String,
    session: TextSession,
    onBack: () -> Unit,
    onMessage: (PageMessage) -> Unit = {},
    type: WorkType = WorkType.BOOKS,
    end: TextEnd = TextEnd.Book,
    chosenFlow: ReaderFlow? = null,
    chapterOf: (Int?) -> ChapterPlace? = { null },
    onPreviousChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    settings: TextSettings = TextSettings.DEFAULT,
    onSettings: ((TextSettings) -> Unit)? = null,
    onFlow: ((ReaderFlow) -> Unit)? = null,
    chapterRows: List<ReaderChapterRow>? = null,
    chapterIdOf: (Int?) -> String? = { null },
    onOpenWorkChapter: ((String) -> Unit)? = null,
    pagesHint: Boolean = false,
    onPagesHintSeen: () -> Unit = {},
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
    // What the panel changed since the work opened; the store keeps it through onSettings and onFlow.
    var current by remember(archive) { mutableStateOf(settings) }
    var chosen by remember(archive) { mutableStateOf(chosenFlow) }
    var panelOpen by remember(archive) { mutableStateOf(false) }
    var goToOpen by remember(archive) { mutableStateOf(false) }
    var chaptersOpen by remember(archive) { mutableStateOf(false) }
    // Until the page measures it, no warning: a false one would blame a device that hyphenates.
    var hyphenates by remember(archive) { mutableStateOf(true) }
    val look = current.effective()
    val systemDark = isSystemInDarkTheme()
    // A new mode opens the book again at the place kept, as the fork misplaces a flow changed on it.
    val flow = ReaderFlow.of(type, touchExploration, chosen)
    val shown = ShownLook(look, PageThemes.colors(look.theme, systemDark))
    // The look the page opened with: a later one goes by command, without opening the book again.
    var pageLook by remember(archive) { mutableStateOf(shown) }
    // Shown at opening; a touch in the middle, or turning the page by touch, hides them; in Scroll a
    // touch anywhere does, as scrolling alone cannot tell a finger from a jump. With explore
    // by touch they never hide: hidden, they could not be found (PERSONALITY, principle 3).
    var controlsShown by remember(archive) { mutableStateOf(true) }
    var progress by remember(archive) { mutableStateOf(TextProgress(0f, chapterOf(null))) }
    // In a split file, the chapter the page is in, counted in the file; the bars move inside the file first.
    var fileChapter by remember(archive) { mutableStateOf<Int?>(null) }
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

    ImmersiveScreen()
    val previousPage = stringResource(R.string.reader_previous_page)
    val nextPage = stringResource(R.string.reader_next_page)
    Box(modifier = Modifier.fillMaxSize().background(Color(shown.colors.background))) {
    // The cutout only: the system bars come and go over a full screen (a sheet brings them back), and
    // a page that followed them would change size and lay the book out again at another place.
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
        val failure = problem
        if (failure != null) {
            // Under the top bar, which stays for Back.
            Text(
                stringResource(failure),
                modifier = Modifier.fillMaxWidth().padding(top = 72.dp, start = 16.dp, end = 16.dp).announced(),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(shown.colors.text),
            )
        } else {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().semantics {
                    // For explore by touch, switch access and keyboards, which have no side of the page to touch.
                    customActions = listOf(
                        CustomAccessibilityAction(previousPage) {
                            if (atEnd) atEnd = false else webView?.goToPreviousPage()
                            ready
                        },
                        CustomAccessibilityAction(nextPage) {
                            if (!atEnd) webView?.goToNextPage()
                            ready
                        },
                    )
                },
            ) {
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
                            pageLook = shown
                            val book = ReaderBook(
                                archive, token, position = cfi, flow = flow, section = section, marker = marker,
                                sections = session.startSections.takeIf { cfi == null },
                                // Also with a CFI: when the file no longer resolves it, the chapter opens at its own start.
                                start = session.startHref,
                                chapters = session.chapters,
                                look = shown,
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
                                        progress = progressOf(message, type, chapterOf)
                                        message.chapter?.let { fileChapter = it }
                                        session.relocated(message, Instant.now())?.let { place ->
                                            atEnd = false
                                            cfi = place.cfi
                                            section = place.section
                                            marker = null
                                        }
                                    }
                                    is PageMessage.Tap -> if (ready) {
                                        when (TapZones.of(message.x, flow)) {
                                            TapZone.CONTROLS -> controlsShown = touchExploration || !controlsShown
                                            TapZone.PREVIOUS -> {
                                                if (!touchExploration) controlsShown = false
                                                if (atEnd) atEnd = false else goToPreviousPage()
                                            }
                                            TapZone.NEXT -> {
                                                if (!touchExploration) controlsShown = false
                                                if (!atEnd) goToNextPage()
                                            }
                                        }
                                    }
                                    is PageMessage.Hyphenation -> hyphenates = message.available
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
            TextReaderFooter(look.footer, progress, shown.colors)
        }
    }
    if (controlsShown || problem != null) {
        TextReaderTopBar(title, progress, onBack, Modifier.align(Alignment.TopCenter).statusBarsPadding())
    }
    if (controlsShown && problem == null && !panelOpen) {
        TextReaderBottomBar(
            enabled = ready,
            contentsLabel = stringResource(if (type == WorkType.NOVELS) R.string.reader_chapters else R.string.reader_contents),
            progress = progress,
            onPreviousChapter = chapterMove(type, contents, session.chapters, fileChapter, -1, webView, onPreviousChapter),
            onContents = { if (chapterRows != null) chaptersOpen = true else contentsOpen = true },
            onNextChapter = chapterMove(type, contents, session.chapters, fileChapter, +1, webView, onNextChapter),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            onProgress = { goToOpen = true },
            end = { if (onSettings != null) SettingsButton(enabled = ready) { panelOpen = true } },
        )
    }
    }

    if (panelOpen && onSettings != null) {
        TextSettingsPanel(
            settings = current,
            onSettings = { changed ->
                current = changed
                onSettings(changed)
            },
            type = type,
            flow = flow,
            onFlow = { changed ->
                chosen = changed
                onFlow?.invoke(changed)
            },
            touchExploration = touchExploration,
            hyphenates = hyphenates,
            systemDark = systemDark,
            onDismiss = { panelOpen = false },
        )
    }

    // A new look with the page open goes by command: reopening would cost the place on screen.
    LaunchedEffect(shown, ready) {
        if (ready && shown != pageLook) {
            webView?.applyLook(PageLook.command(shown))
            pageLook = shown
        }
    }

    if (goToOpen) {
        // In a split file the share is of the chapter the page is in; a whole file is its own chapter.
        val inChapter = fileChapter.takeIf { session.chapters != null }
        GoToSheet(
            progress = progress,
            onGo = { share ->
                goToOpen = false
                webView?.goToFraction(share, inChapter)
            },
            onStart = {
                goToOpen = false
                webView?.goToFraction(0.0, inChapter)
            },
            onDismiss = { goToOpen = false },
        )
    }

    val rows = chapterRows
    if (chaptersOpen && rows != null) {
        val currentId = chapterIdOf(fileChapter)
        ReaderChaptersSheet(
            // The chapter on screen says how far the page is now, not when the reader opened.
            rows = rows.map { if (it.id == currentId) it.copy(percent = progress.percent) else it },
            currentId = currentId,
            onOpen = { id ->
                chaptersOpen = false
                if (id != currentId) onOpenWorkChapter?.invoke(id)
            },
            onDismiss = { chaptersOpen = false },
        )
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

/** "Aa · Settings": only the Aa gains a label that says what it opens (FEATURES, part 6). */
@Composable
private fun SettingsButton(enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.reader_settings_description)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = barTextColors(),
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = description },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Aa", fontFamily = FontFamily.Serif, fontSize = 18.sp, color = LocalPalette.current.action.takeIf { enabled } ?: LocalPalette.current.secondaryText)
            Text(stringResource(R.string.reader_settings), fontSize = 10.sp, maxLines = 1)
        }
    }
}

/**
 * What a chapter button does: in a book, the contents' item [step] away; in a split file, its own
 * chapter [step] away while there is one; otherwise [outside], the work's chapter beside this file.
 */
private fun chapterMove(
    type: WorkType,
    contents: List<ReaderContentsItem>?,
    chapters: ReaderChapters?,
    current: Int?,
    step: Int,
    webView: WebView?,
    outside: (() -> Unit)?,
): (() -> Unit)? {
    if (type != WorkType.NOVELS) return if (contents.isNullOrEmpty()) null else { { webView?.goToNeighbour(step) } }
    val inFile = chapters?.let { known -> current?.plus(step)?.takeIf { it in known.keys.indices } }
    return inFile?.let { chapter -> { webView?.goToChapter(chapter) } } ?: outside
}

/** The place the bars and the footer show for the page's [message]. */
private fun progressOf(message: PageMessage.Relocated, type: WorkType, chapterOf: (Int?) -> ChapterPlace?): TextProgress {
    if (type != WorkType.NOVELS) return TextProgress(message.position.value / 10_000f, null)
    // A split file measures its chapter; a file that is one chapter is its own measure.
    val inChapter = if (message.chapter != null) message.chapterPosition else message.position
    return TextProgress((inChapter?.value ?: 0) / 10_000f, chapterOf(message.chapter))
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
