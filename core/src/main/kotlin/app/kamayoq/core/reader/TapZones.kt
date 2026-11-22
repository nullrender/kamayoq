package app.kamayoq.core.reader

/** What a touch on the page asks for (board SinteseLeitorPaginado, 07/10/2026). */
enum class TapZone { PREVIOUS, CONTROLS, NEXT }

object TapZones {
    /** Each side's share of the width that turns the page in Pages; the middle shows the controls. */
    const val SIDE = 0.3

    /** [x] is the touch's place across the page, from 0 (left) to 1 (right). Scroll has no page to turn. */
    fun of(x: Double, flow: ReaderFlow): TapZone = TapZone.CONTROLS
}
