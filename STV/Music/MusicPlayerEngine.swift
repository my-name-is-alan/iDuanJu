import Foundation
import AVFoundation
import MediaPlayer
import UIKit

enum MusicPlayMode: String, CaseIterable {
    case loopAll
    case loopOne
    case shuffle

    var icon: String {
        switch self {
        case .loopAll: "repeat"
        case .loopOne: "repeat.1"
        case .shuffle: "shuffle"
        }
    }

    var label: String {
        switch self {
        case .loopAll: "列表循环"
        case .loopOne: "单曲循环"
        case .shuffle: "随机播放"
        }
    }

    var next: MusicPlayMode {
        switch self {
        case .loopAll: .loopOne
        case .loopOne: .shuffle
        case .shuffle: .loopAll
        }
    }
}

/// Real background-capable serverless music engine on iOS.
/// - Plays direct CDN / NAS / cached streams via `AVPlayer`.
/// - Pre-resolves the next queue track when <= 20s remain so iOS never suspends between tracks in background.
/// - Acquires `PlaybackBackgroundLease` during manual background track changes or auto-fallback resolution.
/// - Smooth 180ms fade-out on video/TTS interruption & 500ms fade-in on smart resume via `AppAudioArbiter`.
/// - Supports Sleep Timer with 30s smooth background fade-out.
@MainActor
final class MusicPlayerEngine: ObservableObject {
    static let shared = MusicPlayerEngine()

    @Published private(set) var currentTrack: MusicTrack?
    @Published private(set) var queue: [MusicTrack] = []
    @Published private(set) var currentIndex: Int = 0
    @Published private(set) var isPlaying: Bool = false
    @Published private(set) var isLoading: Bool = false
    @Published private(set) var currentTime: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var lyrics: [MusicLyricLine] = []
    @Published private(set) var activeLyricIndex: Int = 0
    @Published private(set) var signalPath: MusicSignalPath?
    @Published private(set) var activeQualityLabel: String = ""
    @Published private(set) var activeEntitlement: MusicEntitlement = .full
    @Published private(set) var statusMessage: String?
    @Published var playMode: MusicPlayMode = .loopAll
    @Published var playbackRate: Float = 1.0 {
        didSet { if isPlaying { player.rate = playbackRate } }
    }
    @Published var showFullPlayer: Bool = false
    @Published var miniBarDismissed: Bool = false
    @Published private(set) var sleepRemainingSec: Int?
    @Published private(set) var immersiveHolders: Set<String> = []

    var hasActiveSession: Bool { currentTrack != nil && !miniBarDismissed }
    var shouldShowGlobalMiniBar: Bool { hasActiveSession && !showFullPlayer && immersiveHolders.isEmpty }

    func enterImmersive(_ holderId: String) {
        immersiveHolders.insert(holderId)
    }

    func leaveImmersive(_ holderId: String) {
        immersiveHolders.remove(holderId)
    }

    func debugPlaybackState() -> String {
        let item = player.currentItem
        let st = item?.status.rawValue ?? -1
        let err = item?.error?.localizedDescription ?? "nil"
        let errCode = (item?.error as NSError?)?.code ?? 0
        let errDom = (item?.error as NSError?)?.domain ?? ""
        let evs = item?.errorLog()?.events.map { "\($0.errorStatusCode):\($0.errorDomain):\($0.errorComment ?? "")" }.joined(separator: "|") ?? "none"
        let tcs = player.timeControlStatus.rawValue
        let wait = player.reasonForWaitingToPlay?.rawValue ?? "nil"
        return "url=\(currentStreamURL?.absoluteString ?? "nil") itemStatus=\(st) err=\(errDom)(\(errCode)):\(err) log=\(evs) tcs=\(tcs) wait=\(wait) rate=\(player.rate) msg=\(statusMessage ?? "nil")"
    }

    private let player = AVPlayer()
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var interruptionObserver: NSObjectProtocol?
    private var itemStatusObs: NSKeyValueObservation?
    private var timeControlObs: NSKeyValueObservation?
    private var activeResourceLoader: MusicDirectResourceLoader?
    private var currentStreamURL: URL?
    private var failureRetryCount = 0
    private var preResolvedNext: (trackId: String, resolved: MusicProviderHub.ResolvedAudio)?
    private var preResolvingId: String?
    private var activeLoadTask: Task<Void, Never>?
    private var fadeTask: Task<Void, Never>?
    private var sleepTask: Task<Void, Never>?
    private var remoteConfigured = false
    private var cachedArtwork: (url: String, image: UIImage)?

