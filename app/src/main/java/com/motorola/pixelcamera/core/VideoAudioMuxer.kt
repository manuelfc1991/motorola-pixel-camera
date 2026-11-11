package com.motorola.pixelcamera.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Puts a silent video and a separately recorded sound track into one MP4, copying both as they
 * are (nothing is re-encoded). The sound is shifted to start with the video's first frame.
 */
object VideoAudioMuxer {

    fun mux(video: File, audio: File?, out: FileDescriptor, latitude: Double?, longitude: Double?) {
        val rotation = MediaMetadataRetriever().run {
            try {
                setDataSource(video.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } finally { release() }
        }
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoEx = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val audioEx = audio?.takeIf { it.length() > 0 }?.let { MediaExtractor().apply { setDataSource(it.absolutePath) } }
        try {
            muxer.setOrientationHint(rotation)
            if (latitude != null && longitude != null) {
                runCatching { muxer.setLocation(latitude.toFloat(), longitude.toFloat()) }
            }
            val vIn = trackOf(videoEx, "video/") ?: error("no video track")
            videoEx.selectTrack(vIn)
            val vOut = muxer.addTrack(videoEx.getTrackFormat(vIn))
            val aIn = audioEx?.let { trackOf(it, "audio/") }
            val aOut = aIn?.let { audioEx.selectTrack(it); muxer.addTrack(audioEx.getTrackFormat(it)) }
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val (firstVideoUs, lastVideoUs) = copy(videoEx, muxer, vOut, buffer, 0L, Long.MAX_VALUE)
            // The microphone keeps going a moment after the last frame: cut the sound there.
            if (aOut != null) copy(audioEx, muxer, aOut, buffer, firstVideoUs, lastVideoUs)
            muxer.stop()
        } finally {
            muxer.release()
            videoEx.release()
            audioEx?.release()
        }
    }

    private fun trackOf(ex: MediaExtractor, prefix: String): Int? =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }

    /**
     * Copies samples, adding [shiftUs] to their time, up to [untilUs] (in output time).
     * Returns the first and last sample times written.
     */
    private fun copy(ex: MediaExtractor, muxer: MediaMuxer, track: Int, buffer: ByteBuffer, shiftUs: Long, untilUs: Long): Pair<Long, Long> {
        val info = MediaCodec.BufferInfo()
        var first = -1L
        var last = 0L
        while (true) {
            buffer.clear()
            val size = ex.readSampleData(buffer, 0)
            if (size < 0) break
            val t = ex.sampleTime
            if (t + shiftUs > untilUs) break
            if (first < 0) first = t
            last = t + shiftUs
            info.set(0, size, t + shiftUs,
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(track, buffer, info)
            ex.advance()
        }
        return first.coerceAtLeast(0L) to last
    }
}
