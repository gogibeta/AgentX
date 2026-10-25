package com.newoether.agora.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProjectScopeTest {

    @Test
    fun normalizeFolder_acceptsAbsoluteUnderRoot() {
        assertEquals("/mnt/shared/my-app", AgentProjectScope.normalizeFolder("/mnt/shared/my-app"))
    }

    @Test
    fun normalizeFolder_acceptsBareNameAsRelative() {
        assertEquals("/mnt/shared/my-app", AgentProjectScope.normalizeFolder("my-app"))
        assertEquals("/mnt/shared/a/b", AgentProjectScope.normalizeFolder("a/b"))
    }

    @Test
    fun normalizeFolder_acceptsRootItselfAsWholeWorkspaceOptIn() {
        assertEquals("/mnt/shared", AgentProjectScope.normalizeFolder("/mnt/shared"))
        assertEquals("/mnt/shared", AgentProjectScope.normalizeFolder("/mnt/shared/"))
    }

    @Test
    fun normalizeFolder_rejectsTraversalOutsideRoot() {
        assertNull(AgentProjectScope.normalizeFolder("/mnt/shared/../etc"))
        assertNull(AgentProjectScope.normalizeFolder("/home/agora"))
        assertNull(AgentProjectScope.normalizeFolder("../shared"))
        assertNull(AgentProjectScope.normalizeFolder("/mnt/shared/a/../../.."))
    }

    @Test
    fun normalizeFolder_rejectsBlank() {
        assertNull(AgentProjectScope.normalizeFolder(""))
        assertNull(AgentProjectScope.normalizeFolder("   "))
    }

    @Test
    fun normalizeFolder_collapsesDotSegments() {
        assertNull(AgentProjectScope.normalizeFolder("/mnt/shared/a/./b/../c"))
    }

    @Test
    fun isPathInScope_allowsInsideAndSelf() {
        val scope = "/mnt/shared/my-app"
        assertTrue(AgentProjectScope.isPathInScope("/mnt/shared/my-app", scope))
        assertTrue(AgentProjectScope.isPathInScope("/mnt/shared/my-app/src/Main.kt", scope))
    }

    @Test
    fun isPathInScope_rejectsOutsideAndTraversal() {
        val scope = "/mnt/shared/my-app"
        assertFalse(AgentProjectScope.isPathInScope("/mnt/shared/other", scope))
        assertFalse(AgentProjectScope.isPathInScope("/home/agora/file", scope))
        assertFalse(AgentProjectScope.isPathInScope("/mnt/shared/my-app/../other", scope))
        // Prefix confusion: /mnt/shared/my-app2 is NOT inside /mnt/shared/my-app.
        assertFalse(AgentProjectScope.isPathInScope("/mnt/shared/my-app2/x", scope))
    }

    @Test
    fun isPathInScope_blankScopeNeverMatches() {
        assertFalse(AgentProjectScope.isPathInScope("/mnt/shared/x", ""))
        assertFalse(AgentProjectScope.isPathInScope("/mnt/shared/x", "   "))
    }

    @Test
    fun displayName_lastSegmentOrSharedFolder() {
        assertEquals("my-app", AgentProjectScope.displayName("/mnt/shared/my-app"))
        assertEquals("Shared folder", AgentProjectScope.displayName("/mnt/shared"))
    }
}
