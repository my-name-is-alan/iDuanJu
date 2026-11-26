import Foundation
import ImageIO
import UIKit
import CommonCrypto
import CoreImage
import SwiftUI

/// How a remote cover must be decoded before UIImage can show it.
enum CoverKind {
    case plain
    /// Huangguo AI CDN (pic.drleao.cn): whole body is AES-128-CBC ciphertext (no padding).
    case aiAES
    /// Huangguo legacy/cloudfront CDN: first 100 bytes XOR with repeating "2019ysapp7527".
    case legacyXOR
}

enum CoverCrypto {
    /// From AI site crypto-worker.js media_key / media_iv (ASCII digits→chars).
    static let aiKey = Array("f5d965df75336270".utf8)
    static let aiIV  = Array("97b60394abc2fbe1".utf8)
    /// From legacy frontend ue.imgKey.
    static let xorKey = Array("2019ysapp7527".utf8)

    static func kind(for url: String) -> CoverKind {
        let l = url.lowercased()
        if l.contains("pic.drleao.cn") || l.contains("drleao.") || l.contains("qnlycc.") { return .aiAES }
        if l.contains("lkkwip.cn") || l.contains("/cf") && l.contains("/image/") { return .legacyXOR }
        // Relative legacy paths are absolutized before fetch; treat bare cf* as XOR.
        if url.hasPrefix("cf") || url.hasPrefix("hc") { return .legacyXOR }
        return .plain
    }

