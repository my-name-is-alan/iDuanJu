import Foundation
import LocalAuthentication
import Combine

/// Which extra sources are visible. Adult long-video sites are OFF by default and only appear after
/// the user enables them in 我的 → 片源管理; an optional Face ID lock hides them again on every cold start.
@MainActor
final class SourceGate: ObservableObject {
    static let shared = SourceGate()

    @Published private(set) var enabled: Set<DramaSource>
    @Published private(set) var lockEnabled: Bool
    /// Session flag: Face ID passed since launch (always true when the lock is off).
    @Published private(set) var unlocked: Bool
    /// Adult Telegram channels (presets bhg1232 / NudefilmsTV, or channels the user marks adult).
    @Published private(set) var adultChannels: Bool
    /// Adult manga sources (none shipped yet; the switch gates any added later).
    @Published private(set) var adultManga: Bool
    /// 黄果 (视频站 / App 源 / AI 站) is 18+: hidden until switched on here, like the long-video sites.
    @Published private(set) var huangguo: Bool
    /// Persisted custom ordering of Home top-bar sources (keys from `HomeSource.key`). Empty = default order.
    @Published private(set) var homeOrder: [String]
    /// Sources folded off the Home top tab bar (still enabled for Search & 1-tap accessible in the Channel sheet).
    @Published private(set) var homeHidden: Set<String>
    /// User-configurable visibility for Home feature tabs (频道 / 漫画 / 小说 / 音乐). Default ON.
    @Published private(set) var showChannelsTab: Bool
    @Published private(set) var showMangaTab: Bool
    @Published private(set) var showNovelTab: Bool
    @Published private(set) var showMusicTab: Bool
    /// Bumped after every state mutation so views observing `$revision` always read post-mutation values.
    @Published private(set) var revision = 0
    private let d = UserDefaults.standard
    private static let homeOrderKey = "stv.home.sourceOrder"
    private static let homeHiddenKey = "stv.home.hiddenSources"
    private static let showChannelsKey = "stv.home.showChannels"
    private static let showMangaKey = "stv.home.showManga"
    private static let showNovelKey = "stv.home.showNovel"
    private static let showMusicKey = "stv.home.showMusic"

    private init() {
        let raw = d.stringArray(forKey: "stv.sources.enabled") ?? []
        enabled = Set(raw.compactMap(DramaSource.init(rawValue:)).filter { $0.isDynamic || $0.isAdult })
        lockEnabled = d.bool(forKey: "stv.sources.lock")
        unlocked = !d.bool(forKey: "stv.sources.lock")
        adultChannels = d.bool(forKey: "stv.sources.adultChannels")
        adultManga = d.bool(forKey: "stv.sources.adultManga")
        huangguo = d.bool(forKey: "stv.sources.huangguo")
        homeOrder = d.stringArray(forKey: Self.homeOrderKey) ?? []
        homeHidden = Set(d.stringArray(forKey: Self.homeHiddenKey) ?? []).subtracting(["builtin:hongguo"])
        showChannelsTab = (d.object(forKey: Self.showChannelsKey) as? Bool) ?? true
        showMangaTab = (d.object(forKey: Self.showMangaKey) as? Bool) ?? true
        showNovelTab = (d.object(forKey: Self.showNovelKey) as? Bool) ?? true
        showMusicTab = (d.object(forKey: Self.showMusicKey) as? Bool) ?? true
        #if DEBUG
        // Simulator screenshot hook: STV_SOURCES=jable,missav enables those sites for this run.
        if let e = ProcessInfo.processInfo.environment["STV_SOURCES"], !e.isEmpty {
            enabled = Set(e.split(separator: ",").compactMap { DramaSource(rawValue: String($0)) })
            huangguo = e.split(separator: ",").contains("huangguo")
            unlocked = true
        }
        if ProcessInfo.processInfo.environment["STV_ADULT_CHANNELS"] == "1" { adultChannels = true; unlocked = true }
        #endif
    }

