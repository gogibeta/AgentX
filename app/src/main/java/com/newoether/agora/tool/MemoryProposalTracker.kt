package com.newoether.agora.tool

import java.util.concurrent.ConcurrentHashMap

/**
 * Accept/reject tracking for `delegate_task` memory proposals (v2.4).
 *
 * Each proposal surfaced to the parent gets a stable `proposal_id`
 * (`prop-<child>-<index>`). When the parent commits a proposal via the memory
 * tools and mentions its ID, [markAccepted] records it; proposals never
 * referenced are counted as rejected once superseded. Diagnostics expose the
 * acceptance rate so future delegation can learn which task shapes pay off.
 * In-memory only: no proposal content leaves the device.
 */
object MemoryProposalTracker {
    private data class Entry(
        val id: String,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var accepted: Boolean = false,
    )

    private val proposals = ConcurrentHashMap<String, Entry>()

    /** Proposal IDs look like `prop-0-2`. */
    private val PROPOSAL_ID = Regex("""prop-\d+-\d+""")

    fun track(id: String, @Suppress("UNUSED_PARAMETER") snippet: String) {
        proposals.putIfAbsent(id, Entry(id))
    }

    fun markAccepted(id: String) {
        proposals[id]?.accepted = true
    }

    /** Scan free text (e.g. a memory write) for proposal IDs and mark them accepted. */
    fun markAcceptedInText(text: String) {
        PROPOSAL_ID.findAll(text).forEach { markAccepted(it.value) }
    }

    fun acceptanceRate(): Double {
        val all = proposals.values.toList()
        if (all.isEmpty()) return 0.0
        return all.count { it.accepted }.toDouble() / all.size
    }

    fun counts(): Pair<Int, Int> {
        val all = proposals.values.toList()
        return all.count { it.accepted } to all.size
    }

    /** Test hook. */
    fun resetForTest() = proposals.clear()
}
