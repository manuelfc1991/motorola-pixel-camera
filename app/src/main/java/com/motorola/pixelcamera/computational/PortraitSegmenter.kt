package com.motorola.pixelcamera.computational

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * Finds the subject and the scene depth of a photo with on-device neural models.
 *
 * The model files are NOT part of this repository. They are read from `assets/gmodels/` when a
 * local build has put them there (see `tools/extract_portrait_models.sh`). Without them
 * [analyse] returns null and portrait mode falls back to its simple radial blur.
 */
object PortraitSegmenter {

    /** Subject mask and depth on one small grid. Either map may be missing, never both. */
    class SubjectMap(
        val width: Int, val height: Int, val alpha: FloatArray?, val depth: FloatArray?,
        /** Body centres of the people found, as x, y pairs in 0..1. Empty without the pose model. */
        val people: FloatArray = FloatArray(0)
    )

    private const val TAG = "PortraitSegmenter"
    private const val DIR = "gmodels"
    private const val SEGMENT_MODEL = "$DIR/subject_segmenter.tflite"
    private const val MATTING_MODEL = "$DIR/portrait_matting.tflite"
    private const val DEPTH_MODEL = "$DIR/depth.tflite"
    private const val POSE_MODEL = "$DIR/pose.tflite"

    private const val SEGMENT_SIZE = 512
    private const val DEPTH_SIZE = 384
    private const val POSE_SIZE = 256
    private const val POSE_MAX_PEOPLE = 100
    private const val POSE_KEYPOINTS = 17
    private const val PERSON_MIN_SCORE = 0.2f
    private const val KEYPOINT_MIN_SCORE = 0.3f
    /** The matting model's fixed input: 768 wide, 1024 tall. */
    private const val MATTE_SHORT = 768
    private const val MATTE_LONG = 1024
    private const val THREADS = 4

    private val lock = Any()

    /** A GPU-backed interpreter only works on the thread that created it, so all model work
     *  happens on this one thread. */
    private val modelThread = Executors.newSingleThreadExecutor { Thread(it, "portrait-models") }
    private val interpreters = HashMap<String, Interpreter?>()

    fun isAvailable(context: Context): Boolean =
        hasAsset(context, SEGMENT_MODEL) || hasAsset(context, DEPTH_MODEL)

    /**
     * Returns the subject mask (1 = subject) and depth (1 = nearest) for [input],
     * or null when no model is installed or inference fails.
     */
    /**
     * Loads the models ahead of the first shot. Preparing them for the GPU takes seconds the
     * first time, so this runs in the background as soon as Portrait mode opens.
     */
    fun warmUp(context: Context) {
        val appContext = context.applicationContext
        modelThread.execute {
            synchronized(lock) {
                try {
                    for (asset in listOf(SEGMENT_MODEL, MATTING_MODEL, DEPTH_MODEL, POSE_MODEL)) interpreter(appContext, asset)
                } catch (e: Exception) {
                    Log.w(TAG, "Portrait model warm-up failed", e)
                }
            }
        }
    }

    fun analyse(context: Context, input: Bitmap): SubjectMap? =
        modelThread.submit<SubjectMap?> { analyseOnModelThread(context, input) }.get()

    private fun analyseOnModelThread(context: Context, input: Bitmap): SubjectMap? = synchronized(lock) {
        try {
            val landscape = input.width > input.height
            val w = if (landscape) MATTE_LONG else MATTE_SHORT
            val h = if (landscape) MATTE_SHORT else MATTE_LONG
            val work = Bitmap.createScaledBitmap(input, w, h, true)
            val pixels = IntArray(w * h)
            work.getPixels(pixels, 0, w, 0, 0, w, h)
            if (work !== input) work.recycle()

            val t0 = SystemClock.elapsedRealtime()
            val coarse = segment(context, pixels, w, h)
            val t1 = SystemClock.elapsedRealtime()
            val alpha = coarse?.let { refine(context, pixels, it, w, h, landscape) ?: it }
            val t2 = SystemClock.elapsedRealtime()
            val depth = depth(context, pixels, w, h)
            val t3 = SystemClock.elapsedRealtime()
            val people = if (alpha == null) FloatArray(0) else people(context, pixels, w, h)
            Log.i(TAG, "segment ${t1 - t0} ms, matting ${t2 - t1} ms, depth ${t3 - t2} ms, " +
                "pose ${SystemClock.elapsedRealtime() - t3} ms (${people.size / 2} people)")
            if (alpha == null && depth == null) null else SubjectMap(w, h, alpha, depth, people)
        } catch (e: Exception) {
            Log.w(TAG, "Portrait models failed, falling back to radial blur", e)
            null
        }
    }

