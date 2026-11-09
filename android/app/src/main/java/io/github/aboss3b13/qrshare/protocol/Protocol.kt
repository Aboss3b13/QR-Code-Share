package io.github.aboss3b13.qrshare.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.Inflater

/** QR Share Transfer Protocol v2 — see PROTOCOL.md. Pure Kotlin, no Android dependencies. */
object Protocol {
    const val HEADER_SIZE = 18
    const val VERSION: Byte = 2
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
        /** Parses the Base45 text content of a QR code. Returns null if it is not a QS v2 packet. */
        fun parseText(text: String): Packet? = Base45.decode(text)?.let { parse(it) }

        /** Returns null if the bytes are not a valid QS v2 packet. */
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

/** RFC 9285 Base45: maps onto the QR alphanumeric charset (~97% as dense as byte mode). */
object Base45 {
    private const val CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    private val VALUES = IntArray(128) { -1 }.also { t -> CHARSET.forEachIndexed { i, c -> t[c.code] = i } }

    fun decode(s: String): ByteArray? {
        val n = s.length
        if (n % 3 == 1) return null
        val out = ByteArray(n / 3 * 2 + if (n % 3 == 2) 1 else 0)
        var o = 0
        var i = 0
        while (i < n) {
            val c = v(s[i]); val d = v(s[i + 1])
            if (c < 0 || d < 0) return null
            if (i + 2 < n) {
                val e = v(s[i + 2])
                if (e < 0) return null
                val x = c + d * 45 + e * 2025
                if (x > 0xFFFF) return null
                out[o++] = (x shr 8).toByte(); out[o++] = x.toByte()
                i += 3
            } else {
                val x = c + d * 45
                if (x > 0xFF) return null
                out[o++] = x.toByte()
                i += 2
            }
        }
        return out
    }

    private fun v(ch: Char): Int = if (ch.code < 128) VALUES[ch.code] else -1
}

class ReceivedFile(val name: String, val data: ByteArray)

class TransferException(message: String) : Exception(message)

/**
 * Fountain decoder for one transfer: peeling plus Gaussian elimination over GF(2).
 * Block data is held as LongArrays so XORs run 8 bytes at a time.
 */
class FountainDecoder(val transferId: Int, val payloadLength: Int, val blockSize: Int) {
    companion object {
        const val MAX_GAUSS_UNKNOWNS = 4000
    }

    val blockCount = Protocol.blockCount(payloadLength, blockSize)
    private val longs = (blockSize + 7) / 8
    private val known = arrayOfNulls<LongArray>(blockCount)
    var knownCount = 0
        private set
    var packetsReceived = 0
        private set
    private val seenSeeds = HashSet<Int>()

    private class Equation(val unknown: MutableSet<Int>, val data: LongArray)
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
        val data = toLongs(packet.data)
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

    private fun toLongs(b: ByteArray): LongArray {
        val out = LongArray(longs)
        val bb = ByteBuffer.wrap(b)
        val full = b.size / 8
        for (i in 0 until full) out[i] = bb.getLong(i * 8)
        if (full < longs) {
            var v = 0L
            for (j in 0 until 8) v = (v shl 8) or (if (full * 8 + j < b.size) (b[full * 8 + j].toLong() and 0xFF) else 0L)
            out[full] = v
        }
        return out
    }

    private fun resolve(first: Int, firstData: LongArray) {
        val queue = ArrayDeque<Pair<Int, LongArray>>()
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
     * blocks, solve the rest with Gaussian elimination over GF(2). A bits-only pass
     * first checks the rank and picks u independent rows; only then are the block
     * data XORs done, on that u x u system.
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
        val rows = pending.toList()
        fun bitsOf(eq: Equation) = LongArray(words).also { w ->
            for (i in eq.unknown) { val col = colOf[i]; w[col shr 6] = w[col shr 6] or (1L shl (col and 63)) }
        }

        // Pass 1: bits only, remember which original rows became pivots.
        val m = Array(rows.size) { bitsOf(rows[it]) }
        val origin = IntArray(rows.size) { it }
        for (col in 0 until u) {
            val w = col shr 6; val bit = 1L shl (col and 63)
            var p = col
            while (p < m.size && m[p][w] and bit == 0L) p++
            if (p == m.size) return // not solvable yet
            if (p != col) {
                val t = m[p]; m[p] = m[col]; m[col] = t
                val o = origin[p]; origin[p] = origin[col]; origin[col] = o
            }
            val pr = m[col]
            for (r in col + 1 until m.size) if (m[r][w] and bit != 0L) {
                val rr = m[r]
                for (k in w until words) rr[k] = rr[k] xor pr[k]
            }
        }

        // Pass 2: Gauss-Jordan with data on the u selected rows.
        val bits = Array(u) { bitsOf(rows[origin[it]]) }
        val data = Array(u) { rows[origin[it]].data }
        for (col in 0 until u) {
            val w = col shr 6; val bit = 1L shl (col and 63)
            var p = col
            while (bits[p][w] and bit == 0L) p++
            if (p != col) {
                val tb = bits[p]; bits[p] = bits[col]; bits[col] = tb
                val td = data[p]; data[p] = data[col]; data[col] = td
            }
            val pb = bits[col]; val pd = data[col]
            for (r in 0 until u) {
                if (r != col && bits[r][w] and bit != 0L) {
                    val rb = bits[r]
                    for (k in 0 until words) rb[k] = rb[k] xor pb[k]
                    xorInto(data[r], pd)
                }
            }
        }
        for (col in 0 until u) known[cols[col]] = data[col]
        knownCount = blockCount
        pending.clear()
        waiting.clear()
    }

    private fun xorInto(target: LongArray, src: LongArray) {
        for (i in target.indices) target[i] = target[i] xor src[i]
    }

    fun payload(): ByteArray {
        check(isComplete)
        val stride = longs * 8
        val bb = ByteBuffer.allocate(blockCount * stride)
        for (i in 0 until blockCount) {
            bb.position(i * stride)
            for (v in known[i]!!) bb.putLong(v)
        }
        val all = bb.array()
        val out = ByteArray(payloadLength)
        for (i in 0 until blockCount) {
            val start = i * blockSize
            System.arraycopy(all, i * stride, out, start, minOf(blockSize, payloadLength - start))
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
