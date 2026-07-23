package com.offline.mesh

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import java.io.File

class OfflineAiManager(private val context: Context) {

    companion object {
        private const val TAG = "OfflineAiManager"
        const val MODEL_FILENAME = "offline_llm_model.task"

        @Volatile
        private var engine: LlmInference? = null

        @Volatile
        private var session: LlmInferenceSession? = null

        fun modelFile(context: Context): File =
            File(context.getExternalFilesDir(null), MODEL_FILENAME)

        fun isModelPresent(context: Context): Boolean = modelFile(context).exists()
    }

    fun interface AnswerCallback {
        fun onResult(answer: String?, error: String?)
    }

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

    fun release() {
        session?.close()
        session = null
        engine?.close()
        engine = null
    }
}
