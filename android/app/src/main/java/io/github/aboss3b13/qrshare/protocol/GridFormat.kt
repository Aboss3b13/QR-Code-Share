package io.github.aboss3b13.qrshare.protocol

import java.nio.ByteBuffer

/**
 * Dense grid mode (PROTOCOL.md, part B): the screen is a grid of cells carrying 1 or 2
 * bits each, with 9 small anchor QR codes that tell the receiver the grid geometry and
 * where the cells are in the camera image. Must stay in sync with GridFormat.cs.
 */
object GridFormat {
    const val ANCHOR_MODULES = 25 // QR version 2
    const val QUIET = 3
    /** Anchor footprint in cells; each anchor module is ams x ams cells. */
    fun footprint(ams: Int) = (ANCHOR_MODULES + 2 * QUIET) * ams
    const val HEADER_SIZE = 23
    const val CW_LEN = 255
    /** Brightness level (0 = dark) -> 2-bit value; Gray code, its own inverse. */
    val GRAY_OF_LEVEL = intArrayOf(0, 1, 3, 2)

    fun anchorOrigins(cols: Int, rows: Int, ams: Int): List<Pair<Int, Int>> {
        val f = footprint(ams)
        val xs = intArrayOf(0, (cols - f) / 2, cols - f)
        val ys = intArrayOf(0, (rows - f) / 2, rows - f)
        return (0 until 9).map { xs[it % 3] to ys[it / 3] }
    }

    fun anchorMask(cols: Int, rows: Int, ams: Int): BooleanArray {
        val mask = BooleanArray(cols * rows)
        val f = footprint(ams)
        for ((ax, ay) in anchorOrigins(cols, rows, ams))
            for (y in ay until ay + f) for (x in ax until ax + f) mask[y * cols + x] = true
        return mask
    }

    fun bandCells(cols: Int, rows: Int, bands: Int, mask: BooleanArray): List<IntArray> = (0 until bands).map { b ->
        val r0 = b * rows / bands
        val r1 = (b + 1) * rows / bands
        val list = ArrayList<Int>((r1 - r0) * cols)
        for (y in r0 until r1) for (x in 0 until cols) if (!mask[y * cols + x]) list.add(y * cols + x)
        list.toIntArray()
    }

    fun packetsInBand(cellCount: Int, bpc: Int, cwPerPacket: Int) = cellCount * bpc / 8 / CW_LEN / cwPerPacket

    fun scramble(band: Int, length: Int): ByteArray {
        val rng = Protocol.XorShift(0x51470000 + band)
        val s = ByteArray(length)
        var x = 0
        for (i in 0 until length) {
            if (i and 3 == 0) x = rng.next()
            s[i] = (x ushr (24 - 8 * (i and 3))).toByte()
        }
        return s
    }
}

/** Contents of an anchor QR code. */
data class GridHeader(
    val anchorIndex: Int,
    val transferId: Int,
    val payloadLength: Int,
    val cols: Int,
    val rows: Int,
    val bpc: Int,
    val ec: Int,
    val cwPerPacket: Int,
    val bands: Int,
    val ams: Int,
) {
    val dataPerCw get() = GridFormat.CW_LEN - ec
    val blockSize get() = cwPerPacket * dataPerCw - 8

    /** Same transfer and geometry (anchor index may differ). */
    fun sameFrameAs(o: GridHeader) = transferId == o.transferId && payloadLength == o.payloadLength &&
        cols == o.cols && rows == o.rows && bpc == o.bpc && ec == o.ec && cwPerPacket == o.cwPerPacket && bands == o.bands && ams == o.ams

    companion object {
        fun parse(text: String): GridHeader? {
            val b = Base45.decode(text) ?: return null
            if (b.size != GridFormat.HEADER_SIZE || b[0] != 0x51.toByte() || b[1] != 0x47.toByte() || b[2] != 1.toByte()) return null
            val bb = ByteBuffer.wrap(b)
            val h = GridHeader(
                anchorIndex = b[3].toInt(),
                transferId = bb.getInt(4),
                payloadLength = bb.getInt(8),
                cols = bb.getShort(12).toInt() and 0xFFFF,
                rows = bb.getShort(14).toInt() and 0xFFFF,
                bpc = b[16].toInt(),
                ec = b[17].toInt() and 0xFF,
                cwPerPacket = b[18].toInt(),
                bands = b[19].toInt() and 0xFF,
                ams = b[20].toInt(),
            )
            val ok = h.anchorIndex in 0..8 && h.payloadLength > 0 && h.bpc in 1..2 && h.ec in 2..200 && h.ec % 2 == 0 &&
                h.cwPerPacket in 1..16 && h.bands in 1..h.rows && h.ams in 1..8 && h.cols >= 3 * GridFormat.footprint(h.ams) && h.rows >= 3 * GridFormat.footprint(h.ams) &&
                h.blockSize > 0
            return if (ok) h else null
        }

        fun isGridAnchor(text: String) = text.length == 35 && parse(text) != null
    }
}
