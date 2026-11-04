package com.example.finalprototype

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * Fragment for destination selection with accessible navigation controls.
 *
 * Features:
 * - Simple up/down navigation through available destinations
 * - Double-tap confirmation system for destination selection
 * - TTS feedback for each destination as user navigates with visual display
 * - Automatic transition to camera fragment after confirmation
 * - State preservation when switching between destinations
 *
 * The interface is designed for accessibility with large buttons and
 * clear audio feedback for each interaction.
 */
class DestinationFragment : Fragment() {

    // UI components for destination selection
    private lateinit var btnUp: Button                    // Previous destination button
    private lateinit var btnDown: Button                  // Next destination button
    private lateinit var selectedDestination: TextView    // Current destination display
    private lateinit var currentSelectionText: TextView   // Status text showing current selection

    // ViewModel for shared navigation state
    private val vm: SharedViewModel by activityViewModels()

    // TTS for accessibility feedback
    private var tts: TextToSpeech? = null

    // Available destinations list - matches MapDataLoader destination mappings
    private val destinations = listOf(
        "Main Entrance","NG-026","NG-033","NG-005", "N007A","N007B","NGT4&5_Toilet","N012A",
        "N012B","N001A","N001B","N009A","N009B"
    )

    // Selection state
    private var currentIndex = 0                         // Current position in destinations list
    private var confirmPressedOnce = false              // Double-tap confirmation state

    /**
     * Initialize TTS when fragment is created
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(requireContext()) {
            if (it == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
    }

    /**
     * Create fragment view and initialize UI components
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_destination, container, false)

        // Initialize UI component references
        btnUp                = v.findViewById(R.id.btnUp)
        btnDown              = v.findViewById(R.id.btnDown)
        selectedDestination  = v.findViewById(R.id.destinationTitle)
        currentSelectionText = v.findViewById(R.id.currentSelectionText)
        val btnConfirm       = v.findViewById<Button>(R.id.btnConfirm)

        // Restore previous selection (if any) from ViewModel
        vm.destination.value?.let { saved ->
            val idx = destinations.indexOf(saved)
            if (idx >= 0) {
                currentIndex = idx
                selectedDestination.text = saved
                currentSelectionText.text = "Current selection: $saved"
            } else {
                applySelection() // fallback to index 0
            }
        } ?: applySelection()

        // Keep UI in sync if ViewModel changes elsewhere
        vm.destination.observe(viewLifecycleOwner) { name ->
            val idx = destinations.indexOf(name)
            if (idx >= 0 && idx != currentIndex) {
                currentIndex = idx
                selectedDestination.text = name
                currentSelectionText.text = "Current selection: $name"
            }
        }

        // Set up button click listeners
        setupButtonListeners(btnConfirm)

        return v
    }

    /**
     * Set up click listeners for navigation and confirmation buttons
     */
    private fun setupButtonListeners(btnConfirm: Button) {
        // Previous destination button (up arrow)
        btnUp.setOnClickListener {
            currentIndex = max(0, currentIndex - 1)
            applySelection()
        }

        // Next destination button (down arrow)
        btnDown.setOnClickListener {
            currentIndex = min(destinations.size - 1, currentIndex + 1)
            applySelection()
        }

        // Confirmation button with double-tap safety
        btnConfirm.setOnClickListener {
            handleConfirmClick()
        }
    }

    /**
     * Handle confirmation button click with double-tap verification
     */
    private fun handleConfirmClick() {
        if (!confirmPressedOnce) {
            // First press - request confirmation
            val name = destinations[currentIndex]
            val message = "You have selected $name, press the confirm button again to proceed."
            speak(message)
            confirmPressedOnce = true
        } else {
            // Second press - proceed with selection
            navigateToCameraFragment()
            confirmPressedOnce = false
        }
    }

    /**
     * Navigate to camera fragment after destination confirmation
     */
    private fun navigateToCameraFragment() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.homeFragmentContainer, CameraFragment())
            .commit()
        confirmPressedOnce = false

        // Provide TTS feedback for camera screen
        val message = "You are now at the camera and navigation screen. " +
                "Point your camera at a QR code to scan it, " +
                "or use the PDR navigation buttons to start step-based navigation."
        speak(message)
    }

    /**
     * Apply current selection to UI and ViewModel, with TTS feedback
     */
    private fun applySelection() {
        val name = destinations[currentIndex]

        // Update UI displays
        selectedDestination.text = name
        currentSelectionText.text = "Current selection: $name"

        // Update ViewModel to propagate change to other fragments
        vm.setDestination(name)

        // Provide audio feedback
        speak(name)

        // Reset confirmation state when selection changes
        confirmPressedOnce = false
    }

    /**
     * Enhanced speak method that also displays visual message through parent fragment
     */
    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dest")

        // Send message to parent HomeFragment for visual display
        (parentFragment as? HomeFragment)?.showTTSMessage(text)
    }

    /**
     * Clean up TTS resources when fragment is destroyed
     */
    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}