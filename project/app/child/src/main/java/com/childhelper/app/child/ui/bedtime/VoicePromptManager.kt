package com.childhelper.app.child.ui.bedtime

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.childhelper.app.child.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Manages TextToSpeech voice prompts for the child app.
 * Provides spoken feedback for actions, calming bedtime messages,
 * and call status announcements.
 *
 * This is a singleton service that should be initialized once and
 * reused across screens to avoid TTS engine churn.
 */
class VoicePromptManager(
    private val context: Context
) {
    private var textToSpeech: TextToSpeech? = null
    private var onInitCallback: (() -> Unit)? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /**
     * Initialize the TextToSpeech engine.
     *
     * @param onReady Called when TTS is initialized and ready to speak
     */
    fun initialize(onReady: (() -> Unit)? = null) {
        if (textToSpeech != null && _isReady.value) {
            onReady?.invoke()
            return
        }

        this.onInitCallback = onReady

        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureTts()
            } else {
                Log.w(TAG, "TextToSpeech initialization failed with status: $status")
            }
        }
    }

    private fun configureTts() {
        val tts = textToSpeech ?: return

        try {
            // Set to US English
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "US English not available, falling back to default")
                tts.setLanguage(Locale.getDefault())
            }

            // Slower, calmer speech rate for children
            tts.setSpeechRate(0.75f)

            // Slightly lower pitch for calming effect
            tts.setPitch(0.95f)

            // Set up progress listener
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _isSpeaking.value = false
                }
            })

            _isReady.value = true
            onInitCallback?.invoke()
            onInitCallback = null

        } catch (e: Exception) {
            Log.e(TAG, "Error configuring TTS", e)
        }
    }

    /**
     * Speak a message aloud.
     *
     * @param text The text to speak
     * @param queueMode TextToSpeech.QUEUE_ADD to queue, TextToSpeech.QUEUE_FLUSH to interrupt
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!_isReady.value) {
            Log.w(TAG, "TTS not ready, message not spoken: $text")
            return
        }

        val utteranceId = UUID.randomUUID().toString()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech?.speak(text, queueMode, null, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            val params = HashMap<String, String>()
            params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = utteranceId
            textToSpeech?.speak(text, queueMode, params)
        }
    }

    /**
     * Speak a calming bedtime message. Interrupts any current speech.
     */
    fun speakBedtimeMessage(message: String) {
        speak(message, TextToSpeech.QUEUE_FLUSH)
    }

    /**
     * Speak a call-related announcement.
     */
    fun speakCallStatus(message: String) {
        speak(message, TextToSpeech.QUEUE_FLUSH)
    }

    /**
     * Stop all speech immediately.
     */
    fun stopSpeaking() {
        textToSpeech?.stop()
        _isSpeaking.value = false
    }

    /**
     * Pre-defined calming bedtime messages for children.
     */
    fun getRandomBedtimeMessage(): String {
        val messages = context.resources.getStringArray(R.array.bedtime_messages)
        return messages.random()
    }

    /**
     * Shutdown and release TTS resources.
     * Call this when the app is being destroyed.
     */
    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        _isReady.value = false
        _isSpeaking.value = false
    }

    companion object {
        private const val TAG = "VoicePromptManager"
    }
}
