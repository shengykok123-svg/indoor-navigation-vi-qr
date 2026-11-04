package com.example.finalprototype

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import android.util.Log
import kotlin.math.atan2

/**
 * Shared ViewModel managing navigation state across all fragments.
 *
 * Responsibilities:
 * - Navigation graph data management (nodes and edges)
 * - Route calculation using Dijkstra's algorithm
 * - Current location and destination tracking
 * - Step counting and distance measurement state
 * - Navigation progress tracking for UI updates
 * - Route validation and error handling
 *
 * This ViewModel serves as the single source of truth for navigation state,
 * allowing multiple fragments to observe and update navigation data consistently.
 */
class SharedViewModel : ViewModel() {

    companion object {
        private const val TAG = "SharedViewModel"
    }

    // Destination selection state
    private val _destination = MutableLiveData("No destination selected")
    val destination: LiveData<String> = _destination

    // Current user location state
    private val _currentLocation = MutableLiveData<String?>(null)
    val currentLocation: LiveData<String?> = _currentLocation

    // Current navigation route as list of node IDs
    private val _currentPath = MutableLiveData<List<String>>(emptyList())
    val currentPath: LiveData<List<String>> = _currentPath

    // Step counting state
    private val _stepCount = MutableLiveData(0)
    val stepCount: LiveData<Int> = _stepCount

    // Distance measurement state
    private val _distanceCovered = MutableLiveData(0.0)
    val distanceCovered: LiveData<Double> = _distanceCovered

    // Navigation instructions for UI display
    private val _navigationInstructions = MutableLiveData("")
    val navigationInstructions: LiveData<String> = _navigationInstructions

    // Navigation active/inactive state
    private val _isNavigationActive = MutableLiveData(false)
    val isNavigationActive: LiveData<Boolean> = _isNavigationActive

    // Current segment step tracking
    private val _segmentStepCount = MutableLiveData(0)
    val segmentStepCount: LiveData<Int> = _segmentStepCount

    // Total journey distance tracking
    private val _totalDistanceCovered = MutableLiveData(0.0)
    val totalDistanceCovered: LiveData<Double> = _totalDistanceCovered

    // Current segment distance tracking
    private val _segmentDistanceCovered = MutableLiveData(0.0)
    val segmentDistanceCovered: LiveData<Double> = _segmentDistanceCovered

    // Navigation progress tracking for current segment
    private val _remainingStepsToTarget = MutableLiveData(0)
    val remainingStepsToTarget: LiveData<Int> = _remainingStepsToTarget

    private val _targetStepsForSegment = MutableLiveData(0)
    val targetStepsForSegment: LiveData<Int> = _targetStepsForSegment

    private val _currentSegmentDestination = MutableLiveData<String?>(null)
    val currentSegmentDestination: LiveData<String?> = _currentSegmentDestination

    private val _currentDirection = MutableLiveData<String>("forward")
    val currentDirection: LiveData<String> = _currentDirection

    // Route validation status for UI feedback
    private val _routeValidationStatus = MutableLiveData<RouteValidationStatus>(RouteValidationStatus.NotValidated)
    val routeValidationStatus: LiveData<RouteValidationStatus> = _routeValidationStatus

    // Navigation graph data storage
    private val nodeMap = mutableMapOf<String, Node>()      // All navigation nodes indexed by ID
    private val edgeList = mutableListOf<Edge>()            // All connections between nodes

    /**
     * Data class representing a navigation node (waypoint/location)
     */
    data class Node(
        val id: String,                    // Unique identifier (e.g., "QR1")
        val name: String,                  // Human-readable name (e.g., "Main Entrance")
        val type: String,                  // Node type (e.g., "Turning Point", "QR Node")
        val x: Double,                     // X coordinate on floor plan
        val y: Double,                     // Y coordinate on floor plan
        val connections: List<String>,     // List of connected node IDs
        val facing: String? = null         // QR code orientation ("Up", "Down", "Left", "Right", "None")
    )

    /**
     * Data class representing a connection between two nodes
     */
    data class Edge(
        val from: String,                  // Source node ID
        val to: String,                    // Destination node ID
        val distance: Double               // Physical distance in meters
    )

