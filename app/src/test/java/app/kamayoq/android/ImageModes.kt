package app.kamayoq.android

import app.kamayoq.core.reader.ImageMode
import app.kamayoq.core.reader.ImageModeChoice
import app.kamayoq.core.reader.ImageModeSettings

/** A work with no mode chosen, whose metadata says [mode]: it opens in [mode]. */
internal fun detectedAs(mode: ImageMode) = ImageModeSettings(ImageModeChoice.AUTOMATIC, own = null, detected = mode)
