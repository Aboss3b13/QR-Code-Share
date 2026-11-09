package io.github.aboss3b13.qrshare

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import io.github.aboss3b13.qrshare.databinding.ActivityMainBinding
import io.github.aboss3b13.qrshare.protocol.ReceivedFile
import io.github.aboss3b13.qrshare.protocol.TransferSession
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private val session = TransferSession()
    private lateinit var analyzer: QrAnalyzer

    @Volatile private var paused = false
    private var savedUri: Uri? = null
    private var savedMime: String = "application/octet-stream"

    // Statistics for the progress line
    private var transferStart = 0L
    private var lastUiUpdate = 0L
    private var qrCount = 0
    private var lastRateTime = 0L
    private var lastRateQr = 0
    private var lastRateFrames = 0L
    private var qrPerSec = 0.0
    private var framesPerSec = 0.0

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else binding.status.text = "Camera permission is needed to receive files."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()
        analyzer = QrAnalyzer(::onQrBytes)

        binding.againButton.setOnClickListener { receiveAnother() }
        binding.openButton.setOnClickListener { openFile() }
        binding.shareButton.setOnClickListener { shareFile() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                binding.status.text = "Could not start camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Called on the camera thread for every decoded QR code. */
    private fun onQrBytes(bytes: ByteArray) {
        if (paused) return
        when (val ev = session.onQrBytes(bytes)) {
            is TransferSession.Event.Progress -> {
                val now = SystemClock.elapsedRealtime()
                if (transferStart == 0L) transferStart = now
                qrCount++
                if (now - lastUiUpdate < 100 && ev.known < ev.total) return
                lastUiUpdate = now
                runOnUiThread { showProgress(ev, now) }
            }
            is TransferSession.Event.Completed -> {
                paused = true
                val file = ev.file
                runOnUiThread { onFileReceived(file) }
            }
            is TransferSession.Event.Failed -> runOnUiThread {
                Toast.makeText(this, "Transfer failed: ${ev.message}. Keep scanning to retry.", Toast.LENGTH_LONG).show()
                resetStats()
            }
            TransferSession.Event.Ignored -> {}
        }
    }

    private fun showProgress(ev: TransferSession.Event.Progress, now: Long) {
        if (now - lastRateTime >= 1000) {
            val dt = (now - lastRateTime) / 1000.0
            if (lastRateTime != 0L) {
                qrPerSec = (qrCount - lastRateQr) / dt
                framesPerSec = (analyzer.framesAnalyzed - lastRateFrames) / dt
            }
            lastRateTime = now
            lastRateQr = qrCount
            lastRateFrames = analyzer.framesAnalyzed
        }
        val fraction = ev.known.toDouble() / ev.total
        binding.progress.setProgressCompat((fraction * 1000).toInt(), true)
        binding.status.text = String.format(Locale.US, "Receiving… %.0f%%", fraction * 100)
        val elapsed = (now - transferStart) / 1000.0
        binding.detail.text = String.format(
            Locale.US,
            "%s of %s  ·  blocks %d/%d\n%d QR read  ·  %.1f QR/s  ·  camera %.0f fps  ·  %.0fs",
            formatSize(ev.known.toLong() * ev.payloadLength / ev.total), formatSize(ev.payloadLength.toLong()),
            ev.known, ev.total, ev.packets, qrPerSec, framesPerSec, elapsed,
        )
    }

    private fun onFileReceived(file: ReceivedFile) {
        binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val seconds = (SystemClock.elapsedRealtime() - transferStart) / 1000.0
        try {
            savedUri = saveToDownloads(file)
            binding.doneTitle.text = "File received · SHA-256 verified"
            binding.doneDetail.text = String.format(Locale.US, "%s in %.0fs · saved to Download/QR Share", formatSize(file.data.size.toLong()), seconds)
        } catch (e: Exception) {
            savedUri = null
            binding.doneTitle.text = "Received, but saving failed"
            binding.doneDetail.text = e.message
        }
        binding.doneName.text = file.name
        binding.openButton.isEnabled = savedUri != null
        binding.shareButton.isEnabled = savedUri != null
        binding.progressPanel.visibility = View.GONE
        binding.guide.visibility = View.GONE
        binding.donePanel.visibility = View.VISIBLE
    }

    private fun saveToDownloads(file: ReceivedFile): Uri {
        val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        savedMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, savedMime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/QR Share")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create file in Downloads")
        resolver.openOutputStream(uri)!!.use { it.write(file.data) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun openFile() {
        val uri = savedUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, savedMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this file type.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile() {
        val uri = savedUri ?: return
        val intent = Intent(Intent.ACTION_SEND).setType(savedMime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share file"))
    }

    private fun receiveAnother() {
        session.reset()
        resetStats()
        binding.donePanel.visibility = View.GONE
        binding.progressPanel.visibility = View.VISIBLE
        binding.guide.visibility = View.VISIBLE
        binding.progress.setProgressCompat(0, false)
        binding.status.text = "Waiting for QR code…"
        binding.detail.text = " "
        paused = false
    }

    private fun resetStats() {
        transferStart = 0L; qrCount = 0; lastRateTime = 0L; lastRateQr = 0; qrPerSec = 0.0
    }

    private fun formatSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", b / 1024.0 / 1024.0)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
