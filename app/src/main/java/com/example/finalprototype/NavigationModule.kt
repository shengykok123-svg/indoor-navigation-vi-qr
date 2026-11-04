package com.example.finalprototype

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.*
import kotlin.math.*

/**
 * Enhanced navigation module implementing Pedestrian Dead Reckoning (PDR) with QR code integration.
 * Optimized for blind users with intelligent QR search detection and step filtering.
 *
 * Key enhancements:
 * - QR search mode detection to filter out non-navigation steps
 * - Confidence-based calibration to handle unreliable step counts
 * - Enhanced audio guidance for QR code location
 * - Movement pattern analysis to distinguish navigation from searching
 */
class NavigationModule(
    private val context: Context,
    private val viewModel: SharedViewModel,
    private val navigationCallback: NavigationCallback,
    private val messageCallback: ((String) -> Unit)? = null
) : SensorEventListener, TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "NavigationModule"

        // Timing constants
        private const val QR_SEARCH_TIMEOUT = 60000L
        private const val INSTRUCTION_REPEAT_INTERVAL = 30000L
        private const val QR_SEARCH_WINDOW = 20000L            // Time window for QR searching
        private const val QR_SCAN_DEBOUNCE_TIME = 5000L

        // Step detection thresholds - adjusted for better sensitivity balance
        private const val MIN_STEP_THRESHOLD = 0.8f             // Increased from 0.5f
        private const val MAX_STEP_THRESHOLD = 3.0f             // Increased from 2.5f
        private const val WINDOW_SIZE = 15
        private const val MIN_TIME_BETWEEN_STEPS = 900L         // Increased from 750L
        private const val MAX_TIME_BETWEEN_STEPS = 2500L

        // Dynamic threshold management
        private const val BASE_THRESHOLD = 1.5f
        private const val THRESHOLD_DECAY_FACTOR = 0.95f        // Gradually lower threshold
        private const val THRESHOLD_BOOST_FACTOR = 1.1f         // Boost after detection
        private const val MIN_DYNAMIC_THRESHOLD = 1.2f          // Never go below this
        private const val MAX_DYNAMIC_THRESHOLD = 2.5f          // Never go above this
        private const val EARLY_SEGMENT_DURATION = 10000L       // 10 seconds
        private const val LATE_SEGMENT_DURATION = 30000L        // 30 seconds

        // Step length parameters
        private const val MIN_STEP_LENGTH = 0.4
        private const val MAX_STEP_LENGTH = 1.2
        private const val DEFAULT_STEP_LENGTH = 0.65

        // Calibration parameters
        private const val MIN_STEPS_FOR_CALIBRATION = 8
        private const val CALIBRATION_WEIGHT_HIGH = 0.3       // High confidence weight
        private const val CALIBRATION_WEIGHT_MEDIUM = 0.15    // Medium confidence weight
        private const val CALIBRATION_WEIGHT_LOW = 0.05       // Low confidence weight
        private const val CONFIDENCE_THRESHOLD = 3

        // QR search detection parameters
        private const val IRREGULAR_MOVEMENT_THRESHOLD = 3.0f  // Higher variance indicates searching
        private const val SEARCH_STEP_CORRECTION_FACTOR = 0.3  // Assume 30% of steps are valid during search

        // Debouncing
        private var lastQRScanTime = 0L
        private var lastScannedNodeId = ""
    }

    interface NavigationCallback {
        fun onStepDetected(totalSteps: Int, segmentSteps: Int, totalDistance: Double, segmentDistance: Double)
        fun onDestinationReached()
        fun onNavigationInstruction(instruction: String)
        fun onQRScanPrompt(nodeId: String, nodeName: String)
        fun onCalibrationUpdate(progress: Int, message: String)
    }

    // Sensor management
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // TTS
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var isTTSReady = false

    // Navigation state
    private var currentPath: List<String> = emptyList()
    private var currentSegmentIndex = 0
    private var totalStepCount = 0
    private var segmentStepCount = 0
    private var totalDistanceCovered = 0.0
    private var targetStepsForSegment = 0
    private var isNavigationActive = false
    private var navigationStartTime: Long = 0L

    // Enhanced QR search tracking for blind users
    private var isSearchingForQR = false
    private var qrSearchStartTime = 0L
    private var qrPromptTime = 0L
    private var stepsBeforeQRSearch = 0
    private var navigationStepCount = 0      // Pure navigation steps (excluding search)
    private var searchStepCount = 0          // Steps during QR searching
    private var lastStepWasSearch = false

    // Movement pattern analysis
    private val recentMovementVariance = LinkedList<Float>()
    private val movementVarianceWindow = 10
    private var currentMovementVariance = 0f

    // Calibration system
    private var adaptiveStepLength = DEFAULT_STEP_LENGTH
    private var calibrationHistory = mutableListOf<Double>()
    private var calibrationConfidence = 0
    private var lastCalibrationFeedback = ""

    // Step detection
    private var adaptiveThreshold = 1.0f
    private val magnitudeBuffer = LinkedList<Float>()
    private val stepIntervals = LinkedList<Long>()
    private var lastStepTime = 0L

    // Dynamic threshold management
    private var dynamicThreshold = 1.5f
    private var stepsSinceLastDetection = 0
    private var lastValidStepMagnitude = 0f
    private var segmentStartTime = 0L

    // Activity state
    private var userActivityState = ActivityState.UNKNOWN

    // Timers
    private val qrSearchTimer = Handler(Looper.getMainLooper())
    private val instructionRepeatTimer = Handler(Looper.getMainLooper())
    private var qrSearchRunnable: Runnable? = null
    private var instructionRepeatRunnable: Runnable? = null

    // Current state
    private var currentInstruction = ""
    private var waitingForQRScan = false

    enum class ActivityState {
        STATIONARY, WALKING_SLOW, WALKING_NORMAL, WALKING_FAST, RUNNING, SEARCHING, UNKNOWN
    }

    init {
        Log.d(TAG, "Enhanced NavigationModule initialized for blind users")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            isTTSReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            Log.d(TAG, "TTS initialized: $isTTSReady")
        }
    }

    private fun speak(text: String) {
        if (isTTSReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
        messageCallback?.invoke(text)
        Log.d(TAG, "TTS: $text")
    }

    /**
     * Enhanced QR code processing with search mode detection and orientation-aware instructions
     */
    fun onQRCodeScanned(scannedNodeId: String): Boolean {
        val currentTime = System.currentTimeMillis()

        // Debounce rapid scans
        if (scannedNodeId == lastScannedNodeId &&
            currentTime - lastQRScanTime < QR_SCAN_DEBOUNCE_TIME) {
            Log.d(TAG, "QR scan debounced: $scannedNodeId")
            return true
        }

        // Exit QR search mode if active
        if (isSearchingForQR) {
            exitQRSearchMode()
        }

        lastQRScanTime = currentTime
        lastScannedNodeId = scannedNodeId

        Log.d(TAG, "QR scanned: $scannedNodeId, Search mode was: $isSearchingForQR")

        if (!isNavigationActive) {
            return handleLocationSetting(scannedNodeId)
        }

        // Check if this is expected node
        val expectedNodeId = if (currentSegmentIndex + 1 < currentPath.size) {
            currentPath[currentSegmentIndex + 1]
        } else null

        if (expectedNodeId != null && scannedNodeId == expectedNodeId) {
            qrSearchRunnable?.let { qrSearchTimer.removeCallbacks(it) }
            waitingForQRScan = false

            val scannedNode = viewModel.getNodeById(scannedNodeId)

            // Provide orientation-aware confirmation when at expected location
            speak("Location confirmed: ${scannedNode?.name}.")

            // If there's a next segment, provide immediate turn instruction
            if (currentSegmentIndex + 2 < currentPath.size) {
                val nextNodeId = currentPath[currentSegmentIndex + 2]
                val nextNode = viewModel.getNodeById(nextNodeId)
                val turnInstruction = getInitialDirection(scannedNodeId, nextNodeId)

                if (nextNode != null) {
                    speak("From this QR code, $turnInstruction to ${nextNode.name}.")
                }
            }

            performEnhancedAutoCalibration()
            proceedToNextSegment()
            return true
        }

        // Check if final destination
        if (currentPath.isNotEmpty() && scannedNodeId == currentPath.last()) {
            speak("Destination reached: ${viewModel.getNodeById(scannedNodeId)?.name}. Congratulations!")
            onDestinationReached()
            return true
        }

        return handleUnexpectedQRScan(scannedNodeId)
    }

    /**
     * Enter QR search mode - used when prompting for QR scan
     */
    private fun enterQRSearchMode() {
        if (!isSearchingForQR) {
            isSearchingForQR = true
            qrSearchStartTime = System.currentTimeMillis()
            stepsBeforeQRSearch = segmentStepCount
            searchStepCount = 0

            Log.d(TAG, "Entered QR search mode. Steps before search: $stepsBeforeQRSearch")
        }
    }

    /**
     * Exit QR search mode and process accumulated steps
     */
    private fun exitQRSearchMode() {
        if (isSearchingForQR) {
            val searchDuration = System.currentTimeMillis() - qrSearchStartTime

            // Apply correction for search steps
            if (searchStepCount > 0) {
                val correctedSearchSteps = (searchStepCount * SEARCH_STEP_CORRECTION_FACTOR).toInt()
                segmentStepCount = stepsBeforeQRSearch + correctedSearchSteps

                Log.d(TAG, "Exited QR search mode. Search steps: $searchStepCount, " +
                        "Corrected: $correctedSearchSteps, Duration: ${searchDuration}ms")
            }

            isSearchingForQR = false
            searchStepCount = 0
            navigationStepCount = segmentStepCount
        }
    }

    /**
     * Detect if user is likely searching for QR code based on movement patterns
     */
    private fun isLikelySearchingForQR(): Boolean {
        // Multiple criteria for detecting QR search behavior
        val timeBasedSearch = waitingForQRScan &&
                (System.currentTimeMillis() - qrPromptTime < QR_SEARCH_WINDOW)

        val patternBasedSearch = currentMovementVariance > IRREGULAR_MOVEMENT_THRESHOLD

        val activityBasedSearch = userActivityState == ActivityState.SEARCHING

        return timeBasedSearch && (patternBasedSearch || activityBasedSearch)
    }

    /**
     * Analyze movement patterns to detect irregular movement (searching behavior)
     */
    private fun analyzeMovementPattern(magnitude: Float) {
        // Calculate variance in recent magnitudes
        recentMovementVariance.offer(magnitude)
        if (recentMovementVariance.size > movementVarianceWindow) {
            recentMovementVariance.poll()
        }

        if (recentMovementVariance.size >= 5) {
            val mean = recentMovementVariance.average()
            val variance = recentMovementVariance.map { (it - mean).pow(2) }.average()
            currentMovementVariance = variance.toFloat()

            // Detect searching pattern
            if (currentMovementVariance > IRREGULAR_MOVEMENT_THRESHOLD && waitingForQRScan) {
                if (userActivityState != ActivityState.SEARCHING) {
                    userActivityState = ActivityState.SEARCHING
                    Log.d(TAG, "Detected searching pattern. Variance: $currentMovementVariance")
                }
            }
        }
    }

    private fun handleLocationSetting(scannedNodeId: String): Boolean {
        val scannedNode = viewModel.getNodeById(scannedNodeId)
        val currentDestination = viewModel.destination.value

        if (scannedNode == null) {
            speak("Invalid QR code. Please scan a valid navigation point.")
            return false
        }

        if (scannedNode.facing == "None") {
            speak("This appears to be a virtual waypoint. Please scan a physical QR code location.")
            return false
        }

        val destinationNodeId = MapDataLoader.mapDestinationNameToNodeId(currentDestination ?: "")

        if (scannedNodeId == destinationNodeId && currentDestination != "No destination selected") {
            speak("You are already at your destination: ${scannedNode.name}. No navigation needed.")
            viewModel.setCurrentLocation(scannedNodeId)
            return true
        }

        viewModel.setCurrentLocation(scannedNodeId)

        // Provide orientation-aware message when setting initial location
        var message = "Current location set to: ${scannedNode.name}."

        // If destination is already selected, provide immediate direction from QR
        if (destinationNodeId != null && currentDestination != "No destination selected") {
            val routeResult = viewModel.calculateRouteWithValidation(scannedNodeId, destinationNodeId)
            if (routeResult is SharedViewModel.RouteResult.Success && routeResult.path.size >= 2) {
                val nextNodeId = routeResult.path[1]
                val nextNode = viewModel.getNodeById(nextNodeId)
                val direction = getInitialDirection(scannedNodeId, nextNodeId)

                if (nextNode != null) {
                    message += " To reach ${currentDestination}, $direction to ${nextNode.name}."
                }
            }
        } else {
            message += " Please select a destination to start navigation."
        }

        speak(message)
        return true
    }

    private fun handleUnexpectedQRScan(scannedNodeId: String): Boolean {
        val scannedNode = viewModel.getNodeById(scannedNodeId)
        val originalDestination = currentPath.lastOrNull()

        if (scannedNode == null) {
            speak("Invalid QR code. Please scan a valid navigation point.")
            return false
        }

        if (scannedNode.facing == "None") {
            speak("This appears to be a virtual waypoint. Please scan a physical QR code location.")
            return false
        }

        if (currentPath.isNotEmpty() && scannedNodeId == currentPath.last()) {
            speak("Destination reached: ${scannedNode.name}. Congratulations!")
            onDestinationReached()
            return true
        }

        if (originalDestination != null) {
            speak("Location updated to ${scannedNode.name}. Recalculating route...")

            val previousSegmentSteps = navigationStepCount  // Use filtered navigation steps

            val newRouteResult = viewModel.calculateRouteWithValidation(scannedNodeId, originalDestination)

            when (newRouteResult) {
                is SharedViewModel.RouteResult.Success -> {
                    if (previousSegmentSteps > 0 && currentSegmentIndex > 0) {
                        performCalibrationBeforeReroute()
                    }

                    currentPath = newRouteResult.path
                    currentSegmentIndex = 0
                    segmentStepCount = 0
                    navigationStepCount = 0
                    searchStepCount = 0
                    waitingForQRScan = false

                    magnitudeBuffer.clear()
                    stepIntervals.clear()
                    recentMovementVariance.clear()

                    updateViewModelWithNewRoute(newRouteResult.path, scannedNodeId)
                    speak("Route recalculated from ${scannedNode.name}.")
                    proceedToNextSegmentFromNewLocation()

                    return true
                }
                is SharedViewModel.RouteResult.SameLocation -> {
                    speak("You have reached your destination: ${scannedNode.name}.")
                    onDestinationReached()
                    return true
                }
                is SharedViewModel.RouteResult.Error -> {
                    speak("Cannot recalculate route. ${newRouteResult.message}")
                    return false
                }
            }
        }

        speak("Navigation error. Please restart navigation.")
        return false
    }

    fun startNavigation(path: List<String>) {
        when {
            path.isEmpty() -> {
                speak("Unable to start navigation. No route available.")
                return
            }
            path.size == 1 -> {
                val node = viewModel.getNodeById(path[0])
                speak("You are already at your destination: ${node?.name}.")
                return
            }
            path.size < 2 -> {
                speak("Unable to start navigation. Invalid route.")
                return
            }
        }

        // Initialize navigation state
        currentPath = path
        currentSegmentIndex = 0
        totalStepCount = 0
        segmentStepCount = 0
        navigationStepCount = 0
        searchStepCount = 0
        totalDistanceCovered = 0.0
        isNavigationActive = true
        waitingForQRScan = false
        isSearchingForQR = false
        navigationStartTime = System.currentTimeMillis()

        viewModel.setNavigationActive(true)

        val confidenceMessage = when {
            calibrationConfidence >= CONFIDENCE_THRESHOLD -> "High accuracy step detection ready."
            calibrationConfidence > 0 -> "Step detection ready. Accuracy will improve as you scan QR codes."
            else -> "Step detection ready. Using standard step length."
        }

        speak("Navigation starting. $confidenceMessage")
        startSensors()
        provideInitialGuidance()

        Log.d(TAG, "Navigation started with ${path.size} waypoints")
    }

    private fun startSensors() {
        magnitudeBuffer.clear()
        stepIntervals.clear()
        recentMovementVariance.clear()
        lastStepTime = 0L

        accelerometer?.let {
            val success = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "Accelerometer registration: $success")
            if (!success) {
                handleSensorFailure()
            }
        }
    }

    private fun provideInitialGuidance() {
        if (currentPath.isEmpty()) return

        val startNodeId = currentPath[0]
        val nextNodeId = currentPath[1]
        val startNode = viewModel.getNodeById(startNodeId)
        val nextNode = viewModel.getNodeById(nextNodeId)

        if (startNode == null || nextNode == null) return

        val initialDirection = getInitialDirection(startNodeId, nextNodeId)
        val distance = viewModel.getDistanceBetweenNodes(startNodeId, nextNodeId)
        val estimatedSteps = (distance / adaptiveStepLength).roundToInt()

        targetStepsForSegment = estimatedSteps

        // Initialize segment timing for dynamic thresholds
        segmentStartTime = System.currentTimeMillis()
        dynamicThreshold = BASE_THRESHOLD
        stepsSinceLastDetection = 0
        lastValidStepMagnitude = 0f

        val scanInstruction = if (nextNode.facing == "None") {
            if (currentPath.size > 2) {
                val afterNextNodeId = currentPath[2]
                val turnAtVirtual = getTurnDirection(startNodeId, nextNodeId, afterNextNodeId)
                when (turnAtVirtual) {
                    "turn left" -> "You will turn left at virtual waypoint ${nextNode.name}."
                    "turn right" -> "You will turn right at virtual waypoint ${nextNode.name}."
                    else -> "Continue through virtual waypoint ${nextNode.name}."
                }
            } else {
                "Continue through virtual waypoint ${nextNode.name}."
            }
        } else {
            "When you arrive, scan the QR code at ${nextNode.name}."
        }

        currentInstruction = "From ${startNode.name}, $initialDirection to ${nextNode.name}. " +
                "Walk approximately $estimatedSteps steps, ${String.format("%.1f", distance)} meters. " +
                scanInstruction

        speak(currentInstruction)
        navigationCallback.onNavigationInstruction(currentInstruction)
        viewModel.updateNavigationInstructions(currentInstruction)

        updateNavigationProgress()
        startInstructionRepeat()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                processAccelerometerForSteps(event)
            }
        }
    }

    private fun processAccelerometerForSteps(event: SensorEvent) {
        val currentTime = System.currentTimeMillis()

        val magnitude = sqrt(
            event.values[0].pow(2) + event.values[1].pow(2) + event.values[2].pow(2)
        )

        val adjustedMagnitude = abs(magnitude - SensorManager.GRAVITY_EARTH)

        // Analyze movement patterns for search detection
        analyzeMovementPattern(adjustedMagnitude)
        updateActivityState(adjustedMagnitude, currentTime)

        if (isNavigationActive) {
            detectStepEnhanced(adjustedMagnitude, currentTime)
        }
    }

    private fun updateActivityState(magnitude: Float, currentTime: Long) {
        val previousState = userActivityState

        // Don't override searching state from pattern analysis
        if (userActivityState != ActivityState.SEARCHING) {
            userActivityState = when {
                magnitude < 0.1f -> ActivityState.STATIONARY
                magnitude < 1.0f -> ActivityState.WALKING_SLOW
                magnitude < 2.5f -> ActivityState.WALKING_NORMAL
                magnitude < 4.0f -> ActivityState.WALKING_FAST
                else -> ActivityState.RUNNING
            }
        }

        if (userActivityState != previousState && currentTime % 500 < 50) {
            Log.d(TAG, "Activity: $previousState -> $userActivityState")
        }
    }

    /**
     * Enhanced step detection with dynamic thresholds and segment-aware sensitivity
     */
    private fun detectStepEnhanced(magnitude: Float, currentTime: Long) {
        val deltaTime = currentTime - lastStepTime

        if (deltaTime < MIN_TIME_BETWEEN_STEPS) {
            return
        }

        // Dynamic threshold adjustment based on segment progress
        val segmentDuration = currentTime - segmentStartTime
        val isNewSegment = segmentDuration < 5000L  // First 5 seconds of segment

        // Determine segment phase for adaptive detection
        val segmentPhase = when {
            segmentDuration < EARLY_SEGMENT_DURATION -> "early"
            segmentDuration < LATE_SEGMENT_DURATION -> "middle"
            else -> "late"
        }

        // Adjust threshold based on context and segment phase
        val contextualThreshold = when {
            isNewSegment -> BASE_THRESHOLD * 0.9f  // More sensitive at start
            stepsSinceLastDetection > 10 -> dynamicThreshold * THRESHOLD_DECAY_FACTOR  // Lower if no detection
            segmentPhase == "late" && segmentStepCount < targetStepsForSegment / 2 -> {
                // If we're late in segment but haven't detected enough steps, increase sensitivity
                dynamicThreshold * 0.85f
            }
            else -> dynamicThreshold
        }.coerceIn(MIN_DYNAMIC_THRESHOLD, MAX_DYNAMIC_THRESHOLD)

        // Activity-based adjustment with reduced differences
        val activityMultiplier = when (userActivityState) {
            ActivityState.WALKING_SLOW -> 0.95f
            ActivityState.WALKING_NORMAL -> 1.0f
            ActivityState.WALKING_FAST -> 1.05f
            ActivityState.SEARCHING -> 0.9f
            else -> 1.0f
        }

        // Segment-aware threshold
        val segmentAwareThreshold = when (segmentStepCount) {
            in 0..5 -> 1.3f     // More sensitive at segment start
            in 6..10 -> 1.5f
            in 11..20 -> 1.6f
            else -> 1.4f        // Return to moderate sensitivity
        }

        // Use the lower of contextual and segment-aware thresholds
        val currentThreshold = minOf(
            contextualThreshold * activityMultiplier,
            segmentAwareThreshold
        )

        stepsSinceLastDetection++

        magnitudeBuffer.offer(magnitude)
        if (magnitudeBuffer.size > WINDOW_SIZE) {
            magnitudeBuffer.poll()
        }

        if (magnitudeBuffer.size >= 8) {
            val current = magnitudeBuffer.last()
            val previous = magnitudeBuffer.dropLast(1)
            val avgPrevious = previous.average().toFloat()
            val maxPrevious = previous.maxOrNull() ?: 0f

            // Adaptive peak detection based on recent history and segment progress
            val adaptiveLocalMaximum = when {
                segmentStepCount < 5 -> maxPrevious + 0.3f   // Less strict initially
                segmentStepCount < 15 -> maxPrevious + 0.4f
                lastValidStepMagnitude > 0 -> maxPrevious + (0.3f * (lastValidStepMagnitude / current))
                else -> maxPrevious + 0.35f  // Balanced for sustained walking
            }

            val adaptiveAboveAverage = when {
                segmentStepCount < 5 -> avgPrevious + 0.4f
                else -> avgPrevious + 0.5f
            }

            val isSignificantPeak = current > currentThreshold
            val isLocalMaximum = current > adaptiveLocalMaximum
            val isAboveAverage = current > adaptiveAboveAverage

            if (isSignificantPeak && isLocalMaximum && isAboveAverage) {
                lastStepTime = currentTime
                lastValidStepMagnitude = current
                stepsSinceLastDetection = 0

                // Update dynamic threshold based on detected step
                dynamicThreshold = (dynamicThreshold * 0.9f + current * 0.1f)
                    .coerceIn(MIN_DYNAMIC_THRESHOLD, MAX_DYNAMIC_THRESHOLD)

                handleStepDetectedEnhanced(currentTime)

                Log.v(TAG, "Step detected: mag=$current, threshold=$currentThreshold, " +
                        "segment_steps=$segmentStepCount, phase=$segmentPhase")
            }
        }

        // Auto-adjust sensitivity if detection rate is too low or too high
        if (segmentDuration > 5000 && segmentDuration % 5000 < 100) {
            autoAdjustSensitivity(currentTime)
        }
    }

    /**
     * Auto-adjust detection sensitivity based on detection rate
     */
    private fun autoAdjustSensitivity(currentTime: Long) {
        val duration = (currentTime - segmentStartTime) / 1000.0  // in seconds
        if (duration <= 0) return

        val detectionRate = segmentStepCount / duration

        when {
            detectionRate < 0.5 -> {  // Less than 0.5 steps per second
                // Too insensitive - lower thresholds
                dynamicThreshold *= 0.95f
                Log.d(TAG, "Lowering threshold - low detection rate: $detectionRate steps/s")
            }
            detectionRate > 2.5 -> {  // More than 2.5 steps per second
                // Too sensitive - raise thresholds
                dynamicThreshold *= 1.05f
                Log.d(TAG, "Raising threshold - high detection rate: $detectionRate steps/s")
            }
        }

        dynamicThreshold = dynamicThreshold.coerceIn(MIN_DYNAMIC_THRESHOLD, MAX_DYNAMIC_THRESHOLD)
    }

    /**
     * Enhanced step handling with search mode filtering
     */
    private fun handleStepDetectedEnhanced(currentTime: Long) {
        if (!isNavigationActive) return

        totalStepCount++

        // Determine if this is a navigation step or search step
        val isSearchStep = isLikelySearchingForQR()

        if (isSearchStep) {
            searchStepCount++
            lastStepWasSearch = true
            Log.v(TAG, "Search step detected. Total search steps: $searchStepCount")
        } else {
            navigationStepCount++
            segmentStepCount = navigationStepCount  // Only count navigation steps
            totalDistanceCovered += adaptiveStepLength
            lastStepWasSearch = false
        }

        Log.d(TAG, "Step: Total=$totalStepCount, Nav=$navigationStepCount, " +
                "Search=$searchStepCount, Segment=$segmentStepCount/$targetStepsForSegment")

        val segmentDistance = segmentStepCount * adaptiveStepLength
        navigationCallback.onStepDetected(totalStepCount, segmentStepCount, totalDistanceCovered, segmentDistance)
        viewModel.updateStepData(totalStepCount, segmentStepCount, totalDistanceCovered, segmentDistance)

        updateNavigationProgress()

        if (segmentStepCount >= targetStepsForSegment && !waitingForQRScan) {
            stopInstructionRepeat()
            handleSegmentTargetReached()
        }
    }

    private fun handleSegmentTargetReached() {
        if (currentSegmentIndex >= currentPath.size - 1) {
            onDestinationReached()
            return
        }

        val nextNodeId = currentPath[currentSegmentIndex + 1]
        val nextNode = viewModel.getNodeById(nextNodeId)

        if (nextNode != null) {
            if (nextNode.facing == "None") {
                // Virtual node - auto proceed
                var announcement = "Approaching virtual waypoint: ${nextNode.name}. "

                if (currentSegmentIndex > 0 && currentSegmentIndex + 2 < currentPath.size) {
                    val prevNodeId = currentPath[currentSegmentIndex]
                    val afterNextNodeId = currentPath[currentSegmentIndex + 2]
                    val turnDirection = getTurnDirection(prevNodeId, nextNodeId, afterNextNodeId)

                    announcement += when (turnDirection) {
                        "turn left" -> "Turn left here."
                        "turn right" -> "Turn right here."
                        else -> "Continue straight."
                    }
                }

                speak(announcement)
                proceedToNextSegmentAutomatically()
            } else {
                // Physical node - request QR scan
                waitingForQRScan = true
                promptForEnhancedQRScan()
            }
        }
    }

    /**
     * Enhanced QR scan prompt with guidance for blind users
     */
    private fun promptForEnhancedQRScan() {
        if (currentSegmentIndex >= currentPath.size - 1) {
            onDestinationReached()
            return
        }

        val currentNodeId = currentPath[currentSegmentIndex + 1]
        val currentNode = viewModel.getNodeById(currentNodeId)

        if (currentNode != null) {
            // Prompt with detailed guidance
            val prompt = buildString {
                append("You should be near ${currentNode.name}. ")
                append("You will hear a confirmation sound when the QR code is scanned successfully.")
            }

            speak(prompt)
            navigationCallback.onQRScanPrompt(currentNodeId, currentNode.name)

            // Enter search mode
            qrPromptTime = System.currentTimeMillis()
            enterQRSearchMode()

            startQRSearchTimeout()
        }
    }

    private fun proceedToNextSegmentAutomatically() {
        waitingForQRScan = false
        performEnhancedAutoCalibration()
        proceedToNextSegment()
    }

    private fun updateNavigationProgress() {
        val remainingSteps = maxOf(0, targetStepsForSegment - segmentStepCount)
        val nextDestination = getCurrentSegmentDestination()
        val direction = getCurrentDirection()
        viewModel.updateNavigationProgress(remainingSteps, targetStepsForSegment, nextDestination, direction)
    }

    private fun getCurrentSegmentDestination(): String? {
        return if (currentSegmentIndex + 1 < currentPath.size) {
            val nextNodeId = currentPath[currentSegmentIndex + 1]
            viewModel.getNodeById(nextNodeId)?.name
        } else {
            null
        }
    }

    private fun getCurrentDirection(): String {
        if (currentSegmentIndex + 1 >= currentPath.size) return "continue straight"

        val currentNodeId = currentPath[currentSegmentIndex]
        val nextNodeId = currentPath[currentSegmentIndex + 1]

        if (currentSegmentIndex == 0) {
            return getInitialDirection(currentNodeId, nextNodeId)
        }

        return getDirectionBetweenNodes(currentNodeId, nextNodeId)
    }

    private fun getTurnDirection(prevNodeId: String, currentNodeId: String, nextNodeId: String): String {
        val prevNode = viewModel.getNodeById(prevNodeId)
        val currentNode = viewModel.getNodeById(currentNodeId)
        val nextNode = viewModel.getNodeById(nextNodeId)

        if (prevNode == null || currentNode == null || nextNode == null) {
            return "continue straight"
        }

        val v1x = currentNode.x - prevNode.x
        val v1y = currentNode.y - prevNode.y
        val v2x = nextNode.x - currentNode.x
        val v2y = nextNode.y - currentNode.y

        val crossProduct = v1x * v2y - v1y * v2x
        val dotProduct = v1x * v2x + v1y * v2y
        val angle = Math.toDegrees(atan2(crossProduct, dotProduct))

        return when {
            angle > 30 -> "turn right"
            angle < -30 -> "turn left"
            else -> "continue straight"
        }
    }

    private fun getDirectionBetweenNodes(fromId: String, toId: String): String {
        if (currentPath.size < 2) {
            return "continue straight"
        }

        val fromIndex = currentPath.indexOf(fromId)
        val toIndex = currentPath.indexOf(toId)

        if (fromIndex > 0 && toIndex == fromIndex + 1) {
            val prevNodeId = currentPath[fromIndex - 1]
            return getTurnDirection(prevNodeId, fromId, toId)
        }

        return "continue straight"
    }

    private fun getInitialDirection(startId: String, nextId: String): String {
        val start = viewModel.getNodeById(startId) ?: return "go straight ahead"
        val next = viewModel.getNodeById(nextId) ?: return "go straight ahead"

        val facingAngles = mapOf(
            "Up" to 270.0,
            "Right" to 0.0,
            "Down" to 90.0,
            "Left" to 180.0
        )

        val reversedFacing = mapOf(
            "Up" to "Down",
            "Down" to "Up",
            "Left" to "Right",
            "Right" to "Left"
        )

        val qrFacing = start.facing ?: "Up"
        val userFacing = reversedFacing[qrFacing] ?: "Up"
        val userAngle = facingAngles[userFacing] ?: 0.0

        val dx = next.x - start.x
        val dy = next.y - start.y
        val pathAngle = (Math.toDegrees(atan2(dy, dx)) + 360) % 360

        val angleDiff = (pathAngle - userAngle + 360) % 360

        return when {
            angleDiff < 45 || angleDiff > 315 -> "go straight ahead"
            angleDiff in 135.0..225.0 -> "turn around and go forward"
            angleDiff in 45.0..135.0 -> "turn right and go forward"
            else -> "turn left and go forward"
        }
    }

    /**
     * Enhanced auto-calibration with confidence scoring
     */
    private fun performEnhancedAutoCalibration() {
        // Exit search mode if still active
        if (isSearchingForQR) {
            exitQRSearchMode()
        }

        // Use filtered navigation steps for calibration
        if (navigationStepCount > 0 && targetStepsForSegment > 0) {
            val currentSegment = currentSegmentIndex
            if (currentSegment < currentPath.size - 1) {
                val actualDistance = viewModel.getDistanceBetweenNodes(
                    currentPath[currentSegment],
                    currentPath[currentSegment + 1]
                )
                performAutoCalibrationWithConfidence(actualDistance, navigationStepCount)
            }
        }
    }

    /**
     * Calibration with confidence-based weighting
     */
    private fun performAutoCalibrationWithConfidence(actualDistance: Double, measuredSteps: Int) {
        if (measuredSteps < MIN_STEPS_FOR_CALIBRATION) {
            Log.d(TAG, "Insufficient steps ($measuredSteps) for calibration")
            return
        }

        val measuredStepLength = actualDistance / measuredSteps

        // Calculate confidence based on step length reasonableness
        val confidence = calculateCalibrationConfidence(measuredStepLength)

        if (measuredStepLength < MIN_STEP_LENGTH || measuredStepLength > MAX_STEP_LENGTH) {
            Log.d(TAG, "Measured step length ($measuredStepLength) outside valid range")
            return
        }

        calibrationHistory.add(measuredStepLength)
        if (calibrationHistory.size > 10) {
            calibrationHistory.removeAt(0)
        }

        val weightedStepLength = if (calibrationHistory.size >= 3) {
            calibrationHistory.sorted()[calibrationHistory.size / 2]
        } else {
            measuredStepLength
        }

        // Apply confidence-weighted calibration
        val calibrationWeight = when {
            confidence > 0.8 -> CALIBRATION_WEIGHT_HIGH
            confidence > 0.5 -> CALIBRATION_WEIGHT_MEDIUM
            else -> CALIBRATION_WEIGHT_LOW
        }

        val previousLength = adaptiveStepLength
        adaptiveStepLength = (adaptiveStepLength * (1 - calibrationWeight) +
                weightedStepLength * calibrationWeight)
            .coerceIn(MIN_STEP_LENGTH, MAX_STEP_LENGTH)

        calibrationConfidence++

        val improvement = abs(adaptiveStepLength - previousLength)
        lastCalibrationFeedback = when {
            improvement > 0.1 -> "Step detection significantly improved"
            improvement > 0.05 -> "Step detection accuracy improved"
            else -> "Step detection confirmed accurate"
        }

        Log.d(TAG, "Calibration: $previousLength -> $adaptiveStepLength " +
                "(confidence: $confidence, weight: $calibrationWeight)")

        if (calibrationConfidence == 1 || calibrationConfidence % 3 == 0) {
            speak(lastCalibrationFeedback)
        }

        navigationCallback.onCalibrationUpdate(calibrationConfidence * 10, lastCalibrationFeedback)
    }

    /**
     * Calculate confidence score for calibration based on step length
     */
    private fun calculateCalibrationConfidence(stepLength: Double): Double {
        return when {
            stepLength < 0.4 -> 0.3  // Too small - likely includes search steps
            stepLength > 1.0 -> 0.5  // Too large - possible error
            stepLength in 0.55..0.75 -> 1.0  // Optimal range
            else -> 0.8  // Acceptable range
        }
    }

    /**
    *  Preserves step calibration data when route changes occur.
    */
    private fun performCalibrationBeforeReroute() {
        if (navigationStepCount > MIN_STEPS_FOR_CALIBRATION && currentSegmentIndex > 0) {
            val previousSegment = currentSegmentIndex - 1
            if (previousSegment >= 0 && previousSegment < currentPath.size - 1) {
                val actualDistance = viewModel.getDistanceBetweenNodes(
                    currentPath[previousSegment],
                    currentPath[previousSegment + 1]
                )
                performAutoCalibrationWithConfidence(actualDistance, navigationStepCount)
                Log.d(TAG, "Calibration preserved before rerouting")
            }
        }
    }

    private fun startQRSearchTimeout() {
        qrSearchRunnable?.let { qrSearchTimer.removeCallbacks(it) }

        qrSearchRunnable = Runnable {
            if (waitingForQRScan) {
                exitQRSearchMode()  // Exit search mode on timeout
                provideNextInstructionWithPDR()
            }
        }

        qrSearchTimer.postDelayed(qrSearchRunnable!!, QR_SEARCH_TIMEOUT)
    }

    private fun provideNextInstructionWithPDR() {
        waitingForQRScan = false
        isSearchingForQR = false
        currentSegmentIndex++
        segmentStepCount = 0
        navigationStepCount = 0
        searchStepCount = 0

        magnitudeBuffer.clear()

        if (currentSegmentIndex >= currentPath.size - 1) {
            onDestinationReached()
            return
        }

        val currentNodeId = currentPath[currentSegmentIndex]
        val nextNodeId = currentPath[currentSegmentIndex + 1]
        val currentNode = viewModel.getNodeById(currentNodeId)
        val nextNode = viewModel.getNodeById(nextNodeId)

        if (currentNode != null && nextNode != null) {
            val distance = viewModel.getDistanceBetweenNodes(currentNodeId, nextNodeId)
            val estimatedSteps = (distance / adaptiveStepLength).roundToInt()

            val direction = if (currentSegmentIndex > 0) {
                val prevNodeId = currentPath[currentSegmentIndex - 1]
                getTurnDirection(prevNodeId, currentNodeId, nextNodeId)
            } else {
                "continue straight"
            }

            targetStepsForSegment = estimatedSteps

            val directionInstruction = when (direction) {
                "turn left" -> "Turn left and walk"
                "turn right" -> "Turn right and walk"
                else -> "Continue straight and walk"
            }

            val nextInstruction = if (nextNode.facing == "None") {
                if (currentSegmentIndex + 2 < currentPath.size) {
                    val afterNextNodeId = currentPath[currentSegmentIndex + 2]
                    val turnAtNext = getTurnDirection(currentNodeId, nextNodeId, afterNextNodeId)
                    when (turnAtNext) {
                        "turn left" -> "You will turn left at ${nextNode.name}."
                        "turn right" -> "You will turn right at ${nextNode.name}."
                        else -> "Continue through ${nextNode.name}."
                    }
                } else {
                    "Continue to ${nextNode.name}."
                }
            } else {
                "Scan QR code at ${nextNode.name}."
            }

            currentInstruction = "QR code not found. Continuing with step guidance. " +
                    "From ${currentNode.name}, $directionInstruction to ${nextNode.name}. " +
                    "Approximately $estimatedSteps steps, ${String.format("%.1f", distance)} meters. " +
                    nextInstruction

            speak(currentInstruction)
            navigationCallback.onNavigationInstruction(currentInstruction)
            viewModel.updateNavigationInstructions(currentInstruction)

            updateNavigationProgress()
            startInstructionRepeat()
        }
    }

    private fun proceedToNextSegment() {
        currentSegmentIndex++
        segmentStepCount = 0
        navigationStepCount = 0
        searchStepCount = 0

        // Reset detection parameters for new segment
        segmentStartTime = System.currentTimeMillis()
        dynamicThreshold = BASE_THRESHOLD
        stepsSinceLastDetection = 0
        lastValidStepMagnitude = 0f

        // Keep recent magnitude history for continuity (don't clear completely)
        while (magnitudeBuffer.size > 5) {
            magnitudeBuffer.poll()
        }

        if (currentSegmentIndex >= currentPath.size - 1) {
            onDestinationReached()
            return
        }

        val currentNodeId = currentPath[currentSegmentIndex]
        val nextNodeId = currentPath[currentSegmentIndex + 1]
        val currentNode = viewModel.getNodeById(currentNodeId)
        val nextNode = viewModel.getNodeById(nextNodeId)

        if (currentNode != null && nextNode != null) {
            val distance = viewModel.getDistanceBetweenNodes(currentNodeId, nextNodeId)
            val estimatedSteps = (distance / adaptiveStepLength).roundToInt()

            // For segments after QR scan, use getInitialDirection since user is facing the QR
            val direction = if (currentNode.facing != null && currentNode.facing != "None") {
                // User just scanned this QR, so they're facing it
                getInitialDirection(currentNodeId, nextNodeId)
            } else if (currentSegmentIndex > 0) {
                // Virtual node or mid-journey turn
                val prevNodeId = currentPath[currentSegmentIndex - 1]
                getTurnDirection(prevNodeId, currentNodeId, nextNodeId)
            } else {
                "continue straight"
            }

            targetStepsForSegment = estimatedSteps

            val scanInstruction = if (nextNode.facing == "None") {
                if (currentSegmentIndex + 2 < currentPath.size) {
                    val afterNextNodeId = currentPath[currentSegmentIndex + 2]
                    val turnAtNext = getTurnDirection(currentNodeId, nextNodeId, afterNextNodeId)
                    when (turnAtNext) {
                        "turn left" -> "You will turn left at ${nextNode.name}."
                        "turn right" -> "You will turn right at ${nextNode.name}."
                        else -> "Continue through ${nextNode.name}."
                    }
                } else {
                    "Continue through ${nextNode.name}."
                }
            } else {
                "When you arrive, scan QR code at ${nextNode.name}."
            }

            // Customize instruction based on whether we just scanned a QR
            val currentInstruction = if (currentNode.facing != null && currentNode.facing != "None") {
                // Just scanned QR - use orientation-aware instruction
                "Now at ${currentNode.name}. From this QR code, $direction to ${nextNode.name}. " +
                        "Approximately $estimatedSteps steps, ${String.format("%.1f", distance)} meters. " +
                        scanInstruction
            } else {
                // Virtual node or continuing journey
                val directionInstruction = when (direction) {
                    "turn left" -> "Turn left and walk"
                    "turn right" -> "Turn right and walk"
                    else -> "Continue straight and walk"
                }

                "Now at ${currentNode.name}. $directionInstruction to ${nextNode.name}. " +
                        "Approximately $estimatedSteps steps, ${String.format("%.1f", distance)} meters. " +
                        scanInstruction
            }

            this.currentInstruction = currentInstruction
            speak(currentInstruction)
            navigationCallback.onNavigationInstruction(currentInstruction)
            viewModel.updateNavigationInstructions(currentInstruction)

            updateNavigationProgress()
            startInstructionRepeat()

            Log.d(TAG, "New segment started: threshold reset to $dynamicThreshold")
        }
    }

    private fun proceedToNextSegmentFromNewLocation() {
        if (currentPath.isEmpty() || currentPath.size < 2) {
            speak("Invalid route. Please restart navigation.")
            return
        }

        val currentNodeId = currentPath[0]
        val nextNodeId = currentPath[1]
        val currentNode = viewModel.getNodeById(currentNodeId)
        val nextNode = viewModel.getNodeById(nextNodeId)

        if (currentNode != null && nextNode != null) {
            val distance = viewModel.getDistanceBetweenNodes(currentNodeId, nextNodeId)
            val estimatedSteps = (distance / adaptiveStepLength).roundToInt()

            // When starting from a QR scan, always use getInitialDirection
            val direction = getInitialDirection(currentNodeId, nextNodeId)

            targetStepsForSegment = estimatedSteps

            val scanInstruction = if (nextNode.facing == "None") {
                if (currentPath.size > 2) {
                    //Get the Current node, Next node(virtual) and Next Next node for Turn Calculation
                    val afterNextNodeId = currentPath[2]
                    val turnAtVirtual = getTurnDirection(currentNodeId, nextNodeId, afterNextNodeId)
                    when (turnAtVirtual) {
                        "turn left" -> "You will turn left at virtual waypoint ${nextNode.name}."
                        "turn right" -> "You will turn right at virtual waypoint ${nextNode.name}."
                        else -> "Continue through virtual waypoint ${nextNode.name}."
                    }
                } else {
                    "Continue through virtual waypoint ${nextNode.name}."
                }
            } else {
                "Scan QR code at ${nextNode.name}."
            }

            // Use orientation-aware instruction since we just scanned a QR
            currentInstruction = "Starting from ${currentNode.name}. From this QR code, $direction to ${nextNode.name}. " +
                    "Walk approximately $estimatedSteps steps, ${String.format("%.1f", distance)} meters. " +
                    scanInstruction

            speak(currentInstruction)
            navigationCallback.onNavigationInstruction(currentInstruction)
            viewModel.updateNavigationInstructions(currentInstruction)

            updateNavigationProgress()
            startInstructionRepeat()
        }
    }

    private fun updateViewModelWithNewRoute(newPath: List<String>, currentLocationId: String) {
        viewModel.setCurrentLocation(currentLocationId)
        viewModel.updateCurrentPath(newPath)

        navigationCallback.onStepDetected(totalStepCount, 0, totalDistanceCovered, 0.0)
        viewModel.updateStepData(totalStepCount, 0, totalDistanceCovered, 0.0)

        updateNavigationProgress()
    }

    private fun onDestinationReached() {
        isNavigationActive = false
        sensorManager.unregisterListener(this)
        viewModel.setNavigationActive(false)

        val finalNode = viewModel.getNodeById(currentPath.last())

        val journeyTime = System.currentTimeMillis() - navigationStartTime
        val accuracyMessage = when {
            calibrationConfidence >= CONFIDENCE_THRESHOLD -> "with high accuracy"
            calibrationConfidence > 0 -> "with improved accuracy"
            else -> "successfully"
        }

        val message = buildString {
            append("Navigation completed $accuracyMessage. ")
            append("You have reached your destination: ${finalNode?.name}. ")
            append("Total steps: $totalStepCount (")
            append("Navigation: $navigationStepCount, ")
            append("Search: $searchStepCount). ")
            append("Distance: ${String.format("%.1f", totalDistanceCovered)} meters.")

            if (journeyTime > 0) {
                val minutes = (journeyTime / 60000).toInt()
                val seconds = ((journeyTime % 60000) / 1000).toInt()
                if (minutes > 0) {
                    append(" Journey time: $minutes minutes and $seconds seconds.")
                } else if (seconds > 0) {
                    append(" Journey time: $seconds seconds.")
                }
            }
        }

        speak(message)
        navigationCallback.onDestinationReached()
        stopAllTimers()
    }

    private fun startInstructionRepeat() {
        instructionRepeatRunnable?.let { instructionRepeatTimer.removeCallbacks(it) }

        instructionRepeatRunnable = Runnable {
            if (isNavigationActive && !waitingForQRScan) {
                val contextualInstruction = generateContextualInstruction()
                speak(contextualInstruction)
                instructionRepeatTimer.postDelayed(instructionRepeatRunnable!!, INSTRUCTION_REPEAT_INTERVAL)
            }
        }

        instructionRepeatTimer.postDelayed(instructionRepeatRunnable!!, INSTRUCTION_REPEAT_INTERVAL)
    }

    private fun generateContextualInstruction(): String {
        val remainingSteps = maxOf(0, targetStepsForSegment - segmentStepCount)
        val nextDestination = getCurrentSegmentDestination()
        val progress = if (targetStepsForSegment > 0) {
            (segmentStepCount.toFloat() / targetStepsForSegment * 100).toInt()
        } else 0

        return when {
            remainingSteps == 0 -> {
                val nextNode = if (currentSegmentIndex + 1 < currentPath.size) {
                    viewModel.getNodeById(currentPath[currentSegmentIndex + 1])
                } else null

                if (nextNode?.facing == "None") {
                    "You should be near virtual waypoint $nextDestination. Continue forward."
                } else {
                    "You should be near $nextDestination. Look for the QR code to scan."
                }
            }
            remainingSteps <= 5 -> "Almost there! $remainingSteps steps to $nextDestination."
            progress < 25 -> "$remainingSteps steps remaining to $nextDestination."
            progress < 75 -> "Halfway there! $remainingSteps steps remaining to $nextDestination."
            else -> "Making good progress. $remainingSteps steps left to $nextDestination."
        }
    }

    private fun stopInstructionRepeat() {
        instructionRepeatRunnable?.let { instructionRepeatTimer.removeCallbacks(it) }
    }

    private fun stopAllTimers() {
        qrSearchRunnable?.let { qrSearchTimer.removeCallbacks(it) }
        instructionRepeatRunnable?.let { instructionRepeatTimer.removeCallbacks(it) }
    }

    private fun handleSensorFailure() {
        speak("Step detection temporarily unavailable. Continue following directions and scan QR codes when available.")
        startTimeBasedEstimation()
    }

    private fun startTimeBasedEstimation() {
        val averageWalkingSpeed = 1.4
        val estimatedTimePerSegment = if (targetStepsForSegment > 0) {
            (targetStepsForSegment * adaptiveStepLength / averageWalkingSpeed * 1000).toLong()
        } else 30000L

        val timeBasedTimer = Handler(Looper.getMainLooper())
        timeBasedTimer.postDelayed({
            if (isNavigationActive && !waitingForQRScan) {
                speak("Based on walking time, you should be near your checkpoint. Look for the QR code.")
                handleSegmentTargetReached()
            }
        }, estimatedTimePerSegment)
    }

    fun stopNavigation() {
        isNavigationActive = false
        waitingForQRScan = false
        isSearchingForQR = false
        sensorManager.unregisterListener(this)
        stopAllTimers()
        magnitudeBuffer.clear()
        stepIntervals.clear()
        recentMovementVariance.clear()

        totalStepCount = 0
        segmentStepCount = 0
        navigationStepCount = 0
        searchStepCount = 0
        totalDistanceCovered = 0.0
        currentSegmentIndex = 0
        currentPath = emptyList()

        viewModel.setNavigationActive(false)
        Log.d(TAG, "Navigation stopped and all counters reset")
    }

    fun pauseNavigation() {
        sensorManager.unregisterListener(this)
        stopAllTimers()
        Log.d(TAG, "Navigation paused")
    }

    fun resumeNavigation() {
        if (isNavigationActive) {
            accelerometer?.let {
                val success = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                if (!success) {
                    handleSensorFailure()
                }
            }
            if (currentInstruction.isNotEmpty()) {
                startInstructionRepeat()
            }
        }
        Log.d(TAG, "Navigation resumed")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor?.name} = $accuracy")
    }

    fun cleanup() {
        stopNavigation()
        tts.shutdown()
        Log.d(TAG, "NavigationModule cleanup complete")
    }
}