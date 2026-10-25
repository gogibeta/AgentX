package com.newoether.agora.ui.browser

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R

/**
 * Browser button for the chat composer (muse.ai-style).
 *
 * Tapping it pops the chat's browser open in a desktop-view dialog — the same
 * dialog the watch panel's fullscreen button shows. The user can watch and
 * drive the page while the agent works; closing the dialog never stops the
 * session (only the Stop button does), and each chat gets its own browser.
 */
@Composable
fun BrowserOpenButton(
    modifier: Modifier = Modifier,
) {
    val controller = LocalBrowserWatchController.current ?: return
    // Hide when the browser tools are disabled in settings.
    val prefs = LocalBrowserPreferenceStore.current
    val browserEnabled = prefs?.browserEnabled?.collectAsState()?.value ?: false
    if (!browserEnabled) return
    var dialogOpen by rememberSaveable { mutableStateOf(false) }

    IconButton(
        onClick = {
            controller.onOpenBrowser()
            dialogOpen = true
        },
        modifier = modifier.size(32.dp),
    ) {
        Icon(
            Icons.Default.Web,
            contentDescription = stringResource(R.string.browser_open),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (dialogOpen) {
        BrowserPopupDialog(
            controller = controller,
            onDismiss = { dialogOpen = false },
        )
    }
}
