package com.example.jewelryar

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import android.widget.Button

class ModelViewerDialog(
    context: Context,
    private val model: Model3D,
    private val onApply: (scale: Float, rotation: FloatArray) -> Unit
) : Dialog(context) {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: ModelRenderer
    private lateinit var touchHandler: ModelTouchHandler
    private lateinit var applyButton: Button

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("ModelViewerDialog", "🎯 Dialog onCreate called")
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        setContentView(R.layout.dialog_model_viewer)
        android.util.Log.d("ModelViewerDialog", "🎯 Dialog layout set")

        glSurfaceView = findViewById(R.id.gl_surface_view)
        applyButton = findViewById(R.id.btn_apply)

        // Setup the renderer
        renderer = ModelRenderer(context, model)
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        // Make GLSurfaceView transparent
        glSurfaceView.holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        glSurfaceView.setZOrderOnTop(true)

        window?.apply {
            val width = (context.resources.displayMetrics.widthPixels * 0.85).toInt()
            val height = (context.resources.displayMetrics.heightPixels * 0.75).toInt()
            setLayout(width, height)
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes.gravity = Gravity.CENTER
            attributes.dimAmount = 0.8f
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            this.attributes = attributes
        }

        touchHandler = ModelTouchHandler(context) {
            // Create a render task with the latest data from the touch handler
            val task = RenderTask(
                positionX = 0f, // Dialog is always centered
                positionY = 0f,
                scale = touchHandler.scaleFactor,
                rotationX = touchHandler.rotation[0], // Pitch
                rotationY = touchHandler.rotation[1]  // Yaw
            )
            // Submit the task to the renderer and request a redraw
            renderer.submitRenderTask(task)
            glSurfaceView.requestRender()
        }

        glSurfaceView.setOnTouchListener { _, event ->
            touchHandler.onTouch(glSurfaceView, event)
            true
        }

        applyButton.setOnClickListener {
            android.util.Log.d("ModelViewerDialog", "🎯 Apply button clicked")
            // Get the final values directly from the touch handler, not the renderer
            onApply(touchHandler.scaleFactor, touchHandler.rotation)
            dismiss()
        }
        
        android.util.Log.d("ModelViewerDialog", "🎯 Dialog setup complete")
    }
    
    override fun show() {
        android.util.Log.d("ModelViewerDialog", "🎯 Dialog show() called")
        super.show()
        android.util.Log.d("ModelViewerDialog", "🎯 Dialog show() completed")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        touchHandler.onTouch(glSurfaceView, event)
        return true
    }
}

