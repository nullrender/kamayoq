package app.kamayoq.android

import androidx.compose.runtime.Composable

/** A chapter of the work as the reader's list shows it; [percent] is how far a chapter begun goes. */
data class ReaderChapterRow(val id: String, val name: String, val read: Boolean, val percent: Int?)

/** Go to. Not drawn yet. */
@Composable
internal fun GoToSheet(progress: TextProgress, onGo: (Double) -> Unit, onStart: () -> Unit, onDismiss: () -> Unit) = Unit

@Composable
internal fun GoToContent(progress: TextProgress, onGo: (Double) -> Unit, onStart: () -> Unit) = Unit

/** A novel's chapters in the reader. Not drawn yet. */
@Composable
internal fun ReaderChaptersSheet(rows: List<ReaderChapterRow>, currentId: String?, onOpen: (String) -> Unit, onDismiss: () -> Unit) = Unit

@Composable
internal fun ReaderChaptersContent(rows: List<ReaderChapterRow>, currentId: String?, onOpen: (String) -> Unit) = Unit
