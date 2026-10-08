package com.newoether.agora.ui.browser

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R

/**
 * Horizontal tab strip for the browser card: one chip per open tab,
 * a close affordance on each, and a + button to open a new tab.
 * The agent's `browser_tab` tool and parallel `browser_run_task` runs
 * operate on the same tabs the user sees here.
 */
@Composable
fun BrowserTabStrip(
    controller: BrowserWatchController,
    modifier: Modifier = Modifier,
) {
    val tabs by controller.tabs.collectAsState()
    val activeTabId by controller.activeTabId.collectAsState()
    if (tabs.isEmpty()) return

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tabId ->
            val selected = tabId == activeTabId
            FilterChip(
                selected = selected,
                onClick = { controller.onSwitchTab(tabId) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Tab $tabId",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                        if (tabs.size > 1) {
                            IconButton(
                                onClick = { controller.onCloseTab(tabId) },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.browser_close_tab),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
        Spacer(Modifier.width(4.dp))
        IconButton(
            onClick = controller::onNewTab,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.browser_new_tab),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
    Spacer(modifier = Modifier.padding(vertical = 2.dp))
}