    /** Coarse subject mask from the foreground segmenter. */
    private fun segment(context: Context, pixels: IntArray, w: Int, h: Int): FloatArray? {
        val model = interpreter(context, SEGMENT_MODEL) ?: return null
        val inBuf = rgbBuffer(resizePixels(pixels, w, h, SEGMENT_SIZE, SEGMENT_SIZE))
        val outBuf = floatBuffer(SEGMENT_SIZE * SEGMENT_SIZE * 2)
        model.run(inBuf, outBuf)
        outBuf.rewind()
        val floats = outBuf.asFloatBuffer()
        // Two channels per pixel: background, foreground.
        val mask = FloatArray(SEGMENT_SIZE * SEGMENT_SIZE) { floats.get(it * 2 + 1) }
        return PortraitMath.resizeBilinear(mask, SEGMENT_SIZE, SEGMENT_SIZE, w, h)
    }

    /** Sharpens the coarse mask along hair and outlines. The model is portrait-only, so
     *  landscape photos go through transposed and come back the same way. */
    private fun refine(
        context: Context, pixels: IntArray, coarse: FloatArray, w: Int, h: Int, landscape: Boolean
    ): FloatArray? {
        val model = interpreter(context, MATTING_MODEL) ?: return null
        val image = if (landscape) transposePixels(pixels, w, h) else pixels
        val mask = if (landscape) PortraitMath.transpose(coarse, w, h) else coarse
        val size = MATTE_SHORT * MATTE_LONG

        val maskBuf = floatBuffer(size)
        maskBuf.asFloatBuffer().put(mask)

        // The model has a low-res and a full-res output; keep the full-res one.
        val outputs = HashMap<Int, Any>()
        var fullIndex = -1
        for (i in 0 until model.outputTensorCount) {
            val count = model.getOutputTensor(i).numElements()
            outputs[i] = floatBuffer(count)
            if (count == size) fullIndex = i
        }
        if (fullIndex < 0) return null
        model.runForMultipleInputsOutputs(arrayOf<Any>(rgbBuffer(image), maskBuf), outputs)

        val out = outputs[fullIndex] as ByteBuffer
        out.rewind()
        val matte = FloatArray(size)
        out.asFloatBuffer().get(matte)
        for (i in matte.indices) matte[i] = matte[i].coerceIn(0f, 1f)
        return if (landscape) PortraitMath.transpose(matte, MATTE_SHORT, MATTE_LONG) else matte
    }

    /** Relative depth, stretched to 0..1 with 1 nearest. */
    private fun depth(context: Context, pixels: IntArray, w: Int, h: Int): FloatArray? {
        val model = interpreter(context, DEPTH_MODEL) ?: return null
        val inBuf = rgbBuffer(resizePixels(pixels, w, h, DEPTH_SIZE, DEPTH_SIZE))
        val outBuf = floatBuffer(DEPTH_SIZE * DEPTH_SIZE)
        model.run(inBuf, outBuf)
        outBuf.rewind()
        val raw = FloatArray(DEPTH_SIZE * DEPTH_SIZE)
        outBuf.asFloatBuffer().get(raw)
        return PortraitMath.resizeBilinear(PortraitMath.normalise(raw), DEPTH_SIZE, DEPTH_SIZE, w, h)
    }

