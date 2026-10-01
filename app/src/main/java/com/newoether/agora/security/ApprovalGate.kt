package com.newoether.agora.security

import android.content.Context
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the agent is asking the user to approve (spec §1.3.6). */
enum class ApprovalKind {
    /** First navigation to a domain not on the allowlist. */
    FIRST_VISIT,

    /** Downloading a file to app storage. */
    DOWNLOAD,

    /** Submitting a form / POST with personal data. */
    FORM_SUBMIT,

    /** Any payment or purchase flow. */
    PAYMENT,
}

/**
 * User decision. Fail-closed: timeouts, superseded requests, and errors all
 * become [DENIED] — the agent never proceeds without an explicit approve.
 */
enum class ApprovalResult {
    APPROVED,
    DENIED,
}

/**
 * Structured approval request. Built ONLY from app-controlled values
 * (kind/domain/redacted detail) — never from page text or model output — so
 * prompt-injected content cannot manufacture or alter what the dialog shows.
 * Rendered by [com.newoether.agora.ui.security.ApprovalDialogHost] OUTSIDE the
 * chat message list (anti-injection, Muse-style).
 */
data class ApprovalRequest(
    val id: String,
    val kind: ApprovalKind,
    val domain: String,
    /**
     * Caller-supplied REDACTED summary, e.g. "Download: report.pdf (2.4 MB)"
     * or "Submit login form on accounts.example.com". Callers MUST NOT pass
     * page text, URLs with credentials, or secret values here.
     */
    val detail: String,
    val requestedAt: Long,
)

/**
 * Approval policy engine for the browser tool layer (spec §1.3.6).
 *
 * Tool-layer call sites (stream A):
 * - before `browser_navigate` to a domain not on the allowlist → [ApprovalKind.FIRST_VISIT]
 * - before starting a download → [ApprovalKind.DOWNLOAD]
 * - before submitting a form / POST with personal data → [ApprovalKind.FORM_SUBMIT]
 * - before any payment/purchase step → [ApprovalKind.PAYMENT]
 *
 * Flow: [requestApproval] suspends until the user taps Approve/Deny in the
 * out-of-chat dialog ([pendingRequest] is what the dialog host observes), the
 * request times out ([APPROVAL_TIMEOUT_MS] → [ApprovalResult.DENIED]), or the
 * request is superseded by a newer one (also [ApprovalResult.DENIED]).
 * [ApprovalKind.FIRST_VISIT] on an allowlisted domain auto-approves without a
 * dialog; approving a first visit adds the domain to the allowlist ("always"
 * scope). Downloads, form submits, and payments ALWAYS require a dialog.
 *
 * Every decision is audit-logged via [DebugLog.event] with (domain, kind,
 * decision, timestamp) — never secret or page content.
 *
 * Thread-safe. Construct with the application context; the single app-scoped
 * instance must be shared between the tool layer and the dialog host.
 */
class ApprovalGate(appContext: Context) {

    private val context: Context = appContext.applicationContext
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _pendingRequest = MutableStateFlow<ApprovalRequest?>(null)

    /**
     * The request currently awaiting a user decision, or null. Observed by the
     * approval dialog host — never by the chat message list.
     */
    val pendingRequest: StateFlow<ApprovalRequest?> = _pendingRequest.asStateFlow()

    private val _allowedDomains = MutableStateFlow<Set<String>>(emptySet())

    /** Domains the user has approved for first-visit (reactive for settings UI). */
    val allowedDomains: StateFlow<Set<String>> = _allowedDomains.asStateFlow()

    init {
        // The allowlist is not sensitive: plain SharedPreferences, loaded off
        // the calling thread so construction is main-thread safe.
        ioScope.launch { _allowedDomains.value = loadAllowlist() }
    }

    // ── Approval API ──────────────────────────────────────────────

