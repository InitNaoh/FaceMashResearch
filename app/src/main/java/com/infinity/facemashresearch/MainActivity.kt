package com.infinity.facemashresearch

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), FaceLandmarkerHelper.LandmarkerListener {
    private lateinit var cameraGLView: CameraGLView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private lateinit var backgroundExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null

    private var mouthBitmap: Bitmap? = null

    private var mouthUVBox: FloatArray? = null

    private lateinit var btnStart: Button

    private lateinit var btnStart2: Button

    private lateinit var btnStart3: Button

    private var gamePlay = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cameraGLView = findViewById(R.id.cameraGLView)
        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraGLView.post { startCamera() }

        backgroundExecutor = Executors.newSingleThreadExecutor()
        backgroundExecutor.execute {
            faceLandmarkerHelper = FaceLandmarkerHelper(
                context = this,
                runningMode = RunningMode.LIVE_STREAM,
                minFaceDetectionConfidence = 0.1f,
                minFaceTrackingConfidence = 0.1f,
                minFacePresenceConfidence = 0.1f,
                maxNumFaces = 1,
                currentDelegate = FaceLandmarkerHelper.DELEGATE_GPU,
                faceLandmarkerHelperListener = this
            )
        }

        btnStart = findViewById<Button>(R.id.btnStart)
        btnStart.setOnClickListener {
            mouthBitmap?.let { bmp ->
                mouthUVBox?.let { box ->
                    Log.d("naoh_debug", "onCreate: btnStart click")
                    cameraGLView.startGameOne(bmp, box, 4f)
                }
            }
        }

        btnStart2 = findViewById<Button>(R.id.btnStart2)
        btnStart2.setOnClickListener {
            gamePlay = 2
            cameraGLView.startGameTwo()
        }

        btnStart3 = findViewById<Button>(R.id.btnStart3)
        btnStart3.setOnClickListener {
            gamePlay = 3
            cameraGLView.startGameThree(4000L)
        }

        cameraGLView.setOnClickListener {
            if (gamePlay == 2) cameraGLView.stopStepGameTwo()
            if (gamePlay == 3) cameraGLView.stopStepGameThree()
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, perms: Array<out String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == 101 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else Toast.makeText(this, "Camera permission needed", Toast.LENGTH_LONG).show()
    }

    private fun startCamera() {
        val surfaceTexture = cameraGLView.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(cameraGLView.width, cameraGLView.height)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview =
                Preview.Builder().setTargetResolution(Size(cameraGLView.width, cameraGLView.height))
                    .build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build().also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        detectFace(image)
                    }
                }


            val surface = Surface(surfaceTexture)
            var width = 1920
            var height = 1080
            preview.setSurfaceProvider { request ->
                val resolution = request.resolution
                width = resolution.width
                height = resolution.height
                Log.d("naoh_debug", "resolution: $width x $height")
                request.provideSurface(surface, cameraExecutor) {}
            }

            val surfaceTexture = cameraGLView.surfaceTexture ?: return@addListener
            Log.d("naoh_debug", "setDefaultBufferSize: $width x $height")
            surfaceTexture.setDefaultBufferSize(width, height)

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)

        }, ContextCompat.getMainExecutor(this))
    }

    private fun detectFace(imageProxy: ImageProxy) {
        faceLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy, isFrontCamera = true
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onError(error: String, errorCode: Int) {
        Log.e("FaceLandmarker", error)
    }

    // Thêm biến lưu vùng MOUTH


    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        val imageW = resultBundle.inputImageWidth
        val imageH = resultBundle.inputImageHeight
        val surfaceW = cameraGLView.width
        val surfaceH = cameraGLView.height

        val scale = surfaceH / imageH
        val scaledImageW = imageW * scale
        val dx = (scaledImageW - surfaceW) / 2f

        val landmarks = resultBundle.result.faceLandmarks()[0].map {
            val px = it.x() * imageW
            val py = it.y() * imageH

            val sx = px * scale - dx
            val sy = py * scale

            val ndcX = sx / surfaceW * 2f - 1f
            val ndcY = 1f - sy / surfaceH * 2f

            Pair(ndcX, ndcY)
        }
        cameraGLView.updateLandmarks(landmarks)

        val bitmap = resultBundle.inputBitmap ?: return
        val landmarkList = resultBundle.result.faceLandmarks()[0]

        handleRegion("MOUTH", GLUtils.MOUTH, landmarkList, bitmap)
        if (gamePlay == 1) handleRegion(
            "MOUTH_OUTSIDE",
            GLUtils.MOUTH_OUTSIDE,
            landmarkList,
            bitmap
        )
        handleRegion("EYE_LEFT", GLUtils.EYE_LEFT, landmarkList, bitmap)
        handleRegion("EYE_RIGHT", GLUtils.EYE_RIGHT, landmarkList, bitmap)
        handleRegion("NOSE", GLUtils.NOSE, landmarkList, bitmap)
        handleRegion("EYE_BROW_LEFT", GLUtils.EYE_BROW_LEFT, landmarkList, bitmap)
        handleRegion("EYE_BROW_RIGHT", GLUtils.EYE_BROW_RIGHT, landmarkList, bitmap)

        handleRegion("FULL_FACE", GLUtils.FULL_FACE, landmarkList, bitmap)
    }

    override fun onEmpty() {
        // Không tìm thấy khuôn mặt
        runOnUiThread {
            cameraGLView.clearTextures()
        }
    }

    private fun handleRegion(
        name: String, indices: List<Int>, landmarkList: List<NormalizedLandmark>, bitmap: Bitmap
    ) {
        val points = indices.map {
            val x = landmarkList[it].x() * bitmap.width
            val y = landmarkList[it].y() * bitmap.height
            PointF(x, y)
        }
        val path = createSmoothPath(points)
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        path.close()

        val bounds = RectF()
        path.computeBounds(bounds, true)

        val croppedW = bounds.width().toInt().coerceAtLeast(1)
        val croppedH = bounds.height().toInt().coerceAtLeast(1)
        val cropped = createBitmap(croppedW, croppedH)
        val canvas = Canvas(cropped)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val offsetPath = Path(path)
        offsetPath.offset(-bounds.left, -bounds.top)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            isDither = true
            isFilterBitmap = true
        }
        canvas.drawPath(offsetPath, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(
            bitmap, Rect(
                bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()
            ), Rect(0, 0, croppedW, croppedH), paint
        )

        val result = GLUtils.applyRadialFade(cropped)

        val uvBox = convertBoxToNDC(
            bounds, bitmap.width, bitmap.height, cameraGLView.width, cameraGLView.height
        )

        if (name == "MOUTH") {
            mouthBitmap = result
            mouthUVBox = uvBox
        }

        if (name == "FULL_FACE") {
            val skinColor = Color.rgb(230, 200, 170)
            val shouldUpdate = lastFullFaceUVBox == null || lastFullFaceUVBox!!.zip(uvBox)
                .any { (a, b) -> kotlin.math.abs(a - b) > 0.01f }
            if (shouldUpdate) {
                lastFullFaceUVBox = uvBox
                val maskBitmap = createSkinMask(bounds, path, skinColor)
                lastFullFaceBitmap = maskBitmap
                cameraGLView.updateRegionTexture("FULL_FACE", maskBitmap, uvBox)
            } else {
                lastFullFaceBitmap?.let { cached ->
                    cameraGLView.updateRegionTexture("FULL_FACE", cached, uvBox)
                }
            }
        } else {
            cameraGLView.updateRegionTexture(name, result, uvBox)
        }
    }

    fun createSmoothPath(points: List<PointF>): Path {
        val path = Path()
        if (points.size < 2) return path

        path.moveTo(points[0].x, points[0].y)

        for (i in 1 until points.size - 2) {
            val p0 = points[i - 1]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[i + 2]

            val cp1x = p1.x + (p2.x - p0.x) / 6f
            val cp1y = p1.y + (p2.y - p0.y) / 6f
            val cp2x = p2.x - (p3.x - p1.x) / 6f
            val cp2y = p2.y - (p3.y - p1.y) / 6f

            path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
        }

        // nối đoạn cuối
        path.lineTo(points.last().x, points.last().y)
        path.close()
        return path
    }


    fun convertBoxToNDC(
        bounds: RectF, imageW: Int, imageH: Int, surfaceW: Int, surfaceH: Int
    ): FloatArray {
        val scale = surfaceH.toFloat() / imageH
        val scaledImageW = imageW * scale
        val dx = (scaledImageW - surfaceW) / 2f

        fun map(x: Float, y: Float): Pair<Float, Float> {
            val sx = x * scale - dx
            val sy = y * scale
            val ndcX = sx / surfaceW * 2f - 1f
            val ndcY = 1f - sy / surfaceH * 2f
            return Pair(ndcX, ndcY)
        }

        val (x0, y0) = map(bounds.left, bounds.top)
        val (x1, y1) = map(bounds.right, bounds.top)
        val (x2, y2) = map(bounds.right, bounds.bottom)
        val (x3, y3) = map(bounds.left, bounds.bottom)

        return floatArrayOf(x0, y0, x1, y1, x2, y2, x3, y3)
    }

    private var lastFullFaceBitmap: Bitmap? = null

    private var lastFullFaceUVBox: FloatArray? = null

    fun createSkinMask(bounds: RectF, path: Path, color: Int): Bitmap {
        val width = bounds.width().toInt().coerceAtLeast(1)
        val height = bounds.height().toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
            alpha = 255 // mềm cạnh
        }
        val offsetPath = Path(path)
        offsetPath.offset(-bounds.left, -bounds.top)
        canvas.drawPath(offsetPath, paint)

        return bitmap
    }


}