package app.kamayoq.core.reader

import app.kamayoq.core.library.ComicsSubtype

/** How the image reader lays a chapter out (product design, "Leitor de imagem", item 1). */
enum class ImageMode {
    STRIP,
    PAGES_LEFT_TO_RIGHT,
    PAGES_RIGHT_TO_LEFT,
}

/** What the person chose in the settings; Automatic follows what the work's metadata says. */
enum class ImageModeChoice(val wire: String, val mode: ImageMode?) {
    AUTOMATIC("auto", null),
    STRIP("strip", ImageMode.STRIP),
    PAGES_LEFT_TO_RIGHT("pages-ltr", ImageMode.PAGES_LEFT_TO_RIGHT),
    PAGES_RIGHT_TO_LEFT("pages-rtl", ImageMode.PAGES_RIGHT_TO_LEFT),
}

/** "Vale para" in the image reader's settings: this work, or every work read as images. */
enum class ImageScope { THIS_WORK, ALL_IMAGE_WORKS }

/**
 * The mode of one work: the general choice, the work's own (null follows the general one) and what
 * its metadata says. Choosing for all works leaves a work's own choice where it is, as the text
 * reader's settings do.
 */
data class ImageModeSettings(
    val general: ImageModeChoice,
    val own: ImageModeChoice?,
    val detected: ImageMode,
) {
    /** The choice the settings show selected for [scope]. */
    fun chosen(scope: ImageScope): ImageModeChoice = general

    /** The mode the chapter opens in. */
    val effective: ImageMode get() = ImageMode.STRIP

    fun choose(scope: ImageScope, choice: ImageModeChoice): ImageModeSettings = this

    /** "Restaurar padrão": this work follows the general choice again; all works go back to Automatic. */
    fun restore(scope: ImageScope): ImageModeSettings = this

    companion object {
        /**
         * What the metadata says (his decision pending, question 1 of slice 6): manga turns pages
         * right to left; manhwa, webtoon and comics with no subtype scroll as the Strip, as the app
         * opened every comic before.
         */
        fun detected(subtype: ComicsSubtype?): ImageMode = ImageMode.STRIP
    }
}
