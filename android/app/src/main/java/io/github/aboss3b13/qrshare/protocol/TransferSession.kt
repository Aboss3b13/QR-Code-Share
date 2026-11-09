package io.github.aboss3b13.qrshare.protocol

/** Tracks the transfer currently being received. Thread-safe. */
class TransferSession {
    sealed class Event {
        data class Progress(val known: Int, val total: Int, val packets: Int, val payloadLength: Int) : Event()
        data class Completed(val file: ReceivedFile) : Event()
        data class Failed(val message: String) : Event()
        object Ignored : Event()
    }

    private var decoder: FountainDecoder? = null
    private val finishedTransfers = HashSet<Int>()

    @Synchronized
    fun reset() {
        decoder = null
    }

    @Synchronized
    fun onQrBytes(bytes: ByteArray): Event {
        val packet = Packet.parse(bytes) ?: return Event.Ignored
        if (packet.transferId in finishedTransfers) return Event.Ignored
        var d = decoder
        if (d == null || d.transferId != packet.transferId || d.blockSize != packet.blockSize) {
            d = FountainDecoder(packet.transferId, packet.payloadLength, packet.blockSize)
            decoder = d
        }
        if (!d.add(packet)) return Event.Ignored
        if (!d.isComplete) return Event.Progress(d.knownCount, d.blockCount, d.packetsReceived, d.payloadLength)

        decoder = null
        return try {
            val file = d.buildFile()
            finishedTransfers.add(packet.transferId)
            Event.Completed(file)
        } catch (e: Exception) {
            Event.Failed(e.message ?: e.toString())
        }
    }
}
