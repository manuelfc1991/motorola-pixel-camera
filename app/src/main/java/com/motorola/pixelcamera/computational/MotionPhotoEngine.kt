package com.motorola.pixelcamera.computational

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Google Camera 9.7 Motion Photo & Top Shot Engine.
 *
 * Implements:
 * 1. Rolling buffer of the last 1.5 s of viewfinder frames, 15 a second, in reused bitmaps
 * 2. Asynchronous hardware AVC (H.264) video encoding via MediaCodec & MediaMuxer
 * 3. Google Motion Photo XMP metadata container injection (GCamera:MotionPhoto="1", GCamera:MicroVideoOffset)
 * 4. MP4 micro-video extraction and seamless looping playback
 */
object MotionPhotoEngine {

    private const val TAG = "MotionPhotoEngine"
    /** Frames kept from before the shutter: 1.5 s at [FRAME_RATE]. */
    private const val MAX_RING_BUFFER_SIZE = 23
    /** Enough for a smooth loop; every viewfinder frame (30 a second) only made heat. */
    private const val FRAME_RATE = 15
    private const val FRAME_INTERVAL_NS = 1_000_000_000L / FRAME_RATE - 5_000_000L
    private const val BIT_RATE = 4_000_000
    private const val POOL_SIZE = 3

    /** One viewfinder frame, upright, with the time it was seen. */
    class MotionFrame(val bitmap: Bitmap, val timestampNs: Long)

