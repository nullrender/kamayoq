package app.kamayoq.android

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The app's three places (decided on 19/11/2026); search lives in the Library's magnifier. */
enum class AppTab(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, KamayoqIcons.home),
    LIBRARY(R.string.nav_library, KamayoqIcons.library),
    SETTINGS(R.string.nav_settings, KamayoqIcons.settings),
}

/** Room the floating bar takes at the bottom, so the end of a list can scroll above it. */
internal val BAR_ROOM = 96.dp

/**
 * The floating bar of the boards (DESIGN.md, "Barra de navegação"): every item labelled, the chosen
 * one in the text colour on the inverted background, the others in the secondary text colour.
 */
@Composable
internal fun AppBar(current: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
            .fillMaxWidth().height(64.dp)
            .background(colors.surfaceContainerHigh, RoundedCornerShape(24.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp)
            .selectableGroup(),
    ) {
        AppTab.entries.forEach { tab ->
            val chosen = tab == current
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .selectable(selected = chosen, role = Role.Tab, onClick = { onSelect(tab) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                Box(
                    modifier = Modifier.size(width = 56.dp, height = 30.dp)
                        .then(if (chosen) Modifier.background(colors.onSurface, RoundedCornerShape(15.dp)) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = null,
                        tint = if (chosen) colors.surface else colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    stringResource(tab.label),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium),
                    color = if (chosen) colors.onSurface else colors.onSurfaceVariant,
                )
            }
        }
    }
}
