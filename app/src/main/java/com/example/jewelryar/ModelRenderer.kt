package com.example.jewelryar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ModelRenderer(
    private val context: Context,
    private var model: Model3D
) : GLSurfaceView.Renderer {

    private var shaderProgram: Int = 0
    private var positionHandle: Int = 0
    private var normalHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var modelViewMatrixHandle: Int = 0
    private var lightPosHandle: Int = 0

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var normalBuffer: FloatBuffer
    private lateinit var indexBuffer: ShortBuffer

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)

    var rotationX = 0f
    var rotationY = 0f
    var scale = 1.0f

    private val lightPosInWorldSpace = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
    private val lightPosInEyeSpace = FloatArray(4)

    private val vertexShaderCode = """
        uniform mat4 u_MVPMatrix;
        uniform mat4 u_MVMatrix;
        attribute vec4 a_Position;
        attribute vec3 a_Normal;
        varying vec3 v_Position;
        varying vec3 v_Normal;
        
        void main() {
            v_Position = vec3(u_MVMatrix * a_Position);
            v_Normal = vec3(u_MVMatrix * vec4(a_Normal, 0.0));
            gl_Position = u_MVPMatrix * a_Position;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        uniform vec3 u_LightPos;
        varying vec3 v_Position;
        varying vec3 v_Normal;
        
        void main() {
            float distance = length(u_LightPos - v_Position);
            vec3 lightVector = normalize(u_LightPos - v_Position);
            float diffuse = max(dot(v_Normal, lightVector), 0.1);
            
            float ambient = 0.3;
            float specular = 0.0;
            
            // Simple specular highlight
            vec3 reflectVector = reflect(-lightVector, v_Normal);
            vec3 viewVector = normalize(-v_Position);
            float specAngle = max(dot(reflectVector, viewVector), 0.0);
            specular = pow(specAngle, 16.0);

            vec4 color = vec4(0.8, 0.8, 0.0, 1.0); // Gold-like color
            gl_FragColor = (diffuse + ambient) * color + vec4(1.0, 1.0, 1.0, 1.0) * specular;
        }
    """.trimIndent()

    init {
        // Sanity check the model data upon initialization
        if (model.vertices.isEmpty() || model.indices.isEmpty()) {
            Log.e("ModelRenderer", "FATAL: Model is invalid. Vertices or indices are empty.")
            // This is a critical error, the renderer will likely fail.
        } else {
            Log.d("ModelRenderer", "Model received. Vertices: ${model.vertexCount}, Indices: ${model.indices.size}")
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d("ModelRenderer", "onSurfaceCreated")
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f) // Transparent background
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)

        try {
            setupBuffers()
            setupShaders()
        } catch (e: Exception) {
            Log.e("ModelRenderer", "FATAL: Exception during setup: ${e.message}", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d("ModelRenderer", "onSurfaceChanged: ${width}x${height}")
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.toFloat()
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 10f)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Don't draw if the model is invalid
        if (model.vertices.isEmpty() || model.indices.isEmpty()) {
            Log.w("ModelRenderer", "⚠️ Model is invalid - vertices: ${model.vertices.size}, indices: ${model.indices.size}")
            return
        }

        Log.d("ModelRenderer", "🎨 Drawing frame - vertices: ${model.vertices.size}, indices: ${model.indices.size}")
        Log.d("ModelRenderer", "🎨 Current scale: $scale, rotation: ($rotationX, $rotationY)")
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(shaderProgram)

        setupMatrices()
        setupLighting()

        // Pass in the position information
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        // Pass in the normal information
        normalBuffer.position(0)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, normalBuffer)
        GLES20.glEnableVertexAttribArray(normalHandle)

        // Draw the model
        Log.d("ModelRenderer", "🎨 Drawing ${model.indices.size / 3} triangles")
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, model.indices.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        Log.d("ModelRenderer", "🎨 Draw call completed")

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
    }

    private fun setupBuffers() {
        Log.d("ModelRenderer", "Setting up buffers...")
        if (model.vertices.isEmpty() || model.indices.isEmpty()) {
            Log.e("ModelRenderer", "Cannot setup buffers, model data is empty.")
            return
        }

        vertexBuffer = ByteBuffer.allocateDirect(model.vertices.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(model.vertices)
                position(0)
            }
        }
        Log.d("ModelRenderer", "Vertex buffer created with capacity: ${vertexBuffer.capacity()}")

        normalBuffer = ByteBuffer.allocateDirect(model.normals.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(model.normals)
                position(0)
            }
        }
        Log.d("ModelRenderer", "Normal buffer created with capacity: ${normalBuffer.capacity()}")

        indexBuffer = ByteBuffer.allocateDirect(model.indices.size * 2).run {
            order(ByteOrder.nativeOrder())
            asShortBuffer().apply {
                put(model.indices)
                position(0)
            }
        }
        Log.d("ModelRenderer", "Index buffer created with capacity: ${indexBuffer.capacity()}")
        Log.d("ModelRenderer", "Buffers setup complete.")
    }

    /**
     * Update the currently rendered model. Must be called on the GL thread
     * (e.g., via GLSurfaceView.queueEvent { renderer.updateModel(newModel) }).
     */
    fun updateModel(newModel: Model3D) {
        Log.d("ModelRenderer", "Updating model: vertices=${newModel.vertexCount} indices=${newModel.indices.size}")
        this.model = newModel
        // Recreate client-side buffers for the new model
        setupBuffers()
    }

    private fun setupShaders() {
        Log.d("ModelRenderer", "Setting up shaders...")
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        if (vertexShader == 0 || fragmentShader == 0) {
            Log.e("ModelRenderer", "Shader creation failed. Aborting setup.")
            return
        }

        shaderProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)

            // Check for linking errors
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val info = GLES20.glGetProgramInfoLog(it)
                Log.e("ModelRenderer", "Shader program linking failed: $info")
                GLES20.glDeleteProgram(it)
                shaderProgram = 0
            }
        }

        if (shaderProgram == 0) {
            Log.e("ModelRenderer", "Shader program is invalid.")
            return
        }

        positionHandle = GLES20.glGetAttribLocation(shaderProgram, "a_Position")
        normalHandle = GLES20.glGetAttribLocation(shaderProgram, "a_Normal")
        mvpMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "u_MVPMatrix")
        modelViewMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "u_MVMatrix")
        lightPosHandle = GLES20.glGetUniformLocation(shaderProgram, "u_LightPos")
        Log.d("ModelRenderer", "Shader setup complete. Program ID: $shaderProgram")
    }

    private fun setupMatrices() {
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 2.5f, 0f, 0f, 0f, 0f, 1f, 0f)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.scaleM(modelMatrix, 0, scale, scale, scale)
        Matrix.rotateM(modelMatrix, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotationY, 0f, 1f, 0f)

        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(modelViewMatrixHandle, 1, false, modelViewMatrix, 0)
    }

    private fun setupLighting() {
        Matrix.multiplyMV(lightPosInEyeSpace, 0, viewMatrix, 0, lightPosInWorldSpace, 0)
        GLES20.glUniform3f(lightPosHandle, lightPosInEyeSpace[0], lightPosInEyeSpace[1], lightPosInEyeSpace[2])
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)

            // Check for compile errors
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val info = GLES20.glGetShaderInfoLog(shader)
                Log.e("ModelRenderer", "Shader compile error: $info")
                GLES20.glDeleteShader(shader)
                return 0 // Return 0 on failure
            }
        }
    }
}