    /// Sites currently shown in 首页 / 搜索 / 继续观看.
    /// Dynamic plugins & CMS sites already carry their own per-entry `enabled` switch in `PluginStore` / `CMSStore`
    /// (`Sites.all` includes only enabled entries); adult entries additionally require the 18+ lock to be open.
    var visibleSites: [DramaSource] {
        Sites.all.filter { s in
            if s.isDynamic { return !s.isAdult || unlocked }
            return unlocked && enabled.contains(s)
        }
    }
    var enabledCount: Int {
        enabled.filter { !$0.isDynamic }.count + Sites.all.filter(\.isDynamic).count
    }
    func isVisible(_ s: DramaSource) -> Bool {
        // TVBox sites are switched on and off in their own config: only the adult lock applies here.
        if s.isTVBox { return !s.isAdult || unlocked }
        if s.isDynamic { return (!s.isAdult || unlocked) && Sites.all.contains(s) }
        return s == .huangguo ? showHuangguo : s.isBuiltin || (unlocked && enabled.contains(s))
    }
    /// Series-level check: Telegram videos from adult channels follow the adult-channel switch.
    func isVisible(_ s: HGSeries) -> Bool {
        guard s.source == .telegram else { return isVisible(s.source) }
        return !(s.tags.contains(TGVideoProvider.adultTag)) || showAdultChannels
    }
    /// Sources offered in the search tab strip.
    var searchSources: [DramaSource] { [.hongguo] + (showHuangguo ? [.huangguo] : []) + visibleSites }

    var showAdultChannels: Bool { unlocked && adultChannels }
    var showAdultManga: Bool { unlocked && adultManga }
    var showHuangguo: Bool { unlocked && huangguo }
    func setHuangguo(_ on: Bool) { huangguo = on; d.set(on, forKey: "stv.sources.huangguo"); revision += 1 }
    func setAdultChannels(_ on: Bool) { adultChannels = on; d.set(on, forKey: "stv.sources.adultChannels"); revision += 1 }
    func setAdultManga(_ on: Bool) { adultManga = on; d.set(on, forKey: "stv.sources.adultManga"); revision += 1 }
    func setShowChannelsTab(_ on: Bool) { showChannelsTab = on; d.set(on, forKey: Self.showChannelsKey); revision += 1 }
    func setShowMangaTab(_ on: Bool) { showMangaTab = on; d.set(on, forKey: Self.showMangaKey); revision += 1 }
    func setShowNovelTab(_ on: Bool) { showNovelTab = on; d.set(on, forKey: Self.showNovelKey); revision += 1 }
    func setShowMusicTab(_ on: Bool) { showMusicTab = on; d.set(on, forKey: Self.showMusicKey); revision += 1 }

    func set(_ s: DramaSource, on: Bool) {
        if let pid = s.pluginID {
            PluginStore.shared.setEnabled(pid, on)
            revision += 1
            return
        }
        if let key = s.cmsKey {
            CMSStore.shared.setEnabled(key, on)
            revision += 1
            return
        }
        guard s.isAdult else { return }
        if on { enabled.insert(s) } else { enabled.remove(s) }
        d.set(enabled.map(\.rawValue).sorted(), forKey: "stv.sources.enabled")
        revision += 1
    }

    func reloadFromDefaults() {
        let raw = d.stringArray(forKey: "stv.sources.enabled") ?? []
        enabled = Set(raw.compactMap(DramaSource.init(rawValue:)).filter { $0.isDynamic || $0.isAdult })
        lockEnabled = d.bool(forKey: "stv.sources.lock")
        if !lockEnabled { unlocked = true }
        adultChannels = d.bool(forKey: "stv.sources.adultChannels")
        adultManga = d.bool(forKey: "stv.sources.adultManga")
        huangguo = d.bool(forKey: "stv.sources.huangguo")
        homeOrder = d.stringArray(forKey: Self.homeOrderKey) ?? []
        homeHidden = Set(d.stringArray(forKey: Self.homeHiddenKey) ?? []).subtracting(["builtin:hongguo"])
        showChannelsTab = (d.object(forKey: Self.showChannelsKey) as? Bool) ?? true
        showMangaTab = (d.object(forKey: Self.showMangaKey) as? Bool) ?? true
        showNovelTab = (d.object(forKey: Self.showNovelKey) as? Bool) ?? true
        showMusicTab = (d.object(forKey: Self.showMusicKey) as? Bool) ?? true
        revision += 1
    }

