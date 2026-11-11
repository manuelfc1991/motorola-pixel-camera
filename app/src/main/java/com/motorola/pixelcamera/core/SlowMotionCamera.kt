package com.motorola.pixelcamera.core

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as Camera2Manager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Slow-motion video on the main camera.
 *
 * The standard camera interface on this phone stops at 60 fps. The driver has a separate
 * slow-motion mode (MediaTek "SMVR"), switched on with its own request key: the sensor then runs
 * at 120 or 240 fps and hands the recorder several frames for every preview frame. CameraX knows
 * nothing of it, so this class runs its own Camera2 session; the caller unbinds CameraX first.
 *
 * Files are written to play at 30 fps, so they are slow without any editing.
 */
class SlowMotionCamera(private val context: Context) {

    /** [fps] at the sensor; the video plays back [factor] times slower than life. */
    enum class Mode(val size: Size, val fps: Int, val bitRate: Int, val label: String) {
        X4(Size(1920, 1080), 120, 32_000_000, "4× · 1080p"),
        X8(Size(1280, 720), 240, 20_000_000, "8× · 720p");

        val factor: Int get() = fps / PLAYBACK_FPS
    }

    private val thread = HandlerThread("slow-motion").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var output: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var mode: Mode? = null

    @Volatile var isRecording = false
        private set

    val isOpen: Boolean get() = device != null

    /**
     * Opens the camera in [mode] and shows its picture on [texture].
     * [onReady] reports on the main thread whether the driver accepted the mode.
     */
    @SuppressLint("MissingPermission")
    fun open(mode: Mode, texture: SurfaceTexture, onReady: (Boolean) -> Unit) {
        val main = Handler(context.mainLooper)
        var reported = false
        fun report(ok: Boolean) {
            if (reported) return
            reported = true
            pendingReady = null
            main.post { onReady(ok) }
        }
        handler.post {
            closeNow()
            pendingReady = { report(false) }
            this.mode = mode
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager
            // The preview keeps the recording's shape; the driver scales it down for the screen.
            texture.setDefaultBufferSize(mode.size.width, mode.size.height)
            val preview = Surface(texture)
            previewSurface = preview
            // The encoder's input must exist before the session does. A recorder prepared once on
            // it fixes its size and format; each recording then attaches a fresh recorder.
            val input = MediaCodec.createPersistentInputSurface()
            recorderSurface = input
            runCatching { newRecorder(mode, input, null).apply { prepare(); release() } }
                .onFailure { Log.e(TAG, "Recorder cannot be prepared for ${mode.label}", it); closeNow(); report(false); return@post }
            open(manager, attemptsLeft = OPEN_ATTEMPTS, onOpened = { camera ->
                device = camera
                runCatching { configure(camera, preview, input, mode, ::report) }
                    .onFailure { Log.e(TAG, "Session setup failed", it); closeNow(); report(false) }
            }, onFailed = { closeNow(); report(false) })
        }
    }

