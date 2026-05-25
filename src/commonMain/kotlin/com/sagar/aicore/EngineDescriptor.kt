package com.sagar.aicore

/**
 * Self-description of a [LocalAiEngine] implementation. Lets the registry,
 * orchestrator, and UI ask "what can this engine do?" without inspecting
 * concrete types. Required by [EngineRegistry] for runtime selection and by
 * future UI surfaces that gate features on engine capabilities (e.g. only
 * show "attach image" when `supportsVision == true`).
 *
 * `id` is the stable string used in DI + persistence + analytics — keep it
 * unique per (engine implementation, model) tuple. `displayName` is for
 * human-facing surfaces only.
 */
data class EngineDescriptor(
    val id: String,
    val displayName: String,
    val supportsTools: Boolean,
    val supportsVision: Boolean,
    val supportsAudio: Boolean,
    val maxContextTokens: Int,
    val approximateMemoryFootprintMb: Long,
    val minDeviceRamMb: Long,
    /**
     * The model file format this engine consumes. Used by
     * [EngineRegistry.engineFor] to route a downloaded model to its
     * matching engine impl.
     */
    val consumes: ModelFormat,
)
