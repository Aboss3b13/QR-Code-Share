import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import io.github.aboss3b13.qrshare.protocol.TransferSession
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Cross-checks the Windows sender against the Android decoder.
 *
 *   lines  <packets.txt> <original> [dropRate]   Base45 packets exported with --export-packets
 *   frames <frameDir>    <original> [scale]      PNG frames exported with --export-frames; every
 *                                                frame is shrunk by `scale` (camera simulation) and
 *                                                all QR codes in it are decoded.
 */
fun main(args: Array<String>) {
    val original = if (args[0] == "grid") ByteArray(0) else File(args[2]).readBytes()
    when (args[0]) {
        "lines" -> lines(File(args[1]), original, args.getOrNull(3)?.toDouble() ?: 0.3)
        "frames" -> frames(File(args[1]), original, args.getOrNull(3)?.toDouble() ?: 0.85)
        "grid" -> gridSim(args.drop(1))
        else -> error("unknown mode")
    }
}

fun lines(file: File, original: ByteArray, dropRate: Double) {
    val packets = file.readLines().filter { it.isNotEmpty() }
    val rnd = Random(42)
    val session = TransferSession()
    var used = 0
    val stream = packets.filter { rnd.nextDouble() >= dropRate }.chunked(5).flatMap { it.shuffled(rnd) }
    val t0 = System.nanoTime()
    for (p in stream) {
        used++
        if (check(session.onQrText(p), original, "after $used received packets (of ${packets.size} sent, drop rate $dropRate)", t0)) return
    }
    error("Not complete after $used packets")
}

fun frames(dir: File, original: ByteArray, scale: Double) {
    val files = dir.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }
    val reader = QRCodeMultiReader()
    val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    val session = TransferSession()
    var codes = 0
    val t0 = System.nanoTime()
    for ((i, f) in files.withIndex()) {
        val src = ImageIO.read(f)
        val w = (src.width * scale).toInt(); val h = (src.height * scale).toInt()
        val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        // Area averaging blurs like camera optics instead of aliasing like plain resampling.
        val scaled = src.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING)
        img.createGraphics().apply { drawImage(scaled, 0, 0, null); dispose() }
        val results = if (System.getenv("QS_CELLS") == null) try {
            reader.decodeMultiple(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(img))), hints)
        } catch (e: Exception) { emptyArray() } else {
            // Decode each grid cell on its own (isolates code readability from multi-code detection).
            val (cols, rows) = System.getenv("QS_CELLS").split("x").map { it.toInt() }
            (0 until cols * rows).mapNotNull { c ->
                val cw = w / cols; val ch = h / rows
                val sub = img.getSubimage((c % cols) * cw, (c / cols) * ch, cw, ch)
                try { com.google.zxing.qrcode.QRCodeReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(sub))), hints) } catch (e: Exception) { null }
            }.toTypedArray()
        }
        if (i == 0) println("frame0: ${results.size} codes found at ${w}x$h")
        for (r in results) {
            codes++
            if (check(session.onQrText(r.text), original, "after ${i + 1} frames, $codes codes", t0)) return
        }
    }
    error("Not complete after ${files.size} frames ($codes codes)")
}

fun check(ev: TransferSession.Event, original: ByteArray, what: String, t0: Long): Boolean = when (ev) {
    is TransferSession.Event.Completed -> {
        val ok = ev.file.data.contentEquals(original)
        println("Completed '${ev.file.name}' $what in ${(System.nanoTime() - t0) / 1_000_000} ms: match=$ok")
        if (!ok) error("MISMATCH")
        true
    }
    is TransferSession.Event.Failed -> error("Failed: ${ev.message}")
    else -> false
}
