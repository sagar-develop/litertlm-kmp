package com.sagar.aicore

/**
 * Registry of available [LocalAiEngine] implementations bound at DI time.
 * Single-engine today (LiteRT-LM) but the abstraction stays so future
 * engines (e.g. a WebGPU engine for CMP-Web) can join the list without
 * touching call sites.
 *
 * `selectDefault()` applies an opinionated RAM-tier + on-disk-presence
 * policy and returns the best engine for the current device.
 * `fallbackChain()` returns engines in priority order — if
 * `initializeEngine()` on the first returns `EngineState.Error`, the
 * caller can try the next.
 *
 * Engines themselves remain `@AppScope` singletons; the registry is also a
 * singleton holding references to all of them.
 */
interface EngineRegistry {
    fun availableEngines(): List<EngineDescriptor>
    fun engineFor(id: String): LocalAiEngine
    /** Lookup engine by the [ModelFormat] it consumes. */
    fun engineFor(format: ModelFormat): LocalAiEngine
    /**
     * RAM-tier + on-disk policy: prefer the highest-capability engine whose
     * `minDeviceRamMb` fits the device AND whose consumed-format has a
     * model file on disk. Calling this also releases native resources held
     * by engines not chosen and caches the result as [active].
     */
    fun selectDefault(): LocalAiEngine
    /**
     * Currently-active engine. Returns the last engine passed to
     * [setActive] or returned by [selectDefault]. Bootstraps via
     * [selectDefault] on first call if neither has run yet.
     *
     * Late-binding consumers (orchestrator + agents) read this on every
     * inference call — that way the active engine can change after first
     * launch (Setup downloads a model the registry didn't know about at
     * DI time) without restarting the app.
     */
    fun active(): LocalAiEngine
    /**
     * Replace the active engine — Setup calls this once the user-chosen
     * model has been downloaded + `initializeEngine`'d, so subsequent
     * inference calls route to it. Also releases native resources on every
     * other engine.
     */
    fun setActive(engine: LocalAiEngine)
    fun fallbackChain(): List<LocalAiEngine>
}