    // MARK: Home source ordering & folding

    func isHiddenOnHome(_ key: String) -> Bool {
        key != "builtin:hongguo" && homeHidden.contains(key)
    }

    func setHomeHidden(_ key: String, hidden: Bool) {
        guard key != "builtin:hongguo" else { return }
        if hidden { homeHidden.insert(key) } else { homeHidden.remove(key) }
        d.set(homeHidden.sorted(), forKey: Self.homeHiddenKey)
        revision += 1
    }

    func setHomeHiddenBatch(_ keys: [String], hidden: Bool) {
        let valid = keys.filter { $0 != "builtin:hongguo" }
        guard !valid.isEmpty else { return }
        if hidden {
            homeHidden.formUnion(valid)
        } else {
            homeHidden.subtract(valid)
        }
        d.set(homeHidden.sorted(), forKey: Self.homeHiddenKey)
        revision += 1
    }

    func clearHomeHidden() {
        guard !homeHidden.isEmpty else { return }
        homeHidden.removeAll()
        d.removeObject(forKey: Self.homeHiddenKey)
        revision += 1
    }

    /// Applies persisted custom order while keeping newly enabled sources appended in their natural order
    /// and preserving positions of temporarily hidden sources in `homeOrder`.
    func orderedKeys(_ activeKeys: [String]) -> [String] {
        guard !homeOrder.isEmpty else { return activeKeys }
        var rank: [String: Int] = [:]
        for (i, k) in homeOrder.enumerated() where rank[k] == nil { rank[k] = i }
        let known = activeKeys.filter { rank[$0] != nil }.sorted { rank[$0]! < rank[$1]! }
        let fresh = activeKeys.filter { rank[$0] == nil }
        return known + fresh
    }

    func setHomeOrder(_ orderedVisibleKeys: [String]) {
        // Preserve relative slots of currently hidden sources after the visible list so re-enabling doesn't lose them.
        let visibleSet = Set(orderedVisibleKeys)
        let hiddenSaved = homeOrder.filter { !visibleSet.contains($0) }
        let merged = orderedVisibleKeys + hiddenSaved
        guard merged != homeOrder else { return }
        homeOrder = merged
        d.set(merged, forKey: Self.homeOrderKey)
        revision += 1
    }

    func resetHomeOrder() {
        guard !homeOrder.isEmpty else { return }
        homeOrder = []
        d.removeObject(forKey: Self.homeOrderKey)
        revision += 1
    }

    func setLock(_ on: Bool) async {
        if on {
            guard await authenticate(reason: "开启隐藏片源锁") else { return }
            lockEnabled = true; unlocked = true
        } else {
            if lockEnabled && !unlocked {
                guard await authenticate(reason: "关闭隐藏片源锁") else { return }
            }
            lockEnabled = false; unlocked = true
        }
        d.set(lockEnabled, forKey: "stv.sources.lock")
        revision += 1
    }

    @discardableResult func unlock() async -> Bool {
        if unlocked { return true }
        unlocked = await authenticate(reason: "解锁隐藏片源")
        revision += 1
        return unlocked
    }
    func relock() { if lockEnabled { unlocked = false; revision += 1 } }

    private func authenticate(reason: String) async -> Bool {
        let ctx = LAContext()
        var err: NSError?
        // Device passcode fallback keeps it usable without biometrics; only skip auth when no device passcode exists (e.g. Simulator).
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else {
            return err?.code == LAError.passcodeNotSet.rawValue
        }
        return (try? await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }
}
