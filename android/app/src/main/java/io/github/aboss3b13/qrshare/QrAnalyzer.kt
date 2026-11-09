package io.github.aboss3b13.qrshare

import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.aboss3b13.qrshare.protocol.AnchorTracker
import io.github.aboss3b13.qrshare.protocol.Box
import io.github.aboss3b13.qrshare.protocol.GridDecoder
import io.github.aboss3b13.qrshare.protocol.GridFrameResult
import io.github.aboss3b13.qrshare.protocol.GridHeader
import io.github.aboss3b13.qrshare.protocol.LumaFrame

/**
 * Reads camera frames in two modes, detected automatically:
 *
 *  - QR mode: ML Kit finds and decodes every QR code in the frame (packets as Base45 text).
 *  - Grid mode: ML Kit finds the 9 anchor QR codes once; after that the anchors are
 *    tracked with ZXing in the full-resolution frame and the dense cell grid between
 *    them is sampled directly (no ML Kit per frame).
 *
 * ML Kit always gets a frame of at most ~2000 px width so it stays fast even when the
 * camera delivers 4K for the grid.
 */
class QrAnalyzer(
    private val onCodes: (List<String>) -> Unit,
    private val onGrid: (GridFrameResult) -> Unit,
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    )
    private val grid = GridDecoder()
    private val tracker = AnchorTracker()
    private var luma = ByteArray(0)
    private var nv21 = ByteArray(0)

    @Volatile var framesAnalyzed = 0L
        private set
    @Volatile var frameSize = ""
        private set

    override fun analyze(image: ImageProxy) {
        try {
            val frame = copyLuma(image)
            frameSize = "${frame.width}×${frame.height}"
            framesAnalyzed++

            if (tracker.locked) {
                val r = grid.decodeFrame(frame, grid.decodeAnchors(frame, tracker.trackedBoxes()))
                if (r.anchors.size >= 4) {
                    tracker.update(r.anchors)
                    onGrid(r)
                    return
                }
                tracker.reset()
            }
            scanWithMlKit(frame)
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun scanWithMlKit(frame: LumaFrame) {
        val factor = if (frame.width > 2000) 2 else 1
        val input = downscaledNv21(frame, factor)
        // Runs on this analyzer thread; the next frame waits (KEEP_ONLY_LATEST drops the backlog).
        val codes = Tasks.await(scanner.process(input))
        val anchors = codes.filter { it.rawValue?.let(GridHeader::isGridAnchor) == true && it.boundingBox != null }
        if (anchors.size >= 4) {
            val boxes = anchors.map { scaled(it.boundingBox!!, factor).expanded(0.35, frame.width, frame.height) }
            val r = grid.decodeFrame(frame, grid.decodeAnchors(frame, boxes))
            tracker.update(r.anchors)
            onGrid(r)
            return
        }
        val texts = codes.mapNotNull { it.rawValue }
        if (texts.isNotEmpty()) onCodes(texts)
    }

    private fun scaled(r: Rect, f: Int) = Box(r.left * f, r.top * f, r.right * f, r.bottom * f)

    private fun copyLuma(image: ImageProxy): LumaFrame {
        val plane = image.planes[0]
        val w = image.width; val h = image.height
        val rowStride = plane.rowStride
        val buf = plane.buffer
        buf.rewind()
        if (luma.size != w * h) luma = ByteArray(w * h)
        if (rowStride == w) buf.get(luma, 0, w * h)
        else for (row in 0 until h) {
            buf.position(row * rowStride)
            buf.get(luma, row * w, w)
        }
        // A fresh array per frame would be cleaner, but the decode finishes before the next frame.
        return LumaFrame(luma, w, h)
    }

    /** Grey NV21 image (luma, neutral chroma) for ML Kit, box-filtered down by [f]. */
    private fun downscaledNv21(frame: LumaFrame, f: Int): InputImage {
        val w = frame.width / f; val h = frame.height / f
        val size = w * h * 3 / 2
        if (nv21.size != size) nv21 = ByteArray(size)
        val src = frame.data
        if (f == 1) {
            System.arraycopy(src, 0, nv21, 0, w * h)
        } else {
            for (y in 0 until h) {
                val r0 = 2 * y * frame.width; val r1 = r0 + frame.width
                for (x in 0 until w) {
                    val i = 2 * x
                    val s = (src[r0 + i].toInt() and 0xFF) + (src[r0 + i + 1].toInt() and 0xFF) +
                        (src[r1 + i].toInt() and 0xFF) + (src[r1 + i + 1].toInt() and 0xFF)
                    nv21[y * w + x] = (s shr 2).toByte()
                }
            }
        }
        java.util.Arrays.fill(nv21, w * h, size, 128.toByte())
        return InputImage.fromByteArray(nv21, w, h, 0, InputImage.IMAGE_FORMAT_NV21)
    }

    fun close() = scanner.close()
}
