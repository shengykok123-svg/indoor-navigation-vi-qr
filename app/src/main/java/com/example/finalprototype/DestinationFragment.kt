package com.example.finalprototype

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import java.util.*

class DestinationFragment : Fragment() {

    private lateinit var destinationList: ListView
    private lateinit var btnUp: Button
    private lateinit var btnDown: Button
    private lateinit var selectedDestination: TextView

    private val vm: SharedViewModel by activityViewModels()

    private var tts: TextToSpeech? = null
    private val destinations = listOf(
        "TP001","TP002","TP003","TP004","TP005","TP006","TP007","TP008","TP009","TP010",
        "TP011","Main Entrance","TP013","TP014","TP015","TP016","NG-026","NG-033","NG-005",
        "N007A","N007B","NGT4&5_Toilet","N012A","N012B","N001A","N001B","N009A","N009B","TP017"
    )
    private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(requireContext()) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.US }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_destination, container, false)

        destinationList      = v.findViewById(R.id.destinationList)
        btnUp                = v.findViewById(R.id.btnUp)
        btnDown              = v.findViewById(R.id.btnDown)
        selectedDestination  = v.findViewById(R.id.destinationTitle)

        // List adapter
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_activated_1, destinations)
        destinationList.adapter = adapter
        destinationList.choiceMode = ListView.CHOICE_MODE_SINGLE

        // Restore previous selection (if any) from VM
        vm.destination.value?.let { saved ->
            val idx = destinations.indexOf(saved)
            if (idx >= 0) {
                currentIndex = idx
                destinationList.setItemChecked(idx, true)
                selectedDestination.text = saved
            } else {
                applySelection() // fallback to index 0
            }
        } ?: applySelection()

        // Keep UI in sync if VM changes elsewhere
        vm.destination.observe(viewLifecycleOwner) { name ->
            val idx = destinations.indexOf(name)
            if (idx >= 0 && idx != currentIndex) {
                currentIndex = idx
                destinationList.setItemChecked(idx, true)
                selectedDestination.text = name
            }
        }

        // Clicks update selection + VM
        destinationList.setOnItemClickListener { _, _, position, _ ->
            currentIndex = position
            applySelection()
        }

        btnUp.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                applySelection()
            }
        }
        btnDown.setOnClickListener {
            if (currentIndex < destinations.lastIndex) {
                currentIndex++
                applySelection()
            }
        }

        return v
    }

    private fun applySelection() {
        val name = destinations[currentIndex]
        selectedDestination.text = name
        destinationList.setItemChecked(currentIndex, true)
        vm.setDestination(name)                    // <-- persist
        tts?.speak(name, TextToSpeech.QUEUE_FLUSH, null, "dest")
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }
}
