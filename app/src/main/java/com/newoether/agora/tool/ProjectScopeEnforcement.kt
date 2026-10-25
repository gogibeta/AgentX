package com.newoether.agora.tool

import com.newoether.agora.agent.AgentProjectScope
import com.newoether.agora.viewmodel.GenerationContext

/**
 * Enforces the agent project-folder scope ([GenerationContext.agentProjectFolder])
 * on local-sandbox file operations.
 *
 * Rules (local sandbox only — remote Conch/SSH shells keep their own paths):
 * - Plan/build mode with no folder set: reject (fail closed) — the agent must
 *   never silently scan the whole home; the UI prompts for a folder first.
 * - Blank path (glob/grep base) resolves to the scope instead of the backend's
 *   legacy `/home/agora` default, so tools never silently scan the whole home.
 * - An explicit path outside the scope is rejected with `path_outside_project_folder`
 *   (fail closed) instead of being read/scanned.
 * - Chat/off mode (or any blank scope outside plan/build) keeps legacy behavior.
 * - Shell workdirs follow the same boundary: blank defaults to the scope, an
 *   explicit workdir outside the scope is rejected, and plan/build with no
 *   folder rejects. (Shell command *contents* stay native-approval-gated; the
 *   workdir boundary is separate and not theater — `cd` out of the project is
 *   still a project-folder violation.)
 *
 * Call sites use the inline form so rejection returns straight out of the tool:
 * `val path = scopedLocalPath("file_read", raw, backend, ctx) { return it }`
 */
internal object ProjectScopeEnforcement {

    inline fun scopedLocalPath(
        tool: String,
        path: String,
        backend: Backend,
        ctx: GenerationContext,
        onReject: (String) -> Nothing,
    ): String {
        if (backend.device != null) return path // Remote shell: paths unchanged.
        val scope = ctx.agentProjectFolder
        if (scope.isBlank()) {
            // Fail closed: plan/build without a project folder cannot touch local files.
            if (ctx.agentMode == "plan" || ctx.agentMode == "build") {
                onReject(
                    jsonError(
                        tool,
                        "project_folder_not_set: plan/build mode needs a project folder " +
                            "before reading local files. Ask the user to pick one.",
                    ),
                )
            }
            return path // Chat/off mode: legacy behavior.
        }
        if (path.isBlank()) return scope // Blank base: search/read inside the project, not /home/agora.
        if (AgentProjectScope.isPathInScope(path, scope)) return path
        onReject(
            jsonError(
                tool,
                "path_outside_project_folder: the path is outside the active project " +
                    "folder ($scope). Stay inside the project folder or ask the user " +
                    "to change it.",
            ),
        )
    }

    /**
     * Enforce the scope on a local shell workdir: blank defaults to the scope,
     * an explicit workdir outside the scope is rejected, and plan/build with
     * no folder fails closed. Remote shells keep their own workdir.
     */
    inline fun scopedLocalWorkdir(
        tool: String,
        workdir: String,
        backend: Backend,
        ctx: GenerationContext,
        onReject: (String) -> Nothing,
    ): String {
        if (backend.device != null) return workdir // Remote shell: unchanged.
        val scope = ctx.agentProjectFolder
        if (scope.isBlank()) {
            // Fail closed: plan/build without a project folder cannot run local shell.
            if (ctx.agentMode == "plan" || ctx.agentMode == "build") {
                onReject(
                    jsonError(
                        tool,
                        "project_folder_not_set: plan/build mode needs a project folder " +
                            "before running local shell commands. Ask the user to pick one.",
                    ),
                )
            }
            return workdir // Chat/off mode: legacy behavior.
        }
        if (workdir.isBlank()) return scope
        if (AgentProjectScope.isPathInScope(workdir, scope)) return workdir
        onReject(
            jsonError(
                tool,
                "path_outside_project_folder: the shell workdir is outside the active " +
                    "project folder ($scope). Stay inside the project folder or ask the " +
                    "user to change it.",
            ),
        )
    }
}
