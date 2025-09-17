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
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        setContentView(R.layout.dialog_model_viewer)

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
            // Update renderer properties based on touch
            renderer.scale = touchHandler.scaleFactor
            renderer.rotationY = touchHandler.rotation[1] // Yaw
            renderer.rotationX = touchHandler.rotation[0] // Pitch
            glSurfaceView.requestRender()
        }

        glSurfaceView.setOnTouchListener { _, event ->
            touchHandler.onTouch(glSurfaceView, event)
            true
        }

        applyButton.setOnClickListener {
            onApply(renderer.scale, floatArrayOf(renderer.rotationX, renderer.rotationY, 0f))
            dismiss()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        touchHandler.onTouch(glSurfaceView, event)
        return true
    }
}

