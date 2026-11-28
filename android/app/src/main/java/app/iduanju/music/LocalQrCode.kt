package app.iduanju.music

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Pure-Kotlin ISO/IEC 18004 QR Code encoder (Byte mode, Level L, Version 4 & 5, single RS block).
 * Renders NetEase QR login codes 100% locally on-device with zero external image proxy.
 */
internal object LocalQrCode {
    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            gfExp[i] = x
            gfLog[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11D
        }
        for (i in 255 until 512) gfExp[i] = gfExp[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else gfExp[gfLog[a] + gfLog[b]]

    private fun rsCompute(data: IntArray, ecLen: Int): IntArray {
        var gen = intArrayOf(1)
        for (i in 0 until ecLen) {
            val next = IntArray(gen.size + 1)
            val factor = gfExp[i]
            for (j in gen.indices) {
                next[j] = next[j] xor gen[j]
                next[j + 1] = next[j + 1] xor gfMul(gen[j], factor)
            }
            gen = next
        }
        val rem = IntArray(ecLen)
        for (b in data) {
            val lead = b xor rem[0]
            System.arraycopy(rem, 1, rem, 0, ecLen - 1)
            rem[ecLen - 1] = 0
            if (lead != 0) {
                for (j in 0 until ecLen) {
                    rem[j] = rem[j] xor gfMul(gen[j + 1], lead)
                }
            }
        }
        return rem
    }

    fun encode(content: String, pixelSize: Int = 360): ImageBitmap? {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val version: Int
        val size: Int
        val dataCw: Int
        val ecCw: Int
        val alignCenter: Int
        when {
            bytes.size <= 78 -> {
                version = 4; size = 33; dataCw = 80; ecCw = 20; alignCenter = 26
            }
            bytes.size <= 106 -> {
                version = 5; size = 37; dataCw = 108; ecCw = 26; alignCenter = 30
            }
            else -> return null
        }

        // 1. Build bit stream (Mode 0100 + 8-bit length + UTF-8 bytes + terminator + pad bytes)
        val bits = ArrayList<Int>(dataCw * 8)
        fun pushBits(v: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add((v ushr i) and 1)
        }
        pushBits(0b0100, 4)
        pushBits(bytes.size, 8)
        for (b in bytes) pushBits(b.toInt() and 0xFF, 8)
        val remTerm = (dataCw * 8 - bits.size).coerceAtMost(4)
        pushBits(0, remTerm)
        while (bits.size % 8 != 0) bits.add(0)
        var padToggle = 0
        while (bits.size < dataCw * 8) {
            pushBits(if (padToggle++ % 2 == 0) 0xEC else 0x11, 8)
        }

        val data = IntArray(dataCw) { idx ->
            var v = 0
            for (k in 0 until 8) v = (v shl 1) or bits[idx * 8 + k]
            v
        }
        val ec = rsCompute(data, ecCw)
        val allCodewords = IntArray(dataCw + ecCw)
        System.arraycopy(data, 0, allCodewords, 0, dataCw)
        System.arraycopy(ec, 0, allCodewords, dataCw, ecCw)

        // 2. Place function patterns
        val modules = Array(size) { BooleanArray(size) }
        val isFn = Array(size) { BooleanArray(size) }

        fun setFn(r: Int, c: Int, dark: Boolean) {
            if (r in 0 until size && c in 0 until size) {
                modules[r][c] = dark
                isFn[r][c] = true
            }
        }

        fun drawFinder(top: Int, left: Int) {
            for (dr in -1..7) {
                for (dc in -1..7) {
                    val r = top + dr
                    val c = left + dc
                    if (r !in 0 until size || c !in 0 until size) continue
                    val inside = dr in 0..6 && dc in 0..6
                    val dark = inside && (
                        dr == 0 || dr == 6 || dc == 0 || dc == 6 ||
                            (dr in 2..4 && dc in 2..4)
                        )
                    setFn(r, c, dark)
                }
            }
        }
        drawFinder(0, 0)
        drawFinder(0, size - 7)
        drawFinder(size - 7, 0)

        // Alignment pattern at (alignCenter, alignCenter)
        for (dr in -2..2) {
            for (dc in -2..2) {
                val dist = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
                setFn(alignCenter + dr, alignCenter + dc, dist != 1)
            }
        }

        // Timing patterns
        for (i in 8 until size - 8) {
            setFn(6, i, i % 2 == 0)
            setFn(i, 6, i % 2 == 0)
        }

        // Format info: Level L (01) + Mask 0 (000) -> BCH(15,5) ^ 0x5412 = 0x77C4
        val fmt = 0x77C4
        fun fmtBit(k: Int) = ((fmt ushr k) and 1) == 1
        // Copy 1 (top-left)
        setFn(8, 0, fmtBit(14)); setFn(8, 1, fmtBit(13)); setFn(8, 2, fmtBit(12))
        setFn(8, 3, fmtBit(11)); setFn(8, 4, fmtBit(10)); setFn(8, 5, fmtBit(9))
        setFn(8, 7, fmtBit(8)); setFn(8, 8, fmtBit(7)); setFn(7, 8, fmtBit(6))
        setFn(5, 8, fmtBit(5)); setFn(4, 8, fmtBit(4)); setFn(3, 8, fmtBit(3))
        setFn(2, 8, fmtBit(2)); setFn(1, 8, fmtBit(1)); setFn(0, 8, fmtBit(0))
        // Copy 2 (bottom-left + top-right) + fixed dark module
        setFn(size - 1, 8, fmtBit(14)); setFn(size - 2, 8, fmtBit(13))
        setFn(size - 3, 8, fmtBit(12)); setFn(size - 4, 8, fmtBit(11))
        setFn(size - 5, 8, fmtBit(10)); setFn(size - 6, 8, fmtBit(9))
        setFn(size - 7, 8, fmtBit(8))
        setFn(4 * version + 9, 8, true)
        setFn(8, size - 8, fmtBit(7)); setFn(8, size - 7, fmtBit(6))
        setFn(8, size - 6, fmtBit(5)); setFn(8, size - 5, fmtBit(4))
        setFn(8, size - 4, fmtBit(3)); setFn(8, size - 3, fmtBit(2))
        setFn(8, size - 2, fmtBit(1)); setFn(8, size - 1, fmtBit(0))

        // 3. Zigzag data placement with Mask 0: (r + c) % 2 == 0
        var bitIdx = 0
        val totalBits = allCodewords.size * 8
        var col = size - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col--
            for (step in 0 until size) {
                val r = if (upward) size - 1 - step else step
                for (dc in 0..1) {
                    val c = col - dc
                    if (!isFn[r][c]) {
                        val rawBit = if (bitIdx < totalBits) {
                            ((allCodewords[bitIdx ushr 3] ushr (7 - (bitIdx and 7))) and 1) == 1
                        } else false
                        bitIdx++
                        val masked = if ((r + c) % 2 == 0) !rawBit else rawBit
                        modules[r][c] = masked
                    }
                }
            }
            col -= 2
            upward = !upward
        }

        // 4. Render crisp nearest-neighbor Bitmap with 4-module quiet zone
        val quiet = 4
        val total = size + quiet * 2
        val scale = (pixelSize / total).coerceAtLeast(4)
        val dim = total * scale
        val pixels = IntArray(dim * dim) { AndroidColor.WHITE }
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (!modules[r][c]) continue
                val y0 = (r + quiet) * scale
                val x0 = (c + quiet) * scale
                for (dy in 0 until scale) {
                    val rowOff = (y0 + dy) * dim + x0
                    for (dx in 0 until scale) pixels[rowOff + dx] = AndroidColor.BLACK
                }
            }
        }
        val bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, dim, 0, 0, dim, dim)
        return bmp.asImageBitmap()
    }
}
