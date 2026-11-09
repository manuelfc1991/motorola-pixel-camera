package com.motorola.pixelcamera.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.motorola.pixelcamera.computational.AstrophotographyEngine
import com.motorola.pixelcamera.computational.DngSaver
import com.motorola.pixelcamera.computational.HdrProcessor
import com.motorola.pixelcamera.computational.NightSightEngine
import com.motorola.pixelcamera.computational.PortraitEngine
import com.motorola.pixelcamera.computational.SuperResolutionEngine
import com.motorola.pixelcamera.hardware.MotorolaVendorHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraManager(private val context: Context) {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null

    var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    var currentZoomRatio: Float = 1.0f
        private set

    var isRawEnabled: Boolean = false
    var is108MpEnabled: Boolean = false

    suspend fun initialize(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = getCameraProvider()
        cameraProvider = provider
        bindCameraUseCases(lifecycleOwner, previewView)
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                try {
                    continuation.resume(providerFuture.get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(context))
        }

    fun bindCameraUseCases(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return

        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            setZoom(currentZoomRatio)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        currentZoomRatio = 1.0f
        bindCameraUseCases(lifecycleOwner, previewView)
    }

    fun setZoom(ratio: Float) {
        currentZoomRatio = ratio
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    fun setExposureCompensation(evIndex: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(evIndex)
    }

    fun focusAt(meteringPoint: MeteringPoint) {
        val action = FocusMeteringAction.Builder(meteringPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    /**
     * Executes capture and applies computational photography pipelines
     */
    suspend fun capturePhoto(mode: CameraMode, onStatusUpdate: (String) -> Unit): Uri? =
        withContext(Dispatchers.IO) {
            val capture = imageCapture ?: return@withContext null

            onStatusUpdate("Exposing sensor...")

            // Frame capture
            val baseBitmap = takeSingleBitmap(capture) ?: return@withContext null

            // If RAW mode is toggled, generate raw buffer alongside JPEG
            if (isRawEnabled) {
                onStatusUpdate("Saving RAW DNG...")
                val stream = ByteArrayOutputStream()
                baseBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                DngSaver.saveRawBuffer(context, stream.toByteArray(), baseBitmap.width, baseBitmap.height)
            }

            val processedBitmap: Bitmap = when (mode) {
                CameraMode.CAMERA -> {
                    onStatusUpdate("Enhancing HDR+ details...")
                    HdrProcessor.processHdr(listOf(baseBitmap))
                }
                CameraMode.NIGHT_SIGHT -> {
                    onStatusUpdate("Night Sight star & noise stacking...")
                    AstrophotographyEngine.processAstroStack(listOf(baseBitmap))
                }
                CameraMode.PORTRAIT -> {
                    onStatusUpdate("Computing portrait bokeh...")
                    PortraitEngine.applyPortraitBokeh(baseBitmap, 0.5f, 0.45f, 14f)
                }
                CameraMode.PRO -> {
                    onStatusUpdate("Processing Pro Capture...")
                    baseBitmap
                }
                CameraMode.VIDEO -> {
                    baseBitmap
                }
            }

            // If 2x zoom was requested, execute Super Resolution reconstruction
            val finalBitmap = if (currentZoomRatio >= 1.9f && mode != CameraMode.PORTRAIT) {
                onStatusUpdate("Super Res 2x reconstruction...")
                SuperResolutionEngine.process2xSuperRes(processedBitmap)
            } else {
                processedBitmap
            }

            onStatusUpdate("Saving photo...")
            saveBitmapToMediaStore(finalBitmap)
        }

    private suspend fun takeSingleBitmap(capture: ImageCapture): Bitmap? =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val bitmap = imageProxyToBitmap(image)
                            image.close()
                            cont.resume(bitmap)
                        } catch (e: Exception) {
                            image.close()
                            cont.resumeWithException(e)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                }
            )
        }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val rotationDegrees = image.imageInfo.rotationDegrees
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap): Uri? {
        val name = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_PXL"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 97, stream)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image to MediaStore", e)
            resolver.delete(uri, null, null)
            return null
        }
    }

    companion object {
        private const val TAG = "CameraManager"
    }
}
