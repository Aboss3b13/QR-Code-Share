import io.github.aboss3b13.qrshare.protocol.AnchorTracker
import io.github.aboss3b13.qrshare.protocol.Box
import io.github.aboss3b13.qrshare.protocol.GridDecoder
import io.github.aboss3b13.qrshare.protocol.GridFormat
import io.github.aboss3b13.qrshare.protocol.LumaFrame
import io.github.aboss3b13.qrshare.protocol.TransferSession
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Simulates a phone camera filming the sender's screen: perspective, lens distortion,
 * pixel integration, blur, vignetting, reduced contrast, sensor noise and rolling-shutter
 * tears between consecutive screen frames. Then runs the real grid decoder on it.
 *
 *   grid <frameDir> <original> <cellPx> [screenWidthInCamera=0.86] [blurSigma=0.8] [noise=5] [tearEvery=2]
 */
fun gridSim(args: List<String>) {
    val dir = File(args[0]); val original = File(args[1]).readBytes(); val cellPx = args[2].toInt()
    val fill = args.getOrNull(3)?.toDouble() ?: 0.86
    val sigma = args.getOrNull(4)?.toDouble() ?: 0.8
    val noise = args.getOrNull(5)?.toDouble() ?: 5.0
    val tearEvery = args.getOrNull(6)?.toInt() ?: 2
    val files = dir.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }
    val screens = files.map { f -> ImageIO.read(f).let { img -> ByteArray(img.width * img.height) { i -> (img.getRGB(i % img.width, i / img.width) and 0xFF).toByte() } } }
    val sw = 1920; val sh = 1080
    val cw = System.getenv("QS_CAMW")?.toInt() ?: 1920; val ch = cw * 9 / 16
    val rnd = java.util.Random(7)

    // Screen -> camera homography from 4 corner correspondences (slightly tilted, rotated).
    val mw = cw * fill; val mh = mw * sh / sw
    val cx = cw / 2.0; val cy = ch / 2.0
    val corners = arrayOf(
        doubleArrayOf(cx - mw / 2 + 0.013 * cw, cy - mh / 2 - 0.005 * cw), doubleArrayOf(cx + mw / 2 - 0.003 * cw, cy - mh / 2 + 0.010 * cw),
        doubleArrayOf(cx + mw / 2 + 0.008 * cw, cy + mh / 2 - 0.003 * cw), doubleArrayOf(cx - mw / 2 - 0.005 * cw, cy + mh / 2 + 0.008 * cw),
    )
    val h = homography(arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(sw.toDouble(), 0.0), doubleArrayOf(sw.toDouble(), sh.toDouble()), doubleArrayOf(0.0, sh.toDouble())), corners)
    val hinv = invert3(h)
    val k1 = System.getenv("QS_K1")?.toDouble() ?: 0.03 // radial distortion strength

    fun cameraToScreen(x: Double, y: Double, out: DoubleArray) {
        val nx = (x - cx) / cx; val ny = (y - cy) / cx
        val f = 1 + k1 * (nx * nx + ny * ny)
        apply(hinv, cx + nx * f * cx, cy + ny * f * cx, out)
    }

    fun render(screen: ByteArray): FloatArray {
        val img = FloatArray(cw * ch)
        val p = DoubleArray(2)
        for (y in 0 until ch) for (x in 0 until cw) {
            var s = 0.0
            for (sy in 0 until 3) for (sx in 0 until 3) {
                cameraToScreen(x + (sx + 0.5) / 3, y + (sy + 0.5) / 3, p)
                val px = p[0].toInt(); val py = p[1].toInt()
                s += if (px in 0 until sw && py in 0 until sh) (screen[py * sw + px].toInt() and 0xFF) else 20
            }
            img[y * cw + x] = (s / 9).toFloat()
        }
        return blur(img, cw, ch, sigma)
    }

    fun finish(img: FloatArray): LumaFrame {
        val out = ByteArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            val nx = (x - cx) / cx; val ny = (y - cy) / cx
            val vig = 1 - 0.22 * (nx * nx + ny * ny)
            val v = (30 + img[y * cw + x] * (195.0 / 255) * vig + rnd.nextGaussian() * noise)
            out[y * cw + x] = v.coerceIn(0.0, 255.0).toInt().toByte()
        }
        return LumaFrame(out, cw, ch)
    }

    // Coarse anchor boxes, standing in for ML Kit's detections (projected, expanded generously).
    val cols = sw / cellPx; val rows = sh / cellPx
    val ox = (sw - cols * cellPx) / 2; val oy = (sh - rows * cellPx) / 2
    val raw = System.getenv("QS_RAW") != null
    val ams = System.getenv("QS_AMS")?.toInt() ?: 1
    val fp = GridFormat.footprint(ams)
    val coarse = GridFormat.anchorOrigins(cols, rows, ams).map { (ax, ay) ->
        val pts = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).map { (i, j) ->
            val o = DoubleArray(2)
            val sxp = ox + (ax + i * fp) * cellPx.toDouble(); val syp = oy + (ay + j * fp) * cellPx.toDouble()
            if (raw) { o[0] = sxp; o[1] = syp } else apply(h, sxp, syp, o); o
        }
        Box(pts.minOf { it[0] }.toInt(), pts.minOf { it[1] }.toInt(), pts.maxOf { it[0] }.toInt(), pts.maxOf { it[1] }.toInt()).expanded(0.3, cw, ch)
    }

    val decoder = GridDecoder(System.getenv("QS_OFF")?.toDouble() ?: 0.1, System.getenv("QS_SHARP")?.toFloat() ?: 0.65f)
    val tracker = AnchorTracker()
    val session = TransferSession()
    var packets = 0; var bandsOk = 0; var bandsTotal = 0; var decodeMs = 0L
    val t0 = System.nanoTime()
    val rendered = HashMap<Int, FloatArray>()
    for (i in screens.indices) {
        val a = if (raw) FloatArray(0) else rendered.getOrPut(i) { render(screens[i]) }
        val img = if (raw) a else if (tearEvery > 0 && i % tearEvery == 1 && i + 1 < screens.size) {
            val b = rendered.getOrPut(i + 1) { render(screens[i + 1]) }
            val tear = ch / 5 + rnd.nextInt(ch * 3 / 5)
            FloatArray(cw * ch) { if (it / cw < tear) a[it] else b[it] }
        } else a
        rendered.remove(i - 1)
        val frame = if (System.getenv("QS_RAW") != null) LumaFrame(screens[i], sw, sh) else finish(img)

        val td = System.nanoTime()
        val boxes = if (tracker.locked) tracker.trackedBoxes() else coarse
        var hits = decoder.decodeAnchors(frame, boxes)
        if (hits.size < 4 && tracker.locked) hits = decoder.decodeAnchors(frame, coarse)
        val r = decoder.decodeFrame(frame, hits)
        tracker.update(r.anchors)
        decodeMs += (System.nanoTime() - td) / 1_000_000
        bandsOk += r.bandsOk; bandsTotal += r.bandsTotal
        if (System.getenv("QS_ONLYERR") != null && i >= 1) error("stop")
        if (i < 3 && decoder.lastSamples.size == cols * rows) {
            val mask = GridFormat.anchorMask(cols, rows, ams)
            var err = 0; var n = 0
            for (c in 0 until cols * rows) {
                if (mask[c]) continue
                val truth = (screens[i][(oy + (c / cols) * cellPx + cellPx / 2) * sw + ox + (c % cols) * cellPx + cellPx / 2].toInt() and 0xFF) < 128
                val got = decoder.lastSamples[c] < decoder.lastMean[c]
                n++; if (truth != got) err++
            }
            println("frame $i: cell error rate ${err * 100.0 / n}%")
        }
        if (i < 3) println("frame $i: anchors=${r.anchors.size} bands ${r.bandsOk}/${r.bandsTotal} packets=${r.packets.size}")
        for (p in r.packets) {
            packets++
            val ev = session.onPacket(p)
            if (check(ev, original, "after ${i + 1} camera frames, $packets packets, bands ok $bandsOk/$bandsTotal, avg decode ${decodeMs / (i + 1)} ms/frame", t0)) return
        }
    }
    error("Not complete after ${screens.size} frames: packets=$packets bands ok $bandsOk/$bandsTotal")
}

