package com.newoether.agora.tool

import com.newoether.agora.viewmodel.GenerationContext

/**
 * Redacts known secrets from tool result text before it reaches the model.
 *
 * The agent's environment variables (see `list_env`) are exported into every shell
 * command, so a command like `export` or `env` echoes every secret value back in its
 * output. Without redaction the model — and the persisted conversation history that
 * feeds future model turns — would see the user's GitHub tokens, Cloudflare keys,
 * and other credentials in plaintext.
 *
 * Redaction happens centrally in [com.newoether.agora.viewmodel.GenerationToolExecutor]
 * so every tool provider is covered and both the live result and the persisted
 * transcript stay secret-free. Pure — unit-tested.
 */
internal object ToolResultSecretRedactor {
    const val REDACTED = "[REDACTED_SECRET]"

    /**
     * Secrets shorter than this are never redacted. Redacting a 2-character value
     * would destroy ordinary output with false positives; real credentials
     * (API keys, tokens) are far longer.
     */
    private const val MIN_SECRET_LENGTH = 8

    /**
     * Collects every secret the model must never see from the generation context:
     * agent environment variable values plus all configured provider API keys.
     * (The main LLM apiKey lives in GenerationConfig, outside tool context;
     * everything reachable from here is covered.)
     */
    fun secretsFrom(ctx: GenerationContext): Set<String> = buildSet {
        addAll(ctx.agentEnv.values)
        add(ctx.embeddingApiKey)
        addAll(ctx.webSearchApiKeys.values)
        add(ctx.imageGenApiKey)
        add(ctx.transcriptionApiKey)
        add(ctx.typeSafeApiKey)
    }

    /**
     * Returns [text] with every known secret value replaced by [REDACTED].
     * Longest values are replaced first so overlapping secrets redact correctly.
     * Values shorter than [MIN_SECRET_LENGTH] (or blank) are ignored.
     */
    fun redact(text: String, secrets: Collection<String>): String {
        if (text.isEmpty()) return text
        return secrets.asSequence()
            .filter { it.length >= MIN_SECRET_LENGTH }
            .sortedByDescending { it.length }
            .fold(text) { redacted, secret -> redacted.replace(secret, REDACTED) }
    }

    /** Redacts secrets from every text field of a tool result. */
    fun redactResult(result: ToolExecutionResult, ctx: GenerationContext): ToolExecutionResult {
        val secrets = secretsFrom(ctx)
        if (secrets.none { it.length >= MIN_SECRET_LENGTH }) return result
        return result.copy(
            text = redact(result.text, secrets),
            displayText = result.displayText?.let { redact(it, secrets) },
            structuredContent = result.structuredContent?.let { redact(it, secrets) },
        )
    }
}
