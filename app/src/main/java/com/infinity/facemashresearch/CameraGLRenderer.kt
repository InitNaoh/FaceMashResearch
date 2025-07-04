package com.infinity.facemashresearch

import android.R.attr.repeatCount
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.random.Random

class CameraGLRenderer : GLSurfaceView.Renderer {

    var surfaceTexture: SurfaceTexture? = null
        private set

    private var oesTextureId = -1
    private lateinit var quadBuffer: FloatBuffer

    // Landmarks
    private val landmarks = mutableListOf<Pair<Float, Float>>()
    private val landmarkLock = Any()

    // Texture từng vùng
    private val textureIds = mutableMapOf<String, Int>()
    private val textureBoxes = mutableMapOf<String, FloatArray>()
    private var hasAnyFaceTexture = false

    // Game One Index
    private var isPlayGameOne = false
    private var fallingRegionTexId: Int = -1
    private var fallingRegionUVBox: FloatArray? = null
    private var fallingStartTime: Long = -1L
    private var fallingRepeatCount = 0
    private var fallingTotalRepeats = 10
    private var fallingDurationPerFall = 4f // thời gian 1 lần rơi (giây)
    private var fallingOffsetXs: FloatArray = FloatArray(repeatCount) {
        Random.nextFloat() * 1.2f - 0.2f  // kết quả nằm trong [-0.8, 0.8]
    }

    // Game Two Index
    private var isPlayGameTwo = false
    private val regionsToFall = listOf(
        "EYE_BROW_LEFT",
        "EYE_BROW_RIGHT",
        "EYE_LEFT",
        "EYE_RIGHT",
        "NOSE",
        "MOUTH_OUTSIDE"
    )
    private var currentRegionIndex = 0
    private var isRegionFalling = false
    private var regionOffsetYMap = mutableMapOf<String, Float>()
    private var regionStoppedMap = mutableMapOf<String, Boolean>()
    private var regionVisibleMap = mutableMapOf<String, Boolean>()

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

        for (region in regionsToFall) {
            regionOffsetYMap[region] = 2f
            regionStoppedMap[region] = false
            regionVisibleMap[region] = true
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        surfaceTexture?.updateTexImage()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Vẽ preview camera
        GLUtils.drawCameraTexture(oesTextureId, quadBuffer)

        // Vẽ landmarks nếu cần
        synchronized(landmarkLock) {
            if (landmarks.isNotEmpty()) {
                // GLUtils.drawLandmarks(landmarks)
                // GLUtils.drawMouthFilled(landmarks)
            }
        }

        if (hasAnyFaceTexture && isPlayGameTwo) gameTwo()

        if (isPlayGameOne) gameOne()
    }

    fun gameOne() {
        // Vẽ texture rơi xuống
        if (fallingRegionTexId > 0 && fallingRegionUVBox != null && fallingStartTime > 0 && fallingRepeatCount < fallingTotalRepeats) {
            val elapsed = (System.currentTimeMillis() - fallingStartTime) / 1000f
            val t = elapsed % fallingDurationPerFall
            val currentRepeat = (elapsed / fallingDurationPerFall).toInt() + fallingRepeatCount

            if (currentRepeat >= fallingTotalRepeats) {
                fallingRegionTexId = 0
                return
            }

            val fallY = 1f - (t / fallingDurationPerFall) * 2f
            val uv = fallingRegionUVBox!!
            val centerY = (uv[1] + uv[5]) / 2f
            val centerX = (uv[0] + uv[4]) / 2f
            val dy = fallY - centerY

            val offsetX = fallingOffsetXs.getOrElse(currentRepeat) { 0f }
            val dx = offsetX - centerX

            val translatedUV = uv.clone()
            for (i in 0 until translatedUV.size step 2) translatedUV[i] += dx
            for (i in 1 until translatedUV.size step 2) translatedUV[i] += dy

            val x0 = translatedUV[0]
            val y0 = translatedUV[1]
            val x2 = translatedUV[6]
            val y2 = translatedUV[7]
            val fallingBox = RectF(
                x0.coerceAtMost(x2),
                y0.coerceAtMost(y2),
                x0.coerceAtLeast(x2),
                y0.coerceAtLeast(y2)
            )

            val mouthBox = getMouthInsideBounds()

            // Lấy 2 điểm môi trên/dưới
            val topY = landmarks.getOrNull(13)?.second ?: return
            val bottomY = landmarks.getOrNull(14)?.second ?: return
            val mouthOpenDist = kotlin.math.abs(bottomY - topY)
            val isMouthOpen = mouthOpenDist > 0.001f

            if (isMouthOpen && mouthBox != null && RectF.intersects(fallingBox, mouthBox)) {
                fallingRepeatCount = currentRepeat + 1
                Log.d("naoh_debug", "Has score")
                fallingStartTime = System.currentTimeMillis()
                return
            }

            GLUtils.drawMouthTexture(fallingRegionTexId, translatedUV, scaleFactor = 0.7f)
        }
    }

