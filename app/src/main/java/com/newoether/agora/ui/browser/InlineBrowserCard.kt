package com.newoether.agora.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R

/**
 * Muse-style INLINE browser card: renders as an item inside the chat
 * message list (LazyColumn), not as a top overlay. Scrolls with the
 * conversation like any other message — the user keeps full access to the
 * chat while the browser works. Tap to expand to the fullscreen dialog.
 *
 * Visibility mirrors [BrowserWatchPanelHost]: only while the chat's browser
 * session is active (or takeover is on) and the panel isn't hidden.
 */
@Composable
fun InlineBrowserCard(
    controller: BrowserWatchController?,
    modifier: Modifier = Modifier,
) {
    if (controller == null) return
    val sessionActive by controller.sessionActive.collectAsState()
    val takeoverActive by controller.takeoverActive.collectAsState()
    val panelHidden by controller.panelHidden.collectAsState()
    AnimatedVisibility(
        visible = (sessionActive || takeoverActive) && !panelHidden,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        InlineBrowserCardContent(controller = controller)
    }
}

@Composable
private fun InlineBrowserCardContent(controller: BrowserWatchController) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val pageUrl by controller.pageUrl.collectAsState()
    val pageTitle by controller.pageTitle.collectAsState()
    val narration by controller.narration.collectAsState()
    val frame by controller.screenshot.collectAsState()
    val liveWebView by controller.liveWebView.collectAsState()
    val takeoverActive by controller.takeoverActive.collectAsState()
    val actionCursor by controller.actionCursor.collectAsState()

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        onClick = { fullscreen = true },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Web,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = pageTitle.ifBlank { stringResource(R.string.browser_watch_title) },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (pageUrl.isNotBlank()) {
                        Text(
                            text = pageUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (takeoverActive) {
                    Icon(
                        Icons.Default.TouchApp,
                        contentDescription = stringResource(R.string.browser_takeover),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = stringResource(R.string.browser_fullscreen),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            // Tab strip: the user can open/switch/close tabs; the agent's
            // browser_tab tool and parallel run_task runs use the same tabs.
            BrowserTabStrip(controller = controller)
            // Compact live thumbnail — the narration carries the story.
            BrowserFrame(
                frame = frame,
                webView = liveWebView,
                takeoverActive = takeoverActive,
                controller = controller,
                actionCursor = actionCursor,
                frameModifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .aspectRatio(16f / 9f),
            )
            if (narration.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = narration,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = controller::onStop) {
                    Text(stringResource(R.string.browser_stop))
                }
                Button(onClick = controller::onTakeOver) {
                    Text(stringResource(R.string.browser_takeover))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = controller::onHidePanel) {
                    Icon(
                        Icons.Default.VisibilityOff,
                        contentDescription = stringResource(R.string.browser_hide),
                    )
                }
            }
        }
    }

    if (fullscreen) {
        BrowserPopupDialog(
            controller = controller,
            onDismiss = { fullscreen = false },
        )
    }
}
