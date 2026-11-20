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

    val folder = stroked("folder", 2f, "M3.5 7.5a2 2 0 0 1 2-2h4l2 2.5h7a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z")

    val server = stroked(
        "server",
        2f,
        "M5.5 4h13a1.5 1.5 0 0 1 1.5 1.5V9a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 9V5.5A1.5 1.5 0 0 1 5.5 4z",
        "M5.5 13.5h13a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18.5V15a1.5 1.5 0 0 1 1.5-1.5z",
        "M8 7.2h.01M8 16.8h.01",
    )

    val restore = stroked("restore", 2f, "M4.5 12a7.5 7.5 0 1 0 2.2-5.3", "M4 4v4h4", "M12 8v4l2.5 2")

    /** A section header's arrow, pointing right; turned down while the section is open. */
    val chevron = stroked("chevron", 2.2f, "M9 6l6 6-6 6")

    val search = stroked("search", 2f, "M17 10.5a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0", "M15.5 15.5l5 5")

    val back = stroked("back", 2f, "M15 5l-7 7 7 7")

    val more = filled(
        "more",
        "M12 4.2a1.8 1.8 0 1 1 0 3.6a1.8 1.8 0 1 1 0-3.6z",
        "M12 10.2a1.8 1.8 0 1 1 0 3.6a1.8 1.8 0 1 1 0-3.6z",
        "M12 16.2a1.8 1.8 0 1 1 0 3.6a1.8 1.8 0 1 1 0-3.6z",
    )

    val close = stroked("close", 2.2f, "M6 6l12 12", "M18 6L6 18")
}
