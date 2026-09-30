package com.newoether.agora.api.typesafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeSafeClientTest {

    @Test
    fun testCanonicalBaseUrl() {
        assertEquals("https://api.typesafe.ai", TypeSafeClient.canonicalBaseUrl(null))
        assertEquals("https://api.typesafe.ai", TypeSafeClient.canonicalBaseUrl("  "))
        assertEquals("https://proxy.example.com/jev", TypeSafeClient.canonicalBaseUrl("https://proxy.example.com/jev/"))
        // Router bases that already carry /v1 are normalized so callers appending
        // /v1/systemone + /v1/models never double it (device-proven on nara).
        assertEquals("https://router.example.com", TypeSafeClient.canonicalBaseUrl("https://router.example.com/v1"))
        assertEquals("https://router.example.com", TypeSafeClient.canonicalBaseUrl("https://router.example.com/v1/"))
    }

    @Test
    fun testParseModelNamesShapes() {
        val openAiStyle = """{"data":[{"name":"jev-latest"},{"name":"jev-1.13.0"}]}"""
        assertEquals(listOf("jev-latest", "jev-1.13.0"), TypeSafeClient.parseModelNames(openAiStyle))
        val modelsKey = """{"models":[{"id":"jev-latest"}]}"""
        assertEquals(listOf("jev-latest"), TypeSafeClient.parseModelNames(modelsKey))
        val bareArray = """[{"name":"jev-latest"}]"""
        assertEquals(listOf("jev-latest"), TypeSafeClient.parseModelNames(bareArray))
    }

    @Test
    fun testChoiceQuestionSerialization() {
        val q = TypeSafeClient.ChoiceQuestion(
            key = "dept",
            instructions = "Which team?",
            criteria = mapOf("billing" to "Payments", "other" to null),
        )
        val json = q.toJson().toString()
        assertTrue(json.contains("\"type\":\"choice\""))
        assertTrue(json.contains("\"billing\":\"Payments\""))
        assertTrue(json.contains("\"other\":null"))
    }

    @Test
    fun testParseChoiceDecision() {
        val body = """
        {
          "model": "jev-1.13.0",
          "answers": {
            "dept": {"type":"choice","choice":"technical",
              "probabilities":{"billing":0.08,"technical":0.85,"sales":0.07},
              "confidence":0.82}
          },
          "usage": {"input_tokens":312,"output_tokens":48}
        }
        """.trimIndent()
        val questions = mapOf(
            "dept" to TypeSafeClient.ChoiceQuestion("dept", "Which team?", emptyMap()),
        )
        val decision = TypeSafeClient.parseDecision(200, body, questions)
        assertEquals("jev-1.13.0", decision.model)
        assertEquals(312, decision.inputTokens)
        val ans = decision.answers["dept"] as TypeSafeClient.JevAnswer.Choice
        assertEquals("technical", ans.value)
        assertEquals(0.82, ans.confidence, 1e-9)
    }

    @Test
    fun testParseNoulDecisionHasNoConfidence() {
        val body = """{"model":"jev-latest","answers":{"urgent":{"type":"noul","noul":0.98}}}"""
        val questions = mapOf("urgent" to TypeSafeClient.NoulQuestion("urgent", "Urgent?"))
        val decision = TypeSafeClient.parseDecision(200, body, questions)
        val ans = decision.answers["urgent"] as TypeSafeClient.JevAnswer.Noul
        assertEquals(0.98, ans.probability, 1e-9)
    }

    @Test
    fun testParseScoreDecisionMeanIsFloat() {
        val body = """{"model":"jev-latest","answers":{"sev":{"type":"score","score":1.3,
          "confidence":0.54,"legend":{"0":"low","1":"mid","2":"high"},
          "probabilities":{"0":0.0,"1":0.7,"2":0.3}}}}"""
        val questions = mapOf(
            "sev" to TypeSafeClient.ScoreQuestion("sev", "Severity?", listOf("low", "mid", "high")),
        )
        val decision = TypeSafeClient.parseDecision(200, body, questions)
        val ans = decision.answers["sev"] as TypeSafeClient.JevAnswer.Score
        assertEquals(1.3, ans.score, 1e-9)
        assertEquals(0.7, ans.probabilities[1] ?: -1.0, 1e-9)
    }

    @Test
    fun testExtractErrorMessageShapes() {
        assertEquals("boom", TypeSafeClient.extractErrorMessage("boom"))
        assertEquals("bad key", TypeSafeClient.extractErrorMessage("""{"message":"bad key"}"""))
        assertEquals(
            "denied",
            TypeSafeClient.extractErrorMessage("""{"detail":{"error_type":"x","message":"denied"}}"""),
        )
    }

    @Test
    fun testBackoffBounds() {
        repeat(20) {
            val first = TypeSafeClient.computeBackoffMs(0)
            assertTrue(first in 375..500)
            val later = TypeSafeClient.computeBackoffMs(10)
            assertTrue(later in 3750..5000)
        }
    }

    @Test
    fun testNoulQuestionSerializationOmitsEmptyCriteria() {
        val q = TypeSafeClient.NoulQuestion("u", "Urgent?")
        val json = q.toJson().toString()
        assertTrue(json.contains("\"type\":\"noul\""))
        assertTrue(!json.contains("criteria"))
        val withCriteria = TypeSafeClient.NoulQuestion("u", "Urgent?", whenTrue = "now")
        assertTrue(withCriteria.toJson().toString().contains("\"true\":\"now\""))
    }
}
