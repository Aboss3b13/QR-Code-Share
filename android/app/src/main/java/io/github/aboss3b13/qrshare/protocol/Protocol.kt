package io.github.aboss3b13.qrshare.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.Inflater

/** QR Share Transfer Protocol v1 — see PROTOCOL.md. Pure Kotlin, no Android dependencies. */
object Protocol {
    const val HEADER_SIZE = 18
    const val VERSION: Byte = 1
    private val DEGREES = intArrayOf(1, 2, 3, 4, 8, 16, 32, 64)
    private val CUMULATIVE = intArrayOf(10, 110, 210, 310, 500, 750, 900, 1000)

    fun blockCount(payloadLength: Int, blockSize: Int): Int = (payloadLength + blockSize - 1) / blockSize

    /** Block indices combined in the packet with the given seed. */
    fun indicesForSeed(seed: Int, k: Int): IntArray {
        if ((seed.toLong() and 0xFFFFFFFFL) < k.toLong()) return intArrayOf(seed)
        val rng = XorShift(seed)
        val r = rng.nextMod(1000)
        var d = DEGREES.last()
        for (i in CUMULATIVE.indices) if (r < CUMULATIVE[i]) { d = DEGREES[i]; break }
        d = minOf(d, k)
        val chosen = LinkedHashSet<Int>()
        while (chosen.size < d) chosen.add(rng.nextMod(k))
        return chosen.toIntArray()
    }

    class XorShift(seed: Int) {
        private var state: Int = (seed * 0x9E3779B1.toInt()) xor 0x85EBCA6B.toInt()

        init { if (state == 0) state = 0x6D2B79F5 }

        fun next(): Int {
            var x = state
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            state = x
            return x
        }

        fun nextMod(n: Int): Int = ((next().toLong() and 0xFFFFFFFFL) % n).toInt()
    }
}

class Packet(
    val transferId: Int,
    val payloadLength: Int,
    val blockSize: Int,
    val seed: Int,
    val data: ByteArray,
) {
    companion object {
        /** Returns null if the bytes are not a valid QS v1 packet. */
        fun parse(bytes: ByteArray): Packet? {
            if (bytes.size < Protocol.HEADER_SIZE) return null
            if (bytes[0] != 0x51.toByte() || bytes[1] != 0x53.toByte()) return null
            if (bytes[2] != Protocol.VERSION) return null
            val buf = ByteBuffer.wrap(bytes)
            val transferId = buf.getInt(4)
            val length = buf.getInt(8)
            val blockSize = buf.getShort(12).toInt() and 0xFFFF
            val seed = buf.getInt(14)
            if (length <= 0 || blockSize <= 0) return null
            if (bytes.size != Protocol.HEADER_SIZE + blockSize) return null
            return Packet(transferId, length, blockSize, seed, bytes.copyOfRange(Protocol.HEADER_SIZE, bytes.size))
        }
    }
}

class ReceivedFile(val name: String, val data: ByteArray)

class TransferException(message: String) : Exception(message)

/** Peeling fountain decoder for one transfer. */
class FountainDecoder(val transferId: Int, val payloadLength: Int, val blockSize: Int) {
    companion object {
        const val MAX_GAUSS_UNKNOWNS = 1500
    }

    val blockCount = Protocol.blockCount(payloadLength, blockSize)
    private val known = arrayOfNulls<ByteArray>(blockCount)
    var knownCount = 0
        private set
    var packetsReceived = 0
        private set
    private val seenSeeds = HashSet<Int>()

    private class Equation(val unknown: MutableSet<Int>, val data: ByteArray)
    private val waiting = HashMap<Int, MutableList<Equation>>()
    private val pending = LinkedHashSet<Equation>()
    private var nextSolveAt = 0

    val isComplete get() = knownCount == blockCount

    /** Returns true if the packet was new to this decoder. */
    fun add(packet: Packet): Boolean {
        if (packet.transferId != transferId || packet.blockSize != blockSize || packet.payloadLength != payloadLength) return false
        if (!seenSeeds.add(packet.seed)) return false
        packetsReceived++
        if (isComplete) return true
        val data = packet.data.copyOf()
        val unknown = HashSet<Int>()
        for (i in Protocol.indicesForSeed(packet.seed, blockCount)) {
            val k = known[i]
            if (k != null) xorInto(data, k) else unknown.add(i)
        }
        when (unknown.size) {
            0 -> {}
            1 -> resolve(unknown.first(), data)
            else -> {
                val eq = Equation(unknown, data)
                pending.add(eq)
                for (i in unknown) waiting.getOrPut(i) { ArrayList() }.add(eq)
            }
        }
        maybeSolve()
        return true
    }

