package app.iduanju.core.novel

import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin TrueType (`sfnt` / TTF) glyph-contour parser compatible with Legado's `QueryTTF`.
 * Parses `head`, `maxp`, `loca`, `cmap` (format 4 & 12), and `glyf` (simple & composite) tables
 * so obfuscated web-font codepoints can be mapped back to real Unicode via glyph contour identity.
 */
class QueryTTF(private val data: ByteArray) {
    private val unicodeToGlyphId = HashMap<Int, Int>()
    private val glyphIdToUnicode = HashMap<Int, Int>()
    private val glyphIdToContour = HashMap<Int, String>()
    private val contourToUnicode = HashMap<String, Int>()
    private val unicodeToContour = HashMap<Int, String>()

    init {
        parse()
    }

    fun getGlyfIdByUnicode(unicode: Int): Int = unicodeToGlyphId[unicode] ?: 0
    fun getUnicodeByGlyfId(glyfId: Int): Int = glyphIdToUnicode[glyfId] ?: 0
    fun getGlyfById(glyfId: Int): String? = glyphIdToContour[glyfId]
    fun getGlyfByUnicode(unicode: Int): String? = unicodeToContour[unicode]
    fun getUnicodeByGlyf(glyf: String?): Int = if (glyf == null) 0 else contourToUnicode[glyf] ?: 0
    fun getCodeByGlyf(glyf: String?): String = getUnicodeByGlyf(glyf).let { if (it > 0) String(Character.toChars(it)) else "" }

    private fun u8(off: Int): Int = if (off in data.indices) data[off].toInt() and 0xFF else 0
    private fun u16(off: Int): Int = (u8(off) shl 8) or u8(off + 1)
    private fun s16(off: Int): Int = u16(off).toShort().toInt()
    private fun u32(off: Int): Int = (u8(off) shl 24) or (u8(off + 1) shl 16) or (u8(off + 2) shl 8) or u8(off + 3)

    private fun parse() {
        if (data.size < 12) return
        val numTables = u16(4)
        val tables = HashMap<String, Pair<Int, Int>>()
        var p = 12
        for (i in 0 until numTables) {
            if (p + 16 > data.size) break
            val tag = String(data, p, 4, StandardCharsets.US_ASCII)
            val offset = u32(p + 8)
            val length = u32(p + 12)
            if (offset >= 0 && length >= 0 && offset + length <= data.size) {
                tables[tag] = offset to length
            }
            p += 16
        }
        val head = tables["head"] ?: return
        val maxp = tables["maxp"] ?: return
        val loca = tables["loca"] ?: return
        val glyf = tables["glyf"] ?: return
        val cmap = tables["cmap"] ?: return

        val indexToLocFormat = s16(head.first + 50)
        val numGlyphs = u16(maxp.first + 4).coerceIn(0, 65535)
        if (numGlyphs == 0) return

        // Read loca offsets
        val offsets = IntArray(numGlyphs + 1)
        if (indexToLocFormat == 0) {
            for (i in 0..numGlyphs) offsets[i] = u16(loca.first + i * 2) * 2
        } else {
            for (i in 0..numGlyphs) offsets[i] = u32(loca.first + i * 4)
        }

        // Read cmap
        readCmap(cmap.first)

        // Read glyf contours
        for (gid in 0 until numGlyphs) {
            val start = offsets[gid]
            val end = offsets[gid + 1]
            val contour = if (end <= start || glyf.first + end > data.size) "" else readGlyphContour(glyf.first + start, glyf.first + end)
            glyphIdToContour[gid] = contour
        }

        // Build bidirectional unicode <-> contour maps
        for ((cp, gid) in unicodeToGlyphId) {
            val contour = glyphIdToContour[gid] ?: continue
            if (contour.isEmpty()) continue
            unicodeToContour[cp] = contour
            if (contour !in contourToUnicode) contourToUnicode[contour] = cp
        }
    }

    private fun readCmap(cmapStart: Int) {
        val numSubtables = u16(cmapStart + 2)
        var bestFormat12 = -1
        var bestFormat4 = -1
        for (i in 0 until numSubtables) {
            val rec = cmapStart + 4 + i * 8
            if (rec + 8 > data.size) break
            val platformId = u16(rec)
            val encodingId = u16(rec + 2)
            val subOffset = cmapStart + u32(rec + 4)
            if (subOffset < 0 || subOffset + 2 > data.size) continue
            val fmt = u16(subOffset)
            if (fmt == 12 && (platformId == 3 || platformId == 0)) bestFormat12 = subOffset
            else if (fmt == 4 && ((platformId == 3 && encodingId == 1) || platformId == 0 || bestFormat4 < 0)) bestFormat4 = subOffset
        }
        if (bestFormat4 >= 0) readCmapFormat4(bestFormat4)
        if (bestFormat12 >= 0) readCmapFormat12(bestFormat12)
    }

    private fun mapUnicode(cp: Int, gid: Int) {
        if (gid <= 0) return
        unicodeToGlyphId[cp] = gid
        if (gid !in glyphIdToUnicode) glyphIdToUnicode[gid] = cp
    }

