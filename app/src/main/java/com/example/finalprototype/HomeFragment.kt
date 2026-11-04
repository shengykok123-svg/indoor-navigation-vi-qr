package com.example.finalprototype

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.fragment.app.activityViewModels
import android.speech.tts.TextToSpeech
import com.caverock.androidsvg.SVG
import java.util.Locale

/**
 * Main container fragment managing the primary app interface.
 *
 * Features:
 * - Bottom navigation between Destination, Map, and Camera tabs
 * - Real-time navigation status display with progress tracking
 * - Route visualization on SVG floor plan
 * - Child fragment management for Destination and Camera screens
 * - TTS welcome messages and navigation feedback with visual display
 * - Efficient fragment caching to preserve state across tab switches
 */
class HomeFragment : Fragment() {

    // UI components for navigation status display
    private lateinit var currentLocationBanner: TextView
    private lateinit var navigationStatusBox: TextView
    private lateinit var totalStepCountDisplay: TextView
    private lateinit var totalDistanceDisplay: TextView
    private lateinit var segmentStepCountDisplay: TextView
    private lateinit var segmentDistanceDisplay: TextView
    private lateinit var stepDistanceLayout: LinearLayout
    private lateinit var routeMapView: RouteMapView
    private lateinit var ttsMessageDisplay: TextView
    private lateinit var messageManager: TTSMessageManager

    // ViewModel and TTS for state management and accessibility
    private val sharedViewModel: SharedViewModel by activityViewModels()
    private var tts: TextToSpeech? = null

    // Fragment caching
    private var destinationFragment: DestinationFragment? = null
    private var cameraFragment: CameraFragment? = null

    // Shared NavigationModule reference
    private var navigationModule: NavigationModule? = null

    // Handler for delayed operations
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Create fragment view and initialize components
     */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        initializeViews(view)
        getSharedNavigationModule()
        setupBottomNavigation(view)
        setupObservers()
        setupMapView()

