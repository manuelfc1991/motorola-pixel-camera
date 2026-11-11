package com.motorola.pixelcamera.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrScannerTest {

    private fun qrLuma(text: String, size: Int): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        return ByteArray(size * size) { i -> if (matrix[i % size, i / size]) 20 else 235.toByte() }
    }

    @Test
    fun readsTheTextOfAQrCode() {
        val size = 320
        assertEquals("https://example.com/menu", QrScanner().decode(qrLuma("https://example.com/menu", size), size, size))
    }

    @Test
    fun returnsNullWhenThereIsNoCode() {
        val size = 320
        assertNull(QrScanner().decode(ByteArray(size * size) { 128.toByte() }, size, size))
    }
}
