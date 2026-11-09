package io.github.aboss3b13.qrshare.protocol

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.reedsolomon.GenericGF
import com.google.zxing.common.reedsolomon.ReedSolomonDecoder
import com.google.zxing.common.reedsolomon.ReedSolomonException
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer
import java.util.stream.Collectors
import java.util.stream.IntStream
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A luminance image (one byte per pixel, row-major). */
class LumaFrame(val data: ByteArray, val width: Int, val height: Int)

/** Pixel rectangle in the camera image. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun expanded(f: Double, w: Int, h: Int): Box {
        val dx = (width * f).toInt(); val dy = (height * f).toInt()
        return Box(max(0, left - dx), max(0, top - dy), min(w, right + dx), min(h, bottom + dy))
    }
}

/** A decoded anchor: its header, where it is, and precise grid-to-camera point pairs. */
class AnchorHit(val header: GridHeader, val box: Box, val cellPts: List<DoubleArray>, val camPts: List<DoubleArray>)

class GridFrameResult(
    val header: GridHeader?,
    val packets: List<Packet>,
    val bandsOk: Int,
    val bandsTotal: Int,
    val anchors: List<AnchorHit>,
)

/**
 * Decodes one camera frame of the dense grid mode. Pure JVM + ZXing core, so the
 * same code runs in the Android app and in the desktop camera-simulation test.
 */
