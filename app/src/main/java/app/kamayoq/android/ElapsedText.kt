package app.kamayoq.android

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import app.kamayoq.core.home.Elapsed
import app.kamayoq.core.home.Home
import java.time.Instant

/** How long ago [at] was, as Home and the Work screen say it ("2 h ago"), by core Home's rule. */
@Composable
internal fun elapsedText(at: Instant): String = elapsedText(LocalResources.current, at, Instant.now())

internal fun elapsedText(resources: Resources, at: Instant, now: Instant): String = when (val gone = Home.elapsed(at, now)) {
    Elapsed.JustNow -> resources.getString(R.string.elapsed_just_now)
    is Elapsed.Minutes -> resources.getQuantityString(R.plurals.elapsed_minutes, gone.count.toInt(), gone.count.toInt())
    is Elapsed.Hours -> resources.getQuantityString(R.plurals.elapsed_hours, gone.count.toInt(), gone.count.toInt())
    Elapsed.Yesterday -> resources.getString(R.string.elapsed_yesterday)
    is Elapsed.Days -> resources.getQuantityString(R.plurals.elapsed_days, gone.count.toInt(), gone.count.toInt())
}
