package com.motorola.pixelcamera.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as Camera2Manager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One photo at the sensor's full pixel count.
 *
 * The standard camera interface on this phone only lists the pixel-binned sizes. The driver
 * accepts the full size on a plain Camera2 session and, asked through Motorola's own request
 * key, switches the sensor out of binning for that one frame. Verified on the front camera
 * (6528x4896); the main camera's driver ignores the request and enlarges a binned frame.
 *
 * The camera must be free: the caller unbinds CameraX first and binds it again afterwards.
 */
object FullResCapture {

    private const val TAG = "FullResCapture"
    private const val KEY_MOT_CLIENT = "com.lenovo.moto.clientapp.is_motcamera2"
    private const val KEY_DO_REMOSAIC = "com.lenovo.moto.quadra_cfa.do_remosaic"
    private const val KEY_FULL_SIZE = "com.lenovo.moto.quadra_cfa.qcfa_dimension"

    private const val OPEN_ATTEMPTS = 6
    private const val OPEN_RETRY_MS = 250L
    private const val MIN_SETTLE_MS = 500L
    private const val MAX_SETTLE_MS = 2000L
    private const val TIMEOUT_MS = 12000L

    /** Full sensor size the driver advertises for [cameraId], or null when it has none. */
    fun fullSize(context: Context, cameraId: String): Size? = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager
        val key = CameraCharacteristics.Key(KEY_FULL_SIZE, IntArray::class.java)
        manager.getCameraCharacteristics(cameraId).get(key)?.takeIf { it.size >= 2 }?.let { Size(it[0], it[1]) }
    }.getOrNull()

    /** Returns the JPEG, or null when the driver did not deliver one. */
    suspend fun capture(context: Context, cameraId: String, size: Size, exposureIndex: Int): ByteArray? {
        val thread = HandlerThread("full-res").apply { start() }
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        var reader: ImageReader? = null
        var preview: Surface? = null
        var texture: SurfaceTexture? = null
        return try {
            withTimeoutOrNull(TIMEOUT_MS) {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager
                val orientation = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val camera = open(manager, cameraId, handler) ?: return@withTimeoutOrNull null
                device = camera

                // A small throwaway preview stream lets exposure and white balance settle first.
                val tex = SurfaceTexture(0).apply { setDefaultBufferSize(1440, 1080) }
                texture = tex
                val previewSurface = Surface(tex)
                preview = previewSurface
                val jpegReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
                reader = jpegReader

                val jpeg = CompletableDeferred<ByteArray?>()
                jpegReader.setOnImageAvailableListener({ r ->
                    val bytes = runCatching {
                        r.acquireNextImage().use { image ->
                            val buffer = image.planes[0].buffer
                            ByteArray(buffer.remaining()).also { buffer.get(it) }
                        }
                    }.getOrNull()
                    jpeg.complete(bytes)
                }, handler)

                val session = configure(camera, previewSurface, jpegReader.surface, handler)
                    ?: return@withTimeoutOrNull null

                val settled = CompletableDeferred<Unit>()
                val started = SystemClock.elapsedRealtime()
                val repeating = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    vendorByte(KEY_MOT_CLIENT, 1)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureIndex)
                }
                session.setRepeatingRequest(repeating.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                        val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                        val ready = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                        if (ready && SystemClock.elapsedRealtime() - started >= MIN_SETTLE_MS) settled.complete(Unit)
                    }
                }, handler)
                withTimeoutOrNull(MAX_SETTLE_MS) { settled.await() }

                val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(jpegReader.surface)
                    vendorByte(KEY_MOT_CLIENT, 1)
                    vendorByte(KEY_DO_REMOSAIC, 1)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureIndex)
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, orientation)
                }
                session.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                        Log.e(TAG, "Capture failed, reason ${failure.reason}")
                        jpeg.complete(null)
                    }
                }, handler)
                jpeg.await().also {
                    Log.i(TAG, "Full-size frame ${size.width}x${size.height}: ${it?.size ?: 0} bytes in " +
                        "${SystemClock.elapsedRealtime() - started} ms")
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera error", e); null
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Camera closed mid-capture", e); null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Driver rejected the full-size stream", e); null
        } finally {
            runCatching { device?.close() }
            runCatching { reader?.close() }
            runCatching { preview?.release() }
            runCatching { texture?.release() }
            thread.quitSafely()
        }
    }

    private fun CaptureRequest.Builder.vendorByte(name: String, value: Int) {
        runCatching { set(CaptureRequest.Key(name, Byte::class.javaPrimitiveType!!), value.toByte()) }
            .onFailure { Log.w(TAG, "Cannot set $name: ${it.message}") }
    }

    /** CameraX lets go of the camera a moment after unbinding, so the first tries may be refused. */
    @SuppressLint("MissingPermission")
    private suspend fun open(manager: Camera2Manager, cameraId: String, handler: Handler): CameraDevice? {
        repeat(OPEN_ATTEMPTS) { attempt ->
            val opened = CompletableDeferred<CameraDevice?>()
            try {
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    // Opened after the caller gave up (timeout): nobody would ever close it.
                    override fun onOpened(camera: CameraDevice) { if (!opened.complete(camera)) camera.close() }
                    override fun onDisconnected(camera: CameraDevice) { camera.close(); opened.complete(null) }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close(); opened.complete(null) }
                }, handler)
            } catch (e: CameraAccessException) {
                opened.complete(null)
            } catch (e: Exception) {
                Log.e(TAG, "Camera cannot be opened", e)
                return null
            }
            val camera = try {
                opened.await()
            } catch (e: CancellationException) {
                opened.cancel()
                throw e
            }
            camera?.let { return it }
            Log.w(TAG, "Camera $cameraId busy, attempt ${attempt + 1}")
            delay(OPEN_RETRY_MS)
        }
        return null
    }

    private suspend fun configure(
        camera: CameraDevice, preview: Surface, still: Surface, handler: Handler
    ): CameraCaptureSession? {
        val configured = CompletableDeferred<CameraCaptureSession?>()
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(preview), OutputConfiguration(still)),
            { handler.post(it) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { configured.complete(session) }
                override fun onConfigureFailed(session: CameraCaptureSession) { configured.complete(null) }
            }
        )
        config.sessionParameters = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            .apply { vendorByte(KEY_MOT_CLIENT, 1) }.build()
        camera.createCaptureSession(config)
        return configured.await().also { if (it == null) Log.e(TAG, "Driver refused the full-size session") }
    }
}
