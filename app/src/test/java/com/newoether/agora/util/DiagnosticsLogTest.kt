package com.newoether.agora.util

import com.newoether.agora.api.HttpClient
import com.newoether.agora.data.AutoBackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the always-on diagnostics log support code:
 * - URL sanitization never leaks query strings / fragments (API keys travel there).
 * - Backup filename matching keeps legacy Agora files visible to retention/baseline.
 */
class DiagnosticsLogTest {

    @Test
    fun sanitizeUrlForLog_stripsQueryAndFragment() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            HttpClient.sanitizeUrlForLog(
                "https://api.openai.com/v1/chat/completions?api_key=sk-secret#frag"
            )
        )
    }

    @Test
    fun sanitizeUrlForLog_keepsHostAndPath() {
        assertEquals(
            "http://192.168.1.10:11434/api/generate",
            HttpClient.sanitizeUrlForLog("http://192.168.1.10:11434/api/generate")
        )
    }

    @Test
    fun sanitizeUrlForLog_malformedUrl_neverThrows() {
        assertEquals(
            "unparseable-url",
            HttpClient.sanitizeUrlForLog("not a url at all [[[")
        )
    }

    @Test
    fun isBackupFileName_matchesCurrentAndLegacy() {
        assertTrue(AutoBackupManager.isBackupFileName("AgentX_backup_2026-09-30.agentx"))
        assertTrue(AutoBackupManager.isBackupFileName("Agora_backup_2026-09-30.agora"))
        assertFalse(AutoBackupManager.isBackupFileName("AgentX_backup_2026-09-30.agora"))
        assertFalse(AutoBackupManager.isBackupFileName("random.txt"))
    }

    @Test
    fun isBackupTmpFileName_matchesCurrentAndLegacy() {
        assertTrue(AutoBackupManager.isBackupTmpFileName("AgentX_backup_x.agentx.tmp"))
        assertTrue(AutoBackupManager.isBackupTmpFileName("Agora_backup_x.agora.tmp"))
        assertFalse(AutoBackupManager.isBackupTmpFileName("AgentX_backup_x.tmp"))
    }
}
