/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore.rag

/**
 * Tunables for the RAG pipeline, so a consumer can adjust retrieval/ingestion
 * behaviour without forking the engine. The defaults are the values NativeLM
 * shipped with (USE-Lite, 100-dim); change them per corpus/model.
 *
 * @param chunkSize characters per chunk (see [TextChunker]).
 * @param chunkOverlap characters shared between consecutive chunks.
 * @param relevanceMaxDistance cosine-distance gate for the vector arm — hits
 *   farther than this are dropped (0 = identical … 2 = opposite).
 * @param vectorPoolSize nearest-neighbour candidates pulled before fusion.
 * @param keywordPoolSize lexical candidates pulled before fusion.
 * @param maxContextChars cap on the fenced grounding block fed to the model.
 */
data class RagConfig(
    val chunkSize: Int = 500,
    val chunkOverlap: Int = 50,
    val relevanceMaxDistance: Double = 0.75,
    val vectorPoolSize: Int = 30,
    val keywordPoolSize: Int = 120,
    val maxContextChars: Int = 4000,
)
