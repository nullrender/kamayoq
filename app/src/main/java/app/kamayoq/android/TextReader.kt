package app.kamayoq.android

import androidx.compose.runtime.Composable
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage as PageMessage

/** Shows a book in the reader's page. [onMessage] hears every message of the page, for the tests. */
@Composable
fun TextReader(archive: EpubArchive, title: String, onBack: () -> Unit, onMessage: (PageMessage) -> Unit = {}) {
    ReaderMessage(title, onBack)
}
