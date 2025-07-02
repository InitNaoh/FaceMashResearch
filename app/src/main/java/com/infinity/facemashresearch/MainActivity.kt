package com.infinity.facemashresearch

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.infinity.facemashresearch.GLUtils.applyRadialFade
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.graphics.createBitmap
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

class MainActivity : AppCompatActivity(), FaceLandmarkerHelper.LandmarkerListener {
    private lateinit var cameraGLView: CameraGLView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private lateinit var backgroundExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null

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
                currentDelegate = FaceLandmarkerHelper.DELEGATE_CPU,
                faceLandmarkerHelperListener = this
            )
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == 101 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else Toast.makeText(this, "Camera permission needed", Toast.LENGTH_LONG).show()
    }

    private fun startCamera() {
        val surfaceTexture = cameraGLView.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(cameraGLView.width, cameraGLView.height)

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider { request ->
                val surface = Surface(surfaceTexture)
                request.provideSurface(surface, cameraExecutor) { }
            }
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetResolution(Size(cameraGLView.width, cameraGLView.height))
                .build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        detectFace(image)
                    }
                }

            val surfaceTexture = cameraGLView.surfaceTexture ?: return@addListener
            surfaceTexture.setDefaultBufferSize(cameraGLView.width, cameraGLView.height)
            val surface = Surface(surfaceTexture)

            preview.setSurfaceProvider { request ->
                request.provideSurface(surface, cameraExecutor) {}
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)

        }, ContextCompat.getMainExecutor(this))
    }

    private fun detectFace(imageProxy: ImageProxy) {
        faceLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = true
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onError(error: String, errorCode: Int) {
        Log.e("FaceLandmarker", error)
    }

    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        val landmarks = resultBundle.result.faceLandmarks()[0].map {
            Pair(it.x() * 2 - 1, 1 - it.y() * 2)
        }
        cameraGLView.updateLandmarks(landmarks)

        val bitmap = resultBundle.inputBitmap ?: return
        val landmarkList = resultBundle.result.faceLandmarks()[0]

        handleRegion("MOUTH", GLUtils.MOUTH_OUTSIDE, landmarkList, bitmap)
        handleRegion("EYE_LEFT", GLUtils.EYE_LEFT, landmarkList, bitmap)
        handleRegion("EYE_RIGHT", GLUtils.EYE_RIGHT, landmarkList, bitmap)
        handleRegion("NOSE", GLUtils.NOSE, landmarkList, bitmap)
    }

    override fun onEmpty() {
        // Không tìm thấy khuôn mặt
        runOnUiThread {
            cameraGLView.clearTextures()
        }
    }

    private fun handleRegion(name: String, indices: List<Int>, landmarkList: List<NormalizedLandmark>, bitmap: Bitmap) {
        val path = Path()
        val points = indices.map {
            val x = landmarkList[it].x() * bitmap.width
            val y = landmarkList[it].y() * bitmap.height
            android.graphics.PointF(x, y)
        }
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
            bitmap,
            Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()),
            Rect(0, 0, croppedW, croppedH),
            paint
        )

        val result = GLUtils.applyRadialFade(cropped)

        val uvBox = floatArrayOf(
            bounds.left / bitmap.width * 2 - 1,
            1 - bounds.top / bitmap.height * 2,
            bounds.right / bitmap.width * 2 - 1,
            1 - bounds.top / bitmap.height * 2,
            bounds.right / bitmap.width * 2 - 1,
            1 - bounds.bottom / bitmap.height * 2,
            bounds.left / bitmap.width * 2 - 1,
            1 - bounds.bottom / bitmap.height * 2
        )

        cameraGLView.updateRegionTexture(name, result, uvBox)
    }

}