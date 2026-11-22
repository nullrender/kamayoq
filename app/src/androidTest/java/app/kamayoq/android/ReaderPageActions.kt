package app.kamayoq.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.annotation.StringRes

/**
 * Turns the page as explore by touch, switch access and keyboards do: by the reader's own action, with
 * no side of the page to touch (slice 5). Fails when the reader offers no such action.
 */
fun AndroidComposeTestRule<*, *>.pageAction(@StringRes label: Int) {
    val name = activity.getString(label)
    val node = onNode(SemanticsMatcher("offers the action $name") { candidate ->
        candidate.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == name } == true
    }).fetchSemanticsNode()
    val action = node.config[SemanticsActions.CustomActions].first { it.label == name }
    runOnUiThread { action.action() }
}
