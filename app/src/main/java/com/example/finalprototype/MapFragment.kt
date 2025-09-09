package com.example.finalprototype

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.caverock.androidsvg.SVG

class MapFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_map, container, false)

        val routeMapView = view.findViewById<RouteMapView>(R.id.routeMapView)

        try {
            val inputStream = requireContext().assets.open("fict_gfloorplan.svg")
            val svg = SVG.getFromInputStream(inputStream)
            routeMapView.setMapSvg(svg)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return view
    }
}