    private init() {
        player.automaticallyWaitsToMinimizeStalling = true
        installObservers()
        AppAudioArbiter.shared.musicHooks = AppAudioArbiter.MusicHooks(
            isPlaying: { [weak self] in self?.isPlaying ?? false },
            fadeOutAndPause: { [weak self] in self?.fadeOutAndPause() },
            fadeInAndResume: { [weak self] in self?.fadeInAndResume() },
            refreshLockScreen: { [weak self] in self?.updateLockScreenInfo() }
        )
    }

    private func installObservers() {
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
            queue: .main
        ) { [weak self] cmTime in
            Task { @MainActor [weak self] in
                self?.onTimeTick(cmTime.seconds)
            }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: nil,
            queue: .main
        ) { [weak self] note in
            Task { @MainActor [weak self] in
                guard let self, note.object as? AVPlayerItem === self.player.currentItem else { return }
                self.handleTrackEnded()
            }
        }
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            guard let typeRaw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt else { return }
            let optRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            Task { @MainActor [weak self] in
                self?.handleSystemInterruption(typeRaw: typeRaw, optionsRaw: optRaw)
            }
        }
    }

    // MARK: - Playback Controls

    func play(track: MusicTrack, in list: [MusicTrack]? = nil, openFull: Bool = false) {
        if let list, !list.isEmpty {
            queue = list
            currentIndex = list.firstIndex(where: { $0.id == track.id }) ?? 0
        } else if !queue.contains(where: { $0.id == track.id }) {
            queue.insert(track, at: 0)
            currentIndex = 0
        } else if let idx = queue.firstIndex(where: { $0.id == track.id }) {
            currentIndex = idx
        }
        miniBarDismissed = false
        if openFull { showFullPlayer = true }
        startTrack(track)
    }

    func togglePlayPause() {
        guard currentTrack != nil else { return }
        if isPlaying {
            pauseImmediate()
            AppAudioArbiter.shared.clearPendingResume()
        } else {
            AppAudioArbiter.shared.claim(.music)
            activateMusicAudioSession()
            player.volume = 1.0
            player.play()
            if playbackRate != 1.0 { player.rate = playbackRate }
            isPlaying = true
            updateLockScreenInfo()
        }
    }

    func nextTrack(userInitiated: Bool = true) {
        guard !queue.isEmpty else { return }
        let nextIdx: Int
        if playMode == .shuffle && queue.count > 1 {
            var pick = Int.random(in: 0..<queue.count)
            if pick == currentIndex { pick = (currentIndex + 1) % queue.count }
            nextIdx = pick
        } else {
            nextIdx = (currentIndex + 1) % queue.count
        }
        currentIndex = nextIdx
        startTrack(queue[nextIdx])
    }

    func previousTrack() {
        guard !queue.isEmpty else { return }
        if currentTime > 4 {
            seek(to: 0)
            return
        }
        currentIndex = (currentIndex - 1 + queue.count) % queue.count
        startTrack(queue[currentIndex])
    }

    func seek(to seconds: Double) {
        let clamped = min(max(0, seconds), max(duration, 1))
        currentTime = clamped
        player.seek(to: CMTime(seconds: clamped, preferredTimescale: 600), toleranceBefore: CMTime(seconds: 0.15, preferredTimescale: 600), toleranceAfter: CMTime(seconds: 0.15, preferredTimescale: 600))
        updateActiveLyric(at: clamped)
        updateLockScreenInfo()
    }

    func switchStream(to alt: MusicAlternative) {
        guard let urlStr = alt.resolvedURL, let url = URL(string: urlStr) else { return }
        let updated = MusicTrack(
            id: alt.track.id,
            source: alt.track.source,
            sourceId: alt.track.sourceId,
            title: currentTrack?.title ?? alt.track.title,
            artist: currentTrack?.artist ?? alt.track.artist,
            album: currentTrack?.album ?? alt.track.album,
            coverURL: (currentTrack?.coverURL.isEmpty == false ? currentTrack!.coverURL : alt.track.coverURL),
            durationSec: alt.track.durationSec,
            qualityLabel: alt.qualityLabel,
            entitlement: alt.entitlement,
            streamURL: urlStr,
            streamHeaders: alt.headers,
            pluginId: alt.track.pluginId,
            extraHash: alt.track.extraHash
        )
        currentTrack = updated
        if queue.indices.contains(currentIndex) { queue[currentIndex] = updated }
        let res = MusicProviderHub.ResolvedAudio(
            url: url,
            headers: alt.headers ?? [:],
            qualityLabel: alt.qualityLabel,
            entitlement: alt.entitlement,
            actualSource: alt.track.source,
            isCached: false
        )
        loadResolved(res, for: updated)
        statusMessage = "已切换至 \(alt.track.source.label) · \(alt.qualityLabel)"
    }

    /// Switches preferred audio quality and seamlessly re-resolves the active track at the current timestamp.
    func switchQuality(to quality: MusicAudioQuality) {
        let store = MusicStore.shared
        store.settings.preferredQuality = quality
        preResolvedNext = nil
        preResolvingId = nil
        guard let track = currentTrack else { return }
        if track.source.isNAS || track.source == .itunes {
            statusMessage = "已将默认音质设为 \(quality.label)（当前音源为固定原流）"
            return
        }
        let resumeSec = currentTime
        let wasPlaying = isPlaying
        store.removeCachedFile(for: track)
        let enabled = store.enabledOnlineSources
        let nas = store.nasConfigs
        let autoFallback = store.settings.autoFallback
        var cookies: [MusicSourceKind: String] = [:]
        for p in store.sourcePrefs { if let c = p.customCookie { cookies[p.kind] = c } }

        activeLoadTask?.cancel()
        isLoading = true
        statusMessage = "正在切换至 \(quality.label)…"
        activeLoadTask = Task {
            let resolved = await MusicProviderHub.shared.resolveStream(
                for: track,
                cachedFile: nil,
                cookies: cookies,
                enabledSources: enabled,
                nasConfigs: nas,
                autoFallback: autoFallback,
                preferredQuality: quality
            )
            guard !Task.isCancelled, self.currentTrack?.id == track.id else { return }
            if let resolved {
                self.loadResolved(resolved, for: track, seekTo: resumeSec, autoPlay: wasPlaying)
                self.statusMessage = "已切换音质：\(resolved.qualityLabel)"
                if !resolved.isCached && !resolved.url.isFileURL && resolved.entitlement == .full {
                    self.backgroundCacheIfEligible(url: resolved.url, headers: resolved.headers, for: track)
                }
            } else {
                self.isLoading = false
                self.statusMessage = "切换 \(quality.label) 失败，保持原音质"
            }
        }
    }

    /// Removes a track from the playback queue cleanly (adjusting currentIndex or advancing if removing the active track).
    func removeFromQueue(at index: Int) {
        guard queue.indices.contains(index) else { return }
        let removingCurrent = (index == currentIndex)
        let wasPlaying = isPlaying
        queue.remove(at: index)
        if queue.isEmpty {
            pauseImmediate()
            currentTrack = nil
            currentIndex = 0
            currentTime = 0
            duration = 0
            showFullPlayer = false
            miniBarDismissed = true
            AppAudioArbiter.shared.release(.music)
            return
        }
        if index < currentIndex {
            currentIndex = max(0, currentIndex - 1)
            return
        }
        if removingCurrent {
            currentIndex = index % queue.count
            let next = queue[currentIndex]
            if wasPlaying {
                startTrack(next)
            } else {
                pauseImmediate()
                currentTrack = next
                currentTime = 0
                duration = next.durationSec
                updateLockScreenInfo()
            }
        }
    }

    func removeFromQueue(trackId: String) {
        guard let idx = queue.firstIndex(where: { $0.id == trackId }) else { return }
        removeFromQueue(at: idx)
    }

    func dismissMiniBar() {
        pauseImmediate()
        miniBarDismissed = true
        AppAudioArbiter.shared.release(.music)
    }

    // MARK: - Sleep Timer (30s smooth background fade-out)

    func setSleepTimer(minutes: Int?) {
        sleepTask?.cancel()
        sleepTask = nil
        guard let minutes, minutes > 0 else {
            sleepRemainingSec = nil
            return
        }
        sleepRemainingSec = minutes * 60
        sleepTask = Task {
            while !Task.isCancelled, let rem = sleepRemainingSec, rem > 0 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard !Task.isCancelled else { return }
                let next = rem - 1
                sleepRemainingSec = next
                if next <= 30 && next > 0 && isPlaying {
                    player.volume = Float(next) / 30.0
                }
                if next == 0 {
                    pauseImmediate()
                    player.volume = 1.0
                    sleepRemainingSec = nil
                    try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
                    return
                }
            }
        }
    }

    // MARK: - Internal Resolution, Pre-Resolve & Fade

    private func startTrack(_ track: MusicTrack) {
        activeLoadTask?.cancel()
        fadeTask?.cancel()
        currentTrack = track
        currentTime = 0
        duration = track.durationSec
        lyrics = []
        activeLyricIndex = 0
        isLoading = true
        statusMessage = nil

        AppAudioArbiter.shared.claim(.music)
        MusicStore.shared.recordPlay(track)

        let lease = PlaybackBackgroundLease()
        let store = MusicStore.shared
        let cached = store.cachedFileURL(for: track)
        let enabled = store.enabledOnlineSources
        let nas = store.nasConfigs
        let autoFallback = store.settings.autoFallback
        let lrclib = store.settings.lrclibEnabled
        let preferredQuality = store.settings.preferredQuality
        var cookies: [MusicSourceKind: String] = [:]
        for p in store.sourcePrefs { if let c = p.customCookie { cookies[p.kind] = c } }

        let preHit = (preResolvedNext?.trackId == track.id) ? preResolvedNext?.resolved : nil
        preResolvedNext = nil
        preResolvingId = nil

        activeLoadTask = Task {
            defer { lease.end() }

            async let lyricsTask = MusicProviderHub.shared.fetchLyrics(for: track, lrclibEnabled: lrclib)
            let resolved: MusicProviderHub.ResolvedAudio?
            if let preHit {
                resolved = preHit
            } else {
                resolved = await MusicProviderHub.shared.resolveStream(
                    for: track,
                    cachedFile: cached,
                    cookies: cookies,
                    enabledSources: enabled,
                    nasConfigs: nas,
                    autoFallback: autoFallback,
                    preferredQuality: preferredQuality
                )
            }
            guard !Task.isCancelled else { return }

            if let resolved {
                self.loadResolved(resolved, for: track)
                // Opportunistic offline cache for remote full tracks
                if !resolved.isCached && !resolved.url.isFileURL && resolved.entitlement == .full {
                    self.backgroundCacheIfEligible(url: resolved.url, headers: resolved.headers, for: track)
                }
            } else {
                self.isLoading = false
                self.isPlaying = false
                self.statusMessage = "当前源暂不可播，请点「换源」或导入音乐插件"
            }

            let fetchedLyrics = await lyricsTask
            guard !Task.isCancelled, self.currentTrack?.id == track.id else { return }
            self.lyrics = fetchedLyrics
            self.updateActiveLyric(at: self.currentTime)
        }
    }

    private func loadResolved(
        _ resolved: MusicProviderHub.ResolvedAudio,
        for track: MusicTrack,
        isRetry: Bool = false,
        seekTo resumeSec: Double = 0,
        autoPlay: Bool = true
    ) {
        activateMusicAudioSession()
        configureRemoteCommandsIfNeeded()
        if !isRetry { failureRetryCount = 0 }
        currentStreamURL = resolved.url
        activeQualityLabel = resolved.qualityLabel
        activeEntitlement = resolved.entitlement
        if var cur = currentTrack, cur.id == track.id {
            cur.qualityLabel = resolved.qualityLabel
            cur.entitlement = resolved.entitlement
            currentTrack = cur
            if queue.indices.contains(currentIndex), queue[currentIndex].id == track.id {
                queue[currentIndex] = cur
            }
        }
        itemStatusObs?.invalidate()
        timeControlObs?.invalidate()
        activeResourceLoader?.invalidate()
        activeResourceLoader = nil

        let asset: AVURLAsset
        if resolved.url.isFileURL {
            asset = AVURLAsset(url: resolved.url)
        } else {
            let loader = MusicDirectResourceLoader(headers: resolved.headers)
            if let directAsset = loader.makeAsset(for: resolved.url) {
                activeResourceLoader = loader
                asset = directAsset
            } else if resolved.headers.isEmpty {
                asset = AVURLAsset(url: resolved.url)
            } else {
                asset = AVURLAsset(url: resolved.url, options: ["AVURLAssetHTTPHeaderFieldsKey": resolved.headers])
            }
        }
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 12
        player.replaceCurrentItem(with: item)
        player.volume = 1.0
        if autoPlay {
            player.play()
        } else {
            player.pause()
        }
        isPlaying = autoPlay
        isLoading = true

        var didInitialSeek = false
        itemStatusObs = item.observe(\.status, options: [.initial, .new]) { [weak self] observed, _ in
            Task { @MainActor [weak self] in
                guard let self, self.player.currentItem === observed else { return }
                switch observed.status {
                case .readyToPlay:
                    self.isLoading = false
                    let d = observed.duration.seconds
                    if d.isFinite && d > 1 { self.duration = d }
                    if !didInitialSeek && resumeSec > 1.0 {
                        didInitialSeek = true
                        let target = min(resumeSec, max(self.duration - 1, 0))
                        self.player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: CMTime(seconds: 0.2, preferredTimescale: 600), toleranceAfter: CMTime(seconds: 0.2, preferredTimescale: 600))
                    }
                    if self.isPlaying {
                        self.player.play()
                        if self.playbackRate != 1.0 { self.player.rate = self.playbackRate }
                    }
                    self.updateLockScreenInfo()
                case .failed:
                    self.handleItemFailure(for: track, failedURL: resolved.url, error: observed.error)
                default:
                    break
                }
            }
        }

        timeControlObs = player.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                switch p.timeControlStatus {
                case .playing:
                    self.isLoading = false
                    self.isPlaying = true
                case .waitingToPlayAtSpecifiedRate:
                    if self.isPlaying { self.isLoading = true }
                case .paused:
                    self.isLoading = false
                @unknown default:
                    break
                }
            }
        }

        let outputName = AVAudioSession.sharedInstance().currentRoute.outputs.first?.portName ?? "本机扬声器"
        let isLossless = resolved.qualityLabel.localizedCaseInsensitiveContains("FLAC")
            || resolved.qualityLabel.localizedCaseInsensitiveContains("ALAC")
            || resolved.qualityLabel.localizedCaseInsensitiveContains("Hi-Res")
            || resolved.qualityLabel.contains("母带")
            || resolved.qualityLabel.contains("无损")
            || resolved.qualityLabel.contains("SQ")
        signalPath = MusicSignalPath(
            sourceStage: "\(resolved.actualSource.fullName) · 客户端直解",
            streamStage: resolved.isCached ? "本机 LRU 离线缓存" : "直连 CDN / NAS 原流（零中转）",
            decodeStage: "AVPlayer 本机硬解 · \(resolved.qualityLabel)",
            outputStage: outputName,
            isLossless: isLossless,
            isCached: resolved.isCached
        )

        if resolved.actualSource != track.source {
            statusMessage = "已自动换源至 \(resolved.actualSource.label)（\(resolved.qualityLabel)）"
        } else if resolved.entitlement == .preview {
            statusMessage = "当前歌曲仅返回试听片段，可点「换源」切其他平台"
        }

        updateLockScreenInfo()
        fetchArtworkForLockScreen(urlString: track.coverURL)
    }

    private func handleItemFailure(for track: MusicTrack, failedURL: URL, error: Error?) {
        guard currentTrack?.id == track.id else { return }
        if failedURL.isFileURL {
            MusicStore.shared.removeCachedFile(for: track)
        }
        if failureRetryCount < 2 {
            failureRetryCount += 1
            let store = MusicStore.shared
            var cookies: [MusicSourceKind: String] = [:]
            for p in store.sourcePrefs { if let c = p.customCookie { cookies[p.kind] = c } }
            let enabled = store.enabledOnlineSources
            let nas = store.nasConfigs
            statusMessage = "主源加载失败，正在自动换源…"
            Task {
                if let fallback = await MusicProviderHub.shared.resolveFallbackStream(
                    for: track,
                    excludingURL: failedURL,
                    cookies: cookies,
                    enabledSources: enabled,
                    nasConfigs: nas
                ), self.currentTrack?.id == track.id {
                    self.loadResolved(fallback, for: track, isRetry: true)
                } else if self.currentTrack?.id == track.id {
                    self.isLoading = false
                    self.isPlaying = false
                    self.statusMessage = "播放失败（\(error?.localizedDescription ?? "流不可用")），请点「换源」"
                }
            }
        } else {
            isLoading = false
            isPlaying = false
            statusMessage = "当前源不可播，请点「换源」或在「我的 → 音乐与音源设置」登录音源"
        }
    }

    private func onTimeTick(_ sec: Double) {
        guard sec.isFinite, sec >= 0 else { return }
        currentTime = sec
        if let itemDur = player.currentItem?.duration.seconds, itemDur.isFinite, itemDur > 1 {
            duration = itemDur
        }
        updateActiveLyric(at: sec)

        // Pre-resolve next track when <= 20s remain so background transition is gapless & never suspended.
        let remaining = duration - sec
        if isPlaying, remaining > 1, remaining <= 20, queue.count > 1, playMode != .loopOne {
            let nextIdx = (currentIndex + 1) % queue.count
            let nextTrack = queue[nextIdx]
            if preResolvedNext?.trackId != nextTrack.id && preResolvingId != nextTrack.id {
                preResolvingId = nextTrack.id
                let store = MusicStore.shared
                let cached = store.cachedFileURL(for: nextTrack)
                let enabled = store.enabledOnlineSources
                let nas = store.nasConfigs
                let autoFb = store.settings.autoFallback
                let prefQ = store.settings.preferredQuality
                var cookies: [MusicSourceKind: String] = [:]
                for p in store.sourcePrefs { if let c = p.customCookie { cookies[p.kind] = c } }
                Task {
                    if let res = await MusicProviderHub.shared.resolveStream(
                        for: nextTrack,
                        cachedFile: cached,
                        cookies: cookies,
                        enabledSources: enabled,
                        nasConfigs: nas,
                        autoFallback: autoFb,
                        preferredQuality: prefQ
                    ) {
                        self.preResolvedNext = (nextTrack.id, res)
                    }
                }
            }
        }
    }

    private func handleTrackEnded() {
        if playMode == .loopOne, let t = currentTrack {
            seek(to: 0)
            player.play()
            if playbackRate != 1.0 { player.rate = playbackRate }
            isPlaying = true
            _ = t
            return
        }
        nextTrack(userInitiated: false)
    }

    private func updateActiveLyric(at sec: Double) {
        guard !lyrics.isEmpty else { return }
        var idx = 0
        for (i, line) in lyrics.enumerated() {
            if line.timeSec <= sec + 0.15 { idx = i } else { break }
        }
        if idx != activeLyricIndex { activeLyricIndex = idx }
    }

    private func fadeOutAndPause() {
        fadeTask?.cancel()
        guard isPlaying else { return }
        fadeTask = Task {
            let steps = 6
            for i in 1...steps {
                player.volume = max(0, 1.0 - Float(i) / Float(steps))
                try? await Task.sleep(nanoseconds: 30_000_000)
                if Task.isCancelled { return }
            }
            pauseImmediate()
            player.volume = 1.0
        }
    }

    private func fadeInAndResume() {
        fadeTask?.cancel()
        guard currentTrack != nil else { return }
        activateMusicAudioSession()
        player.volume = 0.05
        player.play()
        if playbackRate != 1.0 { player.rate = playbackRate }
        isPlaying = true
        updateLockScreenInfo()
        fadeTask = Task {
            let steps = 10
            for i in 1...steps {
                player.volume = min(1.0, Float(i) / Float(steps))
                try? await Task.sleep(nanoseconds: 50_000_000)
                if Task.isCancelled { return }
            }
            player.volume = 1.0
        }
    }

    private func pauseImmediate() {
        player.pause()
        isPlaying = false
        updateLockScreenInfo()
    }

    private func activateMusicAudioSession() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, mode: .default)
        try? s.setActive(true)
    }

    private func handleSystemInterruption(typeRaw: UInt, optionsRaw: UInt) {
        guard AppAudioArbiter.shared.activeOwner == .music else { return }
        if typeRaw == AVAudioSession.InterruptionType.began.rawValue {
            pauseImmediate()
        } else if typeRaw == AVAudioSession.InterruptionType.ended.rawValue {
            let opts = AVAudioSession.InterruptionOptions(rawValue: optionsRaw)
            if opts.contains(.shouldResume) {
                fadeInAndResume()
            }
        }
    }

    // MARK: - Lock Screen / Control Center / Native Dynamic Island

    private func configureRemoteCommandsIfNeeded() {
        guard !remoteConfigured else { return }
        remoteConfigured = true
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.isEnabled = true
        c.pauseCommand.isEnabled = true
        c.togglePlayPauseCommand.isEnabled = true
        c.nextTrackCommand.isEnabled = true
        c.previousTrackCommand.isEnabled = true
        c.changePlaybackPositionCommand.isEnabled = true

        c.playCommand.addTarget { [weak self] _ in
            let owner = AppAudioArbiter.currentOwnerSnapshot
            guard owner == .music || owner == .none else { return .commandFailed }
            Task { @MainActor in
                if self?.isPlaying == false { self?.togglePlayPause() }
            }
            return .success
        }
        c.pauseCommand.addTarget { [weak self] _ in
            guard AppAudioArbiter.currentOwnerSnapshot == .music else { return .commandFailed }
            Task { @MainActor in
                if self?.isPlaying == true { self?.togglePlayPause() }
            }
            return .success
        }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in
            let owner = AppAudioArbiter.currentOwnerSnapshot
            guard owner == .music || owner == .none else { return .commandFailed }
            Task { @MainActor in self?.togglePlayPause() }
            return .success
        }
        c.nextTrackCommand.addTarget { [weak self] _ in
            let owner = AppAudioArbiter.currentOwnerSnapshot
            guard owner == .music || owner == .none else { return .commandFailed }
            Task { @MainActor in self?.nextTrack() }
            return .success
        }
        c.previousTrackCommand.addTarget { [weak self] _ in
            let owner = AppAudioArbiter.currentOwnerSnapshot
            guard owner == .music || owner == .none else { return .commandFailed }
            Task { @MainActor in self?.previousTrack() }
            return .success
        }
        c.changePlaybackPositionCommand.addTarget { [weak self] ev in
            guard AppAudioArbiter.currentOwnerSnapshot == .music,
                  let pos = (ev as? MPChangePlaybackPositionCommandEvent)?.positionTime else { return .commandFailed }
            Task { @MainActor in self?.seek(to: pos) }
            return .success
        }
    }

    func updateLockScreenInfo() {
        guard let t = currentTrack,
              AppAudioArbiter.shared.activeOwner == .music || AppAudioArbiter.shared.activeOwner == .none else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: t.title,
            MPMediaItemPropertyArtist: t.artist,
            MPMediaItemPropertyAlbumTitle: t.album,
            MPMediaItemPropertyPlaybackDuration: max(1, duration),
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? playbackRate : 0.0
        ]
        if let art = cachedArtwork, art.url == t.coverURL {
            let img = art.image
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
    }

    private func fetchArtworkForLockScreen(urlString: String) {
        guard !urlString.isEmpty, cachedArtwork?.url != urlString, let u = URL(string: urlString) else { return }
        Task {
            guard let (data, _) = try? await MusicDirectNet.session.data(from: u),
                  let img = UIImage(data: data) else { return }
            self.cachedArtwork = (urlString, img)
            self.updateLockScreenInfo()
        }
    }

    private func backgroundCacheIfEligible(url: URL, headers: [String: String], for track: MusicTrack) {
        Task.detached(priority: .background) {
            var req = URLRequest(url: url)
            for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
            guard let (data, resp) = try? await MusicDirectNet.mediaSession.data(for: req),
                  let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  data.count <= 45 * 1024 * 1024 else { return }
            await MainActor.run {
                MusicStore.shared.cacheAudioData(data, for: track, sourceURL: url)
            }
        }
    }
}

