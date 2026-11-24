package polaris.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import polaris.android.shell.LocalWindowLayout

/**
 * The app's dialogs. On a phone, Material's centred dialog. When the window is wide (an unfolded
 * phone), it opens on the leading half, over the card it belongs to, so its text never runs
 * across the fold.
 */
@Composable
fun HalfDialog(
    onDismissRequest: () -> Unit,
    title: String?,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    /** Inside the dialog's edge; 0 for content that needs the full width (the date picker). */
    contentPadding: androidx.compose.ui.unit.Dp = 24.dp,
    content: @Composable () -> Unit,
) {
    val layout = LocalWindowLayout.current
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismissRequest)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = if (layout.wide) Alignment.CenterStart else Alignment.Center,
        ) {
            Surface(
                // Clicks inside the dialog don't dismiss it.
                modifier = Modifier
                    .then(if (layout.wide) Modifier.padding(start = 16.dp).width(layout.cardWidth) else Modifier.padding(24.dp).widthIn(max = 560.dp))
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 24.dp, horizontal = contentPadding)) {
                    title?.let {
                        Text(it, Modifier.padding(bottom = 16.dp).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    }
                    content()
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, end = if (contentPadding == 0.dp) 16.dp else 0.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}
