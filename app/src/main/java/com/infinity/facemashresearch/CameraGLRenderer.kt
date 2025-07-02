package com.infinity.facemashresearch

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.nio.FloatBuffer

class CameraGLRenderer : GLSurfaceView.Renderer {

    var surfaceTexture: SurfaceTexture? = null
        private set

    private var oesTextureId = -1
    private lateinit var quadBuffer: FloatBuffer

    // Landmarks (có thể dùng để vẽ outline miệng/mắt nếu cần)
    private val landmarks = mutableListOf<Pair<Float, Float>>()
    private val landmarkLock = Any()

    // Texture từng vùng
    private val textureIds = mutableMapOf<String, Int>()         // regionName -> textureId
    private val textureBoxes = mutableMapOf<String, FloatArray>() // regionName -> box UV
    private var hasAnyFaceTexture = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        oesTextureId = GLUtils.createOESTexture()
        surfaceTexture = SurfaceTexture(oesTextureId)
        GLUtils.surfaceTexture = surfaceTexture
        GLUtils.initShaders()
        quadBuffer = GLUtils.createFullScreenQuad()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        surfaceTexture?.updateTexImage()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Vẽ preview camera
        GLUtils.drawCameraTexture(oesTextureId, quadBuffer)

        // Vẽ landmarks nếu cần
        synchronized(landmarkLock) {
            if (landmarks.isNotEmpty()) {
//                GLUtils.drawLandmarks(landmarks)
                // GLUtils.drawMouthFilled(landmarks) // nếu bạn vẫn muốn tô vùng miệng
            }
        }

        // Vẽ tất cả texture khuôn mặt (miệng, mắt, mũi...)
        if (hasAnyFaceTexture) {
            for ((region, texId) in textureIds) {
                val box = textureBoxes[region]
                if (box != null && texId > 0) {
                    GLUtils.drawMouthTexture(texId, box)
                }
            }
        }
    }

    fun updateLandmarks(points: List<Pair<Float, Float>>) {
        synchronized(landmarkLock) {
            landmarks.clear()
            landmarks.addAll(points)
        }
    }

    fun setRegionTexture(region: String, bitmap: Bitmap, box: FloatArray) {
        textureIds[region]?.let { oldId ->
            GLES20.glDeleteTextures(1, intArrayOf(oldId), 0)
        }
        val newTexId = GLUtils.loadTexture(bitmap)
        textureIds[region] = newTexId
        textureBoxes[region] = box
        hasAnyFaceTexture = true
    }

    fun setMouthBitmap(bitmap: Bitmap, box: FloatArray) {
//        if (mouthTextureId != -1) {
//            val textures = IntArray(1)
//            textures[0] = mouthTextureId
//            GLES20.glDeleteTextures(1, textures, 0)
//        }
//
//        mouthTextureId = GLUtils.loadTexture(bitmap)
//        mouthBox = box
//        hasFaceTexture = true
    }
}