    /**
     * Enhanced route result with better error handling
     */
    sealed class RouteResult {
        data class Success(val path: List<String>) : RouteResult()
        data class SameLocation(val locationName: String) : RouteResult()
        data class Error(val message: String) : RouteResult()
    }

    /**
     * Route validation status for UI feedback
     */
    sealed class RouteValidationStatus {
        object NotValidated : RouteValidationStatus()
        object Valid : RouteValidationStatus()
        object SameLocation : RouteValidationStatus()
        data class Invalid(val reason: String) : RouteValidationStatus()
    }

    /**
     * Set destination and trigger route validation
     */
    fun setDestination(name: String) {
        _destination.value = name
        validateCurrentRoute()
    }

    /**
     * Set current location and trigger route validation
     */
    fun setCurrentLocation(nodeId: String) {
        _currentLocation.value = nodeId
        validateCurrentRoute()
        Log.d(TAG, "Current location set to: $nodeId")
    }

    /**
     * Get current location node ID
     */
    fun getCurrentLocation(): String? = _currentLocation.value

    /**
     * Clear current location and reset validation status
     */
    fun clearCurrentLocation() {
        _currentLocation.value = null
        _routeValidationStatus.value = RouteValidationStatus.NotValidated
    }

    /**
     * PUBLIC METHOD: Update current path - used by NavigationModule for rerouting.
     */
    fun updateCurrentPath(newPath: List<String>) {
        _currentPath.value = newPath
        Log.d(TAG, "Current path updated with ${newPath.size} waypoints")
    }

    /**
     * Enhanced route validation that considers edge cases.
     */
    private fun validateCurrentRoute() {
        val currentLoc = _currentLocation.value
        val dest = _destination.value

        when {
            currentLoc == null || dest == "No destination selected" -> {
                _routeValidationStatus.value = RouteValidationStatus.NotValidated
            }
            else -> {
                val destNodeId = MapDataLoader.mapDestinationNameToNodeId(dest)
                when {
                    destNodeId == null -> {
                        _routeValidationStatus.value = RouteValidationStatus.Invalid("Destination not found")
                    }
                    currentLoc == destNodeId -> {
                        _routeValidationStatus.value = RouteValidationStatus.SameLocation
                    }
                    else -> {
                        val routeResult = calculateRouteWithValidation(currentLoc, destNodeId)
                        _routeValidationStatus.value = when (routeResult) {
                            is RouteResult.Success -> RouteValidationStatus.Valid
                            is RouteResult.SameLocation -> RouteValidationStatus.SameLocation
                            is RouteResult.Error -> RouteValidationStatus.Invalid(routeResult.message)
                        }
                    }
                }
            }
        }
    }

    /**
     * Enhanced ready check with detailed validation.
     */
    fun isReadyToNavigate(): Boolean {
        return when (_routeValidationStatus.value) {
            is RouteValidationStatus.Valid -> true
            is RouteValidationStatus.SameLocation -> false
            else -> false
        }
    }

    /**
     * Get detailed navigation readiness status for UI feedback
     */
    fun getNavigationReadinessStatus(): NavigationReadinessStatus {
        val currentLoc = _currentLocation.value
        val dest = _destination.value

        return when {
            currentLoc == null -> NavigationReadinessStatus.NoLocation
            dest == "No destination selected" -> NavigationReadinessStatus.NoDestination
            _routeValidationStatus.value is RouteValidationStatus.SameLocation ->
                NavigationReadinessStatus.AlreadyAtDestination(dest)
            _routeValidationStatus.value is RouteValidationStatus.Valid ->
                NavigationReadinessStatus.Ready
            _routeValidationStatus.value is RouteValidationStatus.Invalid ->
                NavigationReadinessStatus.InvalidRoute(
                    (_routeValidationStatus.value as RouteValidationStatus.Invalid).reason
                )
            else -> NavigationReadinessStatus.NotReady
        }
    }

    /**
     * Navigation readiness status enumeration for detailed UI feedback
     */
    sealed class NavigationReadinessStatus {
        object Ready : NavigationReadinessStatus()
        object NoLocation : NavigationReadinessStatus()
        object NoDestination : NavigationReadinessStatus()
        data class AlreadyAtDestination(val destinationName: String) : NavigationReadinessStatus()
        data class InvalidRoute(val reason: String) : NavigationReadinessStatus()
        object NotReady : NavigationReadinessStatus()
    }