private fun blur(src: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
    if (sigma <= 0) return src
    val r = max(1, (sigma * 3).toInt())
    val k = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }.let { k -> val s = k.sum(); DoubleArray(k.size) { k[it] / s } }
    val tmp = FloatArray(w * h); val out = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        var s = 0.0
        for (i in -r..r) s += k[i + r] * src[y * w + min(w - 1, max(0, x + i))]
        tmp[y * w + x] = s.toFloat()
    }
    for (y in 0 until h) for (x in 0 until w) {
        var s = 0.0
        for (i in -r..r) s += k[i + r] * tmp[min(h - 1, max(0, y + i)) * w + x]
        out[y * w + x] = s.toFloat()
    }
    return out
}

private fun apply(m: DoubleArray, x: Double, y: Double, out: DoubleArray) {
    val d = m[6] * x + m[7] * y + m[8]
    out[0] = (m[0] * x + m[1] * y + m[2]) / d
    out[1] = (m[3] * x + m[4] * y + m[5]) / d
}

private fun homography(src: Array<DoubleArray>, dst: Array<DoubleArray>): DoubleArray {
    val a = Array(8) { DoubleArray(9) }
    for (i in 0 until 4) {
        val (x, y) = src[i][0] to src[i][1]; val (u, v) = dst[i][0] to dst[i][1]
        a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
        a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
    }
    for (c in 0 until 8) {
        var p = c
        for (r in c + 1 until 8) if (kotlin.math.abs(a[r][c]) > kotlin.math.abs(a[p][c])) p = r
        val t = a[p]; a[p] = a[c]; a[c] = t
        for (r in 0 until 8) if (r != c) { val f = a[r][c] / a[c][c]; for (k in c until 9) a[r][k] -= f * a[c][k] }
    }
    return DoubleArray(9) { if (it == 8) 1.0 else a[it][8] / a[it][it] }
}

private fun invert3(m: DoubleArray): DoubleArray {
    val (a, b, c) = Triple(m[0], m[1], m[2]); val (d, e, f) = Triple(m[3], m[4], m[5]); val (g, h, i) = Triple(m[6], m[7], m[8])
    val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    return doubleArrayOf(
        (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
        (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
        (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
    )
}
