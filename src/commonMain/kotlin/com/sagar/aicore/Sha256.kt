/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.Source
import okio.blackholeSink
import okio.buffer

/**
 * Streams a [Source] through okio's [HashingSource] to produce a
 * lowercase-hex SHA-256. Streaming via [okio.blackholeSink] keeps the
 * full input out of memory — critical for multi-GB model weights.
 * Split out from [sha256OfFile] so it can be unit-tested against an
 * in-memory [okio.Buffer] without a real [FileSystem].
 */
internal fun sha256OfSource(source: Source): String {
    val hashing = HashingSource.sha256(source)
    val bs = hashing.buffer()
    try {
        bs.readAll(blackholeSink())
    } finally {
        bs.close()
    }
    return hashing.hash.hex()
}

/**
 * SHA-256 over an on-disk file. Used by [KtorModelManager] to verify
 * model integrity after a download when [ModelDescriptor.sha256] is set.
 */
internal fun sha256OfFile(fileSystem: FileSystem, path: Path): String =
    sha256OfSource(fileSystem.source(path))
