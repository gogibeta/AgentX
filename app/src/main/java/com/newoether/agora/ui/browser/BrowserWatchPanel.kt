package com.newoether.agora.ui.browser

import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.newoether.agora.R
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge

/**
 * Host for the live browser watch panel, placed near the top of the chat
 * (see ChatApp's message-list Box). Renders nothing until stream A provides a
 * [BrowserWatchController] AND a browser session is active (or take-over is on).
 *
 * The panel mirrors muse.ai's mini-browser widget: live screenshot stream,
 * URL bar, one-line action narration, and Stop / Take over / Approve / Deny
 * controls. Approval *decisions* always go through [BrowserApprovalDialog], a
 * separate dialog window rendered outside the chat message list, so injected
 * page text can never fake an approval inside the conversation.
 */
@Composable
fun BrowserWatchPanelHost(
    controller: BrowserWatchController?,
    modifier: Modifier = Modifier,
) {
    if (controller == null) return
    val sessionActive by controller.sessionActive.collectAsState()
    val takeoverActive by controller.takeoverActive.collectAsState()
    AnimatedVisibility(
        visible = sessionActive || takeoverActive,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        BrowserWatchPanel(controller = controller)
    }
}

@Composable
private fun BrowserWatchPanel(controller: BrowserWatchController) {
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val pageUrl by controller.pageUrl.collectAsState()
    val pageTitle by controller.pageTitle.collectAsState()
    val narration by controller.narration.collectAsState()
    val frame by controller.screenshot.collectAsState()
    val liveWebView by controller.liveWebView.collectAsState()
    val liveGeckoView by controller.liveGeckoView.collectAsState()
    val takeoverActive by controller.takeoverActive.collectAsState()
    val pendingApproval by controller.pendingApproval.collectAsState()
    var approvalDialogFor by remember { mutableStateOf<BrowserApprovalRequest?>(null) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth(),
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
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
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
                IconButton(onClick = { collapsed = !collapsed }) {
                    Icon(
                        if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = stringResource(if (collapsed) R.string.expand else R.string.collapse),
                    )
                }
            }

            AnimatedVisibility(
                visible = !collapsed,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    BrowserFrame(frame = frame, webView = liveWebView, geckoView = liveGeckoView)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = narration.ifBlank { stringResource(R.string.browser_narration_idle) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (takeoverActive) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.browser_takeover_active),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = controller::onResume) {
                                Text(stringResource(R.string.browser_resume))
                            }
                        }
                    } else {
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
                            val approval = pendingApproval
                            if (approval != null) {
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { approvalDialogFor = approval }) {
                                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.browser_approve))
                                }
                                TextButton(onClick = { approvalDialogFor = approval }) {
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.browser_deny))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    approvalDialogFor?.let { request ->
        BrowserApprovalDialog(
            request = request,
            onApproveOnce = {
                controller.onApprove(request.id)
                approvalDialogFor = null
            },
            onDeny = {
                controller.onDeny(request.id)
                approvalDialogFor = null
            },
            onDismiss = { approvalDialogFor = null },
        )
    }
}

/**
 * Browser viewport. When a live engine view is connected (System WebView or
 * GeckoView backend), it is embedded directly (visible + touchable — this is
 * also how take-control works: the user just touches it). Otherwise the
 * latest screenshot frame; placeholder box while the first frame is on its way.
 */
@Composable
private fun BrowserFrame(
    frame: ByteArray?,
    webView: android.webkit.WebView?,
    geckoView: android.view.View?,
) {
    val liveView: android.view.View? = webView ?: geckoView
    if (liveView != null) {
        AndroidView(
            factory = { liveView },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp)),
        )
        return
    }
    val bitmap = remember(frame) {
        frame?.takeIf { it.isNotEmpty() }?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.browser_watch_title),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = stringResource(R.string.browser_watch_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Approval decision dialog. Rendered in its own window ([Dialog]) — outside the
 * chat message list — per the anti-prompt-injection requirement (§1.1): page
 * text rendered as chat content can never manufacture or spoof this UI.
 */
@Composable
private fun BrowserApprovalDialog(
    request: BrowserApprovalRequest,
    onApproveOnce: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogWindowEdgeToEdge()
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.browser_approval_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = request.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                if (request.detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = request.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (request.url.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = request.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDeny) {
                        Text(stringResource(R.string.browser_deny))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onApproveOnce) {
                        Text(stringResource(R.string.browser_approval_approve_once))
                    }
                }
            }
        }
    }
}

/**
 * Chat-surface entry point for the live browser watch panel: a collapsible card
 * aligned near the top of the chat, visible only while a browser session is
 * active (the host renders nothing without a controller / active session).
 */
@Composable
fun BoxScope.ChatBrowserWatchCard() {
    BrowserWatchPanelHost(
        controller = LocalBrowserWatchController.current,
        modifier = Modifier.align(Alignment.TopCenter)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = 840.dp)
            .fillMaxWidth(),
    )
}