    private fun resolve(first: Int, firstData: ByteArray) {
        val queue = ArrayDeque<Pair<Int, ByteArray>>()
        queue.add(first to firstData)
        while (queue.isNotEmpty()) {
            val (idx, data) = queue.removeFirst()
            if (known[idx] != null) continue
            known[idx] = data
            knownCount++
            val eqs = waiting.remove(idx) ?: continue
            for (eq in eqs) {
                if (!eq.unknown.remove(idx)) continue
                xorInto(eq.data, data)
                if (eq.unknown.size <= 1) pending.remove(eq)
                if (eq.unknown.size == 1) {
                    val last = eq.unknown.first()
                    eq.unknown.clear()
                    waiting[last]?.remove(eq)
                    queue.add(last to eq.data)
                }
            }
        }
    }

    /**
     * When peeling stalls but there are at least as many stored equations as unknown
     * blocks, solve the remaining system with Gaussian elimination over GF(2).
     * This brings the overhead close to the theoretical minimum.
     */
    private fun maybeSolve() {
        val u = blockCount - knownCount
        if (u == 0 || u > MAX_GAUSS_UNKNOWNS || pending.size < u || packetsReceived < nextSolveAt) return
        nextSolveAt = packetsReceived + maxOf(2, u / 25)

        val cols = IntArray(u)
        val colOf = IntArray(blockCount) { -1 }
        var c = 0
        for (i in 0 until blockCount) if (known[i] == null) { colOf[i] = c; cols[c++] = i }
        val words = (u + 63) / 64
        val rows = pending.toMutableList()
        val bits = Array(rows.size) { r ->
            LongArray(words).also { w -> for (i in rows[r].unknown) { val col = colOf[i]; w[col shr 6] = w[col shr 6] or (1L shl (col and 63)) } }
        }
        // Rank check on a copy first, so the expensive data XORs only run when we will succeed.
        if (rank(Array(bits.size) { bits[it].copyOf() }, u) < u) return

        val data = Array(rows.size) { rows[it].data }
        for (col in 0 until u) {
            val w = col shr 6
            val m = 1L shl (col and 63)
            var p = col
            while (bits[p][w] and m == 0L) p++
            if (p != col) {
                val tb = bits[p]; bits[p] = bits[col]; bits[col] = tb
                val td = data[p]; data[p] = data[col]; data[col] = td
            }
            for (r in bits.indices) {
                if (r != col && bits[r][w] and m != 0L) {
                    val rb = bits[r]; val pb = bits[col]
                    for (k in 0 until words) rb[k] = rb[k] xor pb[k]
                    xorInto(data[r], data[col])
                }
            }
        }
        for (col in 0 until u) known[cols[col]] = data[col]
        knownCount = blockCount
        pending.clear()
        waiting.clear()
    }

    private fun rank(m: Array<LongArray>, cols: Int): Int {
        var rank = 0
        for (col in 0 until cols) {
            val w = col shr 6
            val bit = 1L shl (col and 63)
            var p = rank
            while (p < m.size && m[p][w] and bit == 0L) p++
            if (p == m.size) return rank
            val t = m[p]; m[p] = m[rank]; m[rank] = t
            for (r in rank + 1 until m.size) if (m[r][w] and bit != 0L) {
                val rr = m[r]; val pr = m[rank]
                for (k in w until rr.size) rr[k] = rr[k] xor pr[k]
            }
            rank++
        }
        return rank
    }

    private fun xorInto(target: ByteArray, src: ByteArray) {
        for (i in target.indices) target[i] = (target[i].toInt() xor src[i].toInt()).toByte()
    }

    fun payload(): ByteArray {
        check(isComplete)
        val out = ByteArray(payloadLength)
        for (i in 0 until blockCount) {
            val start = i * blockSize
            System.arraycopy(known[i]!!, 0, out, start, minOf(blockSize, payloadLength - start))
        }
        return out
    }

    /** Parses, decompresses and verifies the completed payload. */
    fun buildFile(): ReceivedFile = PayloadCodec.decode(payload())
}

object PayloadCodec {
    fun decode(payload: ByteArray): ReceivedFile {
        val buf = ByteBuffer.wrap(payload)
        val compression = buf.get().toInt()
        val nameLen = buf.getShort().toInt() and 0xFFFF
        val nameBytes = ByteArray(nameLen).also { buf.get(it) }
        val size = buf.getLong()
        val hash = ByteArray(32).also { buf.get(it) }
        val body = ByteArray(buf.remaining()).also { buf.get(it) }
        val data = when (compression) {
            0 -> body
            1 -> inflate(body, size)
            else -> throw TransferException("Unknown compression $compression")
        }
        if (data.size.toLong() != size) throw TransferException("Size mismatch")
        if (!MessageDigest.getInstance("SHA-256").digest(data).contentEquals(hash)) {
            throw TransferException("SHA-256 mismatch — file is corrupted")
        }
        val name = sanitize(String(nameBytes, Charsets.UTF_8))
        return ReceivedFile(name, data)
    }

    private fun inflate(body: ByteArray, expected: Long): ByteArray {
        val inflater = Inflater()
        inflater.setInput(body)
        val out = ByteArrayOutputStream(expected.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val buf = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw TransferException("Truncated zlib stream")
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun sanitize(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim()
        return cleaned.ifEmpty { "received.bin" }
    }
}
