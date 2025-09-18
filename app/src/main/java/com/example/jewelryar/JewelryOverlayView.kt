package com.example.jewelryar

import android.content.Context
import android.graphics.*
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix as OpenGLMatrix
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer as NioFloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

data class ModelInfo(
    val vertexCount: Int,
    val faceCount: Int,
    val textureCount: Int,
    val normalCount: Int,
    val materials: List<String> = emptyList(),
    val hasTexture: Boolean = false,
    val texturePath: String? = null
)

// 3D Model data structure
data class Model3D(
    val vertices: FloatArray,
    val normals: FloatArray,
    val textureCoords: FloatArray,
    val indices: ShortArray, // Changed to ShortArray for OpenGL ES
    val vertexCount: Int,
    val faceCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Model3D
        return vertices.contentEquals(other.vertices) &&
               normals.contentEquals(other.normals) &&
               textureCoords.contentEquals(other.textureCoords) &&
               indices.contentEquals(other.indices)
    }

    override fun hashCode(): Int {
        var result = vertices.contentHashCode()
        result = 31 * result + normals.contentHashCode()
        result = 31 * result + textureCoords.contentHashCode()
        result = 31 * result + indices.contentHashCode()
        return result
    }
}

class JewelryOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Store context for asset access
    private val assetManager = context.assets

    private var detectedFace: Face? = null
    private var currentFace: Face? = null
    private var selectedJewelry = "Necklace"
    private var modelInfo: ModelInfo? = null
    private var viewWidth = 0
    private var viewHeight = 0
    private var imageWidth = 0
    private var imageHeight = 0
    private var isFrontCamera = true
    
    // 3D rendering components
    private var current3DModel: Model3D? = null
    private var vertexBuffer: NioFloatBuffer? = null
    
    // 2D image rendering components
    private var current2DImage: Bitmap? = null
    private var is2DMode = false
    private var normalBuffer: NioFloatBuffer? = null
    private var indexBuffer: IntBuffer? = null
    
    // OpenGL shader variables
    private var shaderProgram: Int = 0
    private var positionHandle: Int = 0
    private var normalHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var lightPosHandle: Int = 0
    
    // 3D transformation matrices
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    
    // Face tracking for 3D positioning
    private var neckStartPoint = PointF(0f, 0f)
    private var neckEndPoint = PointF(0f, 0f)
    private var faceScale = 1f

    private var glSurfaceView: GLSurfaceView? = null
    private var modelRenderer: ModelRenderer? = null
    private var headRotationY = 0f

    // Transformation values from the dialog
    private var appliedScale = 1.0f
    private var appliedRotation = floatArrayOf(0f, 0f, 0f)

    private val scale: Float
        get() {
            if (imageWidth == 0 || imageHeight == 0 || viewWidth == 0 || viewHeight == 0) return 1f
            return max(viewWidth.toFloat() / imageWidth.toFloat(), viewHeight.toFloat() / imageHeight.toFloat())
        }

    private val offsetX: Float
        get() = (viewWidth.toFloat() - imageWidth.toFloat() * scale) / 2.0f

    private val offsetY: Float
        get() = (viewHeight.toFloat() - imageHeight.toFloat() * scale) / 2.0f

    private fun translateX(x: Float): Float {
        return if (isFrontCamera) {
            viewWidth - (x * scale + offsetX)
        } else {
            x * scale + offsetX
        }
    }

    private fun translateY(y: Float): Float {
        return y * scale + offsetY
    }

    init {
        // Make the view transparent
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun setGLSurfaceView(glView: GLSurfaceView) {
        this.glSurfaceView = glView
    }

    fun updateFace(face: Face?, viewWidth: Int, viewHeight: Int, imageWidth: Int, imageHeight: Int, jewelry: String, isFront: Boolean) {
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.isFrontCamera = isFront
        
        // Early return if no jewelry is selected to prevent crashes
        if (jewelry.isEmpty()) {
            Log.d("JewelryOverlay", "No jewelry selected, skipping face processing")
            return
        }

        // Only update if we have a valid face with sufficient confidence
        if (face != null) {
            // Check if face is large enough and within reasonable bounds
            val faceArea = face.boundingBox.width() * face.boundingBox.height()
            val minFaceArea = 100 * 100 // Minimum face size for reliable tracking
            
            if (faceArea >= minFaceArea) {
                detectedFace = face
                currentFace = face
                
                Log.d("JewelryOverlay", "👤 Valid face detected, area: $faceArea")
            } else {
                Log.d("JewelryOverlay", "⚠️ Face too small, area: $faceArea")
                // Keep the last known good face for a brief moment
            }
        } else {
            Log.d("JewelryOverlay", "❌ No face detected")
            // Clear face after a delay to avoid flickering
            currentFace = null
        }
        
        // Load jewelry (3D model or 2D image) for rendering if type changed
        if (jewelry != selectedJewelry && jewelry.isNotEmpty()) {
            Log.d("JewelryOverlay", "🔄 Jewelry type changed from '$selectedJewelry' to '$jewelry'. Calling setSelectedJewelry.")
            // Delegate to setSelectedJewelry to handle loading and showing the dialog
            setSelectedJewelry(jewelry)
        } else if (jewelry.isEmpty()) {
            Log.d("JewelryOverlay", "⚠️ Empty jewelry type received")
        }
        
        // Only calculate positioning if we have a current face
        currentFace?.let { face ->
            faceScale = calculateScale(face)
            // Update the renderer if it exists and GLSurfaceView is ready
            val currentRenderer = modelRenderer
            val currentGLView = glSurfaceView
            
            if (currentRenderer != null && currentGLView != null) {
                val neckPoints = calculateNeckPoints(face)
                val headRotation = calculateHeadRotation(face)

                // This is a simplification. A proper implementation would convert screen coordinates
                // to OpenGL world coordinates. For now, we'll pass normalized values.
                currentRenderer.scale = appliedScale * 0.1f // Adjust scale for OpenGL
                currentRenderer.rotationX = appliedRotation[0] + headRotation[0]
                currentRenderer.rotationY = appliedRotation[1] + headRotation[1]
                currentGLView.requestRender()
            } else {
                Log.d("JewelryOverlay", "GLSurfaceView or renderer not ready for rendering")
            }
        } ?: run {
            // If no face, clear the GLSurfaceView only if it's properly initialized
            glSurfaceView?.let { glView ->
                // Try to clear the GLSurfaceView
                try {
                    glView.queueEvent {
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                    }
                } catch (e: Exception) {
                    Log.e("JewelryOverlay", "GLSurfaceView not ready for clear", e)
                }
            }
        }

        invalidate() // Trigger redraw for 2D elements
    }

    fun setSelectedJewelry(jewelry: String) {
        // Prevent redundant loading of the same model
        if (jewelry == selectedJewelry && current3DModel != null) {
            Log.d("JewelryOverlay", "✅ '$jewelry' is already selected and loaded.")
            // Still show the dialog if the model is already loaded but dialog is requested again
            current3DModel?.let {
                ModelViewerDialog(context, it) { scale, rotation ->
                    appliedScale = scale
                    appliedRotation = rotation
                    // Update the main renderer with new values
                    modelRenderer?.scale = scale
                    modelRenderer?.rotationX = rotation[0]
                    modelRenderer?.rotationY = rotation[1]
                    glSurfaceView?.requestRender()
                    invalidate() // Redraw with new values
                }.show()
            }
            return
        }

        selectedJewelry = jewelry
        
        // Check if it's a 2D mode jewelry
        if (jewelry.startsWith("2D_", ignoreCase = true)) {
            Log.d("JewelryOverlay", "🖼️ Loading 2D jewelry: $jewelry")
            is2DMode = true
            current3DModel = null // Clear 3D model
            modelRenderer = null
            glSurfaceView?.setRenderer(null)
            load2DImage(jewelry)
        } else {
            Log.d("JewelryOverlay", "🎯 Loading 3D jewelry: $jewelry")
            // Reset 2D mode
            is2DMode = false
            current2DImage = null
            load3DModel(jewelry) { model ->
                Log.d("JewelryOverlay", "🎯 3D Model loaded successfully, setting up renderer and dialog")
                
                // Setup the renderer for the main view
                modelRenderer = ModelRenderer(context, model)
                glSurfaceView?.setRenderer(modelRenderer)
                glSurfaceView?.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                
                Log.d("JewelryOverlay", "🎯 Renderer set up, creating dialog")
                
                // Show the dialog for model adjustment
                try {
                    val dialog = ModelViewerDialog(context, model) { scale, rotation ->
                        Log.d("JewelryOverlay", "🎯 Dialog apply clicked: scale=$scale, rotation=${rotation.contentToString()}")
                        appliedScale = scale
                        appliedRotation = rotation
                        // Update the main renderer with new values
                        modelRenderer?.scale = scale
                        modelRenderer?.rotationX = rotation[0]
                        modelRenderer?.rotationY = rotation[1]
                        glSurfaceView?.requestRender()
                        invalidate() // Redraw with new values
                    }
                    
                    // Post the dialog show to ensure it's on the UI thread
                    post {
                        dialog.show()
                        Log.d("JewelryOverlay", "🎯 Dialog shown successfully")
                    }
                } catch (e: Exception) {
                    Log.e("JewelryOverlay", "❌ Error showing dialog", e)
                }
            }
        }

        invalidate()
    }
    
    private fun load3DModel(jewelryType: String, onModelLoaded: (Model3D) -> Unit = {}) {
        Thread {
            // --- NEW, ROBUST OBJ PARSER ---
            try {
                val modelPath = when (jewelryType.lowercase()) {
                    "necklace" -> "models/necklace/11777_necklace_v1_l3.obj"
                    "necklace1" -> "models/necklace1/necklace1.obj"
                    "necklace2" -> "models/necklace2/necklace2.obj"
                    "chain" -> "models/chain/11779_blueheart_v1_L3.obj"
                    "simplechain", "simple chain" -> "models/simplechain/simple_chain_new.obj"
                    "ring" -> "models/ring/sample_ring.obj"
                    else -> "models/necklace/11777_necklace_v1_l3.obj"
                }
                Log.d("OBJ_PARSER", "--- Starting Load for: $modelPath ---")
                
                // Check if file exists first
                try {
                    assetManager.open(modelPath).close()
                    Log.d("OBJ_PARSER", "✅ File exists: $modelPath")
                } catch (e: Exception) {
                    Log.e("OBJ_PARSER", "❌ File not found: $modelPath", e)
                    post { current3DModel = null; invalidate() }
                    return@Thread
                }

                // 1. Read all lines and data from the file into temporary lists
                val tempVertices = mutableListOf<Float>()
                val tempNormals = mutableListOf<Float>()
                val tempTexCoords = mutableListOf<Float>()
                val faceLines = mutableListOf<String>()

                assetManager.open(modelPath).bufferedReader().forEachLine { line ->
                    val parts = line.trim().split("\\s+".toRegex())
                    when (parts.getOrNull(0)) {
                        "v" -> if (parts.size >= 4) {
                            tempVertices.add(parts[1].toFloat()); tempVertices.add(parts[2].toFloat()); tempVertices.add(parts[3].toFloat())
                        }
                        "vn" -> if (parts.size >= 4) {
                            tempNormals.add(parts[1].toFloat()); tempNormals.add(parts[2].toFloat()); tempNormals.add(parts[3].toFloat())
                        }
                        "vt" -> if (parts.size >= 3) {
                            tempTexCoords.add(parts[1].toFloat()); tempTexCoords.add(parts[2].toFloat())
                        }
                        "f" -> faceLines.add(line)
                    }
                }
                Log.d("OBJ_PARSER", "1. Raw Data Read Complete.")
                Log.d("OBJ_PARSER", "   - Raw Vertices: ${tempVertices.size / 3}")
                Log.d("OBJ_PARSER", "   - Raw Normals: ${tempNormals.size / 3}")
                Log.d("OBJ_PARSER", "   - Raw Faces: ${faceLines.size}")

                // 2. Process faces to build final, indexed arrays for OpenGL
                val finalVertices = mutableListOf<Float>()
                val finalNormals = mutableListOf<Float>()
                val finalTexCoords = mutableListOf<Float>()
                val finalIndices = mutableListOf<Short>()
                val vertexMap = mutableMapOf<String, Short>()

                for (line in faceLines) {
                    val faceParts = line.trim().split("\\s+".toRegex()).subList(1, line.trim().split("\\s+".toRegex()).size)

                    // Triangulate polygons (faces with > 3 vertices)
                    if (faceParts.size >= 3) {
                        val firstCorner = faceParts[0]
                        for (i in 1 until faceParts.size - 1) {
                            val corners = listOf(firstCorner, faceParts[i], faceParts[i + 1])
                            for (corner in corners) {
                                if (vertexMap.containsKey(corner)) {
                                    finalIndices.add(vertexMap[corner]!!)
                                } else {
                                    val newIndex = (finalVertices.size / 3).toShort()
                                    vertexMap[corner] = newIndex
                                    finalIndices.add(newIndex)

                                    val indices = corner.split("/").map { if (it.isNotEmpty()) it.toInt() - 1 else -1 }
                                    val vIdx = indices.getOrElse(0) { -1 }
                                    val vtIdx = indices.getOrElse(1) { -1 }
                                    val vnIdx = indices.getOrElse(2) { -1 }

                                    // Add vertex position (required)
                                    if (vIdx in 0 until (tempVertices.size / 3)) {
                                        finalVertices.add(tempVertices[vIdx * 3])
                                        finalVertices.add(tempVertices[vIdx * 3 + 1])
                                        finalVertices.add(tempVertices[vIdx * 3 + 2])
                                    } else {
                                        finalVertices.add(0f); finalVertices.add(0f); finalVertices.add(0f)
                                        Log.w("OBJ_PARSER", "   - WARNING: Invalid vertex index '$vIdx' in corner '$corner'. Using (0,0,0).")
                                    }

                                    // Add texture coordinate (optional)
                                    if (vtIdx in 0 until (tempTexCoords.size / 2)) {
                                        finalTexCoords.add(tempTexCoords[vtIdx * 2])
                                        finalTexCoords.add(tempTexCoords[vtIdx * 2 + 1])
                                    } else {
                                        finalTexCoords.add(0f); finalTexCoords.add(0f)
                                    }

                                    // Add normal (optional, but required for our shader)
                                    if (vnIdx in 0 until (tempNormals.size / 3)) {
                                        finalNormals.add(tempNormals[vnIdx * 3])
                                        finalNormals.add(tempNormals[vnIdx * 3 + 1])
                                        finalNormals.add(tempNormals[vnIdx * 3 + 2])
                                    } else {
                                        finalNormals.add(0f); finalNormals.add(0f); finalNormals.add(1f) // Default up
                                    }
                                }
                            }
                        }
                    }
                }
                Log.d("OBJ_PARSER", "2. Vertex Processing Complete.")
                Log.d("OBJ_PARSER", "   - Final Unique Vertices: ${finalVertices.size / 3}")
                Log.d("OBJ_PARSER", "   - Final Indices: ${finalIndices.size}")

                // 3. Create the Model3D object and post to the UI thread
                if (finalVertices.isNotEmpty() && finalIndices.isNotEmpty()) {
                    val model3D = Model3D(
                        vertices = finalVertices.toFloatArray(),
                        normals = finalNormals.toFloatArray(),
                        textureCoords = finalTexCoords.toFloatArray(),
                        indices = finalIndices.toShortArray(),
                        vertexCount = finalVertices.size / 3,
                        faceCount = finalIndices.size / 3
                    )
                    Log.d("OBJ_PARSER", "3. Model3D object created successfully.")
                    post {
                        current3DModel = model3D
                        // Always create a new renderer for each model to ensure proper initialization
                        onModelLoaded(model3D)
                        invalidate()
                    }
                } else {
                    Log.e("OBJ_PARSER", "3. ERROR: No valid geometry was processed. Model will not be loaded.")
                    post { current3DModel = null; invalidate() }
                }

            } catch (e: Exception) {
                Log.e("OBJ_PARSER", "--- FATAL PARSING ERROR ---", e)
                post { current3DModel = null; invalidate() }
            }
        }.start()
    }

    private fun load2DImage(jewelryType: String) {
        try {
            val imagePath = when (jewelryType.lowercase()) {
                "2d_necklace" -> "images/necklace/11777_necklace_v1_l3.png"
                "2d_chain" -> "images/chain/11779_blueheart_diffuse.png"
                "2d_pendant" -> "images/necklace/11777_necklace_v1_l3.png" // Use necklace as fallback for pendant
                else -> {
                    Log.w("JewelryOverlay", "⚠️ Unknown 2D jewelry type: '$jewelryType', falling back to necklace")
                    "images/necklace/11777_necklace_v1_l3.png"
                }
            }
            
            Log.d("JewelryOverlay", "🖼️ Loading 2D image: $imagePath for jewelry type: '$jewelryType'")
            
            // Check if file exists first
            try {
                assetManager.open(imagePath).close()
                Log.d("JewelryOverlay", "✅ Image file exists: $imagePath")
            } catch (e: Exception) {
                Log.e("JewelryOverlay", "❌ Image file not found: $imagePath")
                Log.e("JewelryOverlay", "💡 Hint: Place your jewelry images in the assets/images/ folders")
                current2DImage = null
                is2DMode = false
                post { invalidate() }
                return
            }
            
            // Load the image
            assetManager.open(imagePath).use { inputStream ->
                current2DImage = BitmapFactory.decodeStream(inputStream)
                is2DMode = true
                
                if (current2DImage != null) {
                    Log.d("JewelryOverlay", "✅ Successfully loaded 2D image for $jewelryType!")
                    Log.d("JewelryOverlay", "📊 Image size: ${current2DImage!!.width} x ${current2DImage!!.height}")
                } else {
                    Log.e("JewelryOverlay", "❌ Failed to decode image: $imagePath")
                    is2DMode = false
                }
            }
            
        } catch (e: Exception) {
            Log.e("JewelryOverlay", "❌ Failed to load 2D image for $jewelryType", e)
            current2DImage = null
            is2DMode = false
            post { invalidate() }
        }
    }

    private fun calculateNeckPoints(face: Face): Pair<PointF, PointF> {
        val chinPosition = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val leftCheek = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position
        val rightCheek = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position

        if (chinPosition != null && leftCheek != null && rightCheek != null) {
            val chinPoint = PointF(translateX(chinPosition.x), translateY(chinPosition.y))
            val leftCheekPoint = PointF(translateX(leftCheek.x), translateY(leftCheek.y))
            val rightCheekPoint = PointF(translateX(rightCheek.x), translateY(rightCheek.y))

            val faceWidth = abs(leftCheekPoint.x - rightCheekPoint.x)

            // Neck position is below the chin. Increased multiplier from 0.2f to 0.4f to lower the position.
            val neckCenterY = chinPoint.y + faceWidth * 0.4f // Adjust this factor for placement
            val neckCenterX = (leftCheekPoint.x + rightCheekPoint.x) / 2

            val neckStart = PointF(neckCenterX, neckCenterY)
            val neckEnd = PointF(neckCenterX, neckCenterY + faceWidth * 0.4f) // Arbitrary length

            return Pair(neckStart, neckEnd)
        }

        // Fallback to bounding box if landmarks are not available
        val originalRect = face.boundingBox
        val faceRect = RectF(
            translateX(originalRect.left.toFloat()),
            translateY(originalRect.top.toFloat()),
            translateX(originalRect.right.toFloat()),
            translateY(originalRect.bottom.toFloat())
        )
        val centerX = faceRect.centerX()
        val bottom = faceRect.bottom
        val neckStartY = bottom + 20f
        val neckEndY = neckStartY + 80f
        return Pair(PointF(centerX, neckStartY), PointF(centerX, neckEndY))
    }
    
    private fun render3DJewelry(canvas: Canvas) {
        // This method is now obsolete for 3D rendering as it's handled by GLSurfaceView.
        // We leave it for potential debug drawing on the 2D canvas if needed.
        current3DModel?.let { model ->
            if (currentFace == null) return

            Log.d("JewelryOverlay", "🎭 3D rendering is now handled by ModelRenderer and GLSurfaceView.")

            // The actual rendering happens in ModelRenderer.onDrawFrame()
            // We just need to ensure the renderer has the latest data, which is done in updateFace()
        } ?: run {
            Log.d("JewelryOverlay", "⚠️ No 3D model available for rendering")
        }
    }
    
    private fun renderVerticesDirectly(canvas: Canvas, model: Model3D, centerX: Float, centerY: Float) {
        val paint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        
        // BABY STEP 1: First just draw a simple circle exactly at neck position to verify positioning
        canvas.drawCircle(centerX, centerY, 30f, paint)
        
        // Add debug text to show coordinates
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 20f
        }
        canvas.drawText("Neck: ($centerX, $centerY)", centerX - 50f, centerY + 50f, textPaint)
        
        Log.d("JewelryOverlay", "🎯 Drawing simple circle at neck position: ($centerX, $centerY)")
    }
    
    private fun render3DModelSimple(canvas: Canvas, model: Model3D, neckPoints: Pair<PointF, PointF>, scale: Float, rotation: FloatArray) {
        // This method is now OBSOLETE. The rendering is handled by the GLSurfaceView and ModelRenderer.
        // We can keep it for debugging or as a fallback, but it should not be called in the main flow.
        Log.w("JewelryOverlay", "render3DModelSimple is obsolete and should not be called for 3D rendering.")
    }
    
    private fun calculateScale(face: Face): Float {
        val faceWidth = face.boundingBox.width()
        val faceHeight = face.boundingBox.height()
        
        // Scale based on face dimensions for neck jewelry (reduced for better positioning)
        val avgFaceSize = (faceWidth + faceHeight) / 2f
        val baseSize = 400f // Increased base size to reduce overall scale
        val scale = (avgFaceSize / baseSize) * 0.8f // Reduced multiplier from 1.2f to 0.8f
        
        Log.d("JewelryOverlay", "📏 Calculated scale: $scale (face size: ${avgFaceSize})")
        return scale
    }
    
    private fun calculateHeadRotation(face: Face): FloatArray {
        // Get head rotation from face landmarks
        val rotation = floatArrayOf(
            face.headEulerAngleX,
            face.headEulerAngleY,
            face.headEulerAngleZ
        )
        
        Log.d("JewelryOverlay", "🔄 Head rotation: X=${rotation[0]}, Y=${rotation[1]}, Z=${rotation[2]}")
        return rotation
    }
    
    private fun setupProjectionMatrix(width: Int, height: Int) {
        val ratio = width.toFloat() / height.toFloat()
        OpenGLMatrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 10f)
    }
    
    private fun setupViewMatrix() {
        OpenGLMatrix.setLookAtM(viewMatrix, 0,
            0f, 0f, 3f,  // Eye position
            0f, 0f, 0f,  // Look at center
            0f, 1f, 0f   // Up vector
        )
    }
    
    private fun setupModelMatrix(position: PointF, scale: Float, rotation: FloatArray) {
        OpenGLMatrix.setIdentityM(modelMatrix, 0)
        
        // Apply transformations in order: translate, rotate, scale
        OpenGLMatrix.translateM(modelMatrix, 0, 
            (position.x - width/2f) / width * 2f,  // Normalize to [-1, 1]
            -(position.y - height/2f) / height * 2f, // Flip Y and normalize
            0f
        )
        
        // Apply head rotation
        OpenGLMatrix.rotateM(modelMatrix, 0, rotation[0], 1f, 0f, 0f) // X rotation
        OpenGLMatrix.rotateM(modelMatrix, 0, rotation[1], 0f, 1f, 0f) // Y rotation  
        OpenGLMatrix.rotateM(modelMatrix, 0, rotation[2], 0f, 0f, 1f) // Z rotation
        
        // Apply scale
        OpenGLMatrix.scaleM(modelMatrix, 0, scale, scale, scale)
    }
    
    private fun render3DModel(canvas: Canvas, model: Model3D, neckPoints: Pair<PointF, PointF>, scale: Float) {
        // Create paint for 3D rendering
        val paint = Paint().apply {
            color = Color.rgb(255, 215, 0) // Gold color
            style = Paint.Style.FILL
            isAntiAlias = true
            setShadowLayer(5f, 2f, 2f, Color.argb(80, 0, 0, 0))
        }
        
        val edgePaint = Paint().apply {
            color = Color.rgb(180, 140, 0)
            style = Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        }
        
        // Transform vertices using MVP matrix and draw
        val transformedVertices = mutableListOf<PointF>()
        val projectedPoints = mutableListOf<PointF>()
        
        // Process vertices in groups of 3 (triangles)
        for (i in model.vertices.indices step 3) {
            if (i + 2 < model.vertices.size) {
                // Get vertex position
                val vertex = floatArrayOf(
                    model.vertices[i],
                    model.vertices[i + 1], 
                    model.vertices[i + 2],
                    1f // Homogeneous coordinate
                )
                
                // Transform vertex by MVP matrix
                val transformedVertex = FloatArray(4)
                OpenGLMatrix.multiplyMV(transformedVertex, 0, mvpMatrix, 0, vertex, 0)
                
                // Perspective divide
                if (transformedVertex[3] != 0f) {
                    val x = transformedVertex[0] / transformedVertex[3]
                    val y = transformedVertex[1] / transformedVertex[3]
                    
                    // Convert to screen coordinates
                    val screenX = (x + 1f) * canvas.width / 2f
                    val screenY = (1f - y) * canvas.height / 2f
                    
                    projectedPoints.add(PointF(screenX, screenY))
                }
            }
        }
        
        // Draw triangles
        val path = Path()
        for (i in projectedPoints.indices step 3) {
            if (i + 2 < projectedPoints.size) {
                val p1 = projectedPoints[i]
                val p2 = projectedPoints[i + 1] 
                val p3 = projectedPoints[i + 2]
                
                // Skip degenerate triangles
                if (isValidTriangle(p1, p2, p3)) {
                    path.reset()
                    path.moveTo(p1.x, p1.y)
                    path.lineTo(p2.x, p2.y)
                    path.lineTo(p3.x, p3.y)
                    path.close()
                    
                    // Fill triangle
                    canvas.drawPath(path, paint)
                    
                    // Draw edges for definition
                    canvas.drawPath(path, edgePaint)
                }
            }
        }
        
        Log.d("JewelryOverlay", "🎨 Rendered ${projectedPoints.size/3} triangles")
    }
    
    private fun isValidTriangle(p1: PointF, p2: PointF, p3: PointF): Boolean {
        val area = abs((p1.x * (p2.y - p3.y) + p2.x * (p3.y - p1.y) + p3.x * (p1.y - p2.y)) / 2f)
        return area > 1f // Minimum area threshold
    }

    private fun loadModelFromAssets(jewelryType: String) {
        // Load 3D model only - no 2D fallback
        load3DModel(jewelryType)
    }
    
    private fun load3DModelInfo(jewelryType: String) {
        try {
            val (modelPath, texturePath) = when (jewelryType.lowercase()) {
                "necklace" -> Pair("models/necklace/11777_necklace_v1_l3.obj", "models/necklace/Necklace_stone.jpg")
                "chain" -> Pair("models/chain/11779_blueheart_v1_L3.obj", "models/chain/blueheart_texture.jpg")
                "ring" -> {
                    Log.d("JewelryOverlay", "Ring folder contains .blend file - using sample OBJ for now")
                    Pair("models/ring/sample_ring.obj", "models/ring/ring.jpg")
                }
                else -> Pair("models/necklace/11777_necklace_v1_l3.obj", "models/necklace/Necklace_stone.jpg")
            }
            
            Log.d("JewelryOverlay", "Loading $jewelryType model from: $modelPath")
            
            assetManager.open(modelPath).use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?
                var vertexCount = 0
                var faceCount = 0
                var textureCount = 0
                var normalCount = 0
                val materials = mutableSetOf<String>()
                
                while (reader.readLine().also { line = it } != null) {
                    line?.let { currentLine ->
                        when {
                            currentLine.startsWith("v ") -> vertexCount++
                            currentLine.startsWith("vt ") -> textureCount++
                            currentLine.startsWith("vn ") -> normalCount++
                            currentLine.startsWith("f ") -> faceCount++
                            currentLine.startsWith("usemtl ") -> materials.add(currentLine.substring(7).trim())
                        }
                    }
                }
                
                // Check if texture exists
                val hasTexture = try {
                    assetManager.open(texturePath).close()
                    true
                } catch (e: Exception) {
                    false
                }
                
                modelInfo = ModelInfo(vertexCount, faceCount, textureCount, normalCount, materials.toList(), hasTexture, texturePath)
                
                Log.d("JewelryOverlay", "✅ Successfully loaded $jewelryType model from your assets!")
                Log.d("JewelryOverlay", "📊 Model stats - Vertices: $vertexCount, Faces: $faceCount")
                Log.d("JewelryOverlay", "🎨 Materials found: ${materials.joinToString(", ")}")
            }
        } catch (e: Exception) {
            Log.w("JewelryOverlay", "Could not load model for $jewelryType: ${e.message}")
            modelInfo = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        // The super.onDraw(canvas) is important for a transparent view
        super.onDraw(canvas)

        // The 3D rendering is now handled by the GLSurfaceView which is separate from this View's canvas.
        // This onDraw method is now only responsible for drawing 2D elements like status text and debug info.

        // Display status information
        val statusPaint = Paint().apply {
            color = Color.CYAN
            textSize = 32f
            textAlign = Paint.Align.LEFT
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        
        // Show 3D rendering status
        val has3DModel = current3DModel != null
        val has2DImage = current2DImage != null && is2DMode
        val renderingMethod = if (has2DImage) "🖼️ 2D Image Rendering"
                              else if (has3DModel) "🎯 3D Model Rendering" 
                              else "🎨 Fallback Shape"
        
        canvas.drawText("✓ Selected: '$selectedJewelry' - $renderingMethod", 20f, 60f, statusPaint)
        
        // Show model/image info
        if (has2DImage) {
            canvas.drawText("�️ 2D Image: ${current2DImage!!.width} x ${current2DImage!!.height}", 20f, 140f, statusPaint)
        } else {
            current3DModel?.let { model ->
                canvas.drawText("📊 Vertices: ${model.vertexCount}, Faces: ${model.faceCount}", 20f, 140f, statusPaint)
            } ?: run {
                canvas.drawText("❌ No 3D model loaded for '$selectedJewelry'", 20f, 140f, statusPaint)
            }
            }
        
        canvas.drawText("🎚️ Scale: ${String.format("%.2f", faceScale)}", 20f, 180f, statusPaint)
        canvas.drawText("🎭 Head Rotation: ${String.format("%.1f°", headRotationY)}", 20f, 220f, statusPaint)
        
        // Render jewelry on detected face
        detectedFace?.let { face ->
            // BABY STEP: First draw face bounding box to see where face is detected
            val debugPaint = Paint().apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            
            // Draw face bounding box WITH COORDINATE TRANSFORMATION
            val originalRect = face.boundingBox

            val left = translateX(originalRect.left.toFloat())
            val right = translateX(originalRect.right.toFloat())
            val top = translateY(originalRect.top.toFloat())
            val bottom = translateY(originalRect.bottom.toFloat())

            val faceRect = android.graphics.RectF(
                min(left, right),
                top,
                max(left, right),
                bottom
            )
            
            // Draw the face bounding box
            val yellowPaint = Paint().apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 3f }
            canvas.drawRect(faceRect, yellowPaint)
            
            // Debug text
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 14f
                setShadowLayer(2f, 1f, 1f, Color.BLACK)
            }
            canvas.drawText("Face Detection - Yellow=Adjusted Scale", 20f, viewHeight - 80f, textPaint)
            canvas.drawText("View: ${viewWidth.toInt()}x${viewHeight.toInt()}", 20f, viewHeight - 40f, textPaint)
            
            // Use the correctly positioned rectangle for face center
            val faceCenterX = faceRect.centerX()
            val faceCenterY = faceRect.centerY()
            canvas.drawCircle(faceCenterX, faceCenterY, 10f, yellowPaint)
            
            // Render based on mode
            if (is2DMode && current2DImage != null) {
                Log.d("JewelryOverlay", "🖼️ Rendering 2D image mode")
                render2DJewelry(canvas, face)
            } else if (current3DModel != null) {
                // 3D rendering is handled by GLSurfaceView, no drawing needed on this canvas.
                // We trigger the render in updateFace().
            } else {
                Log.d("JewelryOverlay", "⚠️ No 3D model available, attempting to reload...")
                // Try to reload the model
                load3DModel(selectedJewelry)
                // Render simple wireframe as placeholder
                renderSimpleWireframe(canvas, face)
            }
        }
    }

    private fun render2DJewelry(canvas: Canvas, face: Face) {
        current2DImage?.let { bitmap ->
            val neckPoints = calculateNeckPoints(face)
            val neckCenter = neckPoints.first

            val leftCheek = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position
            val rightCheek = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position

            if (leftCheek == null || rightCheek == null) return

            val faceWidth = abs(translateX(leftCheek.x) - translateX(rightCheek.x))
            
            // Position jewelry at neck area (below face)
            val jewelryWidth = faceWidth * 1.3f // Make it wider than face for necklace
            val jewelryHeight = jewelryWidth * (bitmap.height.toFloat() / bitmap.width.toFloat()) // Maintain aspect ratio
            
            // Scale the bitmap to appropriate size
            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap, 
                jewelryWidth.toInt(), 
                jewelryHeight.toInt(), 
                true
            )
            
            // Position the jewelry image
            val jewelryLeft = neckCenter.x - jewelryWidth / 2
            val jewelryTop = neckCenter.y - jewelryHeight * 0.3f // Overlap slightly with neck
            
            // Create paint for rendering
            val paint = Paint().apply {
                isAntiAlias = true
                alpha = 230 // Slight transparency for natural look
            }

            // Get head rotation for Z-axis (tilt)
            val headAngle = face.headEulerAngleZ

            // Create a matrix for rotation and translation
            val matrix = Matrix()
            matrix.postTranslate(jewelryLeft, jewelryTop)
            matrix.postRotate(headAngle, neckCenter.x, neckCenter.y)

            // Draw the jewelry image using the matrix
            canvas.drawBitmap(scaledBitmap, matrix, paint)

            Log.d("JewelryOverlay", "🖼️ Drew 2D jewelry at position: (${jewelryLeft.toInt()}, ${jewelryTop.toInt()}) size: ${jewelryWidth.toInt()}x${jewelryHeight.toInt()} rotation: ${headAngle.toInt()}°")
        }
    }

    private fun renderSimpleWireframe(canvas: Canvas, face: Face) {
        // Simple necklace shape directly on neck - no complex 3D calculations
        val paint = Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        
        val neckPoints = calculateNeckPoints(face)
        val centerX = neckPoints.first.x
        val centerY = neckPoints.first.y
        val neckWidth = face.boundingBox.width() * 0.8f
        
        // Draw simple necklace chain around neck
        val chainPoints = mutableListOf<PointF>()
        val numPoints = 20
        
        for (i in 0 until numPoints) {
            val angle = (i.toFloat() / numPoints) * Math.PI * 2
            val radiusX = neckWidth / 2
            val radiusY = neckWidth / 4 // Flatter ellipse for necklace
            
            val x = centerX + (radiusX * Math.cos(angle)).toFloat()
            val y = centerY + (radiusY * Math.sin(angle)).toFloat()
            chainPoints.add(PointF(x, y))
        }
        
        // Draw connected chain links
        for (i in 0 until chainPoints.size) {
            val current = chainPoints[i]
            val next = chainPoints[(i + 1) % chainPoints.size]
            canvas.drawLine(current.x, current.y, next.x, next.y, paint)
        }
        
        Log.d("JewelryOverlay", "🔗 Rendered simple wireframe necklace on neck")
    }
    
    private fun drawNecklace(canvas: Canvas, face: Face) {
        // 3D-only approach: This function is not used as we render via render3DJewelry()
        Log.d("JewelryOverlay", "3D necklace rendering handled by render3DJewelry() function")
    }
}
