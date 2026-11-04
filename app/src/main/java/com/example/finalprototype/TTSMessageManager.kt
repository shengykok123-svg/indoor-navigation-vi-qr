package com.example.finalprototype

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView

/**
 * Manages displaying TTS messages as temporary visual notifications
 */
class TTSMessageManager(
    private val messageView: TextView
) {
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    companion object {
        private const val MESSAGE_DURATION = 6000L // 6 seconds
    }

    /**
     * Display a TTS message that will automatically hide after duration
     */
    fun showMessage(message: String) {
        // Cancel any pending hide operation
        hideRunnable?.let { handler.removeCallbacks(it) }

        // Show message immediately
        messageView.text = message
        messageView.visibility = View.VISIBLE
        messageView.alpha = 1f

        // Schedule hide after duration
        hideRunnable = Runnable {
            hideMessage()
        }
        handler.postDelayed(hideRunnable!!, MESSAGE_DURATION)
    }

    /**
     * Hide the message
     */
    private fun hideMessage() {
        messageView.visibility = View.GONE
        messageView.text = ""
        messageView.alpha = 0f
    }

    /**
     * Cleanup when no longer needed
     */
    fun cleanup() {
        hideRunnable?.let { handler.removeCallbacks(it) }
    }
}