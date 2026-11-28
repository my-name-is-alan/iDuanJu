import Foundation

/// Per-source Legado `concurrentRate` limiter (`"ms"` or `"count/ms"`),
/// plus JS concurrency primitives (`singleFlight`, `lock`, `tick`).
enum LegadoRateLimit {
    private struct Spec { var count: Int; var windowSec: TimeInterval }
    private final class Bucket { var stamps: [TimeInterval] = [] }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var buckets: [String: Bucket] = [:]
    nonisolated(unsafe) private static var overrides: [String: String] = [:]
    nonisolated(unsafe) private static var namedLocks: [String: NSRecursiveLock] = [:]
    nonisolated(unsafe) private static var ticks: [String: Int] = [:]

    static func putOverride(_ sourceKey: String, _ rate: String?) {
        lock.lock(); defer { lock.unlock() }
        let t = rate?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if t.isEmpty || t == "0" { overrides.removeValue(forKey: sourceKey) } else { overrides[sourceKey] = t }
    }

    static func effectiveRate(_ sourceKey: String, declared: String?) -> String? {
        lock.lock(); let ov = overrides[sourceKey]; lock.unlock()
        if let ov { return ov }
        let d = declared?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return d.isEmpty || d == "0" ? nil : d
    }

    private static func parse(_ raw: String?) -> Spec? {
        guard let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty, text != "0" else { return nil }
        let parts = text.split(separator: "/").map { String($0).trimmingCharacters(in: .whitespaces) }
        if parts.count == 1, let ms = Double(parts[0]), ms > 0 {
            return Spec(count: 1, windowSec: min(ms, 60_000) / 1000.0)
        }
        if parts.count == 2, let count = Int(parts[0]), let ms = Double(parts[1]), count > 0, ms > 0 {
            return Spec(count: min(count, 100), windowSec: min(ms, 60_000) / 1000.0)
        }
        return nil
    }

    /// Blocks worker thread until source rate budget permits a request. Honors LegadoCancelToken.
    static func acquire(_ sourceKey: String, declaredRate: String?) throws {
        guard let spec = parse(effectiveRate(sourceKey, declared: declaredRate)) else { return }
        while true {
            if LegadoCancelToken.current?.isCancelled == true { throw CancellationError() }
            var waitSec: TimeInterval = 0
            lock.lock()
            let bucket = buckets[sourceKey] ?? { let b = Bucket(); buckets[sourceKey] = b; return b }()
            let now = Date().timeIntervalSinceReferenceDate
            bucket.stamps.removeAll { now - $0 >= spec.windowSec }
            if bucket.stamps.count < spec.count {
                bucket.stamps.append(now)
                lock.unlock()
                return
            }
            waitSec = max(0.005, spec.windowSec - (now - bucket.stamps[0]))
            lock.unlock()
            Thread.sleep(forTimeInterval: min(waitSec, 0.25))
        }
    }

    static func withNamedLock<T>(_ key: String, _ body: () throws -> T) rethrows -> T {
        lock.lock()
        let l = namedLocks[key] ?? { let nl = NSRecursiveLock(); namedLocks[key] = nl; return nl }()
        lock.unlock()
        l.lock(); defer { l.unlock() }
        return try body()
    }

    static func tick(_ sourceKey: String, _ name: String) -> Int {
        lock.lock(); defer { lock.unlock() }
        let k = sourceKey + "|" + name
        let next = (ticks[k] ?? 0) + 1
        ticks[k] = next
        return next
    }

    static func resetForTest() {
        lock.lock(); defer { lock.unlock() }
        buckets.removeAll(); overrides.removeAll(); namedLocks.removeAll(); ticks.removeAll()
    }
}
