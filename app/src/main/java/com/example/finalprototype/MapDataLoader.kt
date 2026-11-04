package com.example.finalprototype

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

/**
 * Utility object for loading navigation graph data from JSON files.
 *
 * Features:
 * - Loads node and edge data from assets/indoor_map_graph.json and edges_with_distance.json
 * - Maps UI destination names to internal node IDs and vice versa
 * - Provides fallback sample data if JSON files are missing
 * - Handles JSON parsing errors gracefully
 *
 * The loader separates the UI-friendly destination names from the internal
 * node ID system, allowing for easy maintenance and localization.
 */
object MapDataLoader {

    /**
     * Main entry point to load complete navigation graph data.
     *
     * @param context Android context for accessing assets
     * @return Pair of (nodeMap, edgeList) for navigation graph
     */
    fun loadMapData(context: Context): Pair<Map<String, SharedViewModel.Node>, List<SharedViewModel.Edge>> {
        return try {
            val nodeMap = loadNodes(context)
            val edgeList = loadEdges(context)
            Pair(nodeMap, edgeList)
        } catch (e: Exception) {
            createSampleData() // Fallback to sample data if files don't exist
        }
    }

    /**
     * Load node data from indoor_map_graph.json with error handling
     */
    private fun loadNodes(context: Context): Map<String, SharedViewModel.Node> {
        return try {
            val inputStream = context.assets.open("indoor_map_graph.json")
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<Map<String, NodeData>>() {}.type
            val nodeData: Map<String, NodeData> = Gson().fromJson(reader, type)

            // Convert JSON data to SharedViewModel.Node format
            nodeData.mapValues { (id, data) ->
                SharedViewModel.Node(
                    id = id,
                    name = data.name,
                    type = data.type,
                    x = data.x,
                    y = data.y,
                    connections = data.connections,
                    facing = data.facing
                )
            }
        } catch (e: Exception) {
            createSampleNodes()
        }
    }

    /**
     * Load edge data from edges_with_distance.json with error handling
     */
    private fun loadEdges(context: Context): List<SharedViewModel.Edge> {
        return try {
            val inputStream = context.assets.open("edges_with_distance.json")
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<List<EdgeData>>() {}.type
            val edgeData: List<EdgeData> = Gson().fromJson(reader, type)

            // Convert JSON data to SharedViewModel.Edge format
            edgeData.map { data ->
                SharedViewModel.Edge(
                    from = data.from,
                    to = data.to,
                    distance = data.distance
                )
            }
        } catch (e: Exception) {
            createSampleEdges()
        }
    }

    /**
     * Map user-friendly destination names to internal QR node IDs.
     * This mapping allows the UI to show readable names while using
     * consistent internal IDs for navigation calculations.
     *
     * @param destinationName User-friendly destination name from UI
     * @return Internal node ID for navigation system, or null if not found
     */
    fun mapDestinationNameToNodeId(destinationName: String): String? {
        return when (destinationName) {
            "TP001" -> "QR1"
            "TP002" -> "QR2"
            "TP003" -> "QR3"
            "TP004" -> "QR4"
            "TP005" -> "QR5"
            "TP006" -> "QR6"
            "TP007" -> "QR7"
            "TP008" -> "QR8"
            "TP009" -> "QR9"
            "TP010" -> "QR10"
            "TP011" -> "QR11"
            "Main Entrance" -> "QR12"
            "TP013" -> "QR13"
            "TP014" -> "QR14"
            "TP015" -> "QR15"
            "TP016" -> "QR16"
            "NG-026" -> "QR17"
            "NG-033" -> "QR18"
            "NG-005" -> "QR19"
            "N007A" -> "QR20"
            "N007B" -> "QR21"
            "NGT4&5_Toilet" -> "QR22"
            "N012A" -> "QR23"
            "N012B" -> "QR24"
            "N001A" -> "QR25"
            "N001B" -> "QR26"
            "N009A" -> "QR27"
            "N009B" -> "QR28"
            "TP017" -> "QR29"
            else -> null
        }
    }

    /**
     * Map internal QR node IDs back to user-friendly destination names.
     * Used for displaying readable names in UI and TTS feedback.
     *
     * @param nodeId Internal node ID from navigation system
     * @return User-friendly destination name, or null if not found
     */
    fun mapNodeIdToDestinationName(nodeId: String): String? {
        return when (nodeId) {
            "QR1" -> "TP001"
            "QR2" -> "TP002"
            "QR3" -> "TP003"
            "QR4" -> "TP004"
            "QR5" -> "TP005"
            "QR6" -> "TP006"
            "QR7" -> "TP007"
            "QR8" -> "TP008"
            "QR9" -> "TP009"
            "QR10" -> "TP010"
            "QR11" -> "TP011"
            "QR12" -> "Main Entrance"
            "QR13" -> "TP013"
            "QR14" -> "TP014"
            "QR15" -> "TP015"
            "QR16" -> "TP016"
            "QR17" -> "NG-026"
            "QR18" -> "NG-033"
            "QR19" -> "NG-005"
            "QR20" -> "N007A"
            "QR21" -> "N007B"
            "QR22" -> "NGT4&5_Toilet"
            "QR23" -> "N012A"
            "QR24" -> "N012B"
            "QR25" -> "N001A"
            "QR26" -> "N001B"
            "QR27" -> "N009A"
            "QR28" -> "N009B"
            "QR29" -> "TP017"
            else -> null
        }
    }

    /**
     * Create fallback sample data if JSON files are not available
     */
    private fun createSampleData(): Pair<Map<String, SharedViewModel.Node>, List<SharedViewModel.Edge>> {
        return Pair(createSampleNodes(), createSampleEdges())
    }

    /**
     * Create minimal sample nodes for fallback operation
     */
    private fun createSampleNodes(): Map<String, SharedViewModel.Node> {
        return mapOf(
            "QR1" to SharedViewModel.Node(
                id = "QR1",
                name = "TP001",
                type = "Turning Point",
                x = 30.547,
                y = 39.052,
                connections = listOf("QR2", "QR4"),
                facing = "Right"
            ),
            "QR2" to SharedViewModel.Node(
                id = "QR2",
                name = "TP002",
                type = "Turning Point",
                x = 36.656,
                y = 108.542,
                connections = listOf("QR1", "QR3", "QR5", "QR17"),
                facing = "Right"
            ),
            "QR12" to SharedViewModel.Node(
                id = "QR12",
                name = "Main Entrance",
                type = "Turning Point",
                x = 726.906,
                y = 35.948,
                connections = listOf("QR13"),
                facing = "Right"
            )
        )
    }

    /**
     * Create minimal sample edges for fallback operation
     */
    private fun createSampleEdges(): List<SharedViewModel.Edge> {
        return listOf(
            SharedViewModel.Edge("QR1", "QR2", 69.758),
            SharedViewModel.Edge("QR1", "QR4", 35.748),
            SharedViewModel.Edge("QR12", "QR13", 32.271)
        )
    }

    /**
     * Data class for JSON parsing - node structure from indoor_map_graph.json
     */
    private data class NodeData(
        val name: String,
        val type: String,
        val x: Double,
        val y: Double,
        val connections: List<String>,
        val facing: String? = null
    )

    /**
     * Data class for JSON parsing - edge structure from edges_with_distance.json
     */
    private data class EdgeData(
        val from: String,
        val to: String,
        val distance: Double
    )
}