    /** Body centres (x, y in 0..1) of the people the pose model finds. */
    private fun people(context: Context, pixels: IntArray, w: Int, h: Int): FloatArray {
        val model = interpreter(context, POSE_MODEL) ?: return FloatArray(0)
        val small = resizePixels(pixels, w, h, POSE_SIZE, POSE_SIZE)
        val inBuf = ByteBuffer.allocateDirect(small.size * 3).order(ByteOrder.nativeOrder())
        for (p in small) {
            inBuf.put((p shr 16).toByte())
            inBuf.put((p shr 8).toByte())
            inBuf.put(p.toByte())
        }
        inBuf.rewind()

        // Outputs are told apart by shape; of the two per-person lists the last one is the score.
        val outputs = HashMap<Int, Any>()
        var pointsIndex = -1
        var pointScoresIndex = -1
        var scoresIndex = -1
        for (i in 0 until model.outputTensorCount) {
            val shape = model.getOutputTensor(i).shape()
            outputs[i] = floatBuffer(model.getOutputTensor(i).numElements())
            when {
                shape.size == 4 && shape[3] == 2 -> pointsIndex = i
                shape.size == 3 && shape[2] == POSE_KEYPOINTS -> pointScoresIndex = i
                shape.size == 2 -> scoresIndex = i
            }
        }
        if (pointsIndex < 0 || pointScoresIndex < 0 || scoresIndex < 0) return FloatArray(0)
        model.runForMultipleInputsOutputs(arrayOf<Any>(inBuf), outputs)

        fun floats(index: Int) = (outputs[index] as ByteBuffer).apply { rewind() }.asFloatBuffer()
        val scores = floats(scoresIndex)
        val points = floats(pointsIndex)
        val pointScores = floats(pointScoresIndex)
        val centres = ArrayList<Float>()
        for (person in 0 until POSE_MAX_PEOPLE) {
            if (scores.get(person) < PERSON_MIN_SCORE) continue
            var sumX = 0f
            var sumY = 0f
            var count = 0
            for (k in 0 until POSE_KEYPOINTS) {
                if (pointScores.get(person * POSE_KEYPOINTS + k) < KEYPOINT_MIN_SCORE) continue
                // Keypoints come as (y, x).
                sumY += points.get((person * POSE_KEYPOINTS + k) * 2)
                sumX += points.get((person * POSE_KEYPOINTS + k) * 2 + 1)
                count++
            }
            if (count == 0) continue
            centres.add((sumX / count).coerceIn(0f, 1f))
            centres.add((sumY / count).coerceIn(0f, 1f))
        }
        return centres.toFloatArray()
    }

    private fun interpreter(context: Context, asset: String): Interpreter? {
        if (interpreters.containsKey(asset)) return interpreters[asset]
        val created = try {
            context.assets.openFd(asset).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    val model = stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    create(context, model, asset)
                }
            }
        } catch (e: IOException) {
            null // model not bundled in this build
        }
        interpreters[asset] = created
        return created
    }

    /** GPU first: several times faster than the CPU here. Any failure falls back to the CPU. */
    private fun create(context: Context, model: ByteBuffer, asset: String): Interpreter {
        try {
            // The GPU programs built for a model are kept on disk, so later launches skip the wait.
            val options = GpuDelegateFactory.Options().setSerializationParams(
                context.codeCacheDir.absolutePath, asset.substringAfterLast('/').substringBefore('.')
            )
            val gpu = GpuDelegate(options)
            try {
                return Interpreter(model, Interpreter.Options().addDelegate(gpu)).also {
                    Log.i(TAG, "$asset runs on the GPU")
                }
            } catch (e: Exception) {
                gpu.close()
                Log.i(TAG, "$asset cannot use the GPU: ${e.message?.take(120)}")
            }
        } catch (e: Throwable) {
            Log.i(TAG, "No GPU delegate on this device: ${e.message?.take(120)}")
        }
        return Interpreter(model, Interpreter.Options().setNumThreads(THREADS))
    }

    private fun hasAsset(context: Context, asset: String): Boolean = try {
        context.assets.openFd(asset).close()
        true
    } catch (e: IOException) {
        false
    }

    private fun floatBuffer(floats: Int): ByteBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

    /** Packs ARGB pixels as RGB floats in -1..1, the range these models were trained on. */
    private fun rgbBuffer(pixels: IntArray): ByteBuffer {
        val buffer = floatBuffer(pixels.size * 3)
        val floats = buffer.asFloatBuffer()
        val rgb = FloatArray(pixels.size * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            rgb[i * 3] = ((p shr 16) and 0xFF) / 127.5f - 1f
            rgb[i * 3 + 1] = ((p shr 8) and 0xFF) / 127.5f - 1f
            rgb[i * 3 + 2] = (p and 0xFF) / 127.5f - 1f
        }
        floats.put(rgb)
        return buffer
    }

    private fun resizePixels(pixels: IntArray, w: Int, h: Int, dw: Int, dh: Int): IntArray {
        val src = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(src, dw, dh, true)
        val out = IntArray(dw * dh)
        scaled.getPixels(out, 0, dw, 0, 0, dw, dh)
        src.recycle()
        if (scaled !== src) scaled.recycle()
        return out
    }

    private fun transposePixels(pixels: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[x * h + y] = pixels[y * w + x]
        return out
    }
}
