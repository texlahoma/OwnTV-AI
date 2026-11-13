package tv.own.owntv.ui.theme

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/** Geometric frost pyramid dimensions; the sum stays inside the former 768 px bitmap budget. */
fun frostMipDimensions(
    baseWidth: Int,
    baseHeight: Int,
    levels: Int = 10,
): List<Pair<Int, Int>> {
    val result = ArrayList<Pair<Int, Int>>(levels.coerceAtLeast(1))
    var width = baseWidth.coerceAtLeast(48)
    var height = baseHeight.coerceAtLeast(28)
    repeat(levels.coerceAtLeast(1)) {
        result += width to height
        width = (width * 0.78f).toInt().coerceAtLeast(48)
        height = (height * 0.78f).toInt().coerceAtLeast(28)
    }
    return result
}

/**
 * Compact, dependency-free separable box blur.
 *
 * Used by Glass to produce the single shared frosted copy of the
 * background image. Three horizontal+vertical passes approximate a Gaussian — visually
 * indistinguishable from stack blur for a backdrop frost, and trivially correct. Pure-Kotlin over
 * an `Int` pixel array → works on every API level (no RenderEffect/RenderScript dependency). Run
 * once per background image at a downscaled size, so the O(width·height·passes) cost is negligible.
 *
 * Mutates and returns [bitmap] in place. [radius] 1..60 (clamped); higher = more frost.
 *
 * Implementation note (sliding-window bookkeeping): each window MUST be initialized over the full
 * `[-r .. r]` range with the index clamped to the edge — so the edge pixel is counted `r+1` times,
 * exactly matching what the slide step subtracts. An earlier version initialized over `0..r` (edge
 * counted once) but slid with `xout = max(x - r, 0)` (edge subtracted `r+1` times), driving the
 * running sum negative and wrapping sign bits into per-channel garbage — visible as saturated
 * red/green/blue blocks behind glass panels. The `coerceIn(0, 255)` clamps below also guard against
 * any future arithmetic slip darkening instead of rainbow-wrapping.
 */
fun stackBlur(bitmap: Bitmap, radius: Int = 18): Bitmap {
    val r = radius.coerceIn(1, 60)
    val w = bitmap.width
    val h = bitmap.height
    if (w < 3 || h < 3 || r < 1) return bitmap

    val src = IntArray(w * h)
    bitmap.getPixels(src, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    var a = src
    var b = tmp

    val denom = r + r + 1

    // Each iteration: blur horizontally (a → b), then vertically (b → a). Repeat for a tighter Gaussian.
    repeat(3) {
        // ── Horizontal pass: a → b ──
        var y = 0
        while (y < h) {
            val row = y * w
            // Initialize the window over [-r .. r] with the index clamped to the edge, so the running
            // sum reflects exactly the window the first slide step assumes (edge counted r+1 times).
            var rs = 0; var gs = 0; var bs = 0
            for (i in -r..r) {
                val px = a[row + i.coerceIn(0, w - 1)]
                rs += px ushr 16 and 0xFF
                gs += px ushr 8 and 0xFF
                bs += px and 0xFF
            }
            var x = 0
            while (x < w) {
                b[row + x] = (0xFF shl 24) or
                    ((rs / denom).coerceIn(0, 255) shl 16) or
                    ((gs / denom).coerceIn(0, 255) shl 8) or
                    ((bs / denom).coerceIn(0, 255))
                // Slide the window: add the pixel entering on the right, drop the one on the left.
                val xin = min(x + r + 1, w - 1)
                val xout = max(x - r, 0)
                val pin = a[row + xin]
                val pout = a[row + xout]
                rs += (pin ushr 16 and 0xFF) - (pout ushr 16 and 0xFF)
                gs += (pin ushr 8 and 0xFF) - (pout ushr 8 and 0xFF)
                bs += (pin and 0xFF) - (pout and 0xFF)
                x++
            }
            y++
        }
        // ── Vertical pass: b → a ──
        var x = 0
        while (x < w) {
            var rs = 0; var gs = 0; var bs = 0
            for (i in -r..r) {
                val px = b[i.coerceIn(0, h - 1) * w + x]
                rs += px ushr 16 and 0xFF
                gs += px ushr 8 and 0xFF
                bs += px and 0xFF
            }
            var yy = 0
            while (yy < h) {
                a[yy * w + x] = (0xFF shl 24) or
                    ((rs / denom).coerceIn(0, 255) shl 16) or
                    ((gs / denom).coerceIn(0, 255) shl 8) or
                    ((bs / denom).coerceIn(0, 255))
                val yin = min(yy + r + 1, h - 1)
                val yout = max(yy - r, 0)
                val pin = b[yin * w + x]
                val pout = b[yout * w + x]
                rs += (pin ushr 16 and 0xFF) - (pout ushr 16 and 0xFF)
                gs += (pin ushr 8 and 0xFF) - (pout ushr 8 and 0xFF)
                bs += (pin and 0xFF) - (pout and 0xFF)
                yy++
            }
            x++
        }
    }

    bitmap.setPixels(a, 0, w, 0, 0, w, h)
    return bitmap
}
