package com.newoether.agora.social

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pre-network behavior of [FxEmbedClient.search] per the worker's llms.txt
 * (2026-10-03 refresh): X search is relay-served again (no local refusal),
 * TikTok/Instagram have no search, Mastodon needs a domain. Refusal paths
 * return before any HTTP call.
 */
class FxEmbedClientSearchTest {

    private val client = FxEmbedClient(
        baseUrl = "https://example.workers.dev",
        userAgent = "AgentX/test",
    )

    @Test
    fun `x search is relay-served, not refused locally`() = runTest {
        // With a dummy host the call fails at the network — the point is it
        // is ATTEMPTED (relay route) instead of refused as search_not_supported.
        val r = client.search("x", "hello")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        val f = r as FxEmbedClient.ResolveResult.Failure
        assertTrue(f.error != "search_not_supported")
    }

    @Test
    fun `twitter alias also relay-served`() = runTest {
        val r = client.search("twitter", "hello")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        assertTrue((r as FxEmbedClient.ResolveResult.Failure).error != "search_not_supported")
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

    @Test
    fun `thread rejects non-numeric tweet id before network`() = runTest {
        val r = client.thread("abc")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        assertEquals("bad_tweet_id", (r as FxEmbedClient.ResolveResult.Failure).error)
    }

    @Test
    fun `quotes rejects blank tweet id before network`() = runTest {
        val r = client.quotes("  ")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        assertEquals("bad_tweet_id", (r as FxEmbedClient.ResolveResult.Failure).error)
    }

    @Test
    fun `conversation rejects non-numeric tweet id before network`() = runTest {
        val r = client.conversation("xyz")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        assertEquals("bad_tweet_id", (r as FxEmbedClient.ResolveResult.Failure).error)
    }

    @Test
    fun `timeline rejects blank handle before network`() = runTest {
        val r = client.timeline("x", "  ")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        assertEquals("no_handle", (r as FxEmbedClient.ResolveResult.Failure).error)
    }

    @Test
    fun `profileSearch needs handle and query`() = runTest {
        val r = client.profileSearch("", "ai")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        assertEquals("bad_arguments", (r as FxEmbedClient.ResolveResult.Failure).error)
    }

    @Test
    fun `rssFeed rejects blank handle before network`() = runTest {
        val r = client.rssFeed("")
        assertTrue(r is FxEmbedClient.ResolveResult.Failure)
        assertEquals("no_handle", (r as FxEmbedClient.ResolveResult.Failure).error)
    }
}
