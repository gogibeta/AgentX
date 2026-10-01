package com.newoether.agora.social

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pre-network behavior of [FxEmbedClient.search] per the worker's llms.txt
 * (2026-10-02): X search removed, TikTok/Instagram have no search,
 * Mastodon needs a domain. These paths return before any HTTP call.
 */
class FxEmbedClientSearchTest {

    private val client = FxEmbedClient(
        baseUrl = "https://example.workers.dev",
        userAgent = "AgentX/test",
    )

    @Test
    fun `x search is refused honestly (route removed from worker)`() = runTest {
        val r = client.search("x", "hello")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        val f = r as FxEmbedClient.ResolveResult.Failure
        assertEquals("search_not_supported", f.error)
        assertTrue(f.hint!!.contains("removed", ignoreCase = true))
    }

    @Test
    fun `twitter alias also refused`() = runTest {
        val r = client.search("twitter", "hello")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        assertEquals("search_not_supported", (r as FxEmbedClient.ResolveResult.Failure).error)
    }

    @Test
    fun `tiktok search refused`() = runTest {
        val r = client.search("tiktok", "cats")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        assertEquals("search_not_supported", (r as FxEmbedClient.ResolveResult.Failure).error)
    }

    @Test
    fun `instagram search refused`() = runTest {
        val r = client.search("instagram", "cats")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        assertEquals("search_not_supported", (r as FxEmbedClient.ResolveResult.Failure).error)
    }

    @Test
    fun `mastodon search without domain fails with hint`() = runTest {
        val r = client.search("mastodon", "cats")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        assertEquals("no_domain", (r as FxEmbedClient.ResolveResult.Failure).error)
    }

    @Test
    fun `empty query refused`() = runTest {
        val r = client.search("bluesky", "   ")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        assertEquals("no_query", (r as FxEmbedClient.ResolveResult.Failure).error)
    }

    @Test
    fun `unknown network lists searchable networks`() = runTest {
        val r = client.search("reddit", "hello")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        val f = r as FxEmbedClient.ResolveResult.Failure
        assertEquals("unknown_network", f.error)
        assertTrue(f.hint!!.contains("bluesky"))
    }
}