    static func decrypt(_ data: Data, kind: CoverKind) throws -> Data {
        switch kind {
        case .plain: return data
        case .aiAES:
            guard data.count >= 16, data.count % 16 == 0 else { throw ProviderError.invalid("AI cover length") }
            var out = [UInt8](repeating: 0, count: data.count)
            var moved = 0
            let st = CCCrypt(CCOperation(kCCDecrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(0),
                             aiKey, aiKey.count, aiIV, [UInt8](data), data.count, &out, out.count, &moved)
            guard st == kCCSuccess else { throw ProviderError.invalid("AI cover AES \(st)") }
            return Data(out[0..<moved])
        case .legacyXOR:
            var b = [UInt8](data)
            let n = min(100, b.count)
            for i in 0..<n { b[i] ^= xorKey[i % xorKey.count] }
            return Data(b)
        }
    }
}

/// Decode sizes (longest side, px). Views that know their size pass one; others are bucketed from their measured size.
enum CoverPixels {
    /// 52–76 pt rows and shelves.
    static let thumb = 240
    /// Three-column 3:4 posters on every iPhone scale.
    static let poster = 480
    /// Two-column 16:9 cards.
    static let wide = 640
    /// Full-width artwork.
    static let hero = 1080
    static let ladder = [240, 480, 640, 1080, 1600]
    static func bucket(_ px: CGFloat) -> Int { ladder.first { CGFloat($0) >= px } ?? 1600 }
}

/// Cancellation for work already queued on the decode queue.
private final class CoverCancel: @unchecked Sendable {
    private let lock = NSLock()
    private var flag = false
    var isSet: Bool { lock.lock(); defer { lock.unlock() }; return flag }
    func set() { lock.lock(); flag = true; lock.unlock() }
}

/// One running load shared by every view asking for the same thing. It is cancelled once all of them are gone, so a fast
/// scroll does not leave a queue of downloads and decodes nobody is looking at any more.
private final class CoverFlight<T> {
    let task: Task<T, Error>
    var waiters = 0
    var gone = 0
    init(_ task: Task<T, Error>) { self.task = task }
}

/// Disk+memory cache of decrypted cover images. Shared by all poster views.
/// Disk reads and decoding run on a small concurrent queue (never on the main thread, never serialised on the actor), and
/// images are decoded to the size they are shown at.
actor CoverStore {
    static let shared = CoverStore()
    private nonisolated let memory: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.totalCostLimit = Int(min(160 * 1024 * 1024, ProcessInfo.processInfo.physicalMemory / 32))
        return cache
    }()
    private var images: [String: CoverFlight<UIImage>] = [:]
    private var bodies: [String: CoverFlight<Data>] = [:]
    private var trimmed = false
    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        // Covers are kept (decrypted) in `diskDir`; a second copy in URLCache only costs disk and memory.
        c.urlCache = nil
        return URLSession(configuration: c)
    }()

    private static let diskDir: URL = {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("stv-covers", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()
    private static func diskFile(_ url: String) -> URL { diskDir.appendingPathComponent(Bytes.hex(Bytes.md5(url)) + ".img") }

    private static let decodeQueue: OperationQueue = {
        let q = OperationQueue()
        q.name = "stv.cover.decode"
        q.qualityOfService = .userInitiated
        q.maxConcurrentOperationCount = max(2, min(4, ProcessInfo.processInfo.activeProcessorCount - 2))
        return q
    }()
    private static func offMain<T>(_ work: @escaping () throws -> T) async throws -> T {
        let cancel = CoverCancel()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<T, Error>) in
                decodeQueue.addOperation {
                    if cancel.isSet { c.resume(throwing: CancellationError()); return }
                    c.resume(with: Result { try autoreleasepool { try work() } })
                }
            }
        } onCancel: { cancel.set() }
    }

    private static func key(_ url: String, _ px: Int?) -> NSString { (px.map { "\(url)|px\($0)" } ?? url) as NSString }

    /// Synchronous thread-safe memory lookup so views (e.g. detail hero) render on frame 0 without flashing a placeholder.
    nonisolated func cachedImage(url raw: String, fallback: String? = nil, maxPixelSize: Int? = nil) -> UIImage? {
        for candidate in [raw, fallback ?? ""] {
            let url = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !url.isEmpty else { continue }
            if let hit = memory.object(forKey: Self.key(url, maxPixelSize)) { return hit }
            if maxPixelSize != nil, let hit = memory.object(forKey: url as NSString) { return hit }
        }
        return nil
    }

    /// Any decoded size of `url`, largest first: what to show while the exact size is on its way.
    nonisolated func cachedVariant(url raw: String, fallback: String? = nil) -> UIImage? {
        for candidate in [raw, fallback ?? ""] {
            let url = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !url.isEmpty else { continue }
            if let hit = memory.object(forKey: url as NSString) { return hit }
            for px in CoverPixels.ladder.reversed() { if let hit = memory.object(forKey: Self.key(url, px)) { return hit } }
        }
        return nil
    }

    func image(url raw: String, headers: [String: String] = [:], maxPixelSize: Int? = nil) async throws -> UIImage {
        let url = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = Self.key(url, maxPixelSize)
        if let hit = memory.object(forKey: key) { return hit }
        let name = key as String
        let flight: CoverFlight<UIImage>
        if let running = images[name] {
            flight = running
        } else {
            flight = CoverFlight(Task.detached(priority: .userInitiated) { try await self.load(url: url, headers: headers, px: maxPixelSize) })
            images[name] = flight
        }
        flight.waiters += 1
        let task = flight.task
        do {
            let img = try await withTaskCancellationHandler { try await task.value } onCancel: { Task { await self.leaveImage(name) } }
            if images[name] === flight { images[name] = nil }
            cache(img, key)
            return img
        } catch {
            if images[name] === flight { images[name] = nil }
            throw error
        }
    }

    private func leaveImage(_ name: String) {
        guard let f = images[name] else { return }
        f.gone += 1
        if f.gone >= f.waiters { f.task.cancel(); images[name] = nil }
    }
    private func leaveBody(_ url: String) {
        guard let f = bodies[url] else { return }
        f.gone += 1
        if f.gone >= f.waiters { f.task.cancel(); bodies[url] = nil }
    }

    private nonisolated func load(url: String, headers: [String: String], px: Int?) async throws -> UIImage {
        #if DEBUG
        if url.hasPrefix("fixture://"), let art = CoverFixture.art(url) { return art }
        #endif
        if url.hasPrefix("tdfile://") {
            // Telegram media read through TDLib (restricted / private channels): downloadFile → local path.
            let local = try await TDLibService.shared.file(url)
            return try await Self.offMain { try Self.decode(file: local, px: px) }
        }
        if url.hasPrefix("file://"), let u = URL(string: url) {
            // Downloaded Telegram photos / offline manga pages / imported book covers.
            return try await Self.offMain { try Self.decode(file: u, px: px) }
        }
        let disk = Self.diskFile(url)
        if let img = try? await Self.offMain({ try Self.decode(file: disk, px: px) }) { return img }
        try Task.checkCancellation()
        // One raw file on disk per URL, so another size reuses it without downloading again.
        let data = try await body(url: url, headers: headers, disk: disk)
        return try await Self.offMain { try Self.decode(data: data, px: px) }
    }

    /// Downloaded (and decrypted) bytes; also written to disk.
    private func body(url: String, headers: [String: String], disk: URL) async throws -> Data {
        let flight: CoverFlight<Data>
        if let running = bodies[url] {
            flight = running
        } else {
            let session = session
            flight = CoverFlight(Task.detached(priority: .userInitiated) {
                let data: Data
                if url.hasPrefix(PluginMedia.scheme) {
                    // Plugin images: allow-list + per-plugin cookies + native transforms (PluginMedia).
                    data = try await PluginMedia.load(url)
                } else {
                    data = try await Self.download(url: url, headers: headers, session: session)
                }
                try? data.write(to: disk, options: .atomic)
                return data
            })
            bodies[url] = flight
            trimOnce()
        }
        flight.waiters += 1
        let task = flight.task
        defer { if bodies[url] === flight { bodies[url] = nil } }
        return try await withTaskCancellationHandler { try await task.value } onCancel: { Task { await self.leaveBody(url) } }
    }

    private static func download(url: String, headers: [String: String], session: URLSession) async throws -> Data {
        // TVBox covers may carry request headers (`url@Referer=…`) and raw characters.
        let picture = TVBoxUrl.picture(url)
        guard !url.isEmpty, var u = URL(string: picture.url) ?? TVBoxUrl.url(picture.url), u.scheme == "http" || u.scheme == "https" else {
            throw ProviderError.invalid("empty cover")
        }
        // ATS: hongguo CDNs (fqnovelpic / douyinpic …) serve the same object over https.
        if u.scheme == "http", let h = u.host, h.hasSuffix("fqnovelpic.com") || h.hasSuffix("douyinpic.com") || h.hasSuffix("byteimg.com"),
           let up = URL(string: "https" + picture.url.dropFirst(4)) { u = up }
        var req = URLRequest(url: u)
        req.setValue(HuangguoClient.userAgent, forHTTPHeaderField: "User-Agent")
        let kind = CoverCrypto.kind(for: url)
        var hdrs = CoverHeaders.for(url: url).merging(picture.headers) { _, given in given }.merging(headers) { _, given in given }
        if kind == .legacyXOR {
            hdrs["Referer"] = hdrs["Referer"] ?? HuangguoClient.frontendURL + "/"
            hdrs["Origin"] = hdrs["Origin"] ?? HuangguoClient.frontendURL
        }
        for (k, v) in hdrs { req.setValue(v, forHTTPHeaderField: k) }
        let (data, resp) = try await session.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code), !data.isEmpty else { throw ProviderError.upstream(data.isEmpty && code == 200 ? "cover empty body" : "cover HTTP \(code)") }
        return try CoverCrypto.decrypt(data, kind: kind)
    }

    // MARK: Decode (decode queue only)

    private static func decode(file: URL, px: Int?) throws -> UIImage {
        guard FileManager.default.fileExists(atPath: file.path), let source = CGImageSourceCreateWithURL(file as CFURL, nil) else {
            throw ProviderError.invalid("local image missing")
        }
        return try decode(source, px: px)
    }

    private static func decode(data: Data, px: Int?) throws -> UIImage {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { throw ProviderError.invalid("cover not an image") }
        return try decode(source, px: px)
    }

    /// Bitmap with the EXIF orientation applied, decoded here and now: `UIImage(data:)` would decode lazily on the main thread.
    private static func decode(_ source: CGImageSource, px: Int?) throws -> UIImage {
        let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        let w = (props?[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue ?? 0
        let h = (props?[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue ?? 0
        let full = max(w, h)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, full > 0 ? min(px ?? full, full) : px ?? 4096),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw ProviderError.invalid("cover not an image")
        }
        return UIImage(cgImage: image, scale: 1, orientation: .up)
    }

    private func cache(_ image: UIImage, _ key: NSString) {
        let cost = image.cgImage.map { $0.bytesPerRow * $0.height } ?? 0
        memory.setObject(image, forKey: key, cost: cost)
    }

    // MARK: Backdrops

    private static let ci = CIContext(options: [.cacheIntermediates: false])

    nonisolated func cachedBackdrop(url raw: String, fallback: String? = nil) -> UIImage? {
        for candidate in [raw, fallback ?? ""] where !candidate.isEmpty {
            if let hit = memory.object(forKey: (candidate.trimmingCharacters(in: .whitespacesAndNewlines) + "|backdrop") as NSString) { return hit }
        }
        return nil
    }

    /// A tiny, already blurred and saturated copy for hero backgrounds. Scaling it up costs nothing per frame, unlike a
    /// live `.blur` layer that the GPU has to redo whenever anything under it moves.
    func backdrop(url raw: String, headers: [String: String] = [:]) async throws -> UIImage {
        let url = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = (url + "|backdrop") as NSString
        if let hit = memory.object(forKey: key) { return hit }
        let source: UIImage
        if let hit = cachedVariant(url: url) { source = hit } else { source = try await image(url: url, headers: headers, maxPixelSize: CoverPixels.thumb) }
        let out = try await Self.offMain { () throws -> UIImage in
            guard let cg = source.cgImage else { throw ProviderError.invalid("cover not an image") }
            let k = 96 / CGFloat(max(cg.width, cg.height, 1))
            let small = CIImage(cgImage: cg).transformed(by: CGAffineTransform(scaleX: k, y: k))
            let soft = small.clampedToExtent().applyingGaussianBlur(sigma: 5)
                .applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: 1.2])
                .cropped(to: small.extent)
            guard let result = Self.ci.createCGImage(soft, from: small.extent.integral) else { throw ProviderError.invalid("backdrop") }
            return UIImage(cgImage: result)
        }
        cache(out, key)
        return out
    }

    // MARK: Disk

    /// Keeps the cover folder under 300 MB: oldest files go first, once per launch, in the background.
    private func trimOnce() {
        guard !trimmed else { return }
        trimmed = true
        Task.detached(priority: .background) {
            try? await Task.sleep(nanoseconds: 20_000_000_000)
            let fm = FileManager.default
            let keys: Set<URLResourceKey> = [.fileSizeKey, .contentModificationDateKey]
            guard let files = try? fm.contentsOfDirectory(at: Self.diskDir, includingPropertiesForKeys: Array(keys)) else { return }
            var list = files.compactMap { f -> (URL, Int, Date)? in
                guard let v = try? f.resourceValues(forKeys: keys) else { return nil }
                return (f, v.fileSize ?? 0, v.contentModificationDate ?? .distantPast)
            }
            var total = list.reduce(0) { $0 + $1.1 }
            guard total > 300 << 20 else { return }
            list.sort { $0.2 < $1.2 }
            for (f, size, _) in list where total > 240 << 20 { try? fm.removeItem(at: f); total -= size }
        }
    }

    func clear() {
        memory.removeAllObjects()
        try? FileManager.default.removeItem(at: Self.diskDir)
        try? FileManager.default.createDirectory(at: Self.diskDir, withIntermediateDirectories: true)
    }
}

