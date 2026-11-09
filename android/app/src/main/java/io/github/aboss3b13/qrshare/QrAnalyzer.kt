package io.github.aboss3b13.qrshare

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor

/**
 * Finds and decodes every QR code in a camera frame with ML Kit (bundled model,
 * runs fully on-device and offline). The sender shows several codes per frame,
 * so this returns all of them at once.
 */
class QrAnalyzer(
    private val callbackExecutor: Executor,
    private val onCodes: (List<String>) -> Unit,
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    )

    @Volatile var framesAnalyzed = 0L
        private set

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        // QR codes are rotation-invariant, so skip ML Kit's rotation step.
        val input = InputImage.fromMediaImage(media, 0)
        scanner.process(input)
            .addOnSuccessListener(callbackExecutor) { codes ->
                framesAnalyzed++
                val texts = codes.mapNotNull { it.rawValue }
                if (texts.isNotEmpty()) onCodes(texts)
            }
            .addOnCompleteListener(callbackExecutor) { image.close() }
    }

    fun close() = scanner.close()
}