/// Streams HTTP/HTTPS audio directly into `AVPlayer` via `MusicDirectNet` (system HTTP/HTTPS/SOCKS proxy disabled),
/// supporting full byte-range seeking, incremental chunks, and NAS/upstream headers without private AVURLAsset keys.
private final class MusicDirectResourceLoader: NSObject, AVAssetResourceLoaderDelegate, URLSessionDataDelegate {
    private static let httpsScheme = "stvmusicds"
    private static let httpScheme = "stvmusicd"

    private struct ActiveRequest {
        let loadingRequest: AVAssetResourceLoadingRequest
        let task: URLSessionDataTask
        var skipBytes: Int64
    }

    private let headers: [String: String]
    private let queue = DispatchQueue(label: "stv.music.directloader")
    private lazy var delegateQueue: OperationQueue = {
        let q = OperationQueue()
        q.maxConcurrentOperationCount = 1
        q.underlyingQueue = queue
        return q
    }()
    private var urlSession: URLSession?
    private var active: [Int: ActiveRequest] = [:]
    private var invalidated = false

    init(headers: [String: String]) {
        self.headers = headers
        super.init()
        let cfg = MusicDirectNet.makeConfiguration(timeout: 15, resourceTimeout: 300)
        self.urlSession = URLSession(configuration: cfg, delegate: self, delegateQueue: delegateQueue)
    }

