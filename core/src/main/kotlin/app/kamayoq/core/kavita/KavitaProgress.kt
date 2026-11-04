package app.kamayoq.core.kavita

import app.kamayoq.core.progress.KavitaDevice
import app.kamayoq.core.progress.StripPosition
import java.time.Instant

/** The position as Kavita counts it and who is right about it (progress contract, section 2d). */
object KavitaProgress {
    /** The device name of a position that came from the server (schema, alternative_position). */
    const val DEVICE = KavitaDevice.NAME

    enum class Decision { IN_SYNC, AGREE, SEND, ADOPT, CONFLICT }

    /**
     * A page counts images from 0, as here; the total marks the chapter read, as the end block does.
     * Kavita and this app may count pages differently, and the server reduces a page above its total,
     * so anything short of the end stays short of its end. Null is nothing to send: never opened, or
     * still on the first image, where any opening starts. Sending 0 would unmark the chapter in the
     * server just because it was opened again.
     */
    fun toRemote(position: StripPosition?, pageCount: Int): Int? = when {
        position == null || position.page == 0 -> null
        position.isAtEnd -> pageCount
        else -> position.page.coerceAtMost((pageCount - 1).coerceAtLeast(0))
    }

    fun fromRemote(page: Int, pageCount: Int, at: Instant): StripPosition =
        StripPosition(page.coerceIn(0, pageCount), pageCount, at, DEVICE)

    /** Null for a chapter with no pages, which has no position. */
    fun fromRemoteOrNull(page: Int, pageCount: Int, at: Instant): StripPosition? =
        if (pageCount < 1) null else fromRemote(page, pageCount, at)

    fun isRead(page: Int, pageCount: Int): Boolean = page >= pageCount

    /**
     * Compares both sides with the last agreement; dates never enter (D2). With no agreement yet, the
     * start of the chapter stands for it: page 0 with no record is what Kavita answers (facts of 30/10).
     * A null [local] has nothing to say, so it never counts as a change here.
     */
    fun decide(local: Int?, agreed: Int?, remote: Int): Decision {
        val base = agreed ?: 0
        val localChanged = local != null && local != base
        val remoteChanged = remote != base
        return when {
            !localChanged && !remoteChanged -> Decision.IN_SYNC
            local == remote -> Decision.AGREE
            !remoteChanged -> Decision.SEND
            !localChanged -> Decision.ADOPT
            else -> Decision.CONFLICT
        }
    }
}
