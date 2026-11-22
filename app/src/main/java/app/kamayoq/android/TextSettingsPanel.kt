package app.kamayoq.android

import androidx.compose.runtime.Composable
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.reader.ReaderFlow
import app.kamayoq.core.reader.TextSettings

/** The settings panel of the text reader. Not drawn yet. */
@Composable
internal fun TextSettingsPanel(
    settings: TextSettings,
    onSettings: (TextSettings) -> Unit,
    type: WorkType,
    flow: ReaderFlow,
    onFlow: (ReaderFlow) -> Unit,
    touchExploration: Boolean,
    hyphenates: Boolean,
    systemDark: Boolean,
    onDismiss: () -> Unit,
) = Unit

/** What the panel holds, apart from its sheet. Not drawn yet. */
@Composable
internal fun TextSettingsContent(
    settings: TextSettings,
    onSettings: (TextSettings) -> Unit,
    type: WorkType,
    flow: ReaderFlow,
    onFlow: (ReaderFlow) -> Unit,
    touchExploration: Boolean,
    hyphenates: Boolean,
    systemDark: Boolean,
) = Unit
