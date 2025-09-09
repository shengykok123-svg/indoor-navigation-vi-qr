package com.example.finalprototype

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.fragment.app.viewModels
import androidx.fragment.app.activityViewModels
import android.speech.tts.TextToSpeech
import java.util.Locale

class HomeFragment : Fragment() {

    private lateinit var instructionBox: TextView
    var currentDestination: String = "No destination selected"
    private val sharedViewModel: SharedViewModel by activityViewModels()
    private var tts: TextToSpeech? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        instructionBox = view.findViewById(R.id.instructionBox)
        val bottomNav = view.findViewById<BottomNavigationView>(R.id.bottomNav)

        // Show Map first
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.homeFragmentContainer, MapFragment())
                .commit()
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_destination -> {
                    instructionBox.text = "Select a Destination"
                    childFragmentManager.beginTransaction()
                        .replace(R.id.homeFragmentContainer, DestinationFragment())
                        .commit()
                    true
                }
                R.id.nav_camera -> {
                    instructionBox.text = "Open Camera"
                    childFragmentManager.beginTransaction()
                        .replace(R.id.homeFragmentContainer, CameraFragment())
                        .commit()
                    true
                }
                else -> false
            }
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.speak(
                    "Welcome! Please press the bottom left corner to select your destination.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "welcome"
                )
            }
        }
    }

    override fun onDestroyView() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroyView()
    }

    fun updateInstruction(text: String) {
        instructionBox.text = text
    }
}