    /**
     * Suspend until the user approves/denies. Returns [ApprovalResult.APPROVED]
     * only on explicit user approval (or an allowlisted [ApprovalKind.FIRST_VISIT]).
     *
     * @param detail redacted, app-built summary — NEVER page text or secrets.
     */
    suspend fun requestApproval(
        kind: ApprovalKind,
        domain: String,
        detail: String,
    ): ApprovalResult {
        val cleanDomain = normalizeDomain(domain)
        if (cleanDomain.isEmpty()) {
            audit(kind, "", "denied", "empty-domain")
            return ApprovalResult.DENIED
        }
        if (kind == ApprovalKind.FIRST_VISIT && isDomainAllowed(cleanDomain)) {
            audit(kind, cleanDomain, "approved", "allowlisted")
            return ApprovalResult.APPROVED
        }

        val request = ApprovalRequest(
            id = UUID.randomUUID().toString(),
            kind = kind,
            domain = cleanDomain,
            detail = detail.take(MAX_DETAIL_CHARS),
            requestedAt = System.currentTimeMillis(),
        )
        val gate = CompletableDeferred<Boolean>()
        // A newer request supersedes a still-waiting one: fail the old one
        // closed rather than leaving it answerable behind the dialog.
        pending.values.forEach { it.completeExceptionally(SupersededException()) }
        pending.clear()
        pending[request.id] = gate
        _pendingRequest.value = request
        audit(kind, cleanDomain, "requested", "dialog-shown")

        var failure: String? = null // "timeout" | "superseded"
        val approved = try {
            withTimeout(APPROVAL_TIMEOUT_MS) { gate.await() }
        } catch (_: SupersededException) {
            failure = "superseded"
            false
        } catch (_: TimeoutCancellationException) {
            failure = "timeout"
            false
        } finally {
            pending.remove(request.id)
            // Don't clear a newer request that replaced this one.
            _pendingRequest.compareAndSet(request, null)
        }

        if (approved && kind == ApprovalKind.FIRST_VISIT) {
            // First-visit approval = "always" scope: remember this site.
            allowDomain(cleanDomain)
        }
        audit(
            kind,
            cleanDomain,
            if (approved) "approved" else "denied",
            if (approved) "user" else failure ?: "user",
        )
        return if (approved) ApprovalResult.APPROVED else ApprovalResult.DENIED
    }

    /**
     * Complete a pending request. Called by the approval dialog's
     * Approve/Deny buttons (main thread — non-suspending by design).
     * Returns false when the request id is unknown or already settled.
     */
    fun respond(requestId: String, approved: Boolean): Boolean =
        pending[requestId]?.complete(approved) ?: false

    // ── Domain allowlist ──────────────────────────────────────────

    /** True when the user has approved first-visit for this domain before. */
    fun isDomainAllowed(domain: String): Boolean =
        normalizeDomain(domain).let { it.isNotEmpty() && it in _allowedDomains.value }

    /** Add a domain to the allowlist (persisted). */
    suspend fun allowDomain(domain: String): Unit = withContext(Dispatchers.IO) {
        val clean = normalizeDomain(domain)
        if (clean.isEmpty()) return@withContext
        mutex.withLock {
            val updated = _allowedDomains.value + clean
            if (updated != _allowedDomains.value) {
                _allowedDomains.value = updated
                saveAllowlist(updated)
                audit(ApprovalKind.FIRST_VISIT, clean, "allowlisted", "explicit")
            }
        }
    }

    /** Remove a domain from the allowlist (persisted). */
    suspend fun revokeDomain(domain: String): Unit = withContext(Dispatchers.IO) {
        val clean = normalizeDomain(domain)
        if (clean.isEmpty()) return@withContext
        mutex.withLock {
            val updated = _allowedDomains.value - clean
            if (updated != _allowedDomains.value) {
                _allowedDomains.value = updated
                saveAllowlist(updated)
                audit(ApprovalKind.FIRST_VISIT, clean, "revoked", "explicit")
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────

    private fun allowlistPrefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadAllowlist(): Set<String> =
        allowlistPrefs().getStringSet(KEY_ALLOWLIST, emptySet()).orEmpty().toSet()

    private fun saveAllowlist(domains: Set<String>) {
        allowlistPrefs().edit().putStringSet(KEY_ALLOWLIST, domains.toSet()).apply()
    }

    /** Defensive host extraction: callers pass domains, but never trust it. */
    private fun normalizeDomain(raw: String): String {
        var d = raw.trim().lowercase()
        val schemeIdx = d.indexOf("://")
        if (schemeIdx >= 0) d = d.substring(schemeIdx + 3)
        val slashIdx = d.indexOf('/')
        if (slashIdx >= 0) d = d.substring(0, slashIdx)
        val portIdx = d.indexOf(':')
        if (portIdx >= 0) d = d.substring(0, portIdx)
        return d.trim()
    }

    /** Audit event: (domain, kind, decision, timestamp) — never secrets or page text. */
    private fun audit(kind: ApprovalKind, domain: String, decision: String, reason: String) {
        DebugLog.event(
            TAG,
            mapOf(
                "event" to "approval.$decision",
                "kind" to kind.name,
                "domain" to domain,
                "reason" to reason,
                "ts" to System.currentTimeMillis().toString(),
            ),
            "approval $decision",
        )
    }

    companion object {
        private const val TAG = "ApprovalGate"
        private const val PREFS_NAME = "approval_allowlist"
        private const val KEY_ALLOWLIST = "allowed_domains"

        /** Dialog auto-denies after this long with no user response (fail-closed). */
        const val APPROVAL_TIMEOUT_MS = 120_000L

        /** Detail is a redacted summary, not page text — keep it short. */
        const val MAX_DETAIL_CHARS = 200
    }
}

/** Thrown into a still-waiting approval when a newer request supersedes it. */
private class SupersededException : Exception()