    private fun readCmapFormat4(start: Int) {
        if (start + 14 > data.size) return
        val segCount = u16(start + 6) / 2
        val endCodeBase = start + 14
        val startCodeBase = endCodeBase + segCount * 2 + 2
        val idDeltaBase = startCodeBase + segCount * 2
        val idRangeOffsetBase = idDeltaBase + segCount * 2
        for (i in 0 until segCount) {
            val endCode = u16(endCodeBase + i * 2)
            val startCode = u16(startCodeBase + i * 2)
            if (startCode == 0xFFFF && endCode == 0xFFFF) break
            val idDelta = s16(idDeltaBase + i * 2)
            val rangeOffPos = idRangeOffsetBase + i * 2
            val idRangeOffset = u16(rangeOffPos)
            for (c in startCode..endCode) {
                val gid = if (idRangeOffset == 0) {
                    (c + idDelta) and 0xFFFF
                } else {
                    val glyphAddr = rangeOffPos + idRangeOffset + (c - startCode) * 2
                    val raw = u16(glyphAddr)
                    if (raw == 0) 0 else (raw + idDelta) and 0xFFFF
                }
                mapUnicode(c, gid)
            }
        }
    }

    private fun readCmapFormat12(start: Int) {
        if (start + 16 > data.size) return
        val numGroups = u32(start + 12)
        var p = start + 16
        for (i in 0 until numGroups) {
            if (p + 12 > data.size) break
            val startChar = u32(p)
            val endChar = u32(p + 4)
            val startGid = u32(p + 8)
            val span = (endChar - startChar).coerceAtMost(65536)
            for (d in 0..span) mapUnicode(startChar + d, startGid + d)
            p += 12
        }
    }

    private fun readGlyphContour(start: Int, end: Int): String {
        if (start + 10 > end) return ""
        val numContours = s16(start)
        var p = start + 10
        if (numContours >= 0) {
            if (numContours == 0 || p + numContours * 2 + 2 > end) return ""
            val endPts = IntArray(numContours)
            for (i in 0 until numContours) { endPts[i] = u16(p); p += 2 }
            val numPoints = endPts[numContours - 1] + 1
            if (numPoints <= 0 || numPoints > 20_000) return ""
            val instrLen = u16(p); p += 2 + instrLen
            if (p > end) return ""

            val flags = IntArray(numPoints)
            var fi = 0
            while (fi < numPoints && p < end) {
                val f = u8(p++)
                flags[fi++] = f
                if ((f and 0x08) != 0 && p < end) {
                    val repeat = u8(p++)
                    for (r in 0 until repeat) {
                        if (fi >= numPoints) break
                        flags[fi++] = f
                    }
                }
            }
            val xs = IntArray(numPoints)
            var curX = 0
            for (i in 0 until numPoints) {
                val f = flags[i]
                val dx = when {
                    (f and 0x02) != 0 -> { val v = u8(p++); if ((f and 0x10) != 0) v else -v }
                    (f and 0x10) != 0 -> 0
                    else -> { val v = s16(p); p += 2; v }
                }
                curX += dx; xs[i] = curX
            }
            val ys = IntArray(numPoints)
            var curY = 0
            for (i in 0 until numPoints) {
                val f = flags[i]
                val dy = when {
                    (f and 0x04) != 0 -> { val v = u8(p++); if ((f and 0x20) != 0) v else -v }
                    (f and 0x20) != 0 -> 0
                    else -> { val v = s16(p); p += 2; v }
                }
                curY += dy; ys[i] = curY
            }
            val sb = StringBuilder(numPoints * 8)
            var contourIdx = 0
            for (i in 0 until numPoints) {
                sb.append(xs[i]).append(',').append(ys[i])
                if ((flags[i] and 0x01) != 0) sb.append('o')
                if (contourIdx < numContours && i == endPts[contourIdx]) {
                    sb.append('|'); contourIdx++
                } else {
                    sb.append(';')
                }
            }
            return sb.toString()
        } else {
            // Composite glyph
            val sb = StringBuilder("[")
            var more = true
            var first = true
            while (more && p + 4 <= end) {
                val flags = u16(p); val gid = u16(p + 2); p += 4
                val arg1: Int; val arg2: Int
                if ((flags and 0x0001) != 0) {
                    arg1 = s16(p); arg2 = s16(p + 2); p += 4
                } else {
                    arg1 = u8(p); arg2 = u8(p + 1); p += 2
                }
                var scale = ""
                if ((flags and 0x0008) != 0) { scale = "${s16(p)}"; p += 2 }
                else if ((flags and 0x0040) != 0) { scale = "${s16(p)},${s16(p + 2)}"; p += 4 }
                else if ((flags and 0x0080) != 0) { scale = "${s16(p)},${s16(p + 2)},${s16(p + 4)},${s16(p + 6)}"; p += 8 }
                if (!first) sb.append(','); first = false
                sb.append("{g:").append(gid).append(",a1:").append(arg1).append(",a2:").append(arg2)
                if (scale.isNotEmpty()) sb.append(",s:").append(scale)
                sb.append('}')
                more = (flags and 0x0020) != 0
            }
            sb.append(']')
            return sb.toString()
        }
    }

    companion object {
        /**
         * Replaces obfuscated codepoints in [text] by matching their glyph outlines in [errorFont]
         * against [correctFont] (same semantics as Legado's `java.replaceFont`).
         */
        fun replaceFont(text: String, errorFont: QueryTTF?, correctFont: QueryTTF?, filter: Boolean = false): String {
            if (text.isEmpty() || errorFont == null || correctFont == null) return text
            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val step = Character.charCount(cp)
                val glyf = errorFont.getGlyfByUnicode(cp)
                if (glyf == null) {
                    sb.appendCodePoint(cp)
                } else {
                    val mapped = correctFont.getUnicodeByGlyf(glyf)
                    if (mapped > 0) sb.appendCodePoint(mapped)
                    else if (!filter) sb.appendCodePoint(cp)
                }
                i += step
            }
            return sb.toString()
        }
    }
}