        return view
    }

    /**
     * Initialize UI component references
     */
    private fun initializeViews(view: View) {
        currentLocationBanner = view.findViewById(R.id.currentLocationBanner)
        navigationStatusBox = view.findViewById(R.id.navigationStatusBox)
        totalStepCountDisplay = view.findViewById(R.id.totalStepCountDisplay)
        totalDistanceDisplay = view.findViewById(R.id.totalDistanceDisplay)
        segmentStepCountDisplay = view.findViewById(R.id.segmentStepCountDisplay)
        segmentDistanceDisplay = view.findViewById(R.id.segmentDistanceDisplay)
        stepDistanceLayout = view.findViewById(R.id.stepDistanceLayout)
        routeMapView = view.findViewById(R.id.routeMapView)
        ttsMessageDisplay = view.findViewById(R.id.ttsMessageDisplay)

        messageManager = TTSMessageManager(ttsMessageDisplay)
    }

    /**
     * Get the shared NavigationModule from MainActivity
     */
    private fun getSharedNavigationModule() {
        navigationModule = (requireActivity() as? MainActivity)?.getNavigationModule { message ->
            messageManager.showMessage(message)
        }
    }

    /**
     * Public method for child fragments to display TTS messages
     */
    fun showTTSMessage(message: String) {
        messageManager.showMessage(message)
    }

    /**
     * Set up the SVG map view and route observation
     */
    private fun setupMapView() {
        try {
            val inputStream = requireContext().assets.open("fict_gfloorplan.svg")
            val svg = SVG.getFromInputStream(inputStream)
            routeMapView.setMapSvg(svg)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setupRouteObserver()
    }

    /**
     * Set up observers for route changes
     */
    private fun setupRouteObserver() {
        sharedViewModel.currentPath.observe(viewLifecycleOwner) { path ->
            if (path.isNotEmpty()) {
                drawRouteOnMap(path)
            } else {
                routeMapView.setRoutePath(emptyList())
            }
        }

        sharedViewModel.isNavigationActive.observe(viewLifecycleOwner) { isActive ->
            val currentPath = sharedViewModel.currentPath.value
            if (isActive && !currentPath.isNullOrEmpty()) {
                drawRouteOnMap(currentPath)
            }
        }
    }

    /**
     * Convert route node IDs to coordinates and draw on map
     */
    private fun drawRouteOnMap(nodePath: List<String>) {
        val routeCoordinates = mutableListOf<Pair<Float, Float>>()

        for (nodeId in nodePath) {
            val node = sharedViewModel.getNodeById(nodeId)
            node?.let {
                routeCoordinates.add(Pair(it.x.toFloat(), it.y.toFloat()))
            }
        }

        routeMapView.setRoutePath(routeCoordinates)
    }

    /**
     * Set up bottom navigation with tab switching
     */
    private fun setupBottomNavigation(view: View) {
        val bottomNav = view.findViewById<BottomNavigationView>(R.id.bottomNav)

        bottomNav.selectedItemId = R.id.nav_map

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_destination -> {
                    routeMapView.visibility = View.GONE
                    notifyCameraFragmentVisibility(false)
                    showDestinationFragment()
                    val message = "You are now at the destination selection menu. " +
                            "Press the middle left side of the screen to select previous destination. " +
                            "Press the middle right side of the screen to select next destination. " +
                            "Press the bottom button to confirm your selection."
                    tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "destination")
                    showTTSMessage(message)
                    true
                }
                R.id.nav_map -> {
                    routeMapView.visibility = View.VISIBLE
                    notifyCameraFragmentVisibility(false)
                    hideFragmentContainer()
                    val message = "You are now viewing the map. The route will be displayed as a red line if navigation is active."
                    tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "map")
                    showTTSMessage(message)
                    true
                }
                R.id.nav_camera -> {
                    routeMapView.visibility = View.GONE
                    showCameraFragment()

                    handler.postDelayed({
                        notifyCameraFragmentVisibility(true)
                    }, 150)

                    val message = "You are now at the camera and navigation screen. " +
                            "Point your camera at a QR code to scan for set your current start location."
                    tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "camera")
                    showTTSMessage(message)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Show destination selection fragment
     */
    private fun showDestinationFragment() {
        val fragmentContainer = view?.findViewById<FrameLayout>(R.id.homeFragmentContainer)
        fragmentContainer?.visibility = View.VISIBLE

        if (destinationFragment == null) {
            destinationFragment = DestinationFragment()
        }

        childFragmentManager.beginTransaction()
            .replace(R.id.homeFragmentContainer, destinationFragment!!)
            .commit()
    }

    /**
     * Show camera/navigation fragment
     */
    private fun showCameraFragment() {
        val fragmentContainer = view?.findViewById<FrameLayout>(R.id.homeFragmentContainer)
        fragmentContainer?.visibility = View.VISIBLE

        if (cameraFragment == null) {
            cameraFragment = CameraFragment()
        }

        childFragmentManager.beginTransaction()
            .replace(R.id.homeFragmentContainer, cameraFragment!!)
            .commit()
    }

    /**
     * Hide fragment container to show map view
     */
    private fun hideFragmentContainer() {
        val fragmentContainer = view?.findViewById<FrameLayout>(R.id.homeFragmentContainer)
        fragmentContainer?.visibility = View.GONE
    }

    /**
     * Notify camera fragment of visibility changes
     */
    private fun notifyCameraFragmentVisibility(isVisible: Boolean) {
        cameraFragment?.let { fragment ->
            if (fragment.isAdded) {
                if (isVisible) {
                    handler.post {
                        fragment.handleVisibilityChange(true)
                    }
                } else {
                    fragment.handleVisibilityChange(false)
                }
            }
        }
    }

    /**
     * Set up observers for ViewModel state changes
     */
    private fun setupObservers() {
        sharedViewModel.currentLocation.observe(viewLifecycleOwner) {
            updateNavigationStatus()
        }

        sharedViewModel.destination.observe(viewLifecycleOwner) {
            updateNavigationStatus()
        }

        sharedViewModel.stepCount.observe(viewLifecycleOwner) { totalSteps ->
            totalStepCountDisplay.text = "Total: $totalSteps steps"
        }

        sharedViewModel.segmentStepCount.observe(viewLifecycleOwner) { segmentSteps ->
            segmentStepCountDisplay.text = "Segment: $segmentSteps steps"
        }

        sharedViewModel.totalDistanceCovered.observe(viewLifecycleOwner) { totalDistance ->
            totalDistanceDisplay.text = "Total: ${String.format("%.1f", totalDistance)}m"
        }

        sharedViewModel.segmentDistanceCovered.observe(viewLifecycleOwner) { segmentDistance ->
            segmentDistanceDisplay.text = "Segment: ${String.format("%.1f", segmentDistance)}m"
        }

        sharedViewModel.isNavigationActive.observe(viewLifecycleOwner) {
            updateNavigationStatus()
        }

        sharedViewModel.currentPath.observe(viewLifecycleOwner) {
            updateNavigationStatus()
        }

        sharedViewModel.navigationInstructions.observe(viewLifecycleOwner) { instructions ->
            if (!instructions.isNullOrEmpty()) {
                updateNavigationStatus()
            }
        }

        sharedViewModel.remainingStepsToTarget.observe(viewLifecycleOwner) {
            updateNavigationStatus()
        }

        sharedViewModel.currentSegmentDestination.observe(viewLifecycleOwner) {
            updateNavigationStatus()
        }

        sharedViewModel.currentDirection.observe(viewLifecycleOwner) {
            updateNavigationStatus()
        }
    }

    /**
     * Update the yellow location banner
     */
    private fun updateCurrentLocationBanner(currentLocation: String?) {
        val destination = sharedViewModel.destination.value ?: "No destination selected"

        val currentLocationName = if (!currentLocation.isNullOrEmpty()) {
            sharedViewModel.getNodeById(currentLocation)?.name ?: currentLocation
        } else {
            "Not Set"
        }

        val bannerText = "From: $currentLocationName → To: $destination"
        currentLocationBanner.text = bannerText
    }

    /**
     * Update navigation status displays
     */
    private fun updateNavigationStatus() {
        val destination = sharedViewModel.destination.value ?: "No destination selected"
        val isNavigating = sharedViewModel.isNavigationActive.value ?: false
        val currentPath = sharedViewModel.currentPath.value ?: emptyList()
        val currentLocation = sharedViewModel.currentLocation.value

        updateCurrentLocationBanner(currentLocation)

        val instructionText = when {
            isNavigating && currentPath.size >= 2 -> {
                // Use the new method that extracts from full instruction
                generateConciseInstruction()
            }
            currentPath.isNotEmpty() && !currentLocation.isNullOrEmpty() -> {
                "Route ready. Start navigation to begin step guidance."
            }
            !currentLocation.isNullOrEmpty() && destination != "No destination selected" -> {
                "Calculating route to $destination..."
            }
            currentLocation.isNullOrEmpty() -> {
                "Scan QR code to set your current location"
            }
            destination == "No destination selected" -> {
                "Select destination to plan your route"
            }
            else -> {
                "Select destination and scan QR code to start navigation"
            }
        }

        navigationStatusBox.text = instructionText

        val showStats = isNavigating
        stepDistanceLayout.visibility = if (showStats) View.VISIBLE else View.GONE
    }

    /**
     * Generate concise instruction for the blue status banner
     * Modified to extract information from the full instruction provided by NavigationModule
     */
    private fun generateConciseInstruction(): String {
        // Get the full instruction from NavigationModule via ViewModel
        val fullInstruction = sharedViewModel.navigationInstructions.value ?: ""

        // If no instruction available, fallback to basic status
        if (fullInstruction.isEmpty()) {
            return "Navigation in progress"
        }

        // Extract concise information from the full instruction
        return extractConciseFromFullInstruction(fullInstruction)
    }

    /**
     * Extract concise direction and distance information from full TTS instruction
     * This ensures the blue banner shows consistent information with what was spoken
     */
    private fun extractConciseFromFullInstruction(fullInstruction: String): String {
        val remainingSteps = sharedViewModel.remainingStepsToTarget.value ?: 0
        val nextDestination = sharedViewModel.currentSegmentDestination.value

        // Extract direction from the full instruction using same keywords
        val direction = when {
            fullInstruction.contains("turn left and go", ignoreCase = true) -> "Turn left"
            fullInstruction.contains("turn right and go", ignoreCase = true) -> "Turn right"
            fullInstruction.contains("turn around and go", ignoreCase = true) -> "Turn around"
            fullInstruction.contains("Turn left and walk", ignoreCase = true) -> "Turn left"
            fullInstruction.contains("Turn right and walk", ignoreCase = true) -> "Turn right"
            fullInstruction.contains("turn left", ignoreCase = true) -> "Turn left"
            fullInstruction.contains("turn right", ignoreCase = true) -> "Turn right"
            fullInstruction.contains("turn around", ignoreCase = true) -> "Turn around"
            fullInstruction.contains("go straight ahead", ignoreCase = true) -> "Go straight"
            fullInstruction.contains("Continue straight", ignoreCase = true) -> "Continue straight"
            fullInstruction.contains("From this QR code", ignoreCase = true) -> {
                // Special case: instruction after QR scan - extract initial direction
                when {
                    fullInstruction.contains("turn left", ignoreCase = true) -> "Turn left"
                    fullInstruction.contains("turn right", ignoreCase = true) -> "Turn right"
                    fullInstruction.contains("go straight", ignoreCase = true) -> "Go straight"
                    fullInstruction.contains("turn around", ignoreCase = true) -> "Turn around"
                    else -> "Continue"
                }
            }
            else -> "Continue"
        }

        // Format the concise instruction
        return when {
            remainingSteps == 0 -> {
                if (nextDestination != null) {
                    val nodeInfo = sharedViewModel.getNodeById(
                        sharedViewModel.currentPath.value?.getOrNull(
                            sharedViewModel.currentPath.value?.indexOf(nextDestination) ?: -1
                        ) ?: ""
                    )
                    if (nodeInfo?.facing != "None") {
                        "Near $nextDestination - scan QR code"
                    } else {
                        "Approaching $nextDestination"
                    }
                } else {
                    "Approaching checkpoint"
                }
            }
            nextDestination != null -> {
                "$direction. Walk $remainingSteps steps to $nextDestination"
            }
            else -> {
                "$direction for $remainingSteps steps"
            }
        }
    }

    /**
     * Initialize TTS and provide welcome message
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                val welcomeMessage = "Welcome to the PDR Navigation System! " +
                        "You are currently viewing the map. " +
                        "Use the bottom navigation to select your destination or access camera navigation."
                tts?.speak(welcomeMessage, TextToSpeech.QUEUE_FLUSH, null, "welcome")
                showTTSMessage(welcomeMessage)
            }
        }
    }

    /**
     * Clean up resources when fragment view is destroyed
     */
    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        messageManager.cleanup()
        tts?.stop()
        tts?.shutdown()
        destinationFragment = null
        cameraFragment = null
        super.onDestroyView()
    }

    override fun onPause() {
        super.onPause()
        notifyCameraFragmentVisibility(false)
    }

    override fun onResume() {
        super.onResume()
        val fragmentContainer = view?.findViewById<FrameLayout>(R.id.homeFragmentContainer)
        if (fragmentContainer?.visibility == View.VISIBLE &&
            cameraFragment != null &&
            cameraFragment!!.isAdded) {
            handler.postDelayed({
                notifyCameraFragmentVisibility(true)
            }, 100)
        }
    }
}