class GridDecoder(
    /** Sample points at the cell centre and +-offset (in cells). */
    private val sampleOffset: Double = 0.1,
    /** Strength of the neighbour-bleed (blur) compensation. */
    private val sharpen: Float = 0.65f,
) {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    private var layoutKey: GridHeader? = null
    private var bandCells: List<IntArray> = emptyList()
    private var mask = BooleanArray(0)
    /** Last frame's cell samples and local means (for diagnostics). */
    var lastSamples = FloatArray(0); private set
    var lastMean = FloatArray(0); private set

    /**
     * Decodes the anchor QR code inside [box] and returns its finder/alignment centres,
     * which are much more precise than a detector's bounding box.
     */
    fun decodeAnchor(frame: LumaFrame, rawBox: Box): AnchorHit? {
        val box = Box(max(0, rawBox.left), max(0, rawBox.top), min(frame.width, rawBox.right), min(frame.height, rawBox.bottom))
        if (box.width < 30 || box.height < 30) return null
        val src = PlanarYUVLuminanceSource(frame.data, frame.width, frame.height, box.left, box.top, box.width, box.height, false)
        val result = try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src)), hints)
        } catch (e: Exception) {
            return null
        }
        val header = GridHeader.parse(result.text) ?: return null
        val pts = result.resultPoints ?: return null
        if (pts.size < 3) return null
        val ams = header.ams.toDouble()
        val (ax, ay) = GridFormat.anchorOrigins(header.cols, header.rows, header.ams)[header.anchorIndex]
        val sx = ax + GridFormat.QUIET * ams
        val sy = ay + GridFormat.QUIET * ams
        val n = GridFormat.ANCHOR_MODULES
        // ZXing order: bottom-left, top-left, top-right finder centres, then the alignment pattern.
        val modulePts = listOf(
            doubleArrayOf(3.5, n - 3.5), doubleArrayOf(3.5, 3.5), doubleArrayOf(n - 3.5, 3.5), doubleArrayOf(n - 6.5, n - 6.5),
        )
        val cellPts = ArrayList<DoubleArray>()
        val camPts = ArrayList<DoubleArray>()
        for (i in 0 until min(4, pts.size)) {
            cellPts.add(doubleArrayOf(sx + modulePts[i][0] * ams, sy + modulePts[i][1] * ams))
            camPts.add(doubleArrayOf(box.left + pts[i].x.toDouble(), box.top + pts[i].y.toDouble()))
        }
        // Tight box around the anchor for tracking in the next frame.
        val xs = camPts.map { it[0] }; val ys = camPts.map { it[1] }
        val span = max(xs.max() - xs.min(), ys.max() - ys.min())
        val tight = Box(
            (xs.min() - span * 0.45).toInt(), (ys.min() - span * 0.45).toInt(),
            (xs.max() + span * 0.45).toInt(), (ys.max() + span * 0.45).toInt(),
        )
        return AnchorHit(header, tight, cellPts, camPts)
    }

    /** Decodes several anchors in parallel (one CPU core each). */
    fun decodeAnchors(frame: LumaFrame, boxes: List<Box>): List<AnchorHit> =
        boxes.parallelStream().map { decodeAnchor(frame, it) }.collect(Collectors.toList()).filterNotNull()

    /** Decodes the data cells of a frame, given at least 4 anchors of the same transfer. */
    fun decodeFrame(frame: LumaFrame, hits: List<AnchorHit>): GridFrameResult {
        val groups = hits.groupBy { it.header.copy(anchorIndex = 0) }
        val best = groups.maxByOrNull { it.value.distinctBy { h -> h.header.anchorIndex }.size }?.value
        val anchors = best?.distinctBy { it.header.anchorIndex } ?: emptyList()
        if (anchors.size < 4) return GridFrameResult(anchors.firstOrNull()?.header, emptyList(), 0, 0, anchors)
        val h = anchors[0].header
        val mapping = GridMapping.fit(anchors.flatMap { it.cellPts }, anchors.flatMap { it.camPts })
            ?: return GridFrameResult(h, emptyList(), 0, h.bands, anchors)
        ensureLayout(h)

        val samples = sampleCells(frame, h, mapping)
        val (mean, std) = localStats(samples, h)
        lastSamples = samples; lastMean = mean
        val results = arrayOfNulls<Pair<List<Packet>, Boolean>>(h.bands)
        IntStream.range(0, h.bands).parallel().forEach { b -> results[b] = decodeBand(h, b, bandCells[b], samples, mean, std) }
        val packets = ArrayList<Packet>()
        var bandsOk = 0
        for (got in results) {
            if (got != null) {
                packets.addAll(got.first)
                if (got.second) bandsOk++
            }
        }
        return GridFrameResult(h, packets, bandsOk, h.bands, anchors)
    }

    private fun ensureLayout(h: GridHeader) {
        if (layoutKey?.let { it.cols == h.cols && it.rows == h.rows && it.bands == h.bands && it.ams == h.ams } == true) return
        mask = GridFormat.anchorMask(h.cols, h.rows, h.ams)
        bandCells = GridFormat.bandCells(h.cols, h.rows, h.bands, mask)
        layoutKey = h
    }

    /** Average brightness at 5 points inside each cell (NaN if outside the image). */
    private fun sampleCells(frame: LumaFrame, h: GridHeader, m: GridMapping): FloatArray {
        val out = FloatArray(h.cols * h.rows)
        val o = sampleOffset
        val offs = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(-o, -o), doubleArrayOf(o, -o), doubleArrayOf(-o, o), doubleArrayOf(o, o))
        IntStream.range(0, h.rows).parallel().forEach { r ->
            val pt = DoubleArray(2)
            for (c in 0 until h.cols) {
                val idx = r * h.cols + c
                if (mask[idx]) continue
                var sum = 0.0
                var ok = true
                for (d in offs) {
                    m.map(c + 0.5 + d[0], r + 0.5 + d[1], pt)
                    val v = bilinear(frame, pt[0], pt[1])
                    if (v < 0) { ok = false; break }
                    sum += v
                }
                out[idx] = if (ok) (sum / offs.size).toFloat() else Float.NaN
            }
        }
        return if (sharpen > 0) sharpenCells(out, h) else out
    }

    /**
     * Camera blur leaks each cell into its 4 neighbours. Subtracting a share of the
     * neighbours' average undoes most of it (a tiny deconvolution on the cell grid).
     */
    private fun sharpenCells(s: FloatArray, h: GridHeader): FloatArray {
        val w = h.cols
        val out = s.copyOf()
        for (r in 1 until h.rows - 1) for (c in 1 until w - 1) {
            val i = r * w + c
            if (mask[i]) continue
            if (s[i].isNaN()) continue
            var sum = 0f; var cnt = 0
            for (j in intArrayOf(i - 1, i + 1, i - w, i + w)) if (!mask[j] && !s[j].isNaN()) { sum += s[j]; cnt++ }
            if (cnt > 0) out[i] = s[i] + sharpen * (s[i] - sum / cnt)
        }
        return out
    }

    private fun bilinear(f: LumaFrame, x: Double, y: Double): Double {
        if (x < 0 || y < 0 || x >= f.width - 1 || y >= f.height - 1) return -1.0
        val x0 = x.toInt(); val y0 = y.toInt()
        val fx = x - x0; val fy = y - y0
        val i = y0 * f.width + x0
        val a = f.data[i].toInt() and 0xFF
        val b = f.data[i + 1].toInt() and 0xFF
        val c = f.data[i + f.width].toInt() and 0xFF
        val d = f.data[i + f.width + 1].toInt() and 0xFF
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /** Local mean and standard deviation over data cells (window ±R cells), via integral images. */
    private fun localStats(s: FloatArray, h: GridHeader): Pair<FloatArray, FloatArray> {
        val w = h.cols; val hh = h.rows
        val sum = DoubleArray((w + 1) * (hh + 1))
        val sq = DoubleArray((w + 1) * (hh + 1))
        val cnt = IntArray((w + 1) * (hh + 1))
        for (y in 0 until hh) {
            var rs = 0.0; var rq = 0.0; var rc = 0
            for (x in 0 until w) {
                val v = s[y * w + x]
                if (!mask[y * w + x] && !v.isNaN()) { rs += v; rq += v.toDouble() * v; rc++ }
                val i = (y + 1) * (w + 1) + x + 1
                sum[i] = sum[i - w - 1] + rs; sq[i] = sq[i - w - 1] + rq; cnt[i] = cnt[i - w - 1] + rc
            }
        }
        val r = 8
        val mean = FloatArray(w * hh)
        val std = FloatArray(w * hh)
        for (y in 0 until hh) {
            val y0 = max(0, y - r); val y1 = min(hh, y + r + 1)
            for (x in 0 until w) {
                if (mask[y * w + x]) continue
                val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                val a = y1 * (w + 1) + x1; val b = y0 * (w + 1) + x1; val c = y1 * (w + 1) + x0; val d = y0 * (w + 1) + x0
                val n = cnt[a] - cnt[b] - cnt[c] + cnt[d]
                if (n == 0) continue
                val m = (sum[a] - sum[b] - sum[c] + sum[d]) / n
                val v = (sq[a] - sq[b] - sq[c] + sq[d]) / n - m * m
                mean[y * w + x] = m.toFloat()
                std[y * w + x] = sqrt(max(v, 0.0)).toFloat()
            }
        }
        return mean to std
    }

    /** Returns the band's valid packets and whether every codeword decoded, or null if nothing usable. */
    private fun decodeBand(h: GridHeader, band: Int, cells: IntArray, s: FloatArray, mean: FloatArray, std: FloatArray): Pair<List<Packet>, Boolean>? {
        val bytes = cells.size * h.bpc / 8
        val packets = GridFormat.packetsInBand(cells.size, h.bpc, h.cwPerPacket)
        if (packets == 0) return null
        val ncw = packets * h.cwPerPacket
        val stream = ByteArray(bytes)
        var bit = 0
        loop@ for (cell in cells) {
            val v = s[cell]
            val value = if (v.isNaN()) 0 else if (h.bpc == 1) {
                if (v < mean[cell]) 1 else 0
            } else {
                val d = std[cell] / 1.118f
                val m = mean[cell]
                val level = (if (v > m - d) 1 else 0) + (if (v > m) 1 else 0) + (if (v > m + d) 1 else 0)
                GridFormat.GRAY_OF_LEVEL[level]
            }
            for (k in h.bpc - 1 downTo 0) {
                val byteIdx = bit ushr 3
                if (byteIdx >= bytes) break@loop
                if ((value ushr k) and 1 == 1) stream[byteIdx] = (stream[byteIdx].toInt() or (0x80 ushr (bit and 7))).toByte()
                bit++
            }
        }
        val scr = GridFormat.scramble(band, bytes)
        for (i in 0 until bytes) stream[i] = (stream[i].toInt() xor scr[i].toInt()).toByte()

        val rs = ReedSolomonDecoder(GenericGF.QR_CODE_FIELD_256)
        val out = ArrayList<Packet>()
        var allOk = true
        val cw = IntArray(GridFormat.CW_LEN)
        val pd = ByteArray(h.cwPerPacket * h.dataPerCw)
        for (p in 0 until packets) {
            var ok = true
            for (c in 0 until h.cwPerPacket) {
                val k = p * h.cwPerPacket + c
                for (j in 0 until GridFormat.CW_LEN) cw[j] = stream[j * ncw + k].toInt() and 0xFF
                try {
                    rs.decode(cw, h.ec)
                } catch (e: ReedSolomonException) {
                    ok = false; break
                } catch (e: IllegalArgumentException) {
                    ok = false; break
                }
                for (j in 0 until h.dataPerCw) pd[c * h.dataPerCw + j] = cw[j].toByte()
            }
            if (!ok) { allOk = false; continue }
            val bb = ByteBuffer.wrap(pd)
            val crc = CRC32().apply { update(pd, 0, 4 + h.blockSize) }.value.toInt()
            if (bb.getInt(4 + h.blockSize) != crc) { allOk = false; continue }
            out.add(Packet(h.transferId, h.payloadLength, h.blockSize, bb.getInt(0), pd.copyOfRange(4, 4 + h.blockSize)))
        }
        return out to allOk
    }
}

