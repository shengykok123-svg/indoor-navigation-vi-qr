package com.example.finalprototype

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Fragment handling QR code scanning and navigation controls.
 *
 * Features:
 * - Camera preview with QR code detection using ML Kit
 * - Navigation start/stop controls with double-tap confirmation
 * - Integration with shared NavigationModule for consistent state
 * - Proper camera lifecycle management to prevent resource leaks
 * - TTS feedback for accessibility with visual message display
 *
 * The fragment coordinates with the parent HomeFragment for visibility management
 * and uses the shared NavigationModule instance from MainActivity.
 */
class CameraFragment : Fragment(), TextToSpeech.OnInitListener {

    // ViewModel for shared navigation state
    private val vm: SharedViewModel by activityViewModels()

    // TTS components for accessibility feedback
    private lateinit var tts: TextToSpeech
    private var ttsReady = true

    // UI components
    private lateinit var previewView: PreviewView
    private lateinit var startNavigationButton: Button
    private lateinit var stopNavigationButton: Button

    // Navigation components - using shared instance from MainActivity
    private var isScanning = false                      // Whether camera is actively scanning
    private var navigationModule: NavigationModule? = null
    private var isNavigationStarted = false

    // Fragment visibility tracking for proper camera management
    private var isFragmentVisible = false
    private var cameraProvider: ProcessCameraProvider? = null

    // Double-tap confirmation system for button safety
    private var startButtonFirstTap = false
    private var stopButtonFirstTap = false
    private val confirmationTimeout = 6000L             // Timeout for double-tap confirmation
    private val handler = Handler(Looper.getMainLooper())
    private var startConfirmationRunnable: Runnable? = null
    private var stopConfirmationRunnable: Runnable? = null

