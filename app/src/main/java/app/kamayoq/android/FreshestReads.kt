package app.kamayoq.android

import java.util.concurrent.atomic.AtomicLong

/**
 * Orders the reads of the store whose results end on one screen. Several run at the same time (the
 * folder's reading, Kavita's, the stored list) and each ends when its thread does, so a read begun
 * before a write can end after a read begun after it, and would show the screen what the write
 * already changed: the Library then said it had no works while the store held them. Each read takes
 * a ticket as it begins; a result is shown only when its ticket is newer than the one on screen.
 * Every write is followed by a read of its own, so the newest read always shows the last write.
 */
class FreshestReads {
    private val begun = AtomicLong()

    /** Called on the read's own thread, just before it reads. */
    fun begin(): Long = begun.incrementAndGet()

    /** Called on the screen's thread with the read's ticket: true when its result may be shown. */
    fun accept(ticket: Long): Boolean = true
}
