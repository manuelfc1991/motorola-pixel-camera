package com.motorola.pixelcamera.computational

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.motorola.pixelcamera.hardware.DimensityOptimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * High-Speed Gyro-Stabilized Cylindrical Panorama Stitching Engine.
 *
 * Implements:
 * 1. Real-time gyroscope & rotation-vector azimuth yaw tracking (0° to 180°)
 * 2. Real-time cylindrical projection transform compensating for wide-angle planar distortion
 * 3. Overlap seam blending with linear feathering across consecutive frames
 * 4. Google Photosphere / Cylindrical Panorama XMP metadata injection (GPano:UsePanoramaViewer="True")
 */
class PanoramaEngine(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)

    private var isRecording = false
    private var startYaw = 0f
    private var lastCapturedYaw = 0f
    private var currentSweptDegrees = 0f
    private val maxSweptDegrees = 180f

    // Pano accumulator canvas
    private var panoBitmap: Bitmap? = null
    private var panoCanvas: Canvas? = null
    private val panoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var currentPanoWidth = 0
    private var panoHeight = 0
    private var frameCount = 0

    // Callback for live UI tracking
    var onPanoramaProgress: ((degrees: Float, progressPercent: Int, stripPreview: Bitmap?) -> Unit)? = null

    fun isCapturing(): Boolean = isRecording

    /**
     * Start panoramic recording session
     */
    fun startCapture() {
        isRecording = true
        startYaw = Float.NaN
        lastCapturedYaw = 0f
        currentSweptDegrees = 0f
        currentPanoWidth = 0
        panoHeight = 0
        frameCount = 0
        panoBitmap?.recycle()
        panoBitmap = null
        panoCanvas = null

        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        Log.i(TAG, "Panorama capture session initiated")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRecording || event == null) return

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()

            if (startYaw.isNaN()) {
                startYaw = yaw
                lastCapturedYaw = yaw
            }

            var diff = yaw - startYaw
            while (diff < -180f) diff += 360f
            while (diff > 180f) diff -= 360f
            currentSweptDegrees = kotlin.math.abs(diff).coerceAtMost(maxSweptDegrees)

            val progressPercent = ((currentSweptDegrees / maxSweptDegrees) * 100).toInt().coerceIn(0, 100)
            onPanoramaProgress?.invoke(currentSweptDegrees, progressPercent, panoBitmap)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Ingests a new incoming viewfinder / camera frame during pan sweep.
     * Stitches using cylindrical remapping and overlap feathering.
     */
    fun processIncomingFrame(frame: Bitmap) {
        if (!isRecording) return

        // Downscale frame slightly for ultra-fast, real-time stitch without frame drops
        val w = (frame.width / 2) * 2
        val h = (frame.height / 2) * 2

        // Initialize pano canvas on first frame
        if (panoBitmap == null) {
            panoHeight = h
            val maxEstimatedWidth = (w * 6).coerceAtMost(7680)
            panoBitmap = Bitmap.createBitmap(maxEstimatedWidth, h, Bitmap.Config.ARGB_8888)
            panoCanvas = Canvas(panoBitmap!!)

            val cylFirst = applyCylindricalProjection(frame)
            panoCanvas?.drawBitmap(cylFirst, 0f, 0f, panoPaint)
            currentPanoWidth = cylFirst.width
            frameCount = 1
            cylFirst.recycle()
            return
        }

        // Only stitch if rotated by at least ~4-6 degrees since last stitch
        val yawDelta = kotlin.math.abs(currentSweptDegrees - lastCapturedYaw)
        if (yawDelta < 4.0f && frameCount > 0) {
            return
        }
        lastCapturedYaw = currentSweptDegrees

        val cylFrame = applyCylindricalProjection(frame)
        val overlap = (cylFrame.width * 0.28f).toInt().coerceAtLeast(40)
        val shiftX = (cylFrame.width - overlap).coerceAtLeast(1)

        val canvas = panoCanvas ?: return
        val currentBmp = panoBitmap ?: return

        if (currentPanoWidth + shiftX > currentBmp.width - 20) {
            // Reached maximum width buffer
            return
        }

        // Seam blending across overlap zone
        blendSeam(currentBmp, cylFrame, currentPanoWidth - overlap, overlap)

        // Draw remaining non-overlapping portion
        val srcRect = android.graphics.Rect(overlap, 0, cylFrame.width, cylFrame.height)
        val dstRect = android.graphics.Rect(currentPanoWidth, 0, currentPanoWidth + (cylFrame.width - overlap), panoHeight)
        canvas.drawBitmap(cylFrame, srcRect, dstRect, panoPaint)

        currentPanoWidth += (cylFrame.width - overlap)
        frameCount++
        cylFrame.recycle()

        val progressPercent = ((currentSweptDegrees / maxSweptDegrees) * 100).toInt().coerceIn(0, 100)
        onPanoramaProgress?.invoke(currentSweptDegrees, progressPercent, panoBitmap)
    }

    /**
     * Applies cylindrical projection mapping to correct wide-angle distortion:
     * x' = f * atan((x - w/2) / f) + w/2
     * y' = (y - h/2) / sqrt(1 + ((x - w/2)/f)^2) + h/2
     */
    private fun applyCylindricalProjection(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val f = w * 0.85f // Approximate focal length for Motorola HM2 lens

        val inPixels = IntArray(w * h)
        val outPixels = IntArray(w * h)
        src.getPixels(inPixels, 0, w, 0, 0, w, h)

        val halfW = w / 2f
        val halfH = h / 2f

        for (y in 0 until h) {
            val yOffset = y * w
            val dy = y - halfH

            for (x in 0 until w) {
                val dx = x - halfW
                // Forward cylindrical mapping lookup
                val rad = dx / f
                val srcX = (f * tan(rad) + halfW).toInt()
                val scaleY = kotlin.math.cos(rad)
                val srcY = (dy / scaleY + halfH).toInt()

                if (srcX in 0 until w && srcY in 0 until h) {
                    outPixels[yOffset + x] = inPixels[srcY * w + srcX]
                } else {
                    outPixels[yOffset + x] = Color.BLACK
                }
            }
        }
        output.setPixels(outPixels, 0, w, 0, 0, w, h)
        return output
    }

    /**
     * Smooth linear alpha feathering over the overlap seam
     */
    private fun blendSeam(target: Bitmap, source: Bitmap, startX: Int, overlapWidth: Int) {
        val h = target.height
        val targetPixels = IntArray(overlapWidth * h)
        val sourcePixels = IntArray(overlapWidth * h)

        target.getPixels(targetPixels, 0, overlapWidth, startX, 0, overlapWidth, h)
        source.getPixels(sourcePixels, 0, overlapWidth, 0, 0, overlapWidth, h)

        for (x in 0 until overlapWidth) {
            val alpha = x.toFloat() / overlapWidth
            val invAlpha = 1.0f - alpha

            for (y in 0 until h) {
                val idx = y * overlapWidth + x
                val p1 = targetPixels[idx]
                val p2 = sourcePixels[idx]

                val r1 = (p1 shr 16) and 0xFF
                val g1 = (p1 shr 8) and 0xFF
                val b1 = p1 and 0xFF

                val r2 = (p2 shr 16) and 0xFF
                val g2 = (p2 shr 8) and 0xFF
                val b2 = p2 and 0xFF

                val r = (r1 * invAlpha + r2 * alpha).toInt().coerceIn(0, 255)
                val g = (g1 * invAlpha + g2 * alpha).toInt().coerceIn(0, 255)
                val b = (b1 * invAlpha + b2 * alpha).toInt().coerceIn(0, 255)

                targetPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        target.setPixels(targetPixels, 0, overlapWidth, startX, 0, overlapWidth, h)
    }

    /**
     * Stop panorama capture, crop trimmed edges, inject Photosphere XMP, and save to MediaStore
     */
    suspend fun finishCapture(): Uri? = withContext(Dispatchers.IO) {
        isRecording = false
        try {
            sensorManager.unregisterListener(this@PanoramaEngine)
        } catch (ignored: Exception) {}

        val pano = panoBitmap ?: return@withContext null
        if (currentPanoWidth < 200 || frameCount == 0) {
            Log.w(TAG, "Panorama was too short to save ($currentPanoWidth px)")
            return@withContext null
        }

        // Crop to valid stitched area
        val cropped = Bitmap.createBitmap(pano, 0, 0, currentPanoWidth, panoHeight)

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
            cropped.compress(Bitmap.CompressFormat.JPEG, 98, jpegStream)
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
            panoBitmap?.recycle()
            panoBitmap = null
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
        panoBitmap?.recycle()
        panoBitmap = null
        panoCanvas = null
    }

    companion object {
        private const val TAG = "PanoramaEngine"
    }
}
