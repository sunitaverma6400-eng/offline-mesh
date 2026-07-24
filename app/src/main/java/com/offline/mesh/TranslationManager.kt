package com.offline.mesh

import android.content.Context

/**
 * On-device translation for multilingual groups.
 *
 * Honest design decision: rather than bundling a SECOND offline ML stack (a
 * dedicated TFLite/MediaPipe translation model, on top of the LLM this app
 * already optionally loads via [OfflineAiManager]), this reuses that same
 * already-integrated local model to do translation via prompting. Trade-offs,
 * stated plainly:
 *
 *  - PRO: zero extra model file to download/manage - if someone already set up
 *    "Offline AI" (see OfflineAiManager), translation works immediately with no
 *    extra setup step.
 *  - CON: quality depends entirely on which small model the person loaded, and
 *    small general-purpose LLMs are noticeably worse at translation than a
 *    model actually trained for it. Treat output as "rough gist", not
 *    professional/legal-grade translation - same honesty rule as
 *    OfflineAiManager already states for its Q&A answers.
 *  - CON: needs the LLM's RAM/CPU footprint - won't work on a phone that
 *    couldn't run OfflineAiManager either.
 *
 * If a dedicated lightweight translation model (e.g. a TFLite NLLB/M2M-100
 * export) is added later, swap the body of [translate] to call that instead -
 * the public interface here (translate/isAvailable) doesn't need to change,
 * so nothing calling this class needs to know which backend is in use.
 */
class TranslationManager(private val context: Context) {

    private val aiManager = OfflineAiManager(context)

    fun interface TranslationCallback {
        fun onResult(translated: String?, error: String?)
    }

    /** True only if an offline model file is actually present - same requirement
     *  as OfflineAiManager's Q&A feature, since this reuses that model. */
    fun isAvailable(): Boolean = OfflineAiManager.isModelPresent(context)

    /**
     * Translates [text] into [targetLanguage] (e.g. "Hindi", "English", "Spanish").
     * Runs on a background thread; [callback] fires on that same thread.
     */
    fun translate(text: String, targetLanguage: String, callback: TranslationCallback) {
        if (text.isBlank()) {
            callback.onResult("", null)
            return
        }
        if (!isAvailable()) {
            callback.onResult(
                null,
                "Translation ke liye offline model chahiye — Settings > Offline AI se model file set karo."
            )
            return
        }
        val prompt = "Translate the following message into $targetLanguage. " +
            "Reply with ONLY the translation, no explanation, no quotes:\n\n$text"
        aiManager.ask(prompt) { answer, error ->
            if (error != null) {
                callback.onResult(null, error)
            } else {
                // Strip common wrapper artifacts small models sometimes add.
                val cleaned = answer?.trim()?.trim('"')?.removePrefix("Translation:")?.trim()
                callback.onResult(cleaned, null)
            }
        }
    }

    fun release() {
        aiManager.release()
    }
}