    func makeAsset(for url: URL) -> AVURLAsset? {
        guard var comps = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let scheme = comps.scheme?.lowercased(),
              scheme == "https" || scheme == "http" else { return nil }
        comps.scheme = (scheme == "https") ? Self.httpsScheme : Self.httpScheme
        guard let rewritten = comps.url else { return nil }
        let asset = AVURLAsset(url: rewritten)
        asset.resourceLoader.setDelegate(self, queue: queue)
        return asset
    }

    func invalidate() {
        queue.async { [weak self] in
            guard let self, !self.invalidated else { return }
            self.invalidated = true
            for (_, entry) in self.active {
                entry.task.cancel()
                if !entry.loadingRequest.isFinished && !entry.loadingRequest.isCancelled {
                    entry.loadingRequest.finishLoading(with: URLError(.cancelled))
                }
            }
            self.active.removeAll()
            self.urlSession?.invalidateAndCancel()
            self.urlSession = nil
        }
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        guard !invalidated, let urlSession, let url = loadingRequest.request.url,
              var comps = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let scheme = comps.scheme?.lowercased(),
              scheme == Self.httpsScheme || scheme == Self.httpScheme else { return false }

        comps.scheme = (scheme == Self.httpsScheme) ? "https" : "http"
        guard let realURL = comps.url else { return false }

        var req = URLRequest(url: realURL)
        req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15", forHTTPHeaderField: "User-Agent")
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }

