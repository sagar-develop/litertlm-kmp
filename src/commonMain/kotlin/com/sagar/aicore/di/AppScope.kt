package com.sagar.aicore.di

import me.tatarka.inject.annotations.Scope

/**
 * Singleton-per-AppComponent scope.
 *
 * kotlin-inject does NOT share instances across the DI graph by default — every
 * constructor-injection consumer of `EmbeddingEngine` etc. would otherwise get
 * its own fresh `MediaPipeEmbeddingEngine`. With `@AppScope` on both the
 * component and the heavy engine classes, kotlin-inject caches one instance per
 * component and reuses it across all consumers.
 *
 * Surfaced by bridge runbook 011 + diagnostic identity-hash logs: pre-scope
 * cold launch created 8 different `MediaPipeEmbeddingEngine` instances within
 * ~80ms, only 2 of which got `initializeEngines()` called. Downstream
 * agents grabbed an uninitialized one and threw "Embedding model not loaded".
 */
@Scope
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class AppScope
