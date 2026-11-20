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

    /** A section header's arrow, pointing right; turned down while the section is open. */
    val chevron = stroked("chevron", 2.2f, "M9 6l6 6-6 6")
}
