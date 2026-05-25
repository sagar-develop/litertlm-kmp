package com.sagar.aicore

import com.sagar.aicore.di.AppScope
import io.github.aakira.napier.Napier
import me.tatarka.inject.annotations.Inject

/**
 * Android [EngineRegistry] binding the production engine list. Single-engine
 * today (LiteRT-LM) but the registry shape stays for future engines.
 *
 * [selectDefault] policy: pick the first engine whose `descriptor.minDeviceRamMb`
 * fits this device AND whose `consumes` format has a model file on disk; else
 * fall back to the last engine and let `initializeEngine` surface a clear error.
 * `releaseResources()` runs on every non-chosen engine — a no-op with one
 * engine, but the helper stays for future multi-engine cases.
 */
@AppScope
@Inject
class DefaultEngineRegistry(
    private val litertLm: LiteRtLmLocalAiEngine,
    private val hardwareProvider: HardwareProvider,
    private val modelCatalog: ModelCatalog,
    private val modelManager: ModelManager,
) : EngineRegistry {

    private val engines: List<LocalAiEngine> = listOf(litertLm)

    /** Late-bound active engine; null until first [selectDefault]/[setActive]. */
    @Volatile
    private var activeEngine: LocalAiEngine? = null

    override fun availableEngines(): List<EngineDescriptor> =
        engines.map { it.descriptor }

    override fun engineFor(id: String): LocalAiEngine =
        engines.firstOrNull { it.descriptor.id == id }
            ?: error("Unknown engine id: $id (known: ${engines.joinToString { it.descriptor.id }})")

    override fun engineFor(format: ModelFormat): LocalAiEngine =
        engines.firstOrNull { it.descriptor.consumes == format }
            ?: error("No engine for format: $format (engines: ${engines.joinToString { it.descriptor.id }})")

    override fun selectDefault(): LocalAiEngine {
        val totalRam = hardwareProvider.getDeviceCapabilities().totalRamMb
        val chosen = engines.firstOrNull { engine ->
            val ramFits = totalRam >= engine.descriptor.minDeviceRamMb
            val fileOnDisk = modelCatalog.all()
                .filter { it.format == engine.descriptor.consumes }
                .any { modelManager.isModelDownloaded(it.fileName) }
            ramFits && fileOnDisk
        } ?: engines.last().also {
            Napier.w(tag = TAG) {
                "selectDefault: no engine had ramFits && fileOnDisk; " +
                    "falling back to ${it.descriptor.id} — initializeEngine will likely error"
            }
        }
        setActiveInternal(chosen, calledFrom = "selectDefault")
        Napier.d(tag = TAG) { "selectDefault → ${chosen.descriptor.id} (totalRamMb=$totalRam)" }
        return chosen
    }

    override fun active(): LocalAiEngine = activeEngine ?: selectDefault()

    override fun setActive(engine: LocalAiEngine) {
        require(engine in engines) {
            "setActive: ${engine.descriptor.id} is not in this registry's engine list " +
                "(${engines.joinToString { it.descriptor.id }})"
        }
        setActiveInternal(engine, calledFrom = "setActive")
    }

    override fun fallbackChain(): List<LocalAiEngine> = engines

    private fun setActiveInternal(engine: LocalAiEngine, calledFrom: String) {
        val previous = activeEngine
        activeEngine = engine
        if (previous !== engine) {
            Napier.d(tag = TAG) {
                "active=${engine.descriptor.id} (was ${previous?.descriptor?.id ?: "<none>"}, via $calledFrom)"
            }
        }
        releaseUnused(engine)
    }

    /**
     * Free native handles on every engine NOT chosen. Idempotent —
     * [LocalAiEngine.releaseResources] is safe to call on an uninitialised
     * engine (it just nulls already-null references).
     */
    private fun releaseUnused(chosen: LocalAiEngine) {
        engines.forEach { e ->
            if (e !== chosen) {
                runCatching { e.releaseResources() }
                    .onSuccess { Napier.d(tag = TAG) { "released unused engine: ${e.descriptor.id}" } }
                    .onFailure { Napier.w(it, tag = TAG) { "releaseResources failed for ${e.descriptor.id}" } }
            }
        }
    }

    private companion object { const val TAG = "EngineRegistry" }
}
