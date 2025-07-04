package com.infinity.facemashresearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.AttributeSet

class CameraGLView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {
    private val renderer: CameraGLRenderer
    val surfaceTexture: SurfaceTexture?
        get() = renderer.surfaceTexture

    init {
//        setEGLConfigChooser(AntiAliasedConfigChooser())
        setEGLContextClientVersion(2)
        renderer = CameraGLRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun updateLandmarks(points: List<Pair<Float, Float>>) = renderer.updateLandmarks(points)

    fun updateRegionTexture(region: String, bitmap: Bitmap, box: FloatArray) {
        queueEvent {
            renderer.setRegionTexture(region, bitmap, box)
        }
    }

    fun clearTextures() {
        queueEvent {
            renderer.clearTextures()
        }
    }

    fun startGameOne(bitmap: Bitmap, uvBox: FloatArray, level: Float) {
        queueEvent {
            val texId = GLUtils.loadTexture(bitmap)
            renderer.startGameOne(texId, uvBox, level)
        }
    }

    fun startGameTwo() {
        queueEvent {
            renderer.resetGameTwoIndex()
            renderer.startGameTwoRender()
        }
    }

    fun stopStepGameTwo() {
        queueEvent {
            renderer.stopStepGameTwo()
        }
    }

    fun startGameThree(level: Long) {
        queueEvent {
            renderer.startGameThree()
        }
    }

    fun stopStepGameThree() {
        queueEvent {
            renderer.stopStepGameThree()
        }
    }
}
