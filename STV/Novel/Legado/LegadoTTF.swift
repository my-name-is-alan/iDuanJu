import Foundation

/// Pure-Swift TrueType (`sfnt` / TTF) glyph-contour parser compatible with Legado's `QueryTTF`.
/// Maps obfuscated web-font codepoints back to true Unicode via glyph contour identity.
final class LegadoTTF {
    private let data: [UInt8]
    private var unicodeToGlyphId: [UInt32: Int] = [:]
    private var glyphIdToUnicode: [Int: UInt32] = [:]
    private var glyphIdToContour: [Int: String] = [:]
    private var contourToUnicode: [String: UInt32] = [:]
    private var unicodeToContour: [UInt32: String] = [:]

    init(_ raw: Data) {
        self.data = [UInt8](raw)
        parse()
    }

    func getGlyfIdByUnicode(_ unicode: UInt32) -> Int { unicodeToGlyphId[unicode] ?? 0 }
    func getUnicodeByGlyfId(_ gid: Int) -> UInt32 { glyphIdToUnicode[gid] ?? 0 }
    func getGlyfById(_ gid: Int) -> String? { glyphIdToContour[gid] }
    func getGlyfByUnicode(_ unicode: UInt32) -> String? { unicodeToContour[unicode] }
    func getUnicodeByGlyf(_ glyf: String?) -> UInt32 { glyf.flatMap { contourToUnicode[$0] } ?? 0 }
    func getCodeByGlyf(_ glyf: String?) -> String {
        let u = getUnicodeByGlyf(glyf)
        guard u > 0, let scalar = UnicodeScalar(u) else { return "" }
        return String(scalar)
    }

    private func u8(_ off: Int) -> Int { (off >= 0 && off < data.count) ? Int(data[off]) : 0 }
    private func u16(_ off: Int) -> Int { (u8(off) << 8) | u8(off + 1) }
    private func s16(_ off: Int) -> Int { Int(Int16(bitPattern: UInt16(u16(off)))) }
    private func u32(_ off: Int) -> Int { (u8(off) << 24) | (u8(off + 1) << 16) | (u8(off + 2) << 8) | u8(off + 3) }

    private func parse() {
        guard data.count >= 12 else { return }
        let numTables = u16(4)
        var tables: [String: (Int, Int)] = [:]
        var p = 12
        for _ in 0..<numTables {
            guard p + 16 <= data.count else { break }
            let tag = String(bytes: data[p..<(p + 4)], encoding: .ascii) ?? ""
            let off = u32(p + 8)
            let len = u32(p + 12)
            if off >= 0, len >= 0, off + len <= data.count { tables[tag] = (off, len) }
            p += 16
        }
        guard let head = tables["head"], let maxp = tables["maxp"],
              let loca = tables["loca"], let glyf = tables["glyf"], let cmap = tables["cmap"] else { return }

        let indexToLocFormat = s16(head.0 + 50)
        let numGlyphs = min(max(0, u16(maxp.0 + 4)), 65535)
        guard numGlyphs > 0 else { return }

        var offsets = [Int](repeating: 0, count: numGlyphs + 1)
        if indexToLocFormat == 0 {
            for i in 0...numGlyphs { offsets[i] = u16(loca.0 + i * 2) * 2 }
        } else {
            for i in 0...numGlyphs { offsets[i] = u32(loca.0 + i * 4) }
        }

        readCmap(cmap.0)

        for gid in 0..<numGlyphs {
            let start = offsets[gid]
            let end = offsets[gid + 1]
            let contour = (end <= start || glyf.0 + end > data.count) ? "" : readGlyphContour(glyf.0 + start, glyf.0 + end)
            glyphIdToContour[gid] = contour
        }

        for (cp, gid) in unicodeToGlyphId {
            guard let contour = glyphIdToContour[gid], !contour.isEmpty else { continue }
            unicodeToContour[cp] = contour
            if contourToUnicode[contour] == nil { contourToUnicode[contour] = cp }
        }
    }

    private func readCmap(_ cmapStart: Int) {
        let numSubtables = u16(cmapStart + 2)
        var best12 = -1, best4 = -1
        for i in 0..<numSubtables {
            let rec = cmapStart + 4 + i * 8
            guard rec + 8 <= data.count else { break }
            let platform = u16(rec), encoding = u16(rec + 2)
            let sub = cmapStart + u32(rec + 4)
            guard sub >= 0, sub + 2 <= data.count else { continue }
            let fmt = u16(sub)
            if fmt == 12 && (platform == 3 || platform == 0) { best12 = sub }
            else if fmt == 4 && ((platform == 3 && encoding == 1) || platform == 0 || best4 < 0) { best4 = sub }
        }
        if best4 >= 0 { readCmapFormat4(best4) }
        if best12 >= 0 { readCmapFormat12(best12) }
    }

    private func mapUnicode(_ cp: UInt32, _ gid: Int) {
        guard gid > 0 else { return }
        unicodeToGlyphId[cp] = gid
        if glyphIdToUnicode[gid] == nil { glyphIdToUnicode[gid] = cp }
    }