/**
 * Grid-cell coordinates -> camera pixels: a least-squares homography over all anchor
 * points, plus a fitted polynomial correction (up to 3rd degree) that absorbs lens
 * distortion. With 9 anchors spread over the screen there are 36 control points.
 */
class GridMapping private constructor(
    private val hm: DoubleArray,
    private val nu: DoubleArray, // normalisation of grid coords: cx, cy, scale
    private val nx: DoubleArray, // normalisation of camera coords
    private val degree: Int,
    private val px: DoubleArray, // polynomial coefficients of the x correction (pixels)
    private val py: DoubleArray,
) {
    fun map(u: Double, v: Double, out: DoubleArray) {
        mapH(u, v, out)
        if (degree > 0) {
            val a = (u - nu[0]) / nu[2]; val b = (v - nu[1]) / nu[2]
            var cx = 0.0; var cy = 0.0
            val t = terms(a, b, degree)
            for (i in t.indices) { cx += px[i] * t[i]; cy += py[i] * t[i] }
            out[0] += cx; out[1] += cy
        }
    }

    private fun mapH(u: Double, v: Double, out: DoubleArray) {
        val a = (u - nu[0]) / nu[2]; val b = (v - nu[1]) / nu[2]
        val d = hm[6] * a + hm[7] * b + 1
        out[0] = (hm[0] * a + hm[1] * b + hm[2]) / d * nx[2] + nx[0]
        out[1] = (hm[3] * a + hm[4] * b + hm[5]) / d * nx[2] + nx[1]
    }

    companion object {
        private fun terms(a: Double, b: Double, degree: Int): DoubleArray = when (degree) {
            3 -> doubleArrayOf(1.0, a, b, a * a, a * b, b * b, a * a * a, a * a * b, a * b * b, b * b * b)
            2 -> doubleArrayOf(1.0, a, b, a * a, a * b, b * b)
            else -> DoubleArray(0)
        }

        fun fit(cell: List<DoubleArray>, cam: List<DoubleArray>): GridMapping? {
            if (cell.size < 4) return null
            val nu = norm(cell); val nx = norm(cam)
            val ata = Array(8) { DoubleArray(8) }
            val atb = DoubleArray(8)
            for (i in cell.indices) {
                val u = (cell[i][0] - nu[0]) / nu[2]; val v = (cell[i][1] - nu[1]) / nu[2]
                val x = (cam[i][0] - nx[0]) / nx[2]; val y = (cam[i][1] - nx[1]) / nx[2]
                val r1 = doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x)
                val r2 = doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y)
                for (j in 0 until 8) {
                    for (k in 0 until 8) ata[j][k] += r1[j] * r1[k] + r2[j] * r2[k]
                    atb[j] += r1[j] * x + r2[j] * y
                }
            }
            val h = solve(ata, atb) ?: return null
            val base = GridMapping(h, nu, nx, 0, DoubleArray(0), DoubleArray(0))

            // Fit the leftover (lens distortion) with a polynomial, as rich as the point count allows.
            val degree = when { cell.size >= 24 -> 3; cell.size >= 14 -> 2; else -> 0 }
            if (degree == 0) return base
            val n = terms(0.0, 0.0, degree).size
            val m = Array(n) { DoubleArray(n) }
            val bx = DoubleArray(n); val by = DoubleArray(n)
            val p = DoubleArray(2)
            for (i in cell.indices) {
                base.mapH(cell[i][0], cell[i][1], p)
                val rx = cam[i][0] - p[0]; val ry = cam[i][1] - p[1]
                val t = terms((cell[i][0] - nu[0]) / nu[2], (cell[i][1] - nu[1]) / nu[2], degree)
                for (j in 0 until n) {
                    for (k in 0 until n) m[j][k] += t[j] * t[k]
                    bx[j] += t[j] * rx; by[j] += t[j] * ry
                }
            }
            for (j in 0 until n) m[j][j] += 1e-6
            val cx = solve(Array(n) { m[it].copyOf() }, bx) ?: return base
            val cy = solve(Array(n) { m[it].copyOf() }, by) ?: return base
            return GridMapping(h, nu, nx, degree, cx, cy)
        }

        private fun norm(p: List<DoubleArray>): DoubleArray {
            val cx = p.sumOf { it[0] } / p.size; val cy = p.sumOf { it[1] } / p.size
            val s = p.sumOf { sqrt((it[0] - cx) * (it[0] - cx) + (it[1] - cy) * (it[1] - cy)) } / p.size
            return doubleArrayOf(cx, cy, if (s > 0) s else 1.0)
        }

        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            for (col in 0 until n) {
                var p = col
                for (r in col + 1 until n) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[p][col])) p = r
                if (kotlin.math.abs(a[p][col]) < 1e-12) return null
                val t = a[p]; a[p] = a[col]; a[col] = t
                val tb = b[p]; b[p] = b[col]; b[col] = tb
                for (r in 0 until n) if (r != col) {
                    val f = a[r][col] / a[col][col]
                    if (f != 0.0) { for (k in col until n) a[r][k] -= f * a[col][k]; b[r] -= f * b[col] }
                }
            }
            return DoubleArray(n) { b[it] / a[it][it] }
        }
    }
}

/** Keeps track of where the anchors are between frames so ML Kit is only needed to (re)acquire them. */
class AnchorTracker {
    private var boxes: List<Box> = emptyList()
    val locked get() = boxes.size >= 4

    fun trackedBoxes(): List<Box> = boxes

    fun update(hits: List<AnchorHit>) {
        boxes = if (hits.size >= 4) hits.map { it.box } else emptyList()
    }

    fun reset() { boxes = emptyList() }
}
