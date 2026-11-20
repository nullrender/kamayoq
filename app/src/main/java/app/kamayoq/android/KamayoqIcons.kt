package app.kamayoq.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The boards' icons, drawn from their own SVG paths on a 24-unit grid, so the app needs no icon
 * library. The colour is set where each icon is used.
 */
internal object KamayoqIcons {
    private fun stroked(name: String, width: Float, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(
                pathData = addPathNodes(it),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    private fun filled(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
    }.build()

    val home = filled("home", "M12 3.2l8.5 7.3V20a1 1 0 0 1-1 1h-4.5v-6.5h-6V21H4.5a1 1 0 0 1-1-1v-9.5z")

    val library = filled(
        "library",
        "M5 4h1.4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z",
        "M10 4h1.4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H10a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z",
        "M14.6 6.3l3.2-.9 3.8 13.8-3.2.9z",
    )

    /** The sliders the first-open board uses for the app's settings. */
    val settings = stroked(
        "settings",
        2.6f,
        "M4 8h10M18 8h2M4 16h2M10 16h10",
        "M18 8a2 2 0 1 1-4 0a2 2 0 1 1 4 0",
        "M10 16a2 2 0 1 1-4 0a2 2 0 1 1 4 0",
    )

    /** A section header's arrow, pointing right; turned down while the section is open. */
    val chevron = stroked("chevron", 2.2f, "M9 6l6 6-6 6")

    val search = stroked("search", 2f, "M17 10.5a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0", "M15.5 15.5l5 5")

    val back = stroked("back", 2f, "M15 5l-7 7 7 7")
}
