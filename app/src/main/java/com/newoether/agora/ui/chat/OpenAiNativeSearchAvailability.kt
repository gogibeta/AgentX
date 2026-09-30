package com.newoether.agora.ui.chat

import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.isResponsesApiEnabledForProvider
import com.newoether.agora.ui.common.AgentXHaptics
import com.newoether.agora.viewmodel.ChatViewModel

internal fun resolveOpenAiNativeSearchAvailability(
    providerName: String,
    builtInOpenAiEnabled: Boolean,
    customProviders: List<CustomProviderConfig>,
): Boolean = isResponsesApiEnabledForProvider(
    providerName = providerName,
    builtInOpenAiEnabled = builtInOpenAiEnabled,
    customProviders = customProviders,
)

internal fun updateOpenAiNativeSearch(
    viewModel: ChatViewModel,
    conversationId: String?,
    haptics: AgentXHaptics,
    enabled: Boolean,
) {
    haptics.toggle(enabled)
    viewModel.updateConversationSetting(conversationId) {
        it.copy(openAiWebSearchEnabled = enabled)
    }
}
