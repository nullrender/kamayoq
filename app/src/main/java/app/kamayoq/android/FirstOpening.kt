package app.kamayoq.android

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The empty app (board PrimeiraAbertura): no tour, the reason it is empty and the ways in, in the
 * order decided on 24/10/2026: files on this device, Kavita, restoring a backup. Restoring is left
 * out when something is stored, a Kavita server with no work: the restore would refuse it.
 */
@Composable
internal fun FirstOpening(
    restoreBusy: Boolean,
    onChooseFolder: () -> Unit,
    onOpenKavita: () -> Unit,
    onPickBackup: () -> Unit,
    canRestore: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.empty_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.empty_humor), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.empty_where), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
        SourceCard(KamayoqIcons.folder, R.string.source_files, R.string.source_files_note, enabled = true, onClick = onChooseFolder)
        SourceCard(KamayoqIcons.server, R.string.kavita_connect, R.string.source_kavita_note, enabled = true, onClick = onOpenKavita)
        if (canRestore) {
            SourceCard(KamayoqIcons.restore, R.string.restore_backup, R.string.source_restore_note, enabled = !restoreBusy, onClick = onPickBackup)
        }
    }
}

@Composable
private fun SourceCard(icon: ImageVector, @StringRes title: Int, @StringRes note: Int, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(1.dp, colors.outlineVariant, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.6f)
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp).background(colors.surfaceContainerHighest, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Icon(KamayoqIcons.chevron, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
