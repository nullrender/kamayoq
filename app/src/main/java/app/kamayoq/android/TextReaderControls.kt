package app.kamayoq.android

import androidx.compose.runtime.Composable

/** The book's controls stay native so explore by touch can find real labeled buttons. */
@Composable
internal fun TextReaderControls(
    enabled: Boolean,
    onPrevious: () -> Unit,
    onContents: () -> Unit,
    onNext: () -> Unit,
) = Unit