    // Camera permission launcher using modern Activity Result API
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                if (isFragmentVisible) {
                    startCamera()
                }
            } else {
                speak("Camera permission is required")
            }
        }

    /**
     * Create fragment view and initialize UI components
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    /**
     * Initialize views and set up observers after view creation
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        getSharedNavigationModule()
        setupObservers()
        setupDoubleClickListeners()

        tts = TextToSpeech(requireContext(), this)

        // Don't start camera automatically - wait for visibility
    }

    /**
     * Fragment becomes visible - start camera if permissions available
     */
    override fun onResume() {
        super.onResume()
        isFragmentVisible = true
        handleVisibilityChange(true)
        updateNavigationButtonState()
    }

    /**
     * Fragment becomes hidden - stop camera to save resources
     */
    override fun onPause() {
        super.onPause()
        isFragmentVisible = false
        handleVisibilityChange(false)

        // Clean up fragment-specific handlers
        startConfirmationRunnable?.let { handler.removeCallbacks(it) }
        stopConfirmationRunnable?.let { handler.removeCallbacks(it) }
        startButtonFirstTap = false
        stopButtonFirstTap = false
    }

    /**
     * Public method to handle visibility changes from parent fragment.
     * Called by HomeFragment when switching between tabs.
     */
    fun handleVisibilityChange(isVisible: Boolean) {
        if (!isAdded || view == null) return

        isFragmentVisible = isVisible

        if (isVisible) {
            // Fragment became visible - start camera if not already scanning
            if (!isScanning) {
                checkCameraPermissionAndStart()
            }
        } else {
            // Fragment became hidden - stop camera but preserve state
            stopCameraPreview()
        }
    }

    /**
     * Public method to restart camera if fragment is visible.
     * Used by parent fragment to ensure camera starts properly.
     */
    fun restartCameraIfVisible() {
        if (isAdded && view != null && isFragmentVisible) {
            if (!isScanning) {
                checkCameraPermissionAndStart()
            }
        }
    }

    /**
     * Initialize UI component references
     */
    private fun initializeViews(view: View) {
        previewView = view.findViewById(R.id.previewView)
        startNavigationButton = view.findViewById(R.id.startNavigationButton)
        stopNavigationButton = view.findViewById(R.id.stopNavigationButton)

        // Initially disable buttons until state is determined
        startNavigationButton.isEnabled = false
        stopNavigationButton.isEnabled = false
    }

    /**
     * Get the shared NavigationModule from MainActivity instead of creating new one.
     * This ensures consistent navigation state across all fragments.
     */
    private fun getSharedNavigationModule() {
        navigationModule = (requireActivity() as? MainActivity)?.getNavigationModule()

        if (navigationModule == null) {
            speak("Navigation system not available. Please restart the app.")
        }
    }

    /**
     * Set up observers for ViewModel state changes to update UI accordingly
     */
    private fun setupObservers() {
        // Update button state when destination changes
        vm.destination.observe(viewLifecycleOwner) { destination ->
            updateNavigationButtonState()
        }

        // Update button state when current location changes
        vm.currentLocation.observe(viewLifecycleOwner) { location ->
            updateNavigationButtonState()
        }

        // Update stop button and navigation state when navigation status changes
        vm.isNavigationActive.observe(viewLifecycleOwner) { isActive ->
            stopNavigationButton.isEnabled = isActive
            isNavigationStarted = isActive
            updateNavigationButtonState()
        }
    }

    /**
     * Update navigation button text and enabled state based on current app state
     */
    private fun updateNavigationButtonState() {
        val currentLocation = vm.getCurrentLocation()
        val destination = vm.destination.value
        val isReady = vm.isReadyToNavigate() && !isNavigationStarted

        startNavigationButton.isEnabled = isReady

        // Determine appropriate button text based on current state
        val buttonText = when {
            isNavigationStarted -> "Navigation Active"
            !currentLocation.isNullOrEmpty() && destination != "No destination selected" -> {
                // Check if already at destination
                val destinationNodeId = MapDataLoader.mapDestinationNameToNodeId(destination ?: "")
                if (currentLocation == destinationNodeId) {
                    "Already at Destination"
                } else {
                    "Start Navigation"
                }
            }
            currentLocation.isNullOrEmpty() -> "Scan QR Code First"
            else -> "Select Destination First"
        }
        startNavigationButton.text = buttonText
    }

    /**
     * Set up double-click listeners for navigation buttons to prevent accidental activation
     */
    private fun setupDoubleClickListeners() {
        startNavigationButton.setOnClickListener {
            handleStartButtonClick()
        }

        stopNavigationButton.setOnClickListener {
            handleStopButtonClick()
        }
    }

    /**
     * Handle start navigation button click with double-tap confirmation
     */
    private fun handleStartButtonClick() {
        if (!startNavigationButton.isEnabled) {
            provideStartButtonFeedback()
            return
        }

        if (!startButtonFirstTap) {
            // First tap - request confirmation
            startButtonFirstTap = true
            speak("You are touching Start Navigation. Tap one more time to confirm the operation.")

            // Set timeout for confirmation
            startConfirmationRunnable = Runnable {
                startButtonFirstTap = false
                speak("Start navigation confirmation timeout. Tap again if you want to start navigation.")
            }
            handler.postDelayed(startConfirmationRunnable!!, confirmationTimeout)

        } else {
            // Second tap - execute action
            startButtonFirstTap = false
            startConfirmationRunnable?.let { handler.removeCallbacks(it) }

            if (vm.isReadyToNavigate() && !isNavigationStarted) {
                startNavigationEnhanced()
            }
        }
    }

    /**
     * Provide audio feedback when start button is pressed but not enabled
     */
    private fun provideStartButtonFeedback() {
        val currentLocation = vm.getCurrentLocation()
        val destination = vm.destination.value

        when {
            currentLocation.isNullOrEmpty() -> {
                speak("Please scan a QR code first to set your current location")
            }
            destination == "No destination selected" || destination.isNullOrEmpty() -> {
                speak("Please select a destination first using the destination tab")
            }
            isNavigationStarted -> {
                speak("Navigation is already active")
            }
            else -> {
                val destinationNodeId = MapDataLoader.mapDestinationNameToNodeId(destination ?: "")
                if (currentLocation == destinationNodeId) {
                    speak("You are already at your destination: $destination. No navigation needed.")
                } else {
                    speak("Unable to start navigation. Please check your location and destination settings")
                }
            }
        }
    }

    /**
     * Handle stop navigation button click with double-tap confirmation
     */
    private fun handleStopButtonClick() {
        if (!stopNavigationButton.isEnabled) {
            speak("No navigation to stop. Navigation is not currently active.")
            return
        }

        if (!stopButtonFirstTap) {
            // First tap - request confirmation
            stopButtonFirstTap = true
            speak("You are touching Stop Navigation. Tap one more time to confirm the operation.")

            // Set timeout for confirmation
            stopConfirmationRunnable = Runnable {
                stopButtonFirstTap = false
                speak("Stop navigation confirmation timeout. Tap again if you want to stop navigation.")
            }
            handler.postDelayed(stopConfirmationRunnable!!, confirmationTimeout)

        } else {
            // Second tap - execute action
            stopButtonFirstTap = false
            stopConfirmationRunnable?.let { handler.removeCallbacks(it) }
            stopPDRNavigation()
        }
    }

    /**
     * Enhanced navigation start using shared NavigationModule with comprehensive validation
     */
    private fun startNavigationEnhanced() {
        val currentLocation = vm.getCurrentLocation()
        val destination = vm.destination.value

        // Validate current location
        if (currentLocation == null) {
            speak("Cannot start navigation. Please scan a QR code first to set your current location")
            return
        }

        // Validate destination
        if (destination == null || destination == "No destination selected") {
            speak("Cannot start navigation. Please select a destination first")
            return
        }

        // Map destination name to node ID
        val endNodeId = MapDataLoader.mapDestinationNameToNodeId(destination)
        if (endNodeId == null) {
            speak("Destination not found in navigation system")
            return
        }

        // Use enhanced route validation from ViewModel
        val routeResult = vm.calculateRouteWithValidation(currentLocation, endNodeId)

        when (routeResult) {
            is SharedViewModel.RouteResult.Success -> {
                // Successfully calculated route - start navigation
                navigationModule?.startNavigation(routeResult.path)
                isNavigationStarted = true

                val startNode = vm.getNodeById(currentLocation)
                val endNode = vm.getNodeById(endNodeId)
                speak("Navigation confirmed. Starting navigation from ${startNode?.name} to ${endNode?.name}")
            }
            is SharedViewModel.RouteResult.SameLocation -> {
                // User is already at destination
                speak("You are already at your destination: ${routeResult.locationName}. No navigation needed.")
                updateNavigationButtonState()
            }
            is SharedViewModel.RouteResult.Error -> {
                // Route calculation failed
                speak("Navigation error: ${routeResult.message}")
            }
        }
    }

    /**
     * Stop PDR navigation and reset both location and destination
     */
    private fun stopPDRNavigation() {
        navigationModule?.stopNavigation()
        isNavigationStarted = false

        // Reset both current location and destination
        vm.clearCurrentLocation()  // This clears the start point
        vm.setDestination("No destination selected")  // This resets the destination

        speak("Navigation stopped. Location and destination have been reset to none.")
        updateNavigationButtonState()
    }

    /**
     * Check camera permission and start camera if granted
     */
    private fun checkCameraPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ->
                startCamera()
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ->
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            else ->
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Stop camera preview and release resources
     */
    private fun stopCameraPreview() {
        try {
            cameraProvider?.unbindAll()
            isScanning = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Start camera with QR code detection using CameraX and ML Kit
     */
    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        if (!isFragmentVisible) return // Don't start camera if fragment not visible

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // Set up camera preview
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Set up image analysis for QR code detection
                val analyzer = ImageAnalysis.Builder().build().also {
                    it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        if (ttsReady && isFragmentVisible) { // Check fragment visibility
                            processImageProxy(imageProxy) { raw -> handleQrResult(raw) }
                        } else {
                            imageProxy.close()
                        }
                    }
                }

                // Bind camera lifecycle
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analyzer
                )

                isScanning = true
                previewView.visibility = View.VISIBLE

            } catch (e: Exception) {
                e.printStackTrace()
                stopCamera()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /**
     * Stop camera completely
     */
    private fun stopCamera() {
        stopCameraPreview()
        previewView.visibility = View.GONE
    }

    /**
     * Process camera image for QR code detection using ML Kit
     */
    @androidx.camera.core.ExperimentalGetImage
    private fun processImageProxy(imageProxy: ImageProxy, onResult: (String) -> Unit) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val scanner = BarcodeScanning.getClient()
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        barcodes[0].rawValue?.let(onResult)
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    /**
     * Enhanced QR code handling using shared NavigationModule.
     * Validates QR code format and delegates processing to NavigationModule.
     */
    private fun handleQrResult(rawValue: String) {
        activity?.runOnUiThread {
            try {
                val json = JSONObject(rawValue)

                // Validate required QR code fields
                if (!json.has("location") || !json.has("ID")) {
                    speak("Invalid QR code format. Missing required fields.")
                    return@runOnUiThread
                }

                val location = json.getString("location").trim()
                val qrNodeId = json.getString("ID").trim()

                // Validate that the ID follows expected format (starts with "QR")
                if (!qrNodeId.startsWith("QR")) {
                    speak("Invalid QR code. ID must start with 'QR'.")
                    return@runOnUiThread
                }

                // Use shared NavigationModule for QR processing
                val success = navigationModule?.onQRCodeScanned(qrNodeId) ?: run {
                    handleQrResultFallback(qrNodeId, location)
                }

                updateNavigationButtonState()

            } catch (e: Exception) {
                speak("Invalid QR code format. Please scan a valid navigation QR code. Error: ${e.message}")
            }
        }
    }

    /**
     * Fallback QR handling when NavigationModule is not available.
     * Provides basic location setting functionality.
     */
    private fun handleQrResultFallback(qrNodeId: String, location: String): Boolean {
        val node = vm.getNodeById(qrNodeId)
        if (node == null) {
            speak("Unknown QR code location. Please scan a valid navigation point.")
            return false
        }

        if (isNavigationStarted) {
            speak("QR code scanned: $location. Navigation system will process this.")
        } else {
            val currentDestination = vm.destination.value
            val destinationNodeId = MapDataLoader.mapDestinationNameToNodeId(currentDestination ?: "")

            if (qrNodeId == destinationNodeId && currentDestination != "No destination selected") {
                speak("You are already at your destination: $location. No navigation needed.")
            }
            else {
                vm.setCurrentLocation(qrNodeId)
                speak("QR Code Scanned. Current location set to: $location. You can now start navigation.")
            }
        }
        return true
    }

    /**
     * TTS initialization callback - set up speech parameters
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.ENGLISH
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { ttsReady = false }
                override fun onDone(id: String?)  { ttsReady = true }
                override fun onError(id: String?) { ttsReady = true }
            })

            val welcomeMessage = "Camera and navigation controls ready. Scan a QR code to set your location, then use the navigation buttons."
            speak(welcomeMessage)
        }
    }

    /**
     * Enhanced speak method that also displays visual message through parent fragment
     */
    private fun speak(text: String) {
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }

        // Send message to parent HomeFragment for visual display
        (parentFragment as? HomeFragment)?.showTTSMessage(text)
    }

    /**
     * Clean up resources when fragment is destroyed
     */
    override fun onDestroy() {
        // Clean up camera resources
        cameraProvider?.unbindAll()
        cameraProvider = null

        tts.stop()
        tts.shutdown()

        // Clean up fragment-specific handlers
        startConfirmationRunnable?.let { handler.removeCallbacks(it) }
        stopConfirmationRunnable?.let { handler.removeCallbacks(it) }

        super.onDestroy()
    }
}