    /**
     * Load navigation graph data from MapDataLoader and trigger validation
     */
    fun loadMapData(nodes: Map<String, Node>, edges: List<Edge>) {
        nodeMap.clear()
        nodeMap.putAll(nodes)
        edgeList.clear()
        edgeList.addAll(edges)
        Log.d(TAG, "Map data loaded: ${nodes.size} nodes, ${edges.size} edges")

        // Re-validate route after loading new data
        validateCurrentRoute()
    }

    /**
     * Enhanced route calculation with comprehensive validation.
     */
    fun calculateRouteWithValidation(startNodeId: String, endNodeId: String): RouteResult {
        Log.d(TAG, "Calculating route from $startNodeId to $endNodeId")

        // Validate nodes exist
        val startNode = nodeMap[startNodeId]
        val endNode = nodeMap[endNodeId]

        when {
            startNode == null -> {
                Log.e(TAG, "Start node not found: $startNodeId")
                return RouteResult.Error("Start location '$startNodeId' not found in navigation system")
            }
            endNode == null -> {
                Log.e(TAG, "End node not found: $endNodeId")
                return RouteResult.Error("Destination '$endNodeId' not found in navigation system")
            }
            startNodeId == endNodeId -> {
                Log.d(TAG, "Same start and end location: ${startNode.name}")
                return RouteResult.SameLocation(startNode.name)
            }
        }

        val path = findShortestPath(startNodeId, endNodeId)

        return when {
            path.isEmpty() -> {
                Log.e(TAG, "No route found between $startNodeId and $endNodeId")
                RouteResult.Error("No route found between ${startNode.name} and ${endNode.name}")
            }
            path.size == 1 -> {
                Log.d(TAG, "Single node path (same location): ${startNode.name}")
                RouteResult.SameLocation(startNode.name)
            }
            else -> {
                Log.d(TAG, "Route found with ${path.size} nodes")
                _currentPath.value = path
                RouteResult.Success(path)
            }
        }
    }

    /**
     * Legacy method for backward compatibility.
     */
    fun calculateRoute(startNodeId: String, endNodeId: String): List<String> {
        val result = calculateRouteWithValidation(startNodeId, endNodeId)
        return when (result) {
            is RouteResult.Success -> result.path
            else -> emptyList()
        }
    }

    /**
     * Update step counting and distance measurement data from NavigationModule
     */
    fun updateStepData(totalSteps: Int, segmentSteps: Int, totalDistance: Double, segmentDistance: Double) {
        _stepCount.value = totalSteps
        _segmentStepCount.value = segmentSteps
        _distanceCovered.value = totalDistance
        _totalDistanceCovered.value = totalDistance
        _segmentDistanceCovered.value = segmentDistance
    }

    /**
     * Update navigation progress information for current segment
     */
    fun updateNavigationProgress(remainingSteps: Int, targetSteps: Int, destination: String?, direction: String) {
        _remainingStepsToTarget.value = remainingSteps
        _targetStepsForSegment.value = targetSteps
        _currentSegmentDestination.value = destination
        _currentDirection.value = direction
    }

    /**
     * Update navigation instructions text for UI display
     */
    fun updateNavigationInstructions(instructions: String) {
        _navigationInstructions.value = instructions
    }

    /**
     * Set navigation active state and reset data when stopping
     */
    fun setNavigationActive(active: Boolean) {
        _isNavigationActive.value = active
        if (!active) {
            // Reset all navigation-related state when stopping navigation
            _stepCount.value = 0
            _segmentStepCount.value = 0
            _distanceCovered.value = 0.0
            _totalDistanceCovered.value = 0.0
            _segmentDistanceCovered.value = 0.0
            _navigationInstructions.value = ""
            _remainingStepsToTarget.value = 0
            _targetStepsForSegment.value = 0
            _currentSegmentDestination.value = null
            _currentDirection.value = "forward"
            _currentPath.value = emptyList()
            Log.d(TAG, "Navigation stopped - all state reset")
        }
    }

    /**
     * Get node information by ID
     */
    fun getNodeById(nodeId: String): Node? = nodeMap[nodeId]

