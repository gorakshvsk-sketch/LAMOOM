package com.aiprocamera

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var exposureLabel: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var exposureSeek: SeekBar
    private lateinit var captureButton: Button
    private lateinit var flashButton: Button
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var frontCamera = false
    private var flashEnabled = false
    private var videoMode = false
    private var currentMode = "PHOTO"
    private var minZoom = 1f
    private var maxZoom = 1f
    private var minExposure = 0
    private var maxExposure = 0
    private var updatingZoom = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cameraExecutor = Executors.newSingleThreadExecutor()
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        zoomLabel = findViewById(R.id.zoomLabel)
        exposureLabel = findViewById(R.id.exposureLabel)
        zoomSeek = findViewById(R.id.zoomSeek)
        exposureSeek = findViewById(R.id.exposureSeek)
        captureButton = findViewById(R.id.captureButton)
        flashButton = findViewById(R.id.flashButton)

        findViewById<Button>(R.id.switchButton).setOnClickListener {
            if (activeRecording != null) { toast("Stop recording before switching cameras"); return@setOnClickListener }
            frontCamera = !frontCamera
            bindCamera()
        }
        flashButton.setOnClickListener {
            flashEnabled = !flashEnabled
            flashButton.text = if (flashEnabled) "FLASH ON" else "FLASH OFF"
            imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            statusText.text = "Flash ${if (flashEnabled) "enabled" else "disabled"} where supported"
        }
        captureButton.setOnClickListener { if (videoMode) toggleVideo() else takePhoto() }
        findViewById<Button>(R.id.photoMode).setOnClickListener { setMode("PHOTO") }
        findViewById<Button>(R.id.videoMode).setOnClickListener { setMode("VIDEO") }
        findViewById<Button>(R.id.portraitMode).setOnClickListener { setMode("PORTRAIT") }
        findViewById<Button>(R.id.nightMode).setOnClickListener { setMode("NIGHT") }
        findViewById<Button>(R.id.moonMode).setOnClickListener { setMode("MOON") }
        findViewById<Button>(R.id.galleryButton).setOnClickListener { openGallery() }
        findViewById<Button>(R.id.aiButton).setOnClickListener {
            toast("AI enhancement is not included in this build; this button opens the latest photo in the gallery.")
            openGallery()
        }
        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingZoom) return
                val ratio = minZoom + (maxZoom - minZoom) * (progress / 1000f)
                camera?.cameraControl?.setZoomRatio(ratio)
                zoomLabel.text = "ZOOM ${String.format(Locale.US, "%.1f", ratio)}×"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        exposureSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val step = progress + minExposure
                val state = camera?.cameraInfo?.exposureState
                val range = state?.exposureCompensationRange
                if (fromUser && range != null) {
                    val clamped = step.coerceIn(range.lower, range.upper)
                    camera?.cameraControl?.setExposureCompensationIndex(clamped)
                    exposureLabel.text = "EXPOSURE ${if (clamped > 0) "+" else ""}$clamped EV steps"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        bindCamera()
    }

    private fun setMode(mode: String) {
        currentMode = mode
        videoMode = mode == "VIDEO"
        captureButton.contentDescription = if (videoMode) "Start or stop video recording" else "Capture photo"
        statusText.text = when (mode) {
            "MOON" -> "MOON mode: zoom/exposure assist; use a steady support for best results"
            "NIGHT" -> "NIGHT mode: lower exposure to protect highlights; no multi-frame stacking"
            "PORTRAIT" -> "PORTRAIT mode: standard camera capture; depth lighting depends on device support"
            "VIDEO" -> "VIDEO mode: tap the shutter to start, tap again to stop"
            else -> "PHOTO mode ready"
        }
        if (mode == "MOON") {
            val target = min(30f, maxZoom)
            if (target > minZoom) camera?.cameraControl?.setZoomRatio(target)
            camera?.cameraControl?.setExposureCompensationIndex(minExposure.coerceAtLeast(-2))
            syncZoomUi(target)
        } else if (mode == "NIGHT") {
            camera?.cameraControl?.setExposureCompensationIndex(minExposure.coerceAtLeast(-1))
        } else if (mode == "PHOTO" || mode == "PORTRAIT") {
            camera?.cameraControl?.setExposureCompensationIndex(0.coerceIn(minExposure, maxExposure))
        }
    }

    private fun bindCamera() {
        statusText.text = "Connecting to camera…"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val selector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) {
                    frontCamera = false
                    if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        statusText.text = "No compatible camera was found on this device"
                        return@addListener
                    }
                }
                provider.unbindAll()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                    .build()
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()
                val video = VideoCapture.withOutput(recorder)
                camera = provider.bindToLifecycle(this, selector, preview, capture, video)
                imageCapture = capture
                videoCapture = video
                val zoom = camera!!.cameraInfo.zoomState.value
                minZoom = max(1f, zoom?.minZoomRatio ?: 1f)
                maxZoom = max(minZoom, zoom?.maxZoomRatio ?: 1f)
                val exposure = camera!!.cameraInfo.exposureState
                minExposure = exposure.exposureCompensationRange.lower
                maxExposure = exposure.exposureCompensationRange.upper
                exposureSeek.max = max(0, maxExposure - minExposure)
                exposureSeek.progress = (0 - minExposure).coerceIn(0, exposureSeek.max)
                updatingZoom = true
                zoomSeek.progress = 0
                updatingZoom = false
                syncZoomUi(minZoom)
                statusText.text = "${if (frontCamera) "Front" else "Rear"} camera ready • ${String.format(Locale.US, "%.1f", maxZoom)}× device zoom range"
                if (currentMode == "MOON") setMode("MOON")
            } catch (e: Exception) {
                statusText.text = "Camera setup failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun syncZoomUi(ratio: Float) {
        val fraction = if (maxZoom > minZoom) ((ratio - minZoom) / (maxZoom - minZoom)).coerceIn(0f, 1f) else 0f
        updatingZoom = true
        zoomSeek.progress = (fraction * 1000).toInt()
        updatingZoom = false
        zoomLabel.text = "ZOOM ${String.format(Locale.US, "%.1f", ratio)}×"
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run { toast("Camera is not ready yet"); return }
        val name = "AIPRO_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AIProCamera")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        statusText.text = "Capturing…"
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                statusText.text = "Photo saved to Pictures/AIProCamera"
                toast("Photo saved")
            }
            override fun onError(exception: ImageCaptureException) {
                statusText.text = "Photo failed: ${exception.message ?: "unknown error"}"
            }
        })
    }

    private fun toggleVideo() {
        if (activeRecording != null) {
            activeRecording?.stop()
            activeRecording = null
            captureButton.text = ""
            statusText.text = "Finishing video…"
            return
        }
        val capture = videoCapture ?: run { toast("Video camera is not ready"); return }
        val name = "AIPRO_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AIProCamera")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        try {
            activeRecording = capture.output.prepareRecording(this, output).start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        captureButton.text = "■"
                        statusText.text = "Recording video… tap shutter to stop"
                    }
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        captureButton.text = ""
                        statusText.text = if (event.hasError()) "Video failed: ${event.error}" else "Video saved to Movies/AIProCamera"
                        if (!event.hasError()) toast("Video saved")
                    }
                }
            }
        } catch (e: Exception) {
            activeRecording = null
            statusText.text = "Video could not start: ${e.localizedMessage ?: "unknown error"}"
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) } catch (_: Exception) { toast("No gallery app found") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        activeRecording?.stop()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
