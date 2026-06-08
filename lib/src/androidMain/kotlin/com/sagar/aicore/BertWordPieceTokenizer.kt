/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

import android.util.JsonReader
import java.io.File
import java.io.InputStreamReader
import java.text.Normalizer

/**
 * Pure-Kotlin BERT WordPiece tokenizer matching the ms-marco MiniLM cross-encoder's
 * HuggingFace `tokenizer.json` (uncased: clean → handle-CJK → lowercase → strip
 * accents → split whitespace/punctuation → greedy `##` WordPiece). Verified
 * bit-for-bit against the reference `transformers` tokenizer (ASCII, punctuation,
 * accents, CJK, emails).
 *
 * Produces cross-encoder pair input: `[CLS] query [SEP] passage [SEP]` with
 * token_type_ids 0 for the first segment and 1 for the second.
 */
class BertWordPieceTokenizer private constructor(
    private val vocab: HashMap<String, Int>,
    private val clsId: Int,
    private val sepId: Int,
    private val unkId: Int,
) {

    class PairEncoding(val ids: LongArray, val typeIds: LongArray, val mask: LongArray)

    fun encodePair(query: String, passage: String): PairEncoding {
        val q = wordPieces(basicTokenize(query))
        val p = wordPieces(basicTokenize(passage))
        val ids = ArrayList<Long>(q.size + p.size + 3)
        val types = ArrayList<Long>(q.size + p.size + 3)
        ids.add(clsId.toLong()); types.add(0)
        for (t in q) { ids.add((vocab[t] ?: unkId).toLong()); types.add(0) }
        ids.add(sepId.toLong()); types.add(0)
        for (t in p) { ids.add((vocab[t] ?: unkId).toLong()); types.add(1) }
        ids.add(sepId.toLong()); types.add(1)
        val idArr = LongArray(ids.size) { ids[it] }
        val typeArr = LongArray(types.size) { types[it] }
        return PairEncoding(idArr, typeArr, LongArray(idArr.size) { 1L })
    }

    /** BERT basic tokenizer: clean, CJK-pad, lowercase, strip accents, split ws + punct. */
    private fun basicTokenize(text: String): List<String> {
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue
            when {
                isWhitespace(cp) -> sb.append(' ')
                isCjk(cp) -> { sb.append(' '); sb.appendCodePoint(cp); sb.append(' ') }
                else -> sb.appendCodePoint(cp)
            }
        }
        // lowercase + strip accents (NFD, drop non-spacing marks)
        val lowered = sb.toString().lowercase()
        val nfd = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        val stripped = StringBuilder(nfd.length)
        var j = 0
        while (j < nfd.length) {
            val cp = nfd.codePointAt(j)
            j += Character.charCount(cp)
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
            stripped.appendCodePoint(cp)
        }
        // split on whitespace, then isolate punctuation
        val out = ArrayList<String>()
        for (word in stripped.toString().split(' ')) {
            if (word.isEmpty()) continue
            val cur = StringBuilder()
            var k = 0
            while (k < word.length) {
                val cp = word.codePointAt(k)
                k += Character.charCount(cp)
                if (isPunct(cp)) {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) }
                    out.add(String(Character.toChars(cp)))
                } else {
                    cur.appendCodePoint(cp)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
        }
        return out
    }

    /** Greedy longest-match WordPiece with `##` continuation; [UNK] on failure. */
    private fun wordPieces(tokens: List<String>): List<String> {
        val out = ArrayList<String>()
        for (token in tokens) {
            if (token.length > MAX_CHARS) { out.add(UNK); continue }
            var start = 0
            val n = token.length
            var bad = false
            val pieces = ArrayList<String>()
            while (start < n) {
                var end = n
                var cur: String? = null
                while (start < end) {
                    val sub = if (start > 0) "##" + token.substring(start, end) else token.substring(start, end)
                    if (vocab.containsKey(sub)) { cur = sub; break }
                    end--
                }
                if (cur == null) { bad = true; break }
                pieces.add(cur)
                start = end
            }
            if (bad) out.add(UNK) else out.addAll(pieces)
        }
        return out
    }

    companion object {
        private const val UNK = "[UNK]"
        private const val MAX_CHARS = 100

        private fun isWhitespace(cp: Int): Boolean =
            cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
                Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

        private fun isControl(cp: Int): Boolean {
            if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
            return when (Character.getType(cp)) {
                Character.CONTROL.toInt(), Character.FORMAT.toInt(),
                Character.SURROGATE.toInt(), Character.PRIVATE_USE.toInt(),
                Character.UNASSIGNED.toInt() -> true
                else -> false
            }
        }

        private fun isPunct(cp: Int): Boolean {
            if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
            return when (Character.getType(cp)) {
                Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt() -> true
                else -> false
            }
        }

        private fun isCjk(cp: Int): Boolean =
            (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) || (cp in 0x20000..0x2A6DF) ||
                (cp in 0x2A700..0x2B73F) || (cp in 0x2B740..0x2B81F) || (cp in 0x2B820..0x2CEAF) ||
                (cp in 0xF900..0xFAFF) || (cp in 0x2F800..0x2FA1F)

        fun load(tokenizerJsonPath: String): BertWordPieceTokenizer {
            val vocab = HashMap<String, Int>(35_000)
            File(tokenizerJsonPath).inputStream().use { stream ->
                JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "model") {
                            r.beginObject()
                            while (r.hasNext()) {
                                if (r.nextName() == "vocab") {
                                    r.beginObject()
                                    while (r.hasNext()) vocab[r.nextName()] = r.nextInt()
                                    r.endObject()
                                } else r.skipValue()
                            }
                            r.endObject()
                        } else r.skipValue()
                    }
                    r.endObject()
                }
            }
            return BertWordPieceTokenizer(vocab, vocab["[CLS]"] ?: 101, vocab["[SEP]"] ?: 102, vocab["[UNK]"] ?: 100)
        }
    }
}
