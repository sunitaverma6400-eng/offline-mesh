package com.offline.mesh

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import java.io.File

/**
 * Wraps a small, fully offline language model for general Q&A inside the mesh app.
 *
 * PLEASE READ before wiring this into more UI — this is the honest description,
 * matching the tone of the rest of this README/codebase:
 *
 *  - This is a *static* model baked at training time. It has NO internet, NO
 *    search, NO connection to real-time information, and no way to check its
 *    own answers against reality. Whatever it "knows" is frozen and can be
 *    outdated or simply wrong.
 *  - It WILL sometimes generate confident, fluent, wrong answers
 *    (hallucination) — this is a known property of all small LLMs, not a bug
 *    to be fixed here. For anything safety-critical in a protest/shutdown
 *    context (legal rights, medical questions, "is X area safe") treat its
 *    output as a rough first draft, not a verified fact — cross-check with
 *    real people where you possibly can.
 *  - The model weights file (few hundred MB to a few GB depending on which
 *    model you pick) is deliberately NOT bundled in this project/zip — it's
 *    too large, and you should fetch it yourself over a connection you trust
 *    *before* you expect to need it offline. See README "Offline AI setup".
 *  - Only phones that actually loaded a model file can answer "Ask AI"
 *    queries. Everyone else in the mesh can still receive answers if
 *    someone chooses to share one into the group chat (see MainActivity),
 *    always clearly tagged as AI-generated and unverified.
 *
 * NOTE on the tasks-genai 0.10.x API split: the engine itself
 * (LlmInference/LlmInferenceOptions) only knows about the model file, max
 * tokens, and backend. Sampling knobs like top-K/temperature moved to a
 * separate *session* object (LlmInferenceSession/LlmInferenceSessionOptions)
 * that sits on top of the engine - that's why loading now happens in two
 * steps below instead of one.
 */
class OfflineAiManager(private val context: Context) {

    companion object {
        private const val TAG = "OfflineAiManager"
        const val MODEL_FILENAME = "offline_llm_model.task"

        @Volatile
        private var engine: LlmInference? = null

        @Volatile
        private var session: LlmInferenceSession? = null

        /** Where the model file lives once imported — app-private external storage,
         *  no extra runtime permission needed on API 26+. */
        fun modelFile(context: Context): File =
            File(context.getExternalFilesDir(null), MODEL_FILENAME)

        fun isModelPresent(context: Context): Boolean = modelFile(context).exists()
    }

    fun interface AnswerCallback {
        fun onResult(answer: String?, error: String?)
    }

    /** Loads the model + session into memory if not already loaded. This is CPU/RAM
     *  heavy — only ever call from a background thread (ask() below already does this). */
    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (engine != null && session != null) return true
        val file = modelFile(context)
        if (!file.exists()) return false
        return try {
            val options = LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(512)
                .build()
            val newEngine = LlmInference.createFromOptions(context, options)
            val sessionOptions = LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.7f)
                .build()
            session = LlmInferenceSession.createFromOptions(newEngine, sessionOptions)
            engine = newEngine
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load offline model", e)
            engine?.close()
            engine = null
            session = null
            false
        }
    }

    /**
     * Runs [question] through the local model on a background thread. [callback]
     * fires on that same background thread — hop back to the main thread yourself
     * before touching any views.
     */
    fun ask(question: String, callback: AnswerCallback) {
        Thread {
            try {
                if (!ensureLoaded()) {
                    callback.onResult(
                        null,
                        "Model load nahi ho paya — pehle Settings > Offline AI se model file set karo."
                    )
                    return@Thread
                }
                val activeSession = session
                if (activeSession == null) {
                    callback.onResult(null, "Model session ready nahi hai.")
                    return@Thread
                }
                activeSession.addQueryChunk(buildPrompt(question))
                val raw = activeSession.generateResponse()
                if (raw.isNullOrBlank()) {
                    callback.onResult(null, "Model se khaali response mila.")
                } else {
                    callback.onResult(raw.trim(), null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed", e)
                callback.onResult(null, "Inference error: ${e.message}")
            }
        }.start()
    }

    private fun buildPrompt(question: String): String =
        "You are a small offline assistant running on a phone with no internet " +
            "access and no way to verify current events. Answer briefly and " +
            "honestly in the same language as the question. If you are not " +
            "confident, or the question needs up-to-date/real-time information " +
            "you cannot possibly have, say so clearly instead of guessing.\n\n" +
            "Question: $question\nAnswer:"

    /** Frees the loaded model's memory — call when done, e.g. onDestroy of the last
     *  activity that might use it, or after a panic wipe. */
    fun release() {
        session?.close()
        session = null
        engine?.close()
        engine = null
    }
}