        if let dataReq = loadingRequest.dataRequest {
            let start = dataReq.currentOffset
            if dataReq.requestsAllDataToEndOfResource {
                req.setValue("bytes=\(start)-", forHTTPHeaderField: "Range")
            } else {
                let end = start + Int64(max(1, dataReq.requestedLength)) - 1
                req.setValue("bytes=\(start)-\(end)", forHTTPHeaderField: "Range")
            }
        } else if loadingRequest.contentInformationRequest != nil {
            req.setValue("bytes=0-1", forHTTPHeaderField: "Range")
        }

        let task = urlSession.dataTask(with: req)
        active[task.taskIdentifier] = ActiveRequest(loadingRequest: loadingRequest, task: task, skipBytes: 0)
        task.resume()
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        if let hit = active.first(where: { $0.value.loadingRequest === loadingRequest }) {
            active.removeValue(forKey: hit.key)
            hit.value.task.cancel()
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard var entry = active[dataTask.taskIdentifier] else {
            completionHandler(.cancel)
            return
        }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            active.removeValue(forKey: dataTask.taskIdentifier)
            if !entry.loadingRequest.isFinished && !entry.loadingRequest.isCancelled {
                entry.loadingRequest.finishLoading(with: URLError(.badServerResponse))
            }
            completionHandler(.cancel)
            return
        }

