package com.example.finalprototype

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider

/**
 * Main activity serving as the single entry point and coordinator for the navigation app.
 *
 * Responsibilities:
 * - Manages the shared NavigationModule instance across all fragments
 * - Initializes and provides the SharedViewModel for state management
 * - Loads map data once at app startup for performance
 * - Handles app-level navigation lifecycle (pause/resume)
 * - Provides dependency access methods for fragments
 *
 * This activity follows a single-activity architecture pattern where fragments
 * handle different screens while sharing common state through the ViewModel
 * and NavigationModule.
 */
class MainActivity : AppCompatActivity() {

    // Shared NavigationModule instance that persists across fragment switches
    private var navigationModule: NavigationModule? = null
    private lateinit var sharedViewModel: SharedViewModel
    private var messageCallback: ((String) -> Unit)? = null

    /**
     * Initialize activity, load map data, and set up navigation system
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize SharedViewModel for state management across fragments
        sharedViewModel = ViewModelProvider(this)[SharedViewModel::class.java]

        // Load map data once at app startup for better performance
        loadMapData()

        // Create NavigationModule once at activity level for consistent state
        initializeNavigationModule()

        // Set up initial fragment if this is a fresh app start
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment())
                .commit()
        }
    }

    /**
     * Load navigation graph data from JSON files and populate ViewModel
     */
    private fun loadMapData() {
        val (nodes, edges) = MapDataLoader.loadMapData(this)
        sharedViewModel.loadMapData(nodes, edges)
    }

    /**
     * Initialize the shared NavigationModule with callback handlers.
     * This module is shared across all fragments for consistent navigation state.
     */
    private fun initializeNavigationModule() {
        navigationModule = NavigationModule(
            context = this,
            viewModel = sharedViewModel,
            navigationCallback = object : NavigationModule.NavigationCallback {
                /**
                 * Handle step detection events - update ViewModel which propagates to all fragments
                 */
                override fun onStepDetected(totalSteps: Int, segmentSteps: Int, totalDistance: Double, segmentDistance: Double) {
                    // Update ViewModel - will propagate to all fragments via LiveData
                    sharedViewModel.updateStepData(totalSteps, segmentSteps, totalDistance, segmentDistance)
                }

                /**
                 * Handle destination reached event
                 */
                override fun onDestinationReached() {
                    runOnUiThread {
                        // Destination reached - this will be reflected in UI through ViewModel observers
                        // The individual fragments will handle their specific UI updates
                    }
                }

                /**
                 * Handle navigation instruction updates
                 */
                override fun onNavigationInstruction(instruction: String) {
                    // Update instruction in ViewModel - will be observed by HomeFragment
                    sharedViewModel.updateNavigationInstructions(instruction)
                }

                /**
                 * Handle QR scan prompts (handled by TTS in NavigationModule)
                 */
                override fun onQRScanPrompt(nodeId: String, nodeName: String) {
                    // QR scan prompts are handled by TTS in NavigationModule
                    // No additional UI updates needed at activity level
                }

                /**
                 * Handle calibration updates (handled by TTS in NavigationModule)
                 */
                override fun onCalibrationUpdate(progress: Int, message: String) {
                    // Calibration updates are handled by TTS in NavigationModule
                    // Could add UI notification here if needed in future
                }
            },
            messageCallback = messageCallback
        )
    }

    /**
     * Provide access to the shared NavigationModule for fragments with optional message callback.
     * This ensures all fragments use the same NavigationModule instance
     * for consistent navigation state.
     *
     * @param onMessage Optional callback for displaying TTS messages visually
     * @return The shared NavigationModule instance, or null if not initialized
     */
    fun getNavigationModule(onMessage: ((String) -> Unit)? = null): NavigationModule? {
        if (onMessage != null && onMessage != messageCallback) {
            messageCallback = onMessage
            // Recreate NavigationModule if callback changed
            initializeNavigationModule()
        }
        return navigationModule
    }

    /**
     * Provide access to the shared ViewModel for fragments that need it.
     *
     * @return The SharedViewModel instance
     */
    fun getSharedViewModel(): SharedViewModel = sharedViewModel

    /**
     * App goes to background - pause navigation to save battery
     */
    override fun onPause() {
        super.onPause()
        // Pause navigation when app goes to background to conserve battery
        navigationModule?.pauseNavigation()
    }

    /**
     * App comes to foreground - resume navigation if it was active
     */
    override fun onResume() {
        super.onResume()
        // Resume navigation when app comes to foreground
        navigationModule?.resumeNavigation()
    }

    /**
     * App is being destroyed - clean up NavigationModule resources
     */
    override fun onDestroy() {
        // Clean up NavigationModule when app is destroyed to prevent memory leaks
        navigationModule?.cleanup()
        navigationModule = null
        super.onDestroy()
    }
}