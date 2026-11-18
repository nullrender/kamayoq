package app.kamayoq.android

import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.core.reader.ReaderChapters
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.SplitReading
import app.kamayoq.core.reader.TextReading
import java.time.Instant

/**
 * What one opening of a text gives the reader's screen and takes from it: where the page opens, and
 * what each move of the person saves. A book is read whole; a compiled novel is one file read across
 * its chapters.
 */
interface TextSession {
    /** The CFI the page opens at, with its section for a CFI the file no longer resolves. */
    val startCfi: String?
    val startSection: Int?

    /** A place from Kavita: its marker, and the sections Kavita counts. */
    val startMarker: String?
    val startSections: Int?

    /** The destination a chapter of a split file opens at when it has no CFI. */
    val startHref: String?

    /** The chapters of a split file, for the page to say which one it is in. */
    val chapters: ReaderChapters?

    fun restored()

    /** Saves what the move changes and returns the place the screen is now at; null when it changes nothing. */
    fun relocated(message: ReaderMessage.Relocated, at: Instant): TextPosition?

    /** The end block shows. */
    fun ended(at: Instant)
}

/** A book, or a chapter that is a whole file: one progress, handed to [onSave] at each change. */
class WholeText(private val reading: TextReading, private val onSave: (ChapterProgress) -> Unit) : TextSession {
    override val startCfi: String? = reading.start
    override val startSection: Int? = reading.position?.section
    override val startMarker: String? = reading.startMarker
    override val startSections: Int? = reading.startSections
    override val startHref: String? = null
    override val chapters: ReaderChapters? = null

    override fun restored() = reading.restored()

    override fun relocated(message: ReaderMessage.Relocated, at: Instant): TextPosition? =
        reading.relocated(message, at)?.also { onSave(reading.progress) }

    override fun ended(at: Instant) {
        reading.ended(at)?.let(onSave)
    }
}

/**
 * A file split by its table of contents: [onSave] gets the progress of each chapter a move changes,
 * in order, the chapter being read last.
 */
class SplitText(
    private val reading: SplitReading,
    override val chapters: ReaderChapters,
    private val onSave: (List<SplitReading.Saved>) -> Unit,
) : TextSession {
    override val startCfi: String? = reading.start
    override val startSection: Int? = reading.startSection
    override val startMarker: String? = null
    override val startSections: Int? = null
    override val startHref: String? = chapters.keys[reading.current]

    override fun restored() = reading.restored()

    override fun relocated(message: ReaderMessage.Relocated, at: Instant): TextPosition? {
        val saves = reading.relocated(message, at)
        if (saves.isEmpty()) return null
        onSave(saves)
        return saves.last().progress.position as? TextPosition
    }

    override fun ended(at: Instant) {
        val saves = reading.ended(at)
        if (saves.isNotEmpty()) onSave(saves)
    }
}