#if DEBUG
/// `fixture://RRGGBB`: deterministic artwork (gradient + shapes) so glass, scrims and crops can be judged without real images.
enum CoverFixture {
    static func art(_ url: String) -> UIImage? {
        let hex = url.dropFirst("fixture://".count).prefix(6)
        guard let v = UInt32(hex, radix: 16) else { return nil }
        func c(_ k: CGFloat) -> UIColor {
            UIColor(red: min(1, CGFloat(v >> 16 & 0xff) / 255 * k), green: min(1, CGFloat(v >> 8 & 0xff) / 255 * k), blue: min(1, CGFloat(v & 0xff) / 255 * k), alpha: 1)
        }
        let portrait = url.hasSuffix("/p")
        let size = portrait ? CGSize(width: 360, height: 480) : CGSize(width: 480, height: 270)
        let f = UIGraphicsImageRendererFormat()
        f.scale = 1
        return UIGraphicsImageRenderer(size: size, format: f).image { ctx in
            let g = CGGradient(colorsSpace: nil, colors: [c(1.25).cgColor, c(0.45).cgColor] as CFArray, locations: [0, 1])!
            ctx.cgContext.drawLinearGradient(g, start: .zero, end: CGPoint(x: size.width, y: size.height), options: [])
            for i in 0..<5 {
                let d = size.width * (0.55 - CGFloat(i) * 0.09), x = size.width * (0.12 + CGFloat((i * 37) % 70) / 100), y = size.height * (0.10 + CGFloat((i * 53) % 75) / 100)
                (i % 2 == 0 ? c(1.6) : c(0.3)).withAlphaComponent(0.35).setFill()
                ctx.cgContext.fillEllipse(in: CGRect(x: x - d / 2, y: y - d / 2, width: d, height: d))
            }
        }
    }
}
#endif

