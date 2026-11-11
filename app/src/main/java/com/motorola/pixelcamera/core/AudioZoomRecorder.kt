package com.motorola.pixelcamera.core

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.os.Build
import android.util.Log
import com.motorola.pixelcamera.computational.AudioZoomDsp
import java.io.File

/**
 * Records the video's sound itself, so it can be zoomed: stereo from the camcorder microphones,
 * pointed forward and narrowed as the picture zooms ([AudioZoomDsp], plus the phone's own
 * direction hint), encoded to AAC in [output].
 *
 * Sound heard before [markVideoStarted] is dropped and none is kept while paused, so the sound
 * lines up with the video, which also leaves out its paused parts.
 */
class AudioZoomRecorder(private val output: File, private val zoom: () -> Float) {

    @Volatile private var running = false
    @Volatile private var started = false
    @Volatile private var paused = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val dsp = AudioZoomDsp()
    private var lastHintZoom = -1f

    /** False if the microphone could not be opened; the caller then records sound the usual way. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        }.getOrNull() ?: return false
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        record = r
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { r.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_AWAY_FROM_USER) }
        }
        runCatching { r.startRecording() }.onFailure { r.release(); record = null; return false }
        running = true
        thread = Thread({ loop(r) }, "AudioZoom").apply { start() }
        return true
    }

    /** The video's first frame is being recorded: sound from now on is kept. */
    fun markVideoStarted() { started = true }

    fun pause() { paused = true }
    fun resume() { paused = false }

    /** Stops and finishes the file. True if it holds sound. */
    fun stop(): Boolean {
        running = false
        thread?.join(3000)
        thread = null
        return written > 0
    }

    @Volatile private var written = 0L

    private fun loop(r: AudioRecord) {
        val channels = 2
        val pcm = ShortArray(FRAMES_PER_READ * channels)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcm.size * 2)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var track = -1
        val info = MediaCodec.BufferInfo()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var framesKept = 0L

            fun drain(endOfStream: Boolean) {
                var waits = 0
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream || ++waits > 100) return
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer!!.addTrack(codec.outputFormat)
                            muxer!!.start()
                        }
                        idx >= 0 -> {
                            val buf = codec.getOutputBuffer(idx)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && track >= 0) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                muxer!!.writeSampleData(track, buf, info)
                                written++
                            }
                            codec.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            fun feed(frames: Int, eos: Boolean) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx < 0) return
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().put(pcm, 0, frames * channels)
                val ptsUs = framesKept * 1_000_000L / SAMPLE_RATE
                codec.queueInputBuffer(idx, 0, frames * channels * 2, ptsUs,
                    if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                framesKept += frames
            }

            while (running) {
                val n = r.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                if (!started || paused) continue
                val frames = n / channels
                val z = zoom()
                hintDirection(r, z)
                dsp.process(pcm, frames, channels, z)
                feed(frames, eos = false)
                drain(endOfStream = false)
            }
            feed(0, eos = true)
            drain(endOfStream = true)
        } catch (e: Exception) {
            Log.e(TAG, "Audio zoom recording failed", e)
        } finally {
            runCatching { r.stop() }
            r.release()
            record = null
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (track >= 0) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /** Asks the phone to narrow its microphones too (it may ignore this). */
    private fun hintDirection(r: AudioRecord, z: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || kotlin.math.abs(z - lastHintZoom) < 0.25f) return
        lastHintZoom = z
        runCatching { r.setPreferredMicrophoneFieldDimension(dsp.amountFor(z)) }
    }

    companion object {
        private const val TAG = "AudioZoomRecorder"
        const val SAMPLE_RATE = 48_000
        private const val FRAMES_PER_READ = 1024
    }
}
