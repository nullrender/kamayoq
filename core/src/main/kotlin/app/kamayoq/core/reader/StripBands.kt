package app.kamayoq.core.reader

/**
 * How a page of the Strip is decoded: shrunk by a power of two to about the screen width, then cut
 * into horizontal bands small enough that no band becomes a huge GPU texture (the approach Mihon
 * uses in WebtoonStripImageView; decision of 25/10/2026). Sizes are in pixels.
 */
object StripBands {
    /** Upper bound for one decoded band, in bytes, at 4 bytes per pixel (Mihon uses about 4 MB). */
    const val MAX_BAND_BYTES = 4 * 1024 * 1024

    /** Widest decoded band: a common GPU texture limit, and the one Mihon keeps to. */
    const val MAX_DECODED_WIDTH = 4096

    /**
     * The largest power of two that keeps the decoded width at least the screen width, while never
     * above [MAX_DECODED_WIDTH]. An image narrower than the screen is not shrunk.
     */
    fun sampleSize(imageWidth: Int, screenWidth: Int): Int {
        require(imageWidth >= 1 && screenWidth >= 1) { "Impossible sizes: $imageWidth, $screenWidth" }
        var sample = 1
        while (imageWidth / (sample * 2) >= screenWidth) sample *= 2
        while (decoded(imageWidth, sample) > MAX_DECODED_WIDTH) sample *= 2
        return sample
    }

    /**
     * Rows of the original image for each band, top to bottom, covering it without gaps or overlap.
     * Every boundary but the last is a multiple of [sampleSize], so shrunk bands meet exactly.
     */
    fun bands(imageWidth: Int, imageHeight: Int, sampleSize: Int): List<IntRange> {
        require(imageWidth >= 1 && imageHeight >= 1) { "Impossible size: $imageWidth × $imageHeight" }
        require(sampleSize >= 1 && sampleSize and (sampleSize - 1) == 0) { "Not a power of two: $sampleSize" }
        val decodedRowBytes = decoded(imageWidth, sampleSize) * BYTES_PER_PIXEL
        // Rows of the original per band: as many shrunk rows as fit, each standing for sampleSize rows.
        val rows = maxOf(1, MAX_BAND_BYTES / decodedRowBytes) * sampleSize
        return (0 until imageHeight step rows).map { top -> top..minOf(top + rows, imageHeight) - 1 }
    }

    /** Height of the page on screen when it fills the screen width, known before decoding. */
    fun displayHeight(imageWidth: Int, imageHeight: Int, screenWidth: Int): Int {
        require(imageWidth >= 1 && imageHeight >= 1 && screenWidth >= 1) { "Impossible sizes" }
        return ((imageHeight.toLong() * screenWidth + imageWidth / 2) / imageWidth).toInt()
    }

    private const val BYTES_PER_PIXEL = 4

    /** Width or height after shrinking; the decoder rounds up. */
    private fun decoded(size: Int, sampleSize: Int) = (size + sampleSize - 1) / sampleSize
}
