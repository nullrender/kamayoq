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
    fun detect(signals: ImageSignals): Detection = Detection(ImageMode.STRIP, DetectionStep.NOTHING_DECIDED)
}