    private func readCmapFormat4(_ start: Int) {
        guard start + 14 <= data.count else { return }
        let segCount = u16(start + 6) / 2
        let endBase = start + 14
        let startBase = endBase + segCount * 2 + 2
        let deltaBase = startBase + segCount * 2
        let rangeBase = deltaBase + segCount * 2
        for i in 0..<segCount {
            let endCode = u16(endBase + i * 2)
            let startCode = u16(startBase + i * 2)
            if startCode == 0xFFFF && endCode == 0xFFFF { break }
            let idDelta = s16(deltaBase + i * 2)
            let rangePos = rangeBase + i * 2
            let rangeOff = u16(rangePos)
            if startCode > endCode { continue }
            for c in startCode...endCode {
                let gid: Int
                if rangeOff == 0 {
                    gid = (c + idDelta) & 0xFFFF
                } else {
                    let raw = u16(rangePos + rangeOff + (c - startCode) * 2)
                    gid = raw == 0 ? 0 : (raw + idDelta) & 0xFFFF
                }
                mapUnicode(UInt32(c), gid)
            }
        }
    }

    private func readCmapFormat12(_ start: Int) {
        guard start + 16 <= data.count else { return }
        let numGroups = u32(start + 12)
        var p = start + 16
        for _ in 0..<numGroups {
            guard p + 12 <= data.count else { break }
            let sChar = u32(p), eChar = u32(p + 4), sGid = u32(p + 8)
            let span = min(max(0, eChar - sChar), 65536)
            for d in 0...span { mapUnicode(UInt32(sChar + d), sGid + d) }
            p += 12
        }
    }

    private func readGlyphContour(_ start: Int, _ end: Int) -> String {
        guard start + 10 <= end else { return "" }
        let numContours = s16(start)
        var p = start + 10
        if numContours >= 0 {
            guard numContours > 0, p + numContours * 2 + 2 <= end else { return "" }
            var endPts = [Int](repeating: 0, count: numContours)
            for i in 0..<numContours { endPts[i] = u16(p); p += 2 }
            let numPoints = endPts[numContours - 1] + 1
            guard numPoints > 0, numPoints <= 20_000 else { return "" }
            let instrLen = u16(p); p += 2 + instrLen
            guard p <= end else { return "" }

            var flags = [Int](repeating: 0, count: numPoints)
            var fi = 0
            while fi < numPoints && p < end {
                let f = u8(p); p += 1
                flags[fi] = f; fi += 1
                if (f & 0x08) != 0 && p < end {
                    let rep = u8(p); p += 1
                    for _ in 0..<rep {
                        if fi >= numPoints { break }
                        flags[fi] = f; fi += 1
                    }
                }
            }
            var xs = [Int](repeating: 0, count: numPoints)
            var curX = 0
            for i in 0..<numPoints {
                let f = flags[i]
                let dx: Int
                if (f & 0x02) != 0 { let v = u8(p); p += 1; dx = (f & 0x10) != 0 ? v : -v }
                else if (f & 0x10) != 0 { dx = 0 }
                else { dx = s16(p); p += 2 }
                curX += dx; xs[i] = curX
            }
            var ys = [Int](repeating: 0, count: numPoints)
            var curY = 0
            for i in 0..<numPoints {
                let f = flags[i]
                let dy: Int
                if (f & 0x04) != 0 { let v = u8(p); p += 1; dy = (f & 0x20) != 0 ? v : -v }
                else if (f & 0x20) != 0 { dy = 0 }
                else { dy = s16(p); p += 2 }
                curY += dy; ys[i] = curY
            }
            var out = ""
            out.reserveCapacity(numPoints * 8)
            var cIdx = 0
            for i in 0..<numPoints {
                out += "\(xs[i]),\(ys[i])"
                if (flags[i] & 0x01) != 0 { out += "o" }
                if cIdx < numContours && i == endPts[cIdx] { out += "|"; cIdx += 1 } else { out += ";" }
            }
            return out
        } else {
            var parts: [String] = []
            var more = true
            while more && p + 4 <= end {
                let flags = u16(p), gid = u16(p + 2); p += 4
                let arg1: Int, arg2: Int
                if (flags & 0x0001) != 0 { arg1 = s16(p); arg2 = s16(p + 2); p += 4 }
                else { arg1 = u8(p); arg2 = u8(p + 1); p += 2 }
                var scale = ""
                if (flags & 0x0008) != 0 { scale = ",s:\(s16(p))"; p += 2 }
                else if (flags & 0x0040) != 0 { scale = ",s:\(s16(p)),\(s16(p + 2))"; p += 4 }
                else if (flags & 0x0080) != 0 { scale = ",s:\(s16(p)),\(s16(p + 2)),\(s16(p + 4)),\(s16(p + 6))"; p += 8 }
                parts.append("{g:\(gid),a1:\(arg1),a2:\(arg2)\(scale)}")
                more = (flags & 0x0020) != 0
            }
            return "[" + parts.joined(separator: ",") + "]"
        }
    }

    static func replaceFont(_ text: String, errorFont: LegadoTTF?, correctFont: LegadoTTF?, filter: Bool = false) -> String {
        guard !text.isEmpty, let errorFont, let correctFont else { return text }
        var scalars = String.UnicodeScalarView()
        for scalar in text.unicodeScalars {
            if let glyf = errorFont.getGlyfByUnicode(scalar.value) {
                let mapped = correctFont.getUnicodeByGlyf(glyf)
                if mapped > 0, let replacement = UnicodeScalar(mapped) {
                    scalars.append(replacement)
                } else if !filter {
                    scalars.append(scalar)
                }
            } else {
                scalars.append(scalar)
            }
        }
        return String(scalars)
    }
}
