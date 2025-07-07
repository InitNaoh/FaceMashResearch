package com.infinity.facemashresearch

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.*
import android.util.Log
import android.view.Surface
import java.io.File

class GameRecorder(private val width: Int, private val height: Int) {
    private lateinit var encoder: MediaCodec
    private lateinit var inputSurface: Surface
    private lateinit var eglDisplay: EGLDisplay
    private lateinit var eglContext: EGLContext
    private lateinit var eglSurface: EGLSurface
    private lateinit var muxer: MediaMuxer
    private var trackIndex = -1
    private var started = false
    private var isStopped = false

    private val TAG = "naoh_debug"

    fun start(outputFile: File) {
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        encoder = MediaCodec.createEncoderByType("video/avc").apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        setupEGL()
        isStopped = false
        Log.d(TAG, "Recording started at ${outputFile.absolutePath}")
    }

    private fun setupEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val sharedContext = EGL14.eglGetCurrentContext()
        eglContext =
            EGL14.eglCreateContext(eglDisplay, configs[0], sharedContext, contextAttribs, 0)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            configs[0],
            inputSurface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
    }

    fun makeCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun swapBuffers() {
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun feedEncoder() {
        if (!::encoder.isInitialized || isStopped) return

        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (true) {
                val outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputBufferId >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outputBufferId)
                            ?: throw RuntimeException("Encoder output buffer $outputBufferId was null")

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size != 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)

                            if (!started) {
                                val format = encoder.outputFormat
                                trackIndex = muxer.addTrack(format)
                                muxer.start()
                                started = true
                            }

                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }

                        encoder.releaseOutputBuffer(outputBufferId, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "End of stream reached")
                            break
                        }
                    }

                    outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        break
                    }

                    outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (started) throw RuntimeException("Format changed twice")
                        val format = encoder.outputFormat
                        trackIndex = muxer.addTrack(format)
                        muxer.start()
                        started = true
                    }
                }
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "feedEncoder: ${e.message}")
        }
    }

    fun stop() {
        if (!::encoder.isInitialized || isStopped) return
        isStopped = true

        try {
            encoder.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.e(TAG, "signalEndOfInputStream failed: ${e.message}")
        }

        feedEncoder()

        try {
            encoder.stop()
            encoder.release()
        } catch (e: Exception) {
            Log.e(TAG, "encoder.stop/release failed: ${e.message}")
        }

        try {
            if (started) {
                muxer.stop()
                muxer.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "muxer.stop/release failed: ${e.message}")
        }

        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)

        Log.d(TAG, "Recording saved")
    }
}