        var totalLength: Int64 = http.expectedContentLength
        var rangeSupported = (http.statusCode == 206)
        if let contentRange = http.value(forHTTPHeaderField: "Content-Range"),
           let slash = contentRange.lastIndex(of: "/") {
            let tail = String(contentRange[contentRange.index(after: slash)...]).trimmingCharacters(in: .whitespaces)
            if let parsed = Int64(tail), parsed > 0 { totalLength = parsed }
            rangeSupported = true
        } else if let accept = http.value(forHTTPHeaderField: "Accept-Ranges"), accept.lowercased().contains("bytes") {
            rangeSupported = true
        }

        if let info = entry.loadingRequest.contentInformationRequest {
            info.contentType = Self.uti(for: http.mimeType, url: http.url)
            info.isByteRangeAccessSupported = rangeSupported
            if totalLength > 0 { info.contentLength = totalLength }
        }

        if http.statusCode == 200, let dataReq = entry.loadingRequest.dataRequest, dataReq.currentOffset > 0 {
            entry.skipBytes = dataReq.currentOffset
            active[dataTask.taskIdentifier] = entry
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard var entry = active[dataTask.taskIdentifier],
              !entry.loadingRequest.isCancelled, !entry.loadingRequest.isFinished else { return }
        if entry.skipBytes > 0 {
            if Int64(data.count) <= entry.skipBytes {
                entry.skipBytes -= Int64(data.count)
                active[dataTask.taskIdentifier] = entry
                return
            }
            let sliced = data.dropFirst(Int(entry.skipBytes))
            entry.skipBytes = 0
            active[dataTask.taskIdentifier] = entry
            entry.loadingRequest.dataRequest?.respond(with: Data(sliced))
            return
        }
        entry.loadingRequest.dataRequest?.respond(with: data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let entry = active.removeValue(forKey: task.taskIdentifier) else { return }
        guard !entry.loadingRequest.isCancelled, !entry.loadingRequest.isFinished else { return }
        if let error {
            entry.loadingRequest.finishLoading(with: error)
        } else {
            entry.loadingRequest.finishLoading()
        }
    }

    private static func uti(for mimeType: String?, url: URL?) -> String {
        let m = (mimeType ?? "").lowercased()
        if m.contains("mpeg") || m.contains("mp3") { return "public.mp3" }
        if m.contains("flac") { return "org.xiph.flac" }
        if m.contains("mp4") || m.contains("m4a") { return "public.mpeg-4-audio" }
        if m.contains("aac") { return "public.aac-audio" }
        if m.contains("wav") { return "com.microsoft.waveform-audio" }
        switch (url?.pathExtension ?? "").lowercased() {
        case "flac": return "org.xiph.flac"
        case "m4a", "mp4": return "public.mpeg-4-audio"
        case "aac": return "public.aac-audio"
        case "wav": return "com.microsoft.waveform-audio"
        default: return "public.mp3"
        }
    }
}