enum CoverPhase { case loading, failed }

/// Cached, downsampled remote image filling its frame, over a placeholder for "not here yet" / "failed".
struct CoverImage<Placeholder: View>: View {
    let url: String
    var headers: [String: String] = [:]
    var corner: CGFloat = 0
    /// Longest side to decode to (px). `nil`: bucketed from the measured size.
    var maxPixelSize: Int? = nil
    /// Tried when `url` is empty or fails (e.g. detail-page cover dead → list-card cover).
    var fallback: String? = nil
    /// When set, the cover is one accessibility element with this id and value loaded / failed / loading.
    var a11yID: String? = nil
    let placeholder: (CoverPhase) -> Placeholder
    @State private var image: UIImage?
    @State private var failed = false
    @State private var measured: Int?
    @Environment(\.displayScale) private var scale

    init(url: String, headers: [String: String] = [:], corner: CGFloat = 0, maxPixelSize: Int? = nil, fallback: String? = nil, a11yID: String? = nil,
         @ViewBuilder placeholder: @escaping (CoverPhase) -> Placeholder) {
        self.url = url
        self.headers = headers
        self.corner = corner
        self.maxPixelSize = maxPixelSize
        self.fallback = fallback
        self.a11yID = a11yID
        self.placeholder = placeholder
        let store = CoverStore.shared
        _image = State(initialValue: store.cachedImage(url: url, fallback: fallback, maxPixelSize: maxPixelSize) ?? store.cachedVariant(url: url, fallback: fallback))
    }

