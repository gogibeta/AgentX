package com.newoether.agora.automation

import com.newoether.agora.viewmodel.GenerationContext

/**
 * Child-run (delegate_task subagent) context customization, extracted from
 * TaskExecutionEngine to keep that file under the 800-line cap.
 *
 * Child runs get a restricted tool set, no memory writes, no user prompts.
 * The child returns memory *proposals*; only the parent commits them via the
 * ordinary memory tools (single writer). Child temp conversations have no
 * persisted folder, so they inherit the parent's project-folder scope, and
 * the engine enforces their max_turns as a hard tool-round cap.
 */
internal fun GenerationContext.forChildRun(
    toolAllowList: Set<String>,
    childProjectFolder: String?,
    maxToolRounds: Int,
): GenerationContext = copy(
    toolAllowList = toolAllowList,
    askUserEnabled = false,
    accessSavedMemories = false,
    accessActiveMemory = false,
    agentProjectFolder = childProjectFolder.orEmpty(),
    maxToolRounds = maxToolRounds,
)
