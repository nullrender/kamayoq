package app.kamayoq.core.reader

/**
 * Turning pages in the image reader's Pages. Index 0 is the first image and [totalPages] the
 * end-of-chapter block after the last one, as in the reading position (StripPosition).
 */
object ImagePages {
    /**
     * What a touch at [x] (0 at the left edge, 1 at the right) asks for, by the same sides as the text
     * reader's Pages. Right to left, the left side goes forward (product design, "Leitor de imagem", 4).
     * The Strip scrolls, so it has no page to turn.
     */
    fun zone(x: Double, mode: ImageMode): TapZone {
        val side = TapZones.of(x, ReaderFlow.PAGES)
        return when (mode) {
            ImageMode.STRIP -> TapZone.CONTROLS
            ImageMode.PAGES_LEFT_TO_RIGHT -> side
            ImageMode.PAGES_RIGHT_TO_LEFT -> when (side) {
                TapZone.PREVIOUS -> TapZone.NEXT
                TapZone.NEXT -> TapZone.PREVIOUS
                TapZone.CONTROLS -> TapZone.CONTROLS
            }
        }
    }

    /**
     * The power of two a page shown whole in Pages is decoded at: the smallest that keeps it within
     * twice its size on screen, so a zoom keeps detail, and within [StripBands.MAX_DECODED_WIDTH] on
     * each side, the common GPU texture limit. Null while the screen has no size yet.
     */
    fun sampleSize(width: Int, height: Int, screenWidth: Int, screenHeight: Int): Int? {
        if (width <= 0 || height <= 0 || screenWidth <= 0 || screenHeight <= 0) return null
        val fit = minOf(screenWidth.toDouble() / width, screenHeight.toDouble() / height)
        val maxWidth = minOf(maxOf(1.0, 2 * width * fit), StripBands.MAX_DECODED_WIDTH.toDouble())
        val maxHeight = minOf(maxOf(1.0, 2 * height * fit), StripBands.MAX_DECODED_WIDTH.toDouble())
        var sample = 1
        while (width / sample > maxWidth || height / sample > maxHeight) sample *= 2
        return sample
    }

    /** The page a turn from [page] reaches; past either end it stays where it is. */
    fun turned(page: Int, totalPages: Int, zone: TapZone): Int = when (zone) {
        TapZone.NEXT -> (page + 1).coerceAtMost(totalPages)
        TapZone.PREVIOUS -> (page - 1).coerceAtLeast(0)
        TapZone.CONTROLS -> page
    }
}
