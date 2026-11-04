package com.example.finalprototype

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.caverock.androidsvg.SVG
import kotlin.math.max
import kotlin.math.min

/**
 * Custom view for displaying SVG floor plans with route overlay and zoom/pan controls.
 *
 * Features:
 * - SVG floor plan rendering using AndroidSVG library
 * - Route path overlay with customizable appearance
 * - Multi-touch zoom and pan gestures
 * - Proper coordinate scaling and translation
 * - Memory-efficient rendering with proper canvas management
 *
 * The view handles the complex coordinate transformations needed to display
 * navigation routes on top of architectural floor plans while maintaining
 * proper aspect ratios and user interaction capabilities.
 */
class RouteMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // SVG and route data
    private var svg: SVG? = null                           // Floor plan SVG
    private var path: List<Pair<Float, Float>> = emptyList()  // Route coordinates

    // Drawing paint for the route overlay
    private val pathPaint = Paint().apply {
        color = Color.RED                                  // Red route line
        strokeWidth = 3f                                   // 3dp line width
        style = Paint.Style.STROKE                         // Outline only
        isAntiAlias = true                                 // Smooth lines
    }

    // Zoom and pan state variables
    private var scaleFactor = 1f                          // Current zoom level
    private var minScale = 1f                             // Minimum zoom (fit to screen)
    private var maxScale = 5f                             // Maximum zoom level
    private var lastFocusX = 0f                           // Last touch X coordinate
    private var lastFocusY = 0f                           // Last touch Y coordinate
    private var posX = 0f                                 // Current pan X offset
    private var posY = 0f                                 // Current pan Y offset

    // Scale gesture detector for pinch-to-zoom
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = max(minScale, min(scaleFactor, maxScale))
            invalidate()
            return true
        }
    })

    /**
     * Set the SVG floor plan to display
     *
     * @param svgData Parsed SVG object containing the floor plan
     */
    fun setMapSvg(svgData: SVG) {
        this.svg = svgData
        invalidate()
    }

    /**
     * Set the route path to overlay on the map
     *
     * @param route List of coordinate pairs representing the navigation route
     */
    fun setRoutePath(route: List<Pair<Float, Float>>) {
        path = route
        invalidate()
    }

    /**
     * Handle touch events for zoom and pan gestures
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        if (event.pointerCount == 1) { // One finger - panning
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastFocusX = event.x
                    lastFocusY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastFocusX
                    val dy = event.y - lastFocusY
                    posX += dx
                    posY += dy
                    lastFocusX = event.x
                    lastFocusY = event.y
                    invalidate()
                }
            }
        }
        return true
    }

    /**
     * Custom drawing method that renders SVG floor plan with route overlay.
     * Handles proper coordinate transformations and scaling.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        svg?.let {
            val viewBox = it.documentViewBox

            // Calculate base scale to fit SVG in view while maintaining aspect ratio
            val baseScaleX = width / viewBox.width()
            val baseScaleY = height / viewBox.height()
            val baseScale = minOf(baseScaleX, baseScaleY)
            minScale = baseScale

            // Apply pan and zoom transformations
            canvas.save()
            canvas.translate(posX, posY)
            canvas.scale(scaleFactor, scaleFactor)

            // Center map initially and apply base scaling
            canvas.scale(baseScale, baseScale)
            canvas.translate(
                (width / baseScale - viewBox.width()) / 2f,
                (height / baseScale - viewBox.height()) / 2f
            )

            // Draw SVG floor plan
            it.renderToCanvas(canvas)

            // Draw route path overlay if available
            if (path.size > 1) {
                for (i in 0 until path.size - 1) {
                    val (x1, y1) = path[i]
                    val (x2, y2) = path[i + 1]
                    canvas.drawLine(x1, y1, x2, y2, pathPaint)
                }
            }

            canvas.restore()
        }
    }
}