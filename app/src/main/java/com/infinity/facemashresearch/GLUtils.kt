package com.infinity.facemashresearch

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import androidx.camera.core.processing.util.GLUtils.createFloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GLUtils {

    val MOUTH = listOf(
        61,
        185,
        40,
        39,
        37,
        0,
        267,
        269,
        270,
        409,
        291,
        375,
        405,
        314,
        17,
        84,
        181,
        91,
        146,
        61
    ) // Miệng trong

    val MOUTH_INSIDE = listOf(
        78,
        191,
        80,
        81,
        82,
        13,
        312,
        311,
        310,
        415,
        308,
        324,
        318,
        402,
        317,
        14,
        87,
        178,
        88,
        95,
        78
    ) // Miệng trong

    val MOUTH_OUTSIDE = listOf(
        212, 202, 204, 194, 201, 200, 421, 418, 424, 422,
        432, 436, 391, 393, 164, 167, 165, 216, 212
    )

    val EYE_BROW_LEFT = listOf(70, 225, 224, 223, 222, 221, 55, 107, 66, 105, 63, 70)

    val EYE_BROW_RIGHT = listOf(336, 296, 334, 293, 300, 276, 445, 444, 443, 442, 441, 285, 336)

    val EYE_LEFT = listOf(113, 30, 29, 27, 28, 56, 190, 243, 232, 231, 230, 229, 228, 31, 35, 113)

    val EYE_RIGHT = listOf(342, 260, 259, 257, 258, 286, 414, 463, 453, 452, 451, 450, 449, 448, 261, 265, 342)

    val NOSE = listOf(168, 122, 217, 126, 203, 167, 164, 393, 423, 355, 437, 351, 168)

    var surfaceTexture: SurfaceTexture? = null

    var isFrontCamera = true

    // Shaders
    private const val VERT_SHADER_OES = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """

    private const val FRAG_SHADER_OES = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """

    private const val VERT_SHADER_LINE = """
        attribute vec2 aPosition;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            gl_PointSize = 8.0;
        }
    """

    private const val FRAG_SHADER_LINE = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """

    private const val VERT_SHADER_TEXTURE = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    private const val FRAG_SHADER_TEXTURE = """
        precision mediump float;
        uniform sampler2D uTexture;
        varying vec2 vTexCoord;
        void main() {
            vec4 color = texture2D(uTexture, vTexCoord);
            if (color.a < 1.0) discard;
            gl_FragColor = color;
        }
    """

    private var cameraProgram = 0
    private var landmarkProgram = 0
    private var textureProgram = 0

    private var aPosCam = 0
    private var aTexCam = 0
    private var uTexCam = 0
    private var uTexMatrix = 0

    private var aPosLine = 0
    private var uColorLine = 0

    private var aPosTex = 0
    private var aTexTex = 0
    private var uTexTex = 0

    fun initShaders() {
        cameraProgram = createProgram(VERT_SHADER_OES, FRAG_SHADER_OES)
        landmarkProgram = createProgram(VERT_SHADER_LINE, FRAG_SHADER_LINE)
        textureProgram = createProgram(VERT_SHADER_TEXTURE, FRAG_SHADER_TEXTURE)

        aPosCam = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        aTexCam = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord")
        uTexCam = GLES20.glGetUniformLocation(cameraProgram, "uTexture")
        uTexMatrix = GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix")

        aPosLine = GLES20.glGetAttribLocation(landmarkProgram, "aPosition")
        uColorLine = GLES20.glGetUniformLocation(landmarkProgram, "uColor")

        aPosTex = GLES20.glGetAttribLocation(textureProgram, "aPosition")
        aTexTex = GLES20.glGetAttribLocation(textureProgram, "aTexCoord")
        uTexTex = GLES20.glGetUniformLocation(textureProgram, "uTexture")
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile error: $log")
        }
        return shader
    }

    private fun createProgram(vs: String, fs: String): Int {
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, loadShader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(program, loadShader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program link error: $log")
        }
        return program
    }

    fun createOESTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    fun createFullScreenQuad(): FloatBuffer {
        val verts = if (isFrontCamera) {
            floatArrayOf(
                -1f, 1f, 1f, 0f,
                -1f, -1f, 1f, 1f,
                1f, 1f, 0f, 0f,
                1f, -1f, 0f, 1f
            )
        } else {
            floatArrayOf(
                -1f, 1f, 0f, 0f,
                -1f, -1f, 0f, 1f,
                1f, 1f, 1f, 0f,
                1f, -1f, 1f, 1f
            )
        }
        return createFloatBuffer(verts)
    }

    fun drawCameraTexture(texId: Int, buffer: FloatBuffer) {
        GLES20.glUseProgram(cameraProgram)

        buffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosCam)
        GLES20.glVertexAttribPointer(aPosCam, 2, GLES20.GL_FLOAT, false, 16, buffer)

        buffer.position(2)
        GLES20.glEnableVertexAttribArray(aTexCam)
        GLES20.glVertexAttribPointer(aTexCam, 2, GLES20.GL_FLOAT, false, 16, buffer)

        val texMatrix = FloatArray(16)
        surfaceTexture?.getTransformMatrix(texMatrix)

        val flipX = floatArrayOf(
            -1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 0f, 0f, 1f
        )
        val flipY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f
        )
        val temp = FloatArray(16)

        if (isFrontCamera) {
            multiplyMatrix(texMatrix, flipX, temp)
            multiplyMatrix(temp, flipY, texMatrix)
        } else {
            multiplyMatrix(texMatrix, flipY, texMatrix)
        }

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTexCam, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosCam)
        GLES20.glDisableVertexAttribArray(aTexCam)
    }

    fun drawLandmarks(points: List<Pair<Float, Float>>) {
        if (points.isEmpty()) return

        val fb = createFloatBuffer(points.flatMap { listOf(it.first, it.second) }.toFloatArray())
        GLES20.glUseProgram(landmarkProgram)
        GLES20.glUniform4f(uColorLine, 1f, 0f, 0f, 1f)
        GLES20.glEnableVertexAttribArray(aPosLine)
        GLES20.glVertexAttribPointer(aPosLine, 2, GLES20.GL_FLOAT, false, 8, fb)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, points.size)
        GLES20.glDisableVertexAttribArray(aPosLine)
    }

    fun drawMouthFilled(points: List<Pair<Float, Float>>) {
        val mouthPoints = MOUTH_OUTSIDE.mapNotNull { points.getOrNull(it) }
        if (mouthPoints.size < 3) return

        val fb = createFloatBuffer(mouthPoints.flatMap { listOf(it.first, it.second) }.toFloatArray())
        GLES20.glUseProgram(landmarkProgram)
        GLES20.glUniform4f(uColorLine, 1f, 0f, 0f, 0.4f)
        GLES20.glEnableVertexAttribArray(aPosLine)
        GLES20.glVertexAttribPointer(aPosLine, 2, GLES20.GL_FLOAT, false, 8, fb)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, mouthPoints.size)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(aPosLine)
    }

    fun drawMouthTexture(texId: Int, box: FloatArray, scaleFactor: Float = 1.3f) {
        if (texId <= 0 || box.size < 8) return

        val centerX = (box[0] + box[4]) / 2f
        val centerY = (box[1] + box[5]) / 2f

        fun scale(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - centerX
            val dy = y - centerY
            return Pair(centerX + dx * scaleFactor, centerY + dy * scaleFactor)
        }

        val (x0, y0) = scale(box[0], box[1])
        val (x1, y1) = scale(box[2], box[3])
        val (x2, y2) = scale(box[4], box[5])
        val (x3, y3) = scale(box[6], box[7])

        val quad = floatArrayOf(
            x0, y0, 0f, 0f,
            x1, y1, 1f, 0f,
            x3, y3, 0f, 1f,
            x2, y2, 1f, 1f
        )
        val buffer = createFloatBuffer(quad)

        GLES20.glUseProgram(textureProgram)

        buffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosTex)
        GLES20.glVertexAttribPointer(aPosTex, 2, GLES20.GL_FLOAT, false, 16, buffer)

        buffer.position(2)
        GLES20.glEnableVertexAttribArray(aTexTex)
        GLES20.glVertexAttribPointer(aTexTex, 2, GLES20.GL_FLOAT, false, 16, buffer)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(
            GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTexTex, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosTex)
        GLES20.glDisableVertexAttribArray(aTexTex)
        GLES20.glDisable(GLES20.GL_BLEND)
    }


    fun loadTexture(bitmap: Bitmap): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return textureIds[0]
    }

    private fun multiplyMatrix(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (i in 0..3) {
            for (j in 0..3) {
                out[i * 4 + j] =
                    a[i * 4 + 0] * b[0 * 4 + j] +
                            a[i * 4 + 1] * b[1 * 4 + j] +
                            a[i * 4 + 2] * b[2 * 4 + j] +
                            a[i * 4 + 3] * b[3 * 4 + j]
            }
        }
    }

    fun removeBorder(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = 0
        var maxY = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val alpha = (pixels[y * w + x] shr 24) and 0xff
                if (alpha > 10) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (minX >= maxX || minY >= maxY) return bitmap
        return Bitmap.createBitmap(bitmap, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    fun applyRadialFade(bitmap: Bitmap, strength: Float = 3.0f): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val centerX = w / 2f
        val centerY = h / 2f
        val maxRadius = Math.hypot(centerX.toDouble(), centerY.toDouble()).toFloat()

        val canvas = Canvas(result)

        // Vẽ ảnh gốc
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        // Tạo radial gradient alpha
        val gradientPaint = Paint().apply {
            isAntiAlias = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = android.graphics.RadialGradient(
                centerX, centerY, maxRadius * strength,
                intArrayOf(0xFFFFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0.6f, 1.0f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }

        // Vẽ mặt nạ alpha
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), gradientPaint)

        return result
    }



}
