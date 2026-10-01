package com.newoether.agora.tool

import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.viewmodel.GenerationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for agent project-folder scope enforcement on local-sandbox file tools. */
class ProjectScopeEnforcementTest {

    private fun fakeBackend(device: ShellDeviceConfig?): Backend = object : Backend {
        override val device: ShellDeviceConfig? = device
        override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String = TODO()
        override suspend fun fileRead(path: String, offset: Long, limit: Long): ShellFileReadResult = TODO()
        override suspend fun fileWrite(path: String, content: String): String? = TODO()
        override suspend fun fileEdit(
            path: String, oldString: String, newString: String, replaceAll: Boolean,
        ): ShellFileEditResult = TODO()
        override suspend fun fileGlob(
            pattern: String, basePath: String, depth: Int?,
        ): Result<Pair<List<String>, Boolean>> = TODO()
        override suspend fun fileGrep(
            pattern: String, basePath: String, fileGlob: String,
        ): Result<Pair<List<String>, Boolean>> = TODO()
    }

    private val local = fakeBackend(null)
    private val remote = fakeBackend(ShellDeviceConfig(name = "s1", serverUrl = "http://a"))
    private fun ctx(folder: String) = GenerationContext(agentMode = "build", agentProjectFolder = folder)

    @Test
    fun `explicit path inside scope passes through`() {
        val out = ProjectScopeEnforcement.scopedLocalPath("file_read", "/mnt/shared/proj/a.txt", local, ctx("/mnt/shared/proj")) { error(it) }
        assertEquals("/mnt/shared/proj/a.txt", out)
    }

    @Test
    fun `explicit path outside scope is rejected`() {
        var rejected: String? = null
        val result = runCatching {
            ProjectScopeEnforcement.scopedLocalPath("file_read", "/mnt/shared/other/a.txt", local, ctx("/mnt/shared/proj")) {
                rejected = it; error("rejected")
            }
        }
        assertTrue(result.isFailure)
        assertTrue(rejected!!.contains("path_outside_project_folder"))
    }

    @Test
    fun `blank path defaults to scope for local sandbox`() {
        val out = ProjectScopeEnforcement.scopedLocalPath("file_glob", "", local, ctx("/mnt/shared/proj")) { error(it) }
        assertEquals("/mnt/shared/proj", out)
    }

    @Test
    fun `blank scope in plan-build mode fails closed`() {
        var rejected: String? = null
        val result = runCatching {
            ProjectScopeEnforcement.scopedLocalPath("file_read", "/home/agora/x", local, ctx("")) {
                rejected = it; error("rejected")
            }
        }
        assertTrue(result.isFailure)
        assertTrue(rejected!!.contains("project_folder_not_set"))
    }

    @Test
    fun `blank scope in chat mode keeps legacy behavior`() {
        val chatCtx = GenerationContext(agentMode = "off", agentProjectFolder = "")
        val out = ProjectScopeEnforcement.scopedLocalPath("file_read", "/home/agora/x", local, chatCtx) { error(it) }
        assertEquals("/home/agora/x", out)
    }

    @Test
    fun `remote backends ignore scope entirely`() {
        val out = ProjectScopeEnforcement.scopedLocalPath("file_read", "/etc/passwd", remote, ctx("/mnt/shared/proj")) { error(it) }
        assertEquals("/etc/passwd", out)
        val blank = ProjectScopeEnforcement.scopedLocalPath("file_glob", "", remote, ctx("/mnt/shared/proj")) { error(it) }
        assertEquals("", blank)
    }

    @Test
    fun `prefix confusion is rejected`() {
        var rejected: String? = null
        val result = runCatching {
            ProjectScopeEnforcement.scopedLocalPath("file_read", "/mnt/shared/proj-evil/a.txt", local, ctx("/mnt/shared/proj")) {
                rejected = it; error("rejected")
            }
        }
        assertTrue(result.isFailure)
        assertTrue(rejected!!.contains("path_outside_project_folder"))
    }

    @Test
    fun `traversal outside scope is rejected`() {
        var rejected: String? = null
        val result = runCatching {
            ProjectScopeEnforcement.scopedLocalPath("file_read", "/mnt/shared/proj/../other/a.txt", local, ctx("/mnt/shared/proj")) {
                rejected = it; error("rejected")
            }
        }
        assertTrue(result.isFailure)
        assertTrue(rejected!!.contains("path_outside_project_folder"))
    }

    @Test
    fun `shell workdir defaults to scope on local sandbox`() {
        assertEquals("/mnt/shared/proj", ProjectScopeEnforcement.scopedLocalWorkdir("", local, ctx("/mnt/shared/proj")))
        assertEquals("/tmp", ProjectScopeEnforcement.scopedLocalWorkdir("/tmp", local, ctx("/mnt/shared/proj")))
        // Remote shells keep their own workdir; blank scope keeps legacy behavior.
        assertEquals("", ProjectScopeEnforcement.scopedLocalWorkdir("", remote, ctx("/mnt/shared/proj")))
        assertEquals("", ProjectScopeEnforcement.scopedLocalWorkdir("", local, ctx("")))
    }
}
