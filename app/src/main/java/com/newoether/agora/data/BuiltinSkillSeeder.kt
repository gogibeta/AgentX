package com.newoether.agora.data

import android.content.Context
import java.io.File

/**
 * Seeds the built-in agent skills (browser, social, Jev) from APK assets into
 * the user's skill_db on first run, and refreshes them when the bundled
 * version changes. Built-ins are prefixed `builtin-` so they never collide
 * with user-created skills; users can edit or delete the copies freely —
 * a newer bundled version re-seeds only when the stored version is older.
 */
object BuiltinSkillSeeder {
    private const val ASSET_DIR = "builtin_skills"
    private const val VERSION_FILE = "builtin_skills_version.txt"

    /** Bump when any bundled skill changes. */
    const val BUNDLED_VERSION = 2

    private val BUILTINS = mapOf(
        "builtin-browser-skill.md" to "Browser automation: backends, the 9 browser tools, failure modes",
        "builtin-social-skill.md" to "Social tools: worker setup, resolve/search, per-network limits",
        "builtin-jev-skill.md" to "Jev prune_context: threshold tuning, score calibration, safety rules",
    )

    fun seed(context: Context, skillManager: SkillManager) {
        val skillDir = File(context.filesDir, "skill_db").apply { mkdirs() }
        val versionFile = File(skillDir, VERSION_FILE)
        val seededVersion = versionFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (seededVersion >= BUNDLED_VERSION) return
        val assets = context.assets
        val bundled = runCatching { assets.list(ASSET_DIR)?.toSet().orEmpty() }.getOrDefault(emptySet())
        for ((assetName, description) in BUILTINS) {
            // Asset file is e.g. browser-skill.md; installed as builtin-browser-skill.md.
            val src = assetName.removePrefix("builtin-")
            if (src !in bundled) continue
            val content = runCatching {
                assets.open("$ASSET_DIR/$src").bufferedReader().readText()
            }.getOrNull() ?: continue
            val dest = File(skillDir, assetName)
            try {
                // Refresh on version bump; never overwrite a user-edited copy at the
                // same version (createFile refuses when the file exists).
                if (seededVersion < BUNDLED_VERSION && dest.exists() && seededVersion > 0) {
                    dest.writeText(content)
                } else {
                    skillManager.createFile(assetName, content, description)
                }
            } catch (_: Exception) {
                // createFile throws if the user already made a file with this name;
                // leave the user's file alone.
            }
        }
        runCatching { versionFile.writeText(BUNDLED_VERSION.toString()) }
    }
}
