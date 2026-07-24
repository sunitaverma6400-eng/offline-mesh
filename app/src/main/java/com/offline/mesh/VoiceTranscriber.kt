package com.offline.mesh

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * Voice note transcription — useful in silent/high-risk situations where audio
 * can't be played back to check what a voice note says.
 *
 * Honest limitation: this uses Android's BUILT-IN SpeechRecognizer with
 * EXTRA_PREFER_OFFLINE set, NOT a bundled model this app ships. Whether it
 * actually runs on-device (no data leaves the phone) depends on:
 *  - the phone having Google's offline speech recognition language pack
 *    downloaded already (Settings > System > Languages > On-device speech
 *    recognition, on stock Android/Google keyboard phones), or an equivalent
 *    on other OEM builds/AOSP forks
 *  - EXTRA_PREFER_OFFLINE is a HINT, not a hard guarantee - some OEM
 *    implementations ignore it and fall back to a network recognizer, which
 *    would silently require connectivity/leave the device. There is no public
 *    Android API to force-verify "this transcription request never touched the
 *    network" - if that's a hard requirement, don't rely on this and use a
 *    truly bundled on-device model (e.g. a Vosk/whisper.cpp .tflite build)
 *    instead, which is more setup work but auditable.
 *  - This is why the transcript is always clearly labeled "AI transcript,
 *    verify by listening if possible" in the UI (see MeshToolsActivity) rather
 *    than presented as a guaranteed-accurate/guaranteed-offline text.
 */
class VoiceTranscriber(private val context: Context) {

    fun interface TranscriptCallback {
        fun onResult(transcript: String?, error: String?)
    }

    /** SpeechRecognizer being available doesn't guarantee an offline engine is
     *  installed - it's the best pre-check the public API offers. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Transcribes live microphone input (used right after/instead of recording a
     * voice note - Android's SpeechRecognizer works on live audio, not arbitrary
     * pre-recorded audio files, so voice notes get transcribed AS they're recorded;
     * see MainActivity's record-voice flow for where this hooks in).
     * [callback] fires on the main thread exactly once (success or error).
     */
    fun startListening(languageTag: String = Locale.getDefault().toLanguageTag(), callback: TranscriptCallback) {
        val mainHandler = Handler(Looper.getMainLooper())
        if (!isAvailable()) {
            callback.onResult(null, "Speech recognizer is phone pe available nahi hai.")
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        var delivered = false
        fun deliver(text: String?, error: String?) {
            if (delivered) return
            delivered = true
            mainHandler.post { callback.onResult(text, error) }
            recognizer.destroy()
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                deliver(matches?.firstOrNull(), null)
            }
            override fun onError(error: Int) {
                deliver(null, "Transcription error code: $error")
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)

        // Safety timeout in case the recognizer never calls back (some OEM builds).
        mainHandler.postDelayed({ deliver(null, "Transcription timed out.") }, 15000)
    }
}
