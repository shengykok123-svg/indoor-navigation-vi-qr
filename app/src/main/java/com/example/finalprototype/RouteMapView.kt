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

class RouteMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var svg: SVG? = null
    private var path: List<Pair<Float, Float>> = emptyList()

    // Drawing paint for the route
    private val pathPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // Zoom & pan variables
    private var scaleFactor = 1f
    private var minScale = 1f
    private var maxScale = 5f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var posX = 0f
    private var posY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = max(minScale, min(scaleFactor, maxScale))
            invalidate()
            return true
        }
    })

    fun setMapSvg(svgData: SVG) {
        this.svg = svgData
        invalidate()
    }

    fun setRoutePath(route: List<Pair<Float, Float>>) {
        path = route
        invalidate()
    }

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        svg?.let {
            val viewBox = it.documentViewBox
            val baseScaleX = width / viewBox.width()
            val baseScaleY = height / viewBox.height()
            val baseScale = minOf(baseScaleX, baseScaleY)
            minScale = baseScale

            // Apply pan & zoom
            canvas.save()
            canvas.translate(posX, posY)
            canvas.scale(scaleFactor, scaleFactor)

            // Center map initially
            canvas.scale(baseScale, baseScale)
            canvas.translate(
                (width / baseScale - viewBox.width()) / 2f,
                (height / baseScale - viewBox.height()) / 2f
            )

            // Draw SVG floor plan
            it.renderToCanvas(canvas)

            // Draw route path
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