    fun gameTwo() {
        for ((region, texId) in textureIds) {
            val box = textureBoxes[region]
            if (box != null && texId > 0 && regionVisibleMap[region] == true) {
                val uv = box.copyOf()
                val offsetY = regionOffsetYMap[region] ?: 0f
                for (i in 1 until uv.size step 2) {
                    uv[i] += offsetY
                }
                GLUtils.drawMouthTexture(texId, uv, scaleFactor = 0.8f)

                if (region == regionsToFall.getOrNull(currentRegionIndex)) {
                    if (isRegionFalling && regionStoppedMap[region] == false) {
                        regionOffsetYMap[region] = offsetY - 0.01f
                        if (offsetY < -2f) {
                            regionVisibleMap[region] = false
                            isRegionFalling = false
                            currentRegionIndex++
                            startRegionFall()
                        }
                    }
                }
            }
        }
    }

    fun getMouthInsideBounds(): RectF? {
        return try {
            val mouthPoints = GLUtils.MOUTH_INSIDE.mapNotNull { landmarks.getOrNull(it) }
            if (mouthPoints.isEmpty()) return null
            val xs = mouthPoints.map { it.first }
            val ys = mouthPoints.map { it.second }
            return RectF(
                xs.minOrNull() ?: 0f,
                ys.minOrNull() ?: 0f,
                xs.maxOrNull() ?: 0f,
                ys.maxOrNull() ?: 0f
            )
        } catch (_: Exception) {
            null
        }
    }

    fun startGameOneRender(texId: Int, uvBox: FloatArray, level: Float) {
        fallingRegionTexId = texId
        fallingRegionUVBox = uvBox.copyOf()
        fallingStartTime = System.currentTimeMillis()
        fallingDurationPerFall = level
        fallingRepeatCount = 0
        isPlayGameOne = true
        isPlayGameTwo = false
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

    fun clearTextures() {
        for (texId in textureIds.values) {
            GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
        }
        textureIds.clear()
        textureBoxes.clear()
        hasAnyFaceTexture = false
    }

    fun startRegionFall() {
        val region = regionsToFall.getOrNull(currentRegionIndex) ?: return
        isRegionFalling = true
        regionStoppedMap[region] = false
        regionVisibleMap[region] = true
        regionOffsetYMap[region] = 2.0f
        isPlayGameTwo = true
        isPlayGameOne = false
    }

    fun stopCurrentRegionFall() {
        val region = regionsToFall.getOrNull(currentRegionIndex) ?: return
        regionStoppedMap[region] = true
        isRegionFalling = false
        currentRegionIndex++
        startRegionFall()
    }

    fun resetAllRegions() {
        currentRegionIndex = 0
        isRegionFalling = false
        for (region in regionsToFall) {
            regionOffsetYMap[region] = 2.0f
            regionStoppedMap[region] = false
            regionVisibleMap[region] = true
        }
    }

}
