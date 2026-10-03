package com.newoether.agora.ui.chat

import com.newoether.agora.agent.AgentProjectScope
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Project-folder prompt/state contract: mode never switches before folder
 * confirmation, cancel keeps the previous mode, plan/build keep separate
 * folders, new chat uses the canonical pending workspace, and unrelated
 * conversation settings survive folder updates.
 */
class ProjectFolderPromptTest {

    private val newChatOwner = canonicalSettingsOwnerId(null)
    private val convOwner = canonicalSettingsOwnerId("conv-123")

    private fun settingsWith(vararg folders: Pair<String, String>) =
        ConversationSettings(agentProjectFolders = mapOf(*folders))

    @Test
    fun `null owner canonicalizes to the new-chat workspace`() {
        assertEquals(NEW_CHAT_WORKSPACE_ID, newChatOwner)
        assertEquals("agentx:new-chat", newChatOwner)
    }

    @Test
    fun `non-null owner passes through unchanged`() {
        assertEquals("conv-123", convOwner)
    }

    @Test
    fun `selecting plan with no folder prompts, even for a new chat`() {
        // New chat must prompt too (previously a null owner skipped the prompt).
        assertEquals("plan", pendingFolderPrompt("plan", newChatOwner, emptyMap()))
        assertEquals("build", pendingFolderPrompt("build", newChatOwner, emptyMap()))
        assertEquals("plan", pendingFolderPrompt("plan", convOwner, emptyMap()))
    }

    @Test
    fun `selecting plan-build with a folder set switches immediately`() {
        val map = mapOf(convOwner to settingsWith("plan" to "/mnt/shared/proj"))
        assertNull(pendingFolderPrompt("plan", convOwner, map))
        // Build has no folder yet: still prompts.
        assertEquals("build", pendingFolderPrompt("build", convOwner, map))
    }

    @Test
    fun `chat and off modes never prompt`() {
        assertNull(pendingFolderPrompt("chat", newChatOwner, emptyMap()))
        assertNull(pendingFolderPrompt("off", newChatOwner, emptyMap()))
        assertNull(pendingFolderPrompt("chat", convOwner, emptyMap()))
    }

    @Test
    fun `plan and build retain separate folders`() {
        val map = mapOf(
            convOwner to settingsWith(
                "plan" to "/mnt/shared/plan-proj",
                "build" to "/mnt/shared/build-proj",
            ),
        )
        assertEquals("/mnt/shared/plan-proj", activeProjectFolderFor(map, convOwner, "plan"))
        assertEquals("/mnt/shared/build-proj", activeProjectFolderFor(map, convOwner, "build"))
    }

    @Test
    fun `active folder is empty when none is set`() {
        assertEquals("", activeProjectFolderFor(emptyMap(), convOwner, "plan"))
        val map = mapOf(convOwner to settingsWith("build" to "/mnt/shared/b"))
        assertEquals("", activeProjectFolderFor(map, convOwner, "plan"))
    }

    @Test
    fun `folder update preserves unrelated conversation settings`() {
        val before = ConversationSettings(
            temperature = 0.7f,
            maxTokens = 1024,
            shellEnabled = true,
            agentProjectFolders = mapOf("plan" to "/mnt/shared/old"),
        )
        // Same copy expression the UI uses on folder confirm.
        val after = before.copy(
            agentProjectFolders = before.agentProjectFolders.orEmpty() + ("plan" to "/mnt/shared/new"),
        )
        assertEquals(0.7f, after.temperature)
        assertEquals(1024, after.maxTokens)
        assertEquals(true, after.shellEnabled)
        assertEquals("/mnt/shared/new", after.agentProjectFolders?.get("plan"))
    }

    @Test
    fun `explicit whole-workspace selection normalizes to the workspace root`() {
        // The dedicated opt-in (checkbox) is the only path to whole-workspace access.
        assertEquals("/mnt/shared", AgentProjectScope.normalizeFolder("/mnt/shared"))
    }

    @Test
    fun `traversal to the workspace root is rejected`() {
        assertNull(AgentProjectScope.normalizeFolder("/mnt/shared/a/../.."))
        assertNull(AgentProjectScope.normalizeFolder(".."))
    }
}
