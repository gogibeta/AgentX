package com.newoether.agora.api.typesafe

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * TypeSafe AI (Jev) provider entry.
 *
 * Jev is a *decision* model, not a chat model: it answers typed Choice / Score / Noul
 * questions via `POST /v1/systemone` ([TypeSafeClient]) instead of generating text.
 * This entry exists so users can store their TypeSafe API key and an optional custom
 * base URL (proxy/gateway/self-hosted Jev-compatible server) in the standard provider
 * settings, refresh the model list via `GET /v1/models`, and — in later phases — drive
 * the Jev decision layer (model routing, search re-rank, guardrails, compaction).
 *
 * Chat generation with a Jev model is rejected with a clear [GenerationError.Configuration]
 * instead of a confusing HTTP error.
 */
class TypeSafeProvider : LlmProvider {
    override val name: String = Constants.PROVIDER_TYPESAFE
    override val defaultBaseUrl: String = TypeSafeClient.DEFAULT_BASE_URL

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig,
    ): Flow<StreamEvent> = flow {
        emit(
            StreamEvent.Error(
                GenerationError.Configuration(
                    "TypeSafe Jev is a decision model, not a chat model: " +
                        "it answers typed Choice/Score/Noul questions via /v1/systemone. " +
                        "Pick a chat provider for this conversation; the TypeSafe key is " +
                        "used for Jev decisions (routing, guardrails, re-rank).",
                ),
            ),
        )
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> =
        withContext(Dispatchers.IO) {
            val effectiveBase = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
            try {
                TypeSafeClient.listModels(apiKey, effectiveBase).ifEmpty {
                    listOf(TypeSafeClient.DEFAULT_MODEL)
                }
            } catch (_: Exception) {
                // Offline / bad key: still offer the alias so settings stay usable.
                listOf(TypeSafeClient.DEFAULT_MODEL)
            }
        }
}
