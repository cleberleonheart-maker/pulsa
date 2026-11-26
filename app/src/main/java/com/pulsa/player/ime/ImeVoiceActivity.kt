package com.pulsa.player.ime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent

class ImeVoiceActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val language = intent.getStringExtra(EXTRA_LANGUAGE)
        val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(com.pulsa.player.R.string.ime_voice_prompt))
        }
        try {
            startActivityForResult(recognitionIntent, REQUEST_RECOGNIZE)
        } catch (_: Exception) {
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_RECOGNIZE && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                val token = intent.getStringExtra(EXTRA_TOKEN)
                sendBroadcast(
                    Intent(ACTION_VOICE_RESULT).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_SESSION_ID, sessionId)
                        putExtra(EXTRA_TOKEN, token)
                        putExtra(EXTRA_RESULT, text)
                    },
                )
            }
        }
        finish()
    }

    companion object {
        const val ACTION_VOICE_RESULT = "com.pulsa.player.ime.ACTION_VOICE_RESULT"
        const val EXTRA_LANGUAGE = "com.pulsa.player.ime.EXTRA_LANGUAGE"
        const val EXTRA_SESSION_ID = "com.pulsa.player.ime.EXTRA_SESSION_ID"
        const val EXTRA_TOKEN = "com.pulsa.player.ime.EXTRA_TOKEN"
        const val EXTRA_RESULT = "com.pulsa.player.ime.EXTRA_RESULT"
        private const val REQUEST_RECOGNIZE = 4201
    }
}
