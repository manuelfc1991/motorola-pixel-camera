package com.motorola.pixelcamera.computational

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2

/**
 * Panorama capture: follows the turn of the phone with the rotation sensor, hands frames to
 * [PanoramaStitcher] (which lines them up by the picture), and saves the result with the
 * Photo Sphere tags gallery apps use to show it as a panorama.
 */
class PanoramaEngine(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile private var isRecording = false
    /** Turn since the start in degrees, signed (right is positive), not wrapped. */
    @Volatile private var yaw = 0f
    private var lastHeading = Float.NaN
    @Volatile private var currentSweptDegrees = 0f
    private val maxSweptDegrees = 180f

    /**
     * Focal length as a share of the frame width with the phone upright (lens focal length over
     * the sensor's short side). Set by the camera for the lens in use; refined from the matches.
     */
    @Volatile var focalToWidth = DEFAULT_FOCAL_TO_WIDTH

    private val lock = Any()
    private var stitcher: PanoramaStitcher? = null
    @Volatile private var strip: Bitmap? = null

    // Callback for live UI tracking
    var onPanoramaProgress: ((degrees: Float, progressPercent: Int, stripPreview: Bitmap?) -> Unit)? = null

    fun isCapturing(): Boolean = isRecording

    /**
     * Start panoramic recording session
     */
    fun startCapture() {
        synchronized(lock) { stitcher = null }
        strip = null
        yaw = 0f
        lastHeading = Float.NaN
        currentSweptDegrees = 0f
        isRecording = true
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        Log.i(TAG, "Panorama capture session initiated")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRecording || event == null || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // Compass heading of where the camera points (out of the back of the phone). Unlike the
        // azimuth of getOrientation, this stays steady with the phone held upright.
        val east = -r[2]
        val north = -r[5]
        if (east * east + north * north < 0.05f) return   // pointing at the floor or the sky
        val heading = Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat()
        if (!lastHeading.isNaN()) {
            var step = heading - lastHeading
            while (step < -180f) step += 360f
            while (step > 180f) step -= 360f
            yaw += step
        }
        lastHeading = heading
        currentSweptDegrees = kotlin.math.abs(yaw).coerceAtMost(maxSweptDegrees)
        onPanoramaProgress?.invoke(currentSweptDegrees, progressPercent(), strip)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun progressPercent(): Int {
        if (stitcher?.isFull == true) return 100
        return ((currentSweptDegrees / maxSweptDegrees) * 100).toInt().coerceIn(0, 100)
    }

    /** Whether a frame now would be used; lets the camera skip converting the others. */
    fun wantsFrame(): Boolean {
        if (!isRecording) return false
        val s = stitcher ?: return true
        return s.wantsFrame(yaw)
    }

    /** Takes one upright viewfinder frame during the sweep. */
    fun processIncomingFrame(frame: Bitmap) {
        if (!isRecording) return
        val used = synchronized(lock) {
            if (!isRecording) return
            val s = stitcher?.takeIf { it.frameW == frame.width && it.frameH == frame.height }
                ?: PanoramaStitcher(
                    frame.width, frame.height,
                    focalPx = frame.width * focalToWidth,
                    pixelsPerDegree = (frame.width * focalToWidth * Math.PI / 180.0).toFloat(),
                    maxSweepDegrees = maxSweptDegrees,
                ).also { stitcher = it }
            if (!s.wantsFrame(yaw)) return
            val pixels = IntArray(frame.width * frame.height)
            frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val ok = s.add(pixels, yaw)
            if (ok) {
                Log.d(TAG, "frame ${s.frameCount} at ${"%.1f".format(yaw)}° in ${android.os.SystemClock.elapsedRealtime() - t0} ms, " +
                    "${"%.2f".format(s.pixelsPerDegree)} px/°")
                s.preview(STRIP_HEIGHT)?.let { p ->
                    strip = Bitmap.createBitmap(p.pixels, p.width, p.height, Bitmap.Config.ARGB_8888)
                }
            }
            ok
        }
        if (used) onPanoramaProgress?.invoke(currentSweptDegrees, progressPercent(), strip)
    }

    /**
     * Stop panorama capture, crop trimmed edges, inject Photosphere XMP, and save to MediaStore
     */
    suspend fun finishCapture(location: android.location.Location? = null): Uri? = withContext(Dispatchers.IO) {
        isRecording = false
        try {
            sensorManager.unregisterListener(this@PanoramaEngine)
        } catch (ignored: Exception) {}

        val image = synchronized(lock) {
            val s = stitcher
            stitcher = null
            if (s == null || s.frameCount < 2 || currentSweptDegrees < MIN_SWEEP_DEGREES) {
                Log.w(TAG, "Panorama was too short to save (${s?.frameCount ?: 0} frames, ${currentSweptDegrees.toInt()}°)")
                null
            } else s.result()
        } ?: return@withContext null
        strip = null
        val cropped = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)

        // Inject Photosphere XMP metadata into JPEG
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "PANO_${timeStamp}_PXL"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null

        try {
            val jpegStream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 95, jpegStream)
            val jpegBytes = jpegStream.toByteArray()

            val fullPanoWidth = if (currentSweptDegrees > 10f) {
                ((cropped.width * 360f) / currentSweptDegrees).toInt()
            } else {
                cropped.width * 2
            }

            // Google Photosphere / Panorama XMP packet
            val panoXmp = buildPhotosphereXmp(cropped.width, cropped.height, fullPanoWidth, cropped.height)
            val finalJpeg = injectPanoXmp(jpegBytes, panoXmp)

            resolver.openOutputStream(uri)?.use { out ->
                out.write(finalJpeg)
                out.flush()
            }

            if (location != null) {
                try {
                    resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                        exif.setGpsInfo(location)
                        exif.saveAttributes()
                    }
                } catch (gpsErr: Exception) {
                    Log.w(TAG, "Failed setting GPS on panorama: ${gpsErr.message}")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            Log.i(TAG, "Panorama saved successfully: $uri (Width: ${cropped.width}px, Angle: ${currentSweptDegrees.toInt()}°)")
            return@withContext uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving panorama", e)
            resolver.delete(uri, null, null)
            return@withContext null
        } finally {
            cropped.recycle()
        }
    }

    private fun buildPhotosphereXmp(
        croppedW: Int,
        croppedH: Int,
        fullW: Int,
        fullH: Int
    ): String {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">" +
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                "<rdf:Description rdf:about=\"\" " +
                "xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\">" +
                "<GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>" +
                "<GPano:CaptureSoftware>Pixel Camera 9.7</GPano:CaptureSoftware>" +
                "<GPano:ProjectionType>cylindrical</GPano:ProjectionType>" +
                "<GPano:CroppedAreaImageWidthPixels>$croppedW</GPano:CroppedAreaImageWidthPixels>" +
                "<GPano:CroppedAreaImageHeightPixels>$croppedH</GPano:CroppedAreaImageHeightPixels>" +
                "<GPano:FullPanoWidthPixels>$fullW</GPano:FullPanoWidthPixels>" +
                "<GPano:FullPanoHeightPixels>$fullH</GPano:FullPanoHeightPixels>" +
                "<GPano:CroppedAreaLeftPixels>0</GPano:CroppedAreaLeftPixels>" +
                "<GPano:CroppedAreaTopPixels>0</GPano:CroppedAreaTopPixels>" +
                "</rdf:Description>" +
                "</rdf:RDF>" +
                "</x:xmpmeta>"
    }

    private fun injectPanoXmp(jpegBytes: ByteArray, xmp: String): ByteArray {
        if (jpegBytes.size < 4 || (jpegBytes[0].toInt() and 0xFF) != 0xFF || (jpegBytes[1].toInt() and 0xFF) != 0xD8) {
            return jpegBytes
        }
        val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)
        val xmpBody = xmp.toByteArray(Charsets.UTF_8)
        val payloadLength = 2 + xmpHeader.size + xmpBody.size

        val app1 = ByteArray(2 + 2 + xmpHeader.size + xmpBody.size)
        app1[0] = 0xFF.toByte()
        app1[1] = 0xE1.toByte()
        app1[2] = ((payloadLength shr 8) and 0xFF).toByte()
        app1[3] = (payloadLength and 0xFF).toByte()
        System.arraycopy(xmpHeader, 0, app1, 4, xmpHeader.size)
        System.arraycopy(xmpBody, 0, app1, 4 + xmpHeader.size, xmpBody.size)

        val out = ByteArrayOutputStream(jpegBytes.size + app1.size)
        out.write(jpegBytes, 0, 2)
        out.write(app1)
        out.write(jpegBytes, 2, jpegBytes.size - 2)
        return out.toByteArray()
    }

    fun release() {
        isRecording = false
        try {
            sensorManager.unregisterListener(this)
        } catch (ignored: Exception) {}
        synchronized(lock) { stitcher = null }
        strip = null
    }

    companion object {
        private const val TAG = "PanoramaEngine"
        private const val MIN_SWEEP_DEGREES = 15f
        /** Main camera: 5.88 mm lens on a 6.3 mm tall sensor. */
        private const val DEFAULT_FOCAL_TO_WIDTH = 0.93f
        private const val STRIP_HEIGHT = 120
    }
}
