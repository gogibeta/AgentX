package com.newoether.agora.agent

/**
 * Project-folder scoping for agent `plan` / `build` modes.
 *
 * When the user switches to plan or build mode they pick one project folder
 * inside the shared workspace ([WORKSPACE_ROOT], the sandbox's `/mnt/shared`).
 * While a scope is active, local file tools and the shell default working
 * directory are confined to that folder; paths outside it are rejected
 * (fail closed) instead of silently falling back to [SANDBOX_HOME].
 *
 * The scope is stored per conversation, keyed by agent mode, so each
 * conversation remembers its own plan-folder and build-folder. Choosing the
 * workspace root itself is the explicit whole-workspace opt-in.
 *
 * Pure logic — safe to unit-test on the JVM.
 */
object AgentProjectScope {

    /** Sandbox path of the shared workspace every project folder lives under. */
    const val WORKSPACE_ROOT = "/mnt/shared"

    /** Sandbox home. Never used as a silent fallback while a scope is active. */
    const val SANDBOX_HOME = "/home/agora"

    /**
     * Normalize a user-supplied project folder to an absolute sandbox path.
     * Accepts absolute paths under [WORKSPACE_ROOT] or bare relative names
     * (resolved under [WORKSPACE_ROOT]). Returns null when the input is blank,
     * contains a literal `..` segment (rejected outright — whole-workspace
     * access needs the explicit opt-in, never a smuggled path), or is otherwise
     * outside the workspace root.
     */
    fun normalizeFolder(input: String): String? {
        val trimmed = input.trim().replace('\\', '/')
        if (trimmed.isBlank()) return null
        val absolute = if (trimmed.startsWith("/")) trimmed else "$WORKSPACE_ROOT/$trimmed"
        val segments = ArrayDeque<String>()
        for (segment in absolute.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                // Literal ".." is rejected: no traversal, benign or otherwise.
                segment == ".." -> return null
                else -> segments.add(segment)
            }
        }
        val normalized = "/" + segments.joinToString("/")
        if (normalized != WORKSPACE_ROOT && !normalized.startsWith("$WORKSPACE_ROOT/")) return null
        return normalized
    }

    /**
     * True when [path] is inside [scope] (or is the scope itself). Both are
     * normalized first; a blank scope never matches (fail closed).
     */
    fun isPathInScope(path: String, scope: String): Boolean {
        val normalizedScope = normalizeFolder(scope) ?: return false
        val normalizedPath = normalizePath(path.trim().replace('\\', '/')) ?: return false
        return normalizedPath == normalizedScope || normalizedPath.startsWith("$normalizedScope/")
    }

    /** Short display name for a scope, e.g. `/mnt/shared/my-app` -> `my-app`. */
    fun displayName(scope: String): String {
        val normalized = normalizeFolder(scope) ?: return scope
        if (normalized == WORKSPACE_ROOT) return "Shared folder"
        return normalized.substringAfterLast('/')
    }

    /**
     * Resolve "." and ".." segments lexically. Returns null on blank input or
     * when ".." would escape the filesystem root.
     */
    internal fun normalizePath(path: String): String? {
        if (path.isBlank()) return null
        val absolute = if (path.startsWith("/")) path else "/$path"
        val segments = ArrayDeque<String>()
        for (segment in absolute.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (segments.isEmpty()) return null else segments.removeLast()
                else -> segments.add(segment)
            }
        }
        return "/" + segments.joinToString("/")
    }
}