    private var want: Int? { maxPixelSize ?? measured }

    var body: some View {
        // No GeometryReader here: this is the most frequent view in scrolling cells.
        Color.clear
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill().transition(.opacity)
                } else {
                    placeholder(failed ? .failed : .loading)
                }
            }
            .modifier(CoverClip(corner: corner))
            // scaledToFill overflows its frame and clipping only clips drawing; without a content shape the invisible
            // overflow kept receiving touches (1.2b4: the hero backdrop sat on top of the search bar / channel row).
            .contentShape(Rectangle())
            .modifier(CoverMeasure(active: maxPixelSize == nil) { size in
                let b = CoverPixels.bucket(max(size.width, size.height) * scale)
                // Only ever upgrade: a transition shrinking the cell must not trigger another decode.
                if b > (measured ?? 0) { measured = b }
            })
            .animation(.easeOut(duration: 0.2), value: image == nil)
            .modifier(CoverA11y(id: a11yID, state: image != nil ? "loaded" : failed ? "failed" : "loading"))
            .task(id: "\(url)|\(fallback ?? "")|\(want ?? 0)") { await load() }
    }

    private func load() async {
        guard let want else { return }
        if failed { failed = false }
        if let hit = CoverStore.shared.cachedImage(url: url, fallback: fallback, maxPixelSize: want) {
            if image !== hit { image = hit }
            return
        }
        // The cell may have been reused for another series: never keep the previous artwork.
        let variant = CoverStore.shared.cachedVariant(url: url, fallback: fallback)
        if image !== variant { image = variant }
        var tried = Set<String>()
        for u in [url, fallback ?? ""] where !u.isEmpty && tried.insert(u).inserted {
            do {
                let loaded = try await CoverStore.shared.image(url: u, headers: headers, maxPixelSize: want)
                try Task.checkCancellation()
                image = loaded
                #if DEBUG
                if u != url { print("[STV] cover fallback used: \(url.prefix(80)) → \(u.prefix(80))") }
                #endif
                return
            } catch { if Task.isCancelled { return } }
        }
        if image == nil { failed = true }
    }
}

/// SwiftUI cover that decrypts AI AES / legacy XOR when needed.
struct CoverView: View {
    let url: String
    var headers: [String: String] = [:]
    var corner: CGFloat = 14
    var maxPixelSize: Int? = nil
    var fallback: String? = nil
    var a11yID: String? = nil
    /// Set `false` for ambient backdrops so a cold load never flashes a placeholder.
    var showPlaceholder: Bool = true