    /**
     * Get distance between two connected nodes
     */
    fun getDistanceBetweenNodes(fromId: String, toId: String): Double {
        return edgeList.find {
            (it.from == fromId && it.to == toId) || (it.from == toId && it.to == fromId)
        }?.distance ?: 0.0
    }

    /**
     * Enhanced pathfinding using Dijkstra's algorithm
     */
    private fun findShortestPath(start: String, end: String): List<String> {
        if (nodeMap.isEmpty()) {
            Log.e(TAG, "No map data loaded for pathfinding")
            return emptyList()
        }

        // Initialize Dijkstra's algorithm data structures
        val dist = mutableMapOf<String, Double>().withDefault { Double.MAX_VALUE }
        val prev = mutableMapOf<String, String?>()
        val visited = mutableSetOf<String>()
        val queue = mutableListOf<String>()

        // Start with distance 0 to starting node
        dist[start] = 0.0
        queue.add(start)

        var iterations = 0
        val maxIterations = nodeMap.size * 2

        while (queue.isNotEmpty() && iterations < maxIterations) {
            iterations++

            // Get unvisited node with minimum distance
            val u = queue.minByOrNull { dist.getValue(it) } ?: break
            queue.remove(u)

            if (u == end) break

            visited.add(u)

            // Examine all neighbors of current node
            val neighbors = edgeList.filter { it.from == u || it.to == u }
            for (edge in neighbors) {
                val v = if (edge.from == u) edge.to else edge.from
                if (v in visited) continue

                // Calculate alternative distance through current node
                val alt = dist.getValue(u) + edge.distance
                if (alt < dist.getValue(v)) {
                    dist[v] = alt
                    prev[v] = u
                    if (!queue.contains(v)) queue.add(v)
                }
            }
        }

        if (iterations >= maxIterations) {
            Log.e(TAG, "Pathfinding exceeded maximum iterations")
            return emptyList()
        }

        // Reconstruct path from destination to start
        val path = mutableListOf<String>()
        var current: String? = end
        while (current != null) {
            path.add(0, current)
            current = prev[current]
        }

        val isValidPath = path.firstOrNull() == start && path.lastOrNull() == end
        Log.d(TAG, "Pathfinding completed: ${path.size} nodes, valid: $isValidPath")

        return if (isValidPath) path else emptyList()
    }

    /**
     * Calculate turn direction between three consecutive nodes
     * Works for all nodes including virtual waypoints
     */
    fun getTurnDirection(prevNodeId: String, currentNodeId: String, nextNodeId: String): String {
        val prevNode = getNodeById(prevNodeId)
        val currentNode = getNodeById(currentNodeId)
        val nextNode = getNodeById(nextNodeId)

        if (prevNode == null || currentNode == null || nextNode == null) {
            return "continue straight"
        }

        // Calculate turn for ALL nodes - virtual nodes can still have turns!
        val v1x = currentNode.x - prevNode.x
        val v1y = currentNode.y - prevNode.y
        val v2x = nextNode.x - currentNode.x
        val v2y = nextNode.y - currentNode.y

        // Calculate turn angle
        val crossProduct = v1x * v2y - v1y * v2x
        val dotProduct = v1x * v2x + v1y * v2y
        val angle = Math.toDegrees(atan2(crossProduct, dotProduct))

        return when {
            angle > 30 -> "turn right"
            angle < -30 -> "turn left"
            else -> "continue straight"
        }
    }