    /** CameraX lets go of the camera a moment after unbinding, so the first tries may be refused. */
    @SuppressLint("MissingPermission")
    private fun open(manager: Camera2Manager, attemptsLeft: Int, onOpened: (CameraDevice) -> Unit, onFailed: () -> Unit) {
        fun retry() {
            if (attemptsLeft <= 1) onFailed()
            else handler.postDelayed({ open(manager, attemptsLeft - 1, onOpened, onFailed) }, OPEN_RETRY_MS)
        }
        try {
            manager.openCamera(CAMERA_ID, object : CameraDevice.StateCallback() {
                private var delivered = false
                override fun onOpened(camera: CameraDevice) { delivered = true; onOpened(camera) }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (delivered) { Log.w(TAG, "Camera taken away"); lost() } else retry()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (delivered) { Log.e(TAG, "Camera error $error"); lost() } else retry()
                }
            }, handler)
        } catch (e: CameraAccessException) {
            retry()
        } catch (e: Exception) {
            // Camera switched off by policy, or gone: not worth retrying.
            Log.e(TAG, "Camera cannot be opened", e)
            onFailed()
        }
    }

    /** Called on the main thread when the camera is taken away after it was opened. */
    var onLost: (() -> Unit)? = null
    private var pendingReady: (() -> Unit)? = null

    /** The camera went away under us: save what was recorded, free everything, tell the screen. */
    private fun lost() {
        stopNow()
        val waiting = pendingReady
        closeNow()
        if (waiting != null) waiting() else Handler(context.mainLooper).post { onLost?.invoke() }
    }

    private fun configure(camera: CameraDevice, preview: Surface, input: Surface, mode: Mode, report: (Boolean) -> Unit) {
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(preview), OutputConfiguration(input)),
            { handler.post(it) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val ok = runCatching { repeat(recording = false) }
                        .onFailure { Log.e(TAG, "Preview could not start", it) }.isSuccess
                    report(ok)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "Driver refused ${mode.label}")
                    closeNow(); report(false)
                }
            }
        )
        config.sessionParameters = request(camera, mode, listOf(preview))
        camera.createCaptureSession(config)
    }

    private fun request(camera: CameraDevice, mode: Mode, targets: List<Surface>): CaptureRequest =
        camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            targets.forEach { addTarget(it) }
            // [sensor fps, frames handed to the recorder per preview frame]
            set(CaptureRequest.Key(KEY_SMVR_MODE, IntArray::class.java), intArrayOf(mode.fps, mode.factor))
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.fps, mode.fps))
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }.build()

    private fun repeat(recording: Boolean) {
        val camera = device ?: return
        val mode = mode ?: return
        val targets = listOfNotNull(previewSurface, recorderSurface.takeIf { recording })
        session?.setRepeatingRequest(request(camera, mode, targets), null, handler)
    }

    private fun newRecorder(mode: Mode, input: Surface, target: ParcelFileDescriptor?): MediaRecorder =
        MediaRecorder(context).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (target != null) setOutputFile(target.fileDescriptor)
            else setOutputFile(java.io.File(context.cacheDir, "slow-motion-setup.mp4").absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(mode.size.width, mode.size.height)
            // Captured at the sensor rate, stamped for 30 fps playback: the file is slow as it is.
            setCaptureRate(mode.fps.toDouble())
            setVideoFrameRate(PLAYBACK_FPS)
            setVideoEncodingBitRate(mode.bitRate)
            setOrientationHint(sensorOrientation())
            setInputSurface(input)
        }

    private fun sensorOrientation(): Int = runCatching {
        (context.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager)
            .getCameraCharacteristics(CAMERA_ID).get(CameraCharacteristics.SENSOR_ORIENTATION)
    }.getOrNull() ?: 90

    /** Starts writing a new video. [onStarted] reports on the main thread. */
    fun startRecording(onStarted: (Boolean) -> Unit) {
        val main = Handler(context.mainLooper)
        handler.post {
            val mode = mode
            val input = recorderSurface
            if (isRecording || mode == null || input == null || session == null) {
                main.post { onStarted(false) }; return@post
            }
            val ok = runCatching {
                val name = "VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_SLOW"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("no media entry")
                outputUri = uri
                val pfd = resolver.openFileDescriptor(uri, "rw") ?: error("no file")
                output = pfd
                val rec = newRecorder(mode, input, pfd).apply { prepare() }
                recorder = rec
                repeat(recording = true)
                rec.start()
                isRecording = true
            }.onFailure { Log.e(TAG, "Recording could not start", it); discardOutput() }.isSuccess
            main.post { onStarted(ok) }
        }
    }

    /** Finishes the video. [onSaved] gets its uri on the main thread, or null when nothing usable was written. */
    fun stopRecording(onSaved: (Uri?) -> Unit) {
        val main = Handler(context.mainLooper)
        handler.post { val uri = stopNow(); main.post { onSaved(uri) } }
    }

    private fun stopNow(): Uri? {
        if (!isRecording) return null
        isRecording = false
        val stopped = runCatching { recorder?.stop() }.onFailure { Log.w(TAG, "Recorder stop: ${it.message}") }.isSuccess
        runCatching { recorder?.release() }
        recorder = null
        runCatching { repeat(recording = false) }
        runCatching { output?.close() }
        output = null
        val uri = outputUri
        outputUri = null
        if (uri == null) return null
        if (!stopped) { runCatching { context.contentResolver.delete(uri, null, null) }; return null }
        runCatching {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        Log.i(TAG, "Slow-motion video saved: $uri")
        return uri
    }

    private fun discardOutput() {
        runCatching { recorder?.release() }
        recorder = null
        runCatching { output?.close() }
        output = null
        outputUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        outputUri = null
        isRecording = false
    }

    /** Stops any recording and frees the camera. [onClosed] runs on the main thread. */
    fun close(onClosed: () -> Unit = {}) {
        val main = Handler(context.mainLooper)
        handler.post { stopNow(); closeNow(); main.post(onClosed) }
    }

    private fun closeNow() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { previewSurface?.release() }
        previewSurface = null
        runCatching { recorderSurface?.release() }
        recorderSurface = null
        mode = null
        pendingReady = null
    }

    fun release() {
        close { thread.quitSafely() }
    }

    companion object {
        private const val TAG = "SlowMotionCamera"
        private const val CAMERA_ID = "0"
        private const val KEY_SMVR_MODE = "com.mediatek.smvrfeature.smvrMode"
        private const val PLAYBACK_FPS = 30
        private const val OPEN_ATTEMPTS = 8
        private const val OPEN_RETRY_MS = 250L
    }
}
