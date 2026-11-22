package app.kamayoq.android

import androidx.compose.runtime.Composable
import app.kamayoq.core.reader.ImageModeChoice
import app.kamayoq.core.reader.ImageModeSettings

@Composable
internal fun ImageModeQuickContent(settings: ImageModeSettings, onChoose: (ImageModeChoice) -> Unit) = Unit

@Composable
internal fun ImageSettingsContent(settings: ImageModeSettings, onSettings: (ImageModeSettings) -> Unit) = Unit
