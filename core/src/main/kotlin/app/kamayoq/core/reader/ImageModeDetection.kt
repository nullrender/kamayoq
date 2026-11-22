package app.kamayoq.core.reader

/** An image's size, read from its header. */
data class ImageSize(val width: Int, val height: Int)

/** The `Manga` field of ComicInfo.xml (Anansi Project's schema): Unknown is no answer, so it has no value here. */
enum class MangaField(val wire: String) {
    NO("No"),
    YES("Yes"),
    YES_AND_RIGHT_TO_LEFT("YesAndRightToLeft"),
    ;

    companion object {
        fun of(text: String?): MangaField? = entries.firstOrNull { it.wire.equals(text?.trim(), ignoreCase = true) }
    }
}

/**
 * What the reader knows of a chapter when it opens: each image's size (null when its header did not
 * say), the work's genre and tag items, from Kavita or from the chapter's ComicInfo.xml, and ComicInfo's
 * `Manga` field.
 */
data class ImageSignals(
    val sizes: List<ImageSize?>,
    val words: List<String> = emptyList(),
    val manga: MangaField? = null,
)

/** Which step of the chain decided (docs: kamayoq-notes, research "image-mode-detection", option A). */
enum class DetectionStep { TALL_IMAGES, FORMAT_TAG, VARYING_WIDTHS, MANGA_METADATA, NOTHING_DECIDED }

data class Detection(val mode: ImageMode, val step: DetectionStep)

/**
 * The image reader's Automatic: Strip or Pages from the chapter's images and the work's metadata, and
 * for Pages the direction. Worked out at each opening and never kept, so a better rule reaches the
 * works already opened (Gustavo's decision of 22/11/2026, option A).
 */
object ImageModeDetection {
    /**
     * Height over width from which an image counts as tall. No measured manga or printed page passed
     * 1.57; 1.7 leaves room to a Strip of 720 × 1271 (the research's 1.8 left it 58 % of the area).
     * Gustavo's lead session chose 1.7 provisionally, on 22/11/2026.
     */
    const val TALL = 1.7

    /** Share of the chapter's image area in tall images that makes it a Strip. */
    const val TALL_SHARE = 0.5

    /** Below this share of images with the most common width (the first and last left out), widths vary. */
    const val SAME_WIDTH_SHARE = 0.8

    /** Images between the first and the last needed for the widths to say anything. */
    const val MIN_MIDDLE = 4

    private val formatTags = setOf("long strip", "webtoon", "webtoons", "vertical")
    private val rightToLeftWords = setOf("manga", "japanese", "japones")
    private val leftToRightWords = setOf(
        "manhwa", "manhua", "korean", "chinese", "english", "comic",
        "coreano", "chines", "ingles", "quadrinho", "quadrinhos",
    )

    fun detect(signals: ImageSignals): Detection {
        // Whole items only, without case or accents: "Contém manga" or "Manwha" say nothing.
        val words = signals.words.map(::plain).toSet()
        val sizes = signals.sizes.filterNotNull().filter { it.width > 0 && it.height > 0 }
        fun pages(step: DetectionStep) = Detection(direction(signals.manga, words), step)
        return when {
            tall(sizes) -> Detection(ImageMode.STRIP, DetectionStep.TALL_IMAGES)
            words.any { it in formatTags } -> Detection(ImageMode.STRIP, DetectionStep.FORMAT_TAG)
            varying(sizes) -> pages(DetectionStep.VARYING_WIDTHS)
            signals.manga == MangaField.YES_AND_RIGHT_TO_LEFT || words.any { it in rightToLeftWords } -> pages(DetectionStep.MANGA_METADATA)
            // Not known is not a guess: a work in pages read as the Strip still reads, in order.
            else -> Detection(ImageMode.STRIP, DetectionStep.NOTHING_DECIDED)
        }
    }

    private fun tall(sizes: List<ImageSize>): Boolean {
        val area = sizes.sumOf { it.width.toLong() * it.height }
        if (area == 0L) return false
        val tallArea = sizes.filter { it.height.toDouble() / it.width >= TALL }.sumOf { it.width.toLong() * it.height }
        return tallArea >= area * TALL_SHARE
    }

    private fun varying(sizes: List<ImageSize>): Boolean {
        // A cover and a credits page often have their own width.
        val middle = sizes.drop(1).dropLast(1)
        if (middle.size < MIN_MIDDLE) return false
        val mostCommon = middle.groupingBy { it.width }.eachCount().values.max()
        return mostCommon < middle.size * SAME_WIDTH_SHARE
    }

    /** ComicInfo first; then the words, which cancel when they disagree; with nothing, left to right. */
    private fun direction(manga: MangaField?, words: Set<String>): ImageMode = when {
        manga == MangaField.YES_AND_RIGHT_TO_LEFT -> ImageMode.PAGES_RIGHT_TO_LEFT
        manga == MangaField.NO -> ImageMode.PAGES_LEFT_TO_RIGHT
        words.any { it in rightToLeftWords } && words.none { it in leftToRightWords } -> ImageMode.PAGES_RIGHT_TO_LEFT
        else -> ImageMode.PAGES_LEFT_TO_RIGHT
    }

    private fun plain(word: String): String =
        java.text.Normalizer.normalize(word.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
