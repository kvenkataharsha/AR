package com.example.jewelryar

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutionException

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var faceDetector: FaceDetector
    private lateinit var jewelryOverlay: JewelryOverlayView
    private lateinit var statusText: TextView
    private var arSession: Session? = null
    private var jewelryAnchor: Anchor? = null
    private var lastDetectedFace: Face? = null
    private var selectedJewelryType: String = ""
    
    // Camera control variables
    private var isUsingFrontCamera = true
    private var cameraProvider: ProcessCameraProvider? = null

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_main)
            Log.d("MainActivity", "Layout set successfully")

            // Check if ARCore is supported
            checkARCoreSupport()
            
            setupAR()
            setupFaceDetection()
            setupJewelrySelection()
            setupCameraControls()
            checkPermissions()
            
            Log.d("MainActivity", "onCreate completed successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate", e)
            Toast.makeText(this, "Error starting app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun checkARCoreSupport() {
        try {
            when (ArCoreApk.getInstance().checkAvailability(this)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                    Log.d("MainActivity", "ARCore is supported and installed")
                }
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                    Log.w("MainActivity", "ARCore needs to be installed/updated")
                    Toast.makeText(this, "ARCore needs to be installed/updated", Toast.LENGTH_LONG).show()
                }
                ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                    Log.e("MainActivity", "ARCore is not supported on this device")
                    Toast.makeText(this, "ARCore is not supported on this device", Toast.LENGTH_LONG).show()
                    finish()
                }
                else -> {
                    Log.w("MainActivity", "Unknown ARCore availability")
                    Toast.makeText(this, "Unknown ARCore availability", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking ARCore support", e)
            // Don't finish the app, just log the error
        }
    }

    private fun setupAR() {
        try {
            // Create camera preview
            previewView = PreviewView(this)
            val cameraContainer = findViewById<FrameLayout>(R.id.camera_container)
            cameraContainer.addView(previewView)

            // Initialize GLSurfaceView for 3D rendering
            glSurfaceView = findViewById(R.id.gl_surface_view_main)
            glSurfaceView.setEGLContextClientVersion(2)
            glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            glSurfaceView.holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            glSurfaceView.setZOrderOnTop(true)

            // Initialize jewelry overlay and status text
            jewelryOverlay = findViewById(R.id.jewelry_overlay)
            jewelryOverlay.setGLSurfaceView(glSurfaceView) // Pass GLSurfaceView to overlay
            statusText = findViewById(R.id.tv_status)

            // Initialize Face Detector
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()

            faceDetector = FaceDetection.getClient(options)
            
            statusText.text = "AR Jewelry Try-On Ready"
            
            Log.d("MainActivity", "Camera preview and overlay setup completed")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in setupAR", e)
        }
    }

    private fun setupFaceDetection() {
        try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()

            faceDetector = FaceDetection.getClient(options)
            Log.d("MainActivity", "Face detection setup completed")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up face detection", e)
        }
    }

    private fun setupJewelrySelection() {
        try {
            findViewById<Button>(R.id.btn_jewelry).setOnClickListener {
                showJewelrySelectionDialog()
            }
            Log.d("MainActivity", "Jewelry selection button setup completed")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up jewelry selection", e)
        }
    }

    private fun setupCameraControls() {
        try {
            findViewById<Button>(R.id.btn_camera_switch).setOnClickListener {
                isUsingFrontCamera = !isUsingFrontCamera
                startFaceTracking()
                
                val cameraType = if (isUsingFrontCamera) "Front" else "Back"
                Toast.makeText(this, "Switched to $cameraType Camera", Toast.LENGTH_SHORT).show()
            }
            Log.d("MainActivity", "Camera controls setup completed")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up camera controls", e)
        }
    }

    private fun showJewelrySelectionDialog() {
        val items = arrayOf("Necklace", "Necklace1", "Necklace2", "Chain", "Simple Chain", "Ring", "2D Images")
        AlertDialog.Builder(this)
            .setTitle("Select Jewelry Type")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> loadNecklaceModel()
                    1 -> loadNecklace1Model()
                    2 -> loadNecklace2Model()
                    3 -> loadChainModel()
                    4 -> loadSimpleChainModel()
                    5 -> loadRingModel()
                    6 -> show2DImageSelectionDialog()
                }
            }
            .show()
    }

    private fun loadNecklaceModel() {
        selectedJewelryType = "Necklace"
        statusText.text = "Necklace selected - Look at camera"
        Toast.makeText(this, "Necklace selected - Position your face in the camera", Toast.LENGTH_SHORT).show()
        startFaceTracking()
    }

    private fun loadNecklace1Model() {
        selectedJewelryType = "Necklace1"
        statusText.text = "Necklace1 selected - Look at camera"
        Toast.makeText(this, "Necklace1 selected - Position your face in the camera", Toast.LENGTH_SHORT).show()
        startFaceTracking()
    }

    private fun loadNecklace2Model() {
        selectedJewelryType = "Necklace2"
        statusText.text = "Necklace2 selected - Look at camera"
        Toast.makeText(this, "Necklace2 selected - Position your face in the camera", Toast.LENGTH_SHORT).show()
        startFaceTracking()
    }

    private fun loadChainModel() {
        selectedJewelryType = "Chain"
        statusText.text = "Chain selected - Look at camera"
        Toast.makeText(this, "Chain selected - Position your face in the camera", Toast.LENGTH_SHORT).show()
        startFaceTracking()
    }

    private fun loadSimpleChainModel() {
        selectedJewelryType = "SimpleChain"
        statusText.text = "Simple Chain selected - Look at camera"
        Toast.makeText(this, "Simple Chain selected - Position your face in the camera", Toast.LENGTH_SHORT).show()
        startFaceTracking()
    }

    private fun loadRingModel() {
        selectedJewelryType = "Ring"
        statusText.text = "Ring selected - Hand detection needed"
        Toast.makeText(this, "Ring selected - Hand detection feature coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun show2DImageSelectionDialog() {
        val imageItems = arrayOf("2D Necklace", "2D Chain", "2D Pendant")
        AlertDialog.Builder(this)
            .setTitle("Select 2D Jewelry Image")
            .setItems(imageItems) { _, which ->
                when (which) {
                    0 -> load2DNecklaceImage()
                    1 -> load2DChainImage()
                    2 -> load2DPendantImage()
                }
            }
            .show()
    }

    private fun load2DNecklaceImage() {
        selectedJewelryType = "2D_Necklace"
        statusText.text = "2D Necklace selected - Look at camera"
        Toast.makeText(this, "2D Necklace selected - Position your face in the camera", Toast.LENGTH_SHORT).show()
        startFaceTracking()
    }

    private fun load2DChainImage() {
        selectedJewelryType = "2D_Chain"
        statusText.text = "2D Chain selected - Look at camera"
        Toast.makeText(this, "2D Chain selected - Position your face in the camera", Toast.LENGTH_SHORT).show()
        startFaceTracking()
    }

    private fun load2DPendantImage() {
        selectedJewelryType = "2D_Pendant"
        statusText.text = "2D Pendant selected - Look at camera"
        Toast.makeText(this, "2D Pendant selected - Position your face in the camera", Toast.LENGTH_SHORT).show()
        startFaceTracking()
    }

    private fun startFaceTracking() {
        try {
            Log.d("MainActivity", "Starting face tracking")
            
            // Start camera preview and face detection
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            // Setup camera preview
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            
            val cameraSelector = if (isUsingFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                try {
                    cameraProvider = providerFuture.get()
                    cameraProvider?.unbindAll()
                    cameraProvider?.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )
                } catch (ex: Exception) {
                    Log.e("MainActivity", "Camera provider init failed", ex)
                    Toast.makeText(this, "Camera init failed: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }, ContextCompat.getMainExecutor(this))
            
            Log.d("MainActivity", "Face tracking started successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Camera binding failed", e)
            Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return imageProxy.close()
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty() && selectedJewelryType.isNotEmpty()) {
                    val face = faces[0]

                    val imageWidth = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.width else imageProxy.height
                    val imageHeight = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.height else imageProxy.width
                    
                    // Update overlay with face and jewelry type
                    runOnUiThread {
                        jewelryOverlay.updateFace(face, previewView.width, previewView.height, imageWidth, imageHeight, selectedJewelryType, isUsingFrontCamera)
                        statusText.text = "$selectedJewelryType placed on face"
                    }
                } else if (selectedJewelryType.isNotEmpty()) {
                    runOnUiThread {
                        jewelryOverlay.updateFace(null, previewView.width, previewView.height, 0, 0, "", isUsingFrontCamera)
                        statusText.text = "No face detected - Look at camera"
                    }
                }
            }
            .addOnFailureListener { e -> 
                Log.e("MainActivity", "Face detection error", e) 
                runOnUiThread {
                    statusText.text = "Face detection error"
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        } else {
            // Permission already granted, start camera
            startCamera()
        }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            startFaceTracking()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startCamera()
                } else {
                    Toast.makeText(this, "Camera permission is required for AR", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        try {
            arSession?.resume()
            Log.d("MainActivity", "AR session resumed")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error resuming AR session", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        try {
            arSession?.pause()
            Log.d("MainActivity", "AR session paused")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error pausing AR session", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            arSession?.close()
            Log.d("MainActivity", "AR session closed")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error closing AR session", e)
        }
    }
}