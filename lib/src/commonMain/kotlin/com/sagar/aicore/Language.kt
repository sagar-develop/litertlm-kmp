/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.aicore

/**
 * Output languages the on-device model can answer in. Shared in the engine so every
 * consumer app (NativeLM, kids apps, …) reuses one battle-tested language layer.
 *
 * The approach is **prompt-only** — no translation model. Gemma generates natively in the
 * target language, steered by [outputDirective], which is appended to the prompt as
 * `"Output language: <directive>."`. For weak-coverage Indic scripts (Kannada, Punjabi)
 * the model otherwise silently falls back to Devanagari/Hindi, so those carry an emphatic
 * **strict-script directive** (proven fix from the astrology app).
 *
 * [code] is the BCP-47 tag. [nativeName] is the language in its own script (for selectors);
 * [englishName] is the transliteration / English label.
 */
enum class Language(
    val code: String,
    val nativeName: String,
    val englishName: String,
) {
    // English + the Indian languages (ported from the astrology app, strict-script tuned).
    ENGLISH("en", "English", "English"),
    HINDI("hi", "हिन्दी", "Hindi"),
    BENGALI("bn", "বাংলা", "Bengali"),
    TAMIL("ta", "தமிழ்", "Tamil"),
    MARATHI("mr", "मराठी", "Marathi"),
    TELUGU("te", "తెలుగు", "Telugu"),
    GUJARATI("gu", "ગુજરાતી", "Gujarati"),
    KANNADA("kn", "ಕನ್ನಡ", "Kannada"),
    MALAYALAM("ml", "മലയാളം", "Malayalam"),
    PUNJABI("pa", "ਪੰਜਾਬੀ", "Punjabi"),

    // Global languages (NativeLM has a worldwide audience). Quality varies by model
    // coverage — verify per language on-device; add strict-script lines below if a script
    // comes out wrong.
    SPANISH("es", "Español", "Spanish"),
    FRENCH("fr", "Français", "French"),
    GERMAN("de", "Deutsch", "German"),
    PORTUGUESE("pt", "Português", "Portuguese"),
    ITALIAN("it", "Italiano", "Italian"),
    RUSSIAN("ru", "Русский", "Russian"),
    ARABIC("ar", "العربية", "Arabic"),
    CHINESE("zh", "中文", "Chinese (Simplified)"),
    JAPANESE("ja", "日本語", "Japanese"),
    INDONESIAN("id", "Bahasa Indonesia", "Indonesian");

    /**
     * The directive injected into the prompt (`"Output language: <this>."`). Most languages
     * just name themselves; Kannada and Punjabi get an emphatic strict-script clause because
     * Gemma otherwise "plays it safe" with Devanagari — the proven fix.
     */
    val outputDirective: String
        get() = when (this) {
            KANNADA ->
                "Kannada. CRITICAL: output STRICTLY in Kannada script (ಕನ್ನಡ ಲಿಪಿ). " +
                    "NEVER use Devanagari / Hindi script — that would be wrong"
            PUNJABI ->
                "Punjabi. CRITICAL: output STRICTLY in Gurmukhi script (ਗੁਰਮੁਖੀ ਲਿਪੀ). " +
                    "NEVER use Devanagari / Hindi script — that would be wrong"
            else -> englishName
        }

    companion object {
        val DEFAULT: Language = ENGLISH

        fun fromCode(code: String?): Language =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
