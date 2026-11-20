package com.calculator.scientific

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 100% Silent Auto-Focus Paper Capture Manager.
 *
 * Why this makes ZERO shutter sound (even on Korean Samsung Buddy / Vega phones):
 * Standard `ImageCapture.takePicture()` triggers the firmware shutter sound (`camera_click.ogg`).
 * Instead, this manager binds a high-resolution `ImageAnalysis` stream (1920x1080+) and extracts
 * the exact focused `ImageProxy` frame directly into a `Bitmap` in memory (`imageProxy.toBitmap()`),
 * completely bypassing the OS shutter sound trigger while also muting system audio streams.
 */
class SmartFocusCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isCapturing = AtomicBoolean(false)
    @Volatile
    private var isHardwareAfLocked = false
    private var consecutiveSharpSteadyFrames = 0
    private var previousSampleLuma: IntArray? = null

    fun isActive(): Boolean = isCapturing.get()

    @SuppressLint("UnsafeOptInUsageError")
    fun startAutoFocusCapture(
        onStatusUpdate: (String) -> Unit,
        onPhotoCaptured: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isCapturing.compareAndSet(false, true)) {
            return
        }

        muteDeviceStreams()

        isHardwareAfLocked = false
        consecutiveSharpSteadyFrames = 0
        previousSampleLuma = null

        onStatusUpdate("CAM: FOCUSING...")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                // High-resolution silent frame stream (1920x1440 / 1080p) so text on paper is crisp
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1440),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

                // Monitor hardware AF & AE lock directly on the analysis session
                val camera2Extender = Camera2Interop.Extender(analysisBuilder)
                camera2Extender.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        val afReady = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                                afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                        val aeReady = aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                        isHardwareAfLocked = afReady && aeReady
                    }
                })

                val imageAnalysis = analysisBuilder.build()
                val startTimeMs = System.currentTimeMillis()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (!isCapturing.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val metrics = evaluatePaperSharpnessAndStability(imageProxy)
                    val elapsedMs = System.currentTimeMillis() - startTimeMs

                    // Wait at least 550ms for initial lens sweep before locking
                    if (elapsedMs > 550) {
                        val isSharpAndSteady = metrics.sharpness >= SHARPNESS_THRESHOLD &&
                                metrics.motionDelta <= MOTION_THRESHOLD

                        if (isSharpAndSteady && (isHardwareAfLocked || elapsedMs > 1400)) {
                            consecutiveSharpSteadyFrames++
                            mainHandler.post {
                                onStatusUpdate("LOCKED [${consecutiveSharpSteadyFrames}/3]")
                            }
                        } else {
                            consecutiveSharpSteadyFrames = 0
                            mainHandler.post {
                                onStatusUpdate(
                                    if (metrics.motionDelta > MOTION_THRESHOLD) "HOLD STEADY" else "FOCUSING..."
                                )
                            }
                        }

                        // Silently grab the exact focused frame from memory without calling takePicture()
                        if (consecutiveSharpSteadyFrames >= 3 || elapsedMs >= 3500) {
                            if (isCapturing.compareAndSet(true, false)) {
                                try {
                                    val bitmap = silentFrameToBitmap(imageProxy)
                                    imageProxy.close()
                                    stopCamera()
                                    mainHandler.post {
                                        onPhotoCaptured(bitmap)
                                    }
                                    return@setAnalyzer
                                } catch (e: Exception) {
                                    imageProxy.close()
                                    stopCamera()
                                    mainHandler.post {
                                        onError("FRAME ERR")
                                    }
                                    return@setAnalyzer
                                }
                            }
                        }
                    }

                    imageProxy.close()
                }

                // Bind ONLY Preview + ImageAnalysis (NO ImageCapture -> 0% chance of OS shutter sound)
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )

                // Trigger center-point autofocus & metering on the paper
                val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                val centerPoint = factory.createPoint(0.5f, 0.5f)
                val focusAction = FocusMeteringAction.Builder(
                    centerPoint,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                )
                    .setAutoCancelDuration(2, TimeUnit.SECONDS)
                    .build()
                camera.cameraControl.startFocusAndMetering(focusAction)

            } catch (e: Exception) {
                isCapturing.set(false)
                onError("CAM ERR: ${e.localizedMessage ?: "Init failed"}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Converts the live YUV_420_888 ImageProxy frame directly into an upright Bitmap in memory.
     * Because this does not use `ImageCapture.takePicture()`, Android never plays a shutter click.
     */
    private fun silentFrameToBitmap(imageProxy: ImageProxy): Bitmap {
        val rawBitmap = imageProxy.toBitmap()
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation == 0) return rawBitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(
            rawBitmap,
            0,
            0,
            rawBitmap.width,
            rawBitmap.height,
            matrix,
            true
        )
    }

    private fun muteDeviceStreams() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val streams = intArrayOf(
                AudioManager.STREAM_SYSTEM,
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_ALARM,
                AudioManager.STREAM_RING
            )
            for (stream in streams) {
                try {
                    audioManager.setStreamVolume(stream, 0, 0)
                } catch (_: SecurityException) {
                }
            }
        } catch (_: Exception) {
        }
    }

    fun stopCamera() {
        isCapturing.set(false)
        mainHandler.post {
            try {
                cameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    fun shutdown() {
        stopCamera()
        cameraExecutor.shutdown()
    }

    private data class FrameMetrics(val sharpness: Double, val motionDelta: Double)

    private fun evaluatePaperSharpnessAndStability(image: ImageProxy): FrameMetrics {
        val plane = image.planes.firstOrNull() ?: return FrameMetrics(0.0, 999.0)
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height

        // Analyze center 50% region where the paper/equation is located
        val startX = width / 4
        val endX = (width * 3) / 4
        val startY = height / 4
        val endY = (height * 3) / 4
        val step = 4

        var laplacianSum = 0.0
        var sampleCount = 0
        val currentSamples = IntArray(((endY - startY) / step + 1) * ((endX - startX) / step + 1))

        var idx = 0
        var y = startY
        while (y < endY - step) {
            var x = startX
            while (x < endX - step) {
                val c = getLuma(buffer, y, x, rowStride, pixelStride)
                val up = getLuma(buffer, y - step, x, rowStride, pixelStride)
                val down = getLuma(buffer, y + step, x, rowStride, pixelStride)
                val left = getLuma(buffer, y, x - step, rowStride, pixelStride)
                val right = getLuma(buffer, y, x + step, rowStride, pixelStride)

                // 2D Laplacian operator for edge/text sharpness on paper
                val lap = abs(4 * c - up - down - left - right)
                laplacianSum += lap
                if (idx < currentSamples.size) {
                    currentSamples[idx++] = c
                }
                sampleCount++
                x += step
            }
            y += step
        }

        val sharpness = if (sampleCount > 0) laplacianSum / sampleCount else 0.0

        val prev = previousSampleLuma
        var motionSum = 0.0
        if (prev != null && prev.size == currentSamples.size && idx > 0) {
            for (i in 0 until idx) {
                motionSum += abs(currentSamples[i] - prev[i])
            }
            motionSum /= idx
        } else {
            motionSum = 999.0
        }
        previousSampleLuma = currentSamples

        return FrameMetrics(sharpness = sharpness, motionDelta = motionSum)
    }

    private fun getLuma(
        buffer: ByteBuffer,
        y: Int,
        x: Int,
        rowStride: Int,
        pixelStride: Int
    ): Int {
        val offset = y * rowStride + x * pixelStride
        return if (offset in 0 until buffer.limit()) {
            buffer.get(offset).toInt() and 0xFF
        } else {
            0
        }
    }

    companion object {
        // Empirical thresholds for text/math on paper
        private const val SHARPNESS_THRESHOLD = 14.0
        private const val MOTION_THRESHOLD = 6.5
    }
}
