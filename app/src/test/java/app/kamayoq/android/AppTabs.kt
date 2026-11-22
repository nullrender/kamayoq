package app.kamayoq.android

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick

/**
 * Goes to the Library by its item in the bar, as a person does: the app opens on Home (slice 4 of
 * the v1 interface). Waits up to 30 s for the bar, as the first screen of a run is slow in CI.
 */
internal fun ComposeTestRule.goToLibrary(context: Context) {
    val item = hasText(context.getString(R.string.nav_library)) and isSelectable()
    waitUntil(30_000) { onAllNodes(item).fetchSemanticsNodes().isNotEmpty() }
    onNode(item).performClick()
}
