package com.newoether.agora.api.monid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonidClientTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun testParseSearchOutput() {
        val output = obj(
            """{"query":"test","results":[
              {"position":1,"title":"T","url":"https://a.com/x","site_name":"a","snippet":"S"},
              {"position":2,"title":"NoUrl","snippet":"S"},
              {"position":3,"title":"","url":"","snippet":""}
            ]}""",
        )
        val hits = MonidClient.parseSearchOutput(output)
        assertEquals(1, hits.size)
        assertEquals("T", hits[0].title)
        assertEquals("https://a.com/x", hits[0].url)
        assertEquals("S", hits[0].snippet)
    }

    @Test
    fun testParseFetchOutput() {
        val output = obj(
            """{"results":[
              {"url":"https://a.com","text":"# Hello"},
              {"url":"https://b.com","text":""},
              {"url":"","text":"x"}
            ],"errors":[]}""",
        )
        val fetched = MonidClient.parseFetchOutput(output)
        assertEquals(mapOf("https://a.com" to "# Hello"), fetched)
    }

    @Test
    fun testNormalizeUrlDedupes() {
        assertEquals(
            MonidClient.normalizeUrl("https://Example.com/Path/?utm_source=x&fbclid=y"),
            MonidClient.normalizeUrl("http://www.example.com/Path"),
        )
        assertEquals("a.com/x", MonidClient.normalizeUrl("https://a.com/x/"))
    }

    @Test
    fun testFuseDedupeTinyFishFirst() {
        val tiny = listOf(
            MonidClient.WebHit("T1", "https://a.com/1", "s"),
            MonidClient.WebHit("T2", "https://b.com/", "s"),
        )
        val ddg = listOf(
            MonidClient.WebHit("D1", "https://a.com/1?utm_x=1", "s"),
            MonidClient.WebHit("D2", "https://c.com/", "s"),
        )
        val fused = MonidClient.fuseDedupe(tiny, ddg, 4)
        // a.com/1 appears in both → single entry, TinyFish copy wins.
        assertEquals(3, fused.size)
        assertEquals("https://a.com/1", fused[0].url)
        assertEquals("tinyfish", fused[0].source)
        assertEquals("https://b.com/", fused[1].url)
        assertEquals("https://c.com/", fused[2].url)
        assertEquals("duckduckgo", fused[2].source)
    }

    @Test
    fun testFuseDedupeRespectsMax() {
        val tiny = (1..5).map { MonidClient.WebHit("T$it", "https://t$it.com/", "s") }
        val ddg = (1..5).map { MonidClient.WebHit("D$it", "https://d$it.com/", "s") }
        assertEquals(3, MonidClient.fuseDedupe(tiny, ddg, 3).size)
    }

    @Test
    fun testIsTerminal() {
        assertTrue(MonidClient.isTerminal(obj("""{"status":"COMPLETED"}""")))
        assertTrue(MonidClient.isTerminal(obj("""{"status":"FAILED"}""")))
        assertTrue(MonidClient.isTerminal(obj("""{"status":"BLOCKED"}""")))
        assertFalse(MonidClient.isTerminal(obj("""{"status":"RUNNING"}""")))
        assertFalse(MonidClient.isTerminal(obj("""{}""")))
    }

    @Test
    fun testCanonicalBaseUrl() {
        assertEquals("https://api.monid.ai", MonidClient.canonicalBaseUrl(null))
        assertEquals("https://proxy.example.com/m", MonidClient.canonicalBaseUrl("https://proxy.example.com/m/"))
    }
}
