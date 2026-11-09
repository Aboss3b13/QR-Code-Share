import io.github.aboss3b13.qrshare.protocol.TransferSession
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Feeds C#-generated packets to the Kotlin decoder, dropping and shuffling them like a real camera would. */
fun main(args: Array<String>) {
    val packets = ArrayList<ByteArray>()
    DataInputStream(File(args[0]).inputStream().buffered()).use { input ->
        val lenBuf = ByteArray(4)
        while (input.read(lenBuf) == 4) {
            val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int
            packets.add(ByteArray(len).also { input.readFully(it) })
        }
    }
    val original = File(args[1]).readBytes()
    val dropRate = args.getOrNull(2)?.toDouble() ?: 0.3
    val rnd = Random(42)
    val session = TransferSession()
    var used = 0
    // Drop a share of frames and locally shuffle the rest.
    val stream = packets.filter { rnd.nextDouble() >= dropRate }.chunked(5).flatMap { it.shuffled(rnd) }
    for (p in stream) {
        used++
        when (val ev = session.onQrBytes(p)) {
            is TransferSession.Event.Completed -> {
                val ok = ev.file.data.contentEquals(original)
                println("Completed '${ev.file.name}' after $used received packets (of ${packets.size} sent, drop rate $dropRate): match=$ok")
                if (!ok) error("MISMATCH")
                return
            }
            is TransferSession.Event.Failed -> error("Failed: ${ev.message}")
            else -> {}
        }
    }
    error("Not complete after $used packets")
}
