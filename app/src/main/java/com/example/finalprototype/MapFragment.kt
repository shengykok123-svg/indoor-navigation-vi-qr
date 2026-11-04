package com.example.finalprototype

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.caverock.androidsvg.SVG

/**
 * Standalone map fragment displaying navigation routes on SVG floor plan.
 *
 * Features:
 * - SVG floor plan rendering with route overlay
 * - Real-time route updates based on ViewModel state
 * - Coordinate conversion from node positions to map visualization
 *
 * Note: This fragment is currently unused in the app architecture but provides
 * a dedicated map view that could be used for alternative UI layouts.
 * The main map functionality is handled by RouteMapView in HomeFragment.
 */
class MapFragment : Fragment() {

    // ViewModel for observing navigation state changes
    private val sharedViewModel: SharedViewModel by activityViewModels()
    private lateinit var routeMapView: RouteMapView

    /**
     * Create fragment view and initialize map components
     */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_map, container, false)

        // Initialize map view
        routeMapView = view.findViewById(R.id.routeMapView)

        // Load SVG floor plan from assets
        try {
            val inputStream = requireContext().assets.open("fict_gfloorplan.svg")
            val svg = SVG.getFromInputStream(inputStream)
            routeMapView.setMapSvg(svg)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Set up route observation for real-time updates
        setupRouteObserver()

        return view
    }

    /**
     * Set up observers for route changes to update map visualization
     */
    private fun setupRouteObserver() {
        // Observe current path from ViewModel and draw route when it changes
        sharedViewModel.currentPath.observe(viewLifecycleOwner) { path ->
            if (path.isNotEmpty()) {
                drawRouteOnMap(path)
            } else {
                // Clear route if no path
                routeMapView.setRoutePath(emptyList())
            }
        }

        // Also observe navigation state changes to refresh route display
        sharedViewModel.isNavigationActive.observe(viewLifecycleOwner) { isActive ->
            // Update route visualization based on navigation state
            val currentPath = sharedViewModel.currentPath.value
            if (isActive && !currentPath.isNullOrEmpty()) {
                drawRouteOnMap(currentPath)
            }
        }
    }

    /**
     * Convert node path to coordinate pairs for route visualization
     */
    private fun drawRouteOnMap(nodePath: List<String>) {
        // Convert node IDs to coordinate pairs for drawing
        val routeCoordinates = mutableListOf<Pair<Float, Float>>()

        for (nodeId in nodePath) {
            val node = sharedViewModel.getNodeById(nodeId)
            node?.let {
                // Convert node coordinates to map coordinates
                routeCoordinates.add(Pair(it.x.toFloat(), it.y.toFloat()))
            }
        }

        // Set the route path on the map view
        routeMapView.setRoutePath(routeCoordinates)
    }
}