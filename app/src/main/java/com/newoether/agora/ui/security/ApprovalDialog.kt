package com.newoether.agora.ui.security

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.security.ApprovalGate
import com.newoether.agora.security.ApprovalKind
import com.newoether.agora.security.ApprovalRequest

/**
 * Approval dialog host — renders approval requests as a modal system-style
 * dialog OUTSIDE the chat message list (spec §1.3.6, Muse-style).
 *
 * PLACEMENT (integrator note): put [ApprovalDialogHost] at a top-level
 * scaffold — e.g. beside the root NavHost in MainActivity — NEVER inside the
 * chat message list or the browser watch panel. The same app-scoped
 * [ApprovalGate] instance must back both this host and the browser tool layer,
 * so [ApprovalGate.respond] completes the [ApprovalGate.requestApproval] the
 * tool is suspended on.
 *
 * ANTI-PROMPT-INJECTION: every string shown here is built by the app from the
 * structured [ApprovalRequest] (kind/domain/redacted detail). Page text and
 * model output can NEVER reach this dialog, so injected content cannot
 * manufacture a fake approval or alter what the user is approving. Dismissing
 * without choosing is impossible (no tap-outside/back dismiss); an unanswered
 * request auto-denies after [ApprovalGate.APPROVAL_TIMEOUT_MS] (fail-closed).
 */
@Composable
fun ApprovalDialogHost(gate: ApprovalGate) {
    val request by gate.pendingRequest.collectAsState()
    request?.let { req ->
        ApprovalDialog(
            request = req,
            onApprove = { gate.respond(req.id, true) },
            onDeny = { gate.respond(req.id, false) },
        )
    }
}

/**
 * The modal approval dialog itself. Content comes only from [request]'s
 * structured fields — see the anti-injection note on [ApprovalDialogHost].
 */
@Composable
fun ApprovalDialog(
    request: ApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        // Explicit choice required: no dismiss-on-tap-outside. Timeout denies.
        onDismissRequest = {},
        icon = {
            Icon(
                imageVector = when (request.kind) {
                    ApprovalKind.FIRST_VISIT -> Icons.Default.Public
                    ApprovalKind.DOWNLOAD -> Icons.Default.Download
                    ApprovalKind.FORM_SUBMIT -> Icons.Default.Send
                    ApprovalKind.PAYMENT -> Icons.Default.Payment
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.approval_title)) },
        text = {
            Column {
                Text(
                    text = when (request.kind) {
                        ApprovalKind.FIRST_VISIT -> stringResource(R.string.approval_kind_first_visit)
                        ApprovalKind.DOWNLOAD -> stringResource(R.string.approval_kind_download)
                        ApprovalKind.FORM_SUBMIT -> stringResource(R.string.approval_kind_form_submit)
                        ApprovalKind.PAYMENT -> stringResource(R.string.approval_kind_payment)
                    },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = request.domain,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (request.detail.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = request.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (request.kind == ApprovalKind.FIRST_VISIT) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.approval_auto_allow_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove) {
                Text(stringResource(R.string.approval_approve))
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text(stringResource(R.string.approval_deny))
            }
        },
    )
}
