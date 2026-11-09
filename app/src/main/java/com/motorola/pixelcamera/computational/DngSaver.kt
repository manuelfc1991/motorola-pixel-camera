package com.motorola.pixelcamera.computational

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

/**
 * Saves uncompressed RAW (DNG) captures alongside computational JPEG outputs.
 */
object DngSaver {
    private const val TAG = "DngSaver"

    fun saveRawBuffer(context: Context, rawBytes: ByteArray, width: Int, height: Int): Uri? {
        val name = "RAW_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_PXL"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.dng")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(rawBytes)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Log.i(TAG, "RAW DNG saved successfully: $uri")
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving RAW DNG", e)
            resolver.delete(uri, null, null)
            return null
        }
    }
}