    var body: some View {
        CoverImage(url: url, headers: headers, corner: corner, maxPixelSize: maxPixelSize, fallback: fallback, a11yID: a11yID) { phase in
            if showPlaceholder {
                // A still fill: one repeating shimmer animation per cell kept the render loop busy during every scroll.
                STVTheme.elevated.opacity(0.7)
                if phase == .failed { Image(systemName: "film").font(.title2).foregroundStyle(STVTheme.tertiary) }
            }
        }
    }
}

private struct CoverClip: ViewModifier {
    let corner: CGFloat
    func body(content: Content) -> some View {
        if corner > 0 { content.clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous)) } else { content.clipped() }
    }
}

private struct CoverMeasure: ViewModifier {
    let active: Bool
    let action: (CGSize) -> Void
    func body(content: Content) -> some View {
        if active { content.compatOnSize(action: action) } else { content }
    }
}

private struct CoverA11y: ViewModifier {
    let id: String?
    let state: String
    func body(content: Content) -> some View {
        if let id { content.accessibilityElement(children: .ignore).accessibilityIdentifier(id).accessibilityValue(state) }
        else { content.accessibilityHidden(true) }
    }
}

/// Blurred, saturated artwork filling its frame (hero grounds). Pre-rendered off the main thread, see `CoverStore.backdrop`.
struct CoverBackdrop: View {
    let url: String
    var fallback: String? = nil
    var headers: [String: String] = [:]
    @State private var image: UIImage?

    init(url: String, fallback: String? = nil, headers: [String: String] = [:]) {
        self.url = url
        self.fallback = fallback
        self.headers = headers
        _image = State(initialValue: CoverStore.shared.cachedBackdrop(url: url, fallback: fallback))
    }

    var body: some View {
        Color.clear
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().interpolation(.high).scaledToFill()
                        .id(ObjectIdentifier(image))
                        .transition(.opacity)
                }
            }
            .clipped()
            .animation(.easeInOut(duration: 0.4), value: image.map(ObjectIdentifier.init))
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .task(id: url + "|" + (fallback ?? "")) {
                for u in [url, fallback ?? ""] where !u.isEmpty {
                    if let b = try? await CoverStore.shared.backdrop(url: u, headers: headers) {
                        if !Task.isCancelled, image !== b { image = b }
                        return
                    }
                    if Task.isCancelled { return }
                }
            }
    }
}

/// Skeleton block. Opacity pulse only (no moving gradient, no mask): composited without re-rendering.
struct ShimmerBlock: View {
    var corner: CGFloat = 12
    @State private var dim = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        RoundedRectangle(cornerRadius: corner, style: .continuous)
            .fill(STVTheme.elevated)
            .opacity(dim ? 0.38 : 0.8)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: dim)
            .onAppear { dim = true }
            .accessibilityHidden(true)
    }
}

/// Extra request headers some cover CDNs need (hotlink protection). Filled from live probes.
enum CoverHeaders {
    /// Host suffix → Referer. Live probe 2026-11: pix-cdn77.phncdn.com answers 403 without the
    /// pornhub Referer (pix-fl / ei don't care); the others are hotlink-protected CDNs where sending
    /// the site's own Referer is what a browser does anyway.
    static let referers: [(String, String)] = [
        ("phncdn.com", "https://www.pornhub.com/"),
        ("xvideos-cdn.com", "https://www.xvideos.com/"),
        ("xnxx-cdn.com", "https://www.xnxx.com/"),
        ("hembed.com", "https://hanime1.me/"),
        ("jable.tv", "https://jable.tv/"),
        ("mrstcdn.store", "https://missav.ws/"),
        ("fourhoi.com", "https://missav.ws/"),
        ("supjav.com", "https://supjav.com/"),
        ("eporner.com", "https://www.eporner.com/"),
        ("ypncdn.com", "https://www.youporn.com/"),
        ("rdtcdn.com", "https://www.redtube.com/"),
        ("hqporner.com", "https://hqporner.com/"),
    ]
    static func `for`(url: String) -> [String: String] {
        guard let h = URL(string: url)?.host?.lowercased() else { return [:] }
        for (suffix, ref) in referers where h == suffix || h.hasSuffix("." + suffix) { return ["Referer": ref] }
        return [:]
    }
}

/// Which cover the detail hero shows: the detail page's own cover, else the list card's.
enum DetailCover {
    static func pick(detail: String, list: String) -> String {
        let d = detail.trimmingCharacters(in: .whitespacesAndNewlines)
        return d.isEmpty ? list : d
    }
}