    private val lock = Any()
    private val ring = ArrayDeque<MotionFrame>()
    /** Bitmaps of frames that fell out of the ring, reused so steady running allocates nothing. */
    private val pool = ArrayDeque<Bitmap>()
    private var scratch: Bitmap? = null
    private var lastTimestampNs = 0L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    /**
     * Offers a viewfinder frame (RGBA ImageAnalysis output). Only about [FRAME_RATE] a second are
     * kept; the rest return at once without being touched.
     */
    fun offerFrame(image: ImageProxy) {
        val ts = image.imageInfo.timestamp
        if (ts - lastTimestampNs in 0 until FRAME_INTERVAL_NS) return
        lastTimestampNs = ts
        try {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val stride = plane.rowStride / plane.pixelStride
            val buffer = plane.buffer
            buffer.rewind()
            val raw = scratch?.takeIf { it.width == stride && it.height == h }
                ?: Bitmap.createBitmap(stride, h, Bitmap.Config.ARGB_8888).also { scratch?.recycle(); scratch = it }
            val source = if (buffer.remaining() >= raw.byteCount) {
                raw.copyPixelsFromBuffer(buffer); raw
            } else {
                image.toBitmap()   // last row shorter than the stride: let CameraX unpack it
            }
            val rotation = image.imageInfo.rotationDegrees
            val outW = if (rotation % 180 == 0) w else h
            val outH = if (rotation % 180 == 0) h else w
            val target = synchronized(lock) {
                pool.removeFirstOrNull()?.takeIf { it.width == outW && it.height == outH && !it.isRecycled }
            } ?: Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            matrix.reset()
            matrix.postTranslate(-w / 2f, -h / 2f)
            matrix.postRotate(rotation.toFloat())
            matrix.postTranslate(outW / 2f, outH / 2f)
            val canvas = Canvas(target)
            canvas.concat(matrix)
            canvas.drawBitmap(source, Rect(0, 0, w, h), Rect(0, 0, w, h), paint)
            if (source !== raw) source.recycle()
            synchronized(lock) {
                ring.addLast(MotionFrame(target, ts))
                while (ring.size > MAX_RING_BUFFER_SIZE) giveBack(ring.removeFirst().bitmap)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame push warning: ${e.message}")
        }
    }

    /** Must hold [lock]. */
    private fun giveBack(bitmap: Bitmap) {
        if (pool.size < POOL_SIZE && !bitmap.isRecycled) pool.addLast(bitmap) else bitmap.recycle()
    }

    /** Takes the frames gathered so far; the buffer starts again empty. */
    fun snapshotFrames(): List<MotionFrame> = synchronized(lock) {
        val list = ring.toList()
        ring.clear()
        list
    }

    /** Hands frames from [snapshotFrames] back once they are no longer needed. */
    fun recycleFrames(frames: List<MotionFrame>) = synchronized(lock) {
        frames.forEach { giveBack(it.bitmap) }
    }

    /** Drops every buffered frame, for when motion photos are off or the mode has no use for them. */
    fun clear() = synchronized(lock) {
        if (ring.isEmpty() && pool.isEmpty() && scratch == null) return
        ring.forEach { it.bitmap.recycle() }
        ring.clear()
        pool.forEach { it.recycle() }
        pool.clear()
        scratch?.recycle()
        scratch = null
    }

    /**
     * Encodes [frames] to an H.264 MP4 using the hardware encoder, timed as they were seen.
     */
    suspend fun encodeFramesToMp4(
        frames: List<MotionFrame>,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (frames.size < 4) {
            Log.w(TAG, "Not enough frames to produce Motion Photo video (${frames.size})")
            return@withContext false
        }

        val width = (frames[0].bitmap.width / 2) * 2
        val height = (frames[0].bitmap.height / 2) * 2
        val t0 = frames[0].timestampNs

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        val ok = try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var track = -1
            val info = MediaCodec.BufferInfo()
            val argb = IntArray(width * height)
            var next = 0
            var outputDone = false
            var idle = 0
            // Feed and drain in one loop: a busy encoder delays a frame instead of dropping it.
            while (!outputDone && idle < 300) {
                if (next < frames.size) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val image = codec.getInputImage(inputIndex) ?: throw IllegalStateException("encoder gave no input image")
                        val frame = frames[next]
                        frame.bitmap.getPixels(argb, 0, width, 0, 0, width, height)
                        writeYuv(argb, width, height, image)
                        val last = next == frames.size - 1
                        codec.queueInputBuffer(
                            inputIndex, 0, width * height * 3 / 2,
                            ((frame.timestampNs - t0) / 1000L).coerceAtLeast(0L),
                            if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        )
                        next++
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        idle = 0
                        val out = codec.getOutputBuffer(outIndex)
                        val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (out != null && info.size > 0 && muxerStarted && !config) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            muxer.writeSampleData(track, out, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                    else -> idle++
                }
            }
            if (!outputDone) Log.w(TAG, "Encoder did not finish (${next}/${frames.size} frames in)")
            muxerStarted && outputDone
        } catch (e: Exception) {
            Log.e(TAG, "Hardware AVC encoding error", e)
            false
        } finally {
            // Each step on its own: a failed stop must not keep the hardware encoder from being released.
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
        if (!ok) outputFile.delete()
        ok
    }

    /** Writes ARGB pixels into an encoder input image, whatever its plane layout. */
    private fun writeYuv(argb: IntArray, width: Int, height: Int, image: Image) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride
        for (j in 0 until height) {
            for (i in 0 until width) {
                val p = argb[j * width + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235)
                yBuf.put(j * yRow + i * yPix, y.toByte())
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(16, 240)
                    uBuf.put((j / 2) * uRow + (i / 2) * uPix, u.toByte())
                    vBuf.put((j / 2) * vRow + (i / 2) * vPix, v.toByte())
                }
            }
        }
    }

    /**
     * Injects Google Camera Motion Photo XMP metadata into the JPEG and appends the MP4 file
     */
    suspend fun attachMotionPhoto(
        context: Context,
        imageUri: Uri,
        mp4File: File,
        presentationTimestampUs: Long = 750_000L
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val originalJpegBytes = resolver.openInputStream(imageUri)?.use { it.readBytes() } ?: return@withContext false
            val mp4Bytes = mp4File.readBytes()
            val mp4Length = mp4Bytes.size

            // Construct standard Google Camera Motion Photo XMP
            val xmpString = buildXmpPacket(mp4Length, presentationTimestampUs)

            // Inject XMP into JPEG APP1 section
            val jpegWithXmp = injectXmpMarker(originalJpegBytes, xmpString)

            // Combine JPEG + MP4 binary payload
            val outputStream = ByteArrayOutputStream(jpegWithXmp.size + mp4Bytes.size)
            outputStream.write(jpegWithXmp)
            outputStream.write(mp4Bytes)
            val combinedBytes = outputStream.toByteArray()

            resolver.openOutputStream(imageUri, "wt")?.use { out ->
                out.write(combinedBytes)
                out.flush()
            }

            Log.i(TAG, "Motion Photo successfully written: JPEG=${jpegWithXmp.size}B + MP4=${mp4Length}B (URI=$imageUri)")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Failed attaching Motion Photo to JPEG", e)
            return@withContext false
        }
    }

    /**
     * Builds Google Camera 9.7 standard XMP packet
     */
    private fun buildXmpPacket(videoLength: Int, presentationTimestampUs: Long): String {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">" +
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                "<rdf:Description rdf:about=\"\" " +
                "xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" " +
                "GCamera:MotionPhoto=\"1\" " +
                "GCamera:MotionPhotoVersion=\"1\" " +
                "GCamera:MotionPhotoPresentationTimestampUs=\"$presentationTimestampUs\" " +
                "GCamera:MicroVideo=\"1\" " +
                "GCamera:MicroVideoVersion=\"1\" " +
                "GCamera:MicroVideoOffset=\"$videoLength\" " +
                "GCamera:MicroVideoPresentationTimestampUs=\"$presentationTimestampUs\">" +
                "<GCamera:MotionPhotoDirectory>" +
                "<rdf:Seq>" +
                "<rdf:li rdf:parseType=\"Resource\" " +
                "xmlns:Container=\"http://schemas.google.com/photos/1.0/container/\" " +
                "xmlns:Item=\"http://schemas.google.com/photos/1.0/container/item/\" " +
                "Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/>" +
                "<rdf:li rdf:parseType=\"Resource\" " +
                "xmlns:Container=\"http://schemas.google.com/photos/1.0/container/\" " +
                "xmlns:Item=\"http://schemas.google.com/photos/1.0/container/item/\" " +
                "Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"$videoLength\" Item:Padding=\"0\"/>" +
                "</rdf:Seq>" +
                "</GCamera:MotionPhotoDirectory>" +
                "</rdf:Description>" +
                "</rdf:RDF>" +
                "</x:xmpmeta>"
    }

    /**
     * Injects an APP1 XMP marker (0xFF 0xE1) into the beginning of the JPEG (after SOI 0xFF 0xD8)
     */
    private fun injectXmpMarker(jpegBytes: ByteArray, xmp: String): ByteArray {
        if (jpegBytes.size < 4 || (jpegBytes[0].toInt() and 0xFF) != 0xFF || (jpegBytes[1].toInt() and 0xFF) != 0xD8) {
            return jpegBytes
        }

        val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)
        val xmpBody = xmp.toByteArray(Charsets.UTF_8)
        val payloadLength = 2 + xmpHeader.size + xmpBody.size // includes 2 length bytes

        val app1 = ByteArray(2 + 2 + xmpHeader.size + xmpBody.size)
        app1[0] = 0xFF.toByte()
        app1[1] = 0xE1.toByte()
        app1[2] = ((payloadLength shr 8) and 0xFF).toByte()
        app1[3] = (payloadLength and 0xFF).toByte()
        System.arraycopy(xmpHeader, 0, app1, 4, xmpHeader.size)
        System.arraycopy(xmpBody, 0, app1, 4 + xmpHeader.size, xmpBody.size)

        val out = ByteArrayOutputStream(jpegBytes.size + app1.size)
        // Write SOI
        out.write(jpegBytes, 0, 2)
        // Write XMP APP1
        out.write(app1)
        // Write remainder of original JPEG
        out.write(jpegBytes, 2, jpegBytes.size - 2)

        return out.toByteArray()
    }

    /**
     * Checks if the photo file is a Google Motion Photo
     */
    fun isMotionPhoto(context: Context, uri: Uri): Boolean {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(65536)
                val read = stream.read(buffer)
                if (read > 0) {
                    val str = String(buffer, 0, read, Charsets.ISO_8859_1)
                    if (str.contains("GCamera:MotionPhoto=\"1\"") || str.contains("GCamera:MicroVideo=\"1\"")) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "isMotionPhoto check error: ${e.message}")
        }
        return false
    }

    /**
     * Extracts the embedded MP4 micro-video from a Motion Photo JPEG
     */
    fun extractMotionVideo(context: Context, uri: Uri, destFile: File): Boolean {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
            val offset = parseMicroVideoOffset(bytes)
            if (offset > 1024 && offset < bytes.size) {
                val start = bytes.size - offset
                destFile.outputStream().use { out ->
                    out.write(bytes, start, offset)
                }
                return true
            }

            // Fallback: search backwards for 'ftyp' MP4 box
            val ftypIdx = findFtypOffset(bytes)
            if (ftypIdx >= 4) {
                val start = ftypIdx - 4
                val len = bytes.size - start
                destFile.outputStream().use { out ->
                    out.write(bytes, start, len)
                }
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractMotionVideo error", e)
        }
        return false
    }

    private fun parseMicroVideoOffset(bytes: ByteArray): Int {
        val searchLen = minOf(bytes.size, 65536)
        val header = String(bytes, 0, searchLen, Charsets.ISO_8859_1)
        val key = "GCamera:MicroVideoOffset=\""
        val idx = header.indexOf(key)
        if (idx != -1) {
            val start = idx + key.length
            val end = header.indexOf("\"", start)
            if (end != -1) {
                return header.substring(start, end).toIntOrNull() ?: -1
            }
        }
        return -1
    }

    private fun findFtypOffset(bytes: ByteArray): Int {
        val ftyp = "ftyp".toByteArray(Charsets.US_ASCII)
        for (i in bytes.size - 5 downTo maxOf(0, bytes.size - 30_000_000)) {
            if (bytes[i] == ftyp[0] && bytes[i + 1] == ftyp[1] && bytes[i + 2] == ftyp[2] && bytes[i + 3] == ftyp[3]) {
                return i
            }
        }
        return -1
    }
}
