package com.example.jewelryar

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class ModelTouchHandler(
    context: Context,
    private val onTransformChanged: () -> Unit
) : View.OnTouchListener {

    private val scaleGestureDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    var scaleFactor = 1.0f
        private set

    val rotation = floatArrayOf(0f, 0f, 0f) // x, y, z rotation

    init {
        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
    }

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        // Let the gesture detectors inspect all events.
        val scaleResult = scaleGestureDetector.onTouchEvent(event)
        val gestureResult = gestureDetector.onTouchEvent(event)
        return scaleResult || gestureResult
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            // Clamp the scale factor to a reasonable range.
            scaleFactor = max(0.2f, min(scaleFactor, 5.0f))
            onTransformChanged() // Trigger redraw
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true // Necessary to receive subsequent events.
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            // Invert distance for more natural rotation feel.
            // The division slows down the rotation speed.
            val dx = -distanceX / 10.0f
            val dy = -distanceY / 10.0f

            rotation[0] += dy // Corresponds to rotation around X-axis
            rotation[1] += dx // Corresponds to rotation around Y-axis
            
            onTransformChanged() // Trigger redraw
            return true
        }
    }
}
