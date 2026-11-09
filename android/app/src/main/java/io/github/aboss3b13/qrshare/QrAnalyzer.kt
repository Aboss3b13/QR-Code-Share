package io.github.aboss3b13.qrshare

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayOutputStream

/** Decodes QR codes from camera frames and hands out the raw binary content. */
class QrAnalyzer(private val onQr: (ByteArray) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.CHARACTER_SET to "ISO-8859-1")
    private var buffer = ByteArray(0)

    @Volatile var framesAnalyzed = 0L
        private set

    override fun analyze(image: ImageProxy) {
        try {
            framesAnalyzed++
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val src = plane.buffer
            src.rewind()
            if (buffer.size != width * height) buffer = ByteArray(width * height)
            if (rowStride == width) {
                src.get(buffer, 0, width * height)
            } else {
                for (row in 0 until height) {
                    src.position(row * rowStride)
                    src.get(buffer, row * width, width)
                }
            }
            // Only look at the centered square: faster, and that's where the user aims.
            val side = minOf(width, height)
            val left = (width - side) / 2
            val top = (height - side) / 2
            val source = PlanarYUVLuminanceSource(buffer, width, height, left, top, side, side, false)
            val result = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            } catch (e: NotFoundException) {
                null
            } catch (e: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            if (result != null) onQr(rawBytes(result))
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun rawBytes(result: com.google.zxing.Result): ByteArray {
        @Suppress("UNCHECKED_CAST")
        val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>
        if (!segments.isNullOrEmpty()) {
            val out = ByteArrayOutputStream()
            segments.forEach { out.write(it) }
            return out.toByteArray()
        }
        return result.text.toByteArray(Charsets.ISO_8859_1)
    }
}