    /**
     * Calculate initial walking direction (only place for "turn around")
     */
    fun getInitialDirection(startId: String, nextId: String): String {
        val start = nodeMap[startId] ?: return "go straight ahead"
        val next = nodeMap[nextId] ?: return "go straight ahead"

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
            angleDiff in 135.0..225.0 -> "turn around"
            angleDiff in 45.0..135.0 -> "turn right"
            else -> "turn left"
        }
    }

    /**
     * Get current navigation direction
     */
    fun getCurrentNavigationDirection(): String {
        val path = _currentPath.value ?: emptyList()
        if (path.size < 2) return "continue straight"

        // This would need more context about current position in path
        return "continue straight"
    }

    /**
     * Generate navigation instructions with proper handling of virtual nodes
     */
    fun generateNavigationInstructions(path: List<String>): List<String> {
        if (path.isEmpty()) return listOf("No path found")
        if (path.size == 1) return listOf("You are already at your destination")

        val instructions = mutableListOf<String>()
        var totalDistance = 0.0

        // Initial instruction
        if (path.size >= 2) {
            val startId = path[0]
            val nextId = path[1]
            val startNode = nodeMap[startId]
            val nextNode = nodeMap[nextId]

            if (startNode != null && nextNode != null) {
                val initial = getInitialDirection(startId, nextId)
                val distance = getDistanceBetweenNodes(startId, nextId)

                val nodeType = if (nextNode.facing == "None") " (virtual waypoint)" else ""
                instructions.add("Start from ${startNode.name}, $initial to ${nextNode.name}$nodeType (${String.format("%.1f", distance)}m).")
                totalDistance += distance
            }
        }

        // Generate turn-by-turn instructions for all waypoints
        for (i in 2 until path.size) {
            val prevId = path[i - 2]
            val currId = path[i - 1]
            val nextId = path[i]

            val curr = nodeMap[currId]
            val next = nodeMap[nextId]

            if (curr == null || next == null) continue

            val turn = getTurnDirection(prevId, currId, nextId)
            val distance = getDistanceBetweenNodes(currId, nextId)
            totalDistance += distance

            // Indicate if current node is virtual
            val currNodeType = if (curr.facing == "None") {
                " (pass through)"
            } else {
                ""
            }

            val turnInstruction = when (turn) {
                "turn left" -> "Turn left at ${curr.name}$currNodeType"
                "turn right" -> "Turn right at ${curr.name}$currNodeType"
                else -> "Continue straight through ${curr.name}$currNodeType"
            }

            val nextNodeType = if (next.facing == "None") " (virtual waypoint)" else ""
            instructions.add("$turnInstruction, walk ${String.format("%.1f", distance)}m to ${next.name}$nextNodeType.")
        }

        instructions.add("Total distance: ${String.format("%.1f", totalDistance)} meters.")
        return instructions
    }

    /**
     * Calculate angle between three points for turn instructions
     */
    private fun angleBetween(p1: Node, p2: Node, p3: Node): Double {
        val v1x = p2.x - p1.x
        val v1y = p2.y - p1.y
        val v2x = p3.x - p2.x
        val v2y = p3.y - p2.y

        val dot = v1x * v2x + v1y * v2y
        val det = v1x * v2y - v1y * v2x
        return Math.toDegrees(atan2(det, dot))
    }

    /**
     * Debug method to validate map data integrity
     */
    fun validateMapData(): MapValidationResult {
        val issues = mutableListOf<String>()

        // Check for orphaned nodes
        val connectedNodes = mutableSetOf<String>()
        edgeList.forEach { edge ->
            connectedNodes.add(edge.from)
            connectedNodes.add(edge.to)
        }

        val orphanedNodes = nodeMap.keys - connectedNodes
        if (orphanedNodes.isNotEmpty()) {
            issues.add("Orphaned nodes: $orphanedNodes")
        }

        // Check for invalid edge references
        val invalidEdges = edgeList.filter { edge ->
            !nodeMap.containsKey(edge.from) || !nodeMap.containsKey(edge.to)
        }

        if (invalidEdges.isNotEmpty()) {
            issues.add("Invalid edges referencing non-existent nodes: ${invalidEdges.size}")
        }

        // Check for duplicate edges
        val edgeSignatures = edgeList.map { edge ->
            if (edge.from < edge.to) "${edge.from}-${edge.to}" else "${edge.to}-${edge.from}"
        }
        val duplicates = edgeSignatures.groupBy { it }.filter { it.value.size > 1 }

        if (duplicates.isNotEmpty()) {
            issues.add("Duplicate edges: ${duplicates.keys}")
        }

        return MapValidationResult(
            isValid = issues.isEmpty(),
            nodeCount = nodeMap.size,
            edgeCount = edgeList.size,
            issues = issues
        )
    }

    /**
     * Result of map data validation
     */
    data class MapValidationResult(
        val isValid: Boolean,
        val nodeCount: Int,
        val edgeCount: Int,
        val issues: List<String>
    )
}