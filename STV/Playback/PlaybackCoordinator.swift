import AVFoundation
import AVKit
import MediaPlayer
import Combine
import UIKit

/// Drives every player in the app (short drama, long video, TVBox 点播 / 直播, video books): one playback core at a time
/// (picked per media, the other one as fallback: see `PlayerEngineSettings.order`), per-episode prepared sources,
/// automatic failover across cores / streams / lines, neighbour preloading, resume and local progress persistence.
@MainActor
final class PlaybackCoordinator: NSObject, @preconcurrency AVPictureInPictureControllerDelegate, ObservableObject {
    /// `.remote`: upstream MP4 played by AVFoundation directly (proxy fallback).
    enum Source { case file(URL), stream(HLSPlayback, String), direct(URL), remote(URL, [String: String]) }

    private static func mp4AssetOptions(headers: [String: String] = [:]) -> [String: Any]? {
        var o: [String: Any] = [:]
        if #available(iOS 17.0, *) { o[AVURLAssetOverrideMIMETypeKey] = "video/mp4" }
        if !headers.isEmpty { o["AVURLAssetHTTPHeaderFieldsKey"] = headers }
        return o.isEmpty ? nil : o
    }

    /// AVPlayerItem for a source. Progressive MP4s get an explicit out-of-band MIME type so AVFoundation
    /// never has to guess the container from an extensionless / query-string URL.
    static func makeItem(_ src: Source, stream: HGStream? = nil) -> AVPlayerItem? {
        switch src {
        case .file(let u): return AVPlayerItem(url: u)
        case .direct(let u):
            let mp4 = u.path.hasSuffix(".mp4") || u.path.hasPrefix("/tg/")
            return AVPlayerItem(asset: AVURLAsset(url: u, options: mp4 ? mp4AssetOptions() : nil))
        case .remote(let u, let headers):
            var o: [String: Any] = headers.isEmpty ? [:] : ["AVURLAssetHTTPHeaderFieldsKey": headers]
            // The MIME override must match the media: forcing "video/mp4" on a playlist made every HLS address fail here.
            let container = MediaContainer.of(u, stream: stream)
            if #available(iOS 17.0, *), let mime = container.mime, container != .hls || MediaContainer.from(url: u) != .hls {
                o[AVURLAssetOverrideMIMETypeKey] = mime
            }
            return AVPlayerItem(asset: AVURLAsset(url: u, options: o.isEmpty ? nil : o))
        case .stream(let loader, let url):
            return loader.makeAsset(url: url).map { AVPlayerItem(asset: $0) }
        }
    }

    /// A loopback-proxied item that failed gets one retry straight from the CDN (fresh resolve → fresh token).
    static func canBypassProxy(_ src: Source?, stream: HGStream?) -> Bool {
        #if DEBUG
        if ProcessInfo.processInfo.environment["STV_NO_FALLBACK"] == "1" { return false }
        #endif
        guard case .direct(let u)? = src, u.host == "127.0.0.1", let stream else { return false }
        return !stream.url.hasPrefix("tdfile://") && !stream.url.hasPrefix("file://") && LocalHLSProxy.token(of: stream.url) == nil
    }

    /// User-facing failure text with the AVFoundation code (so a device report pins the cause).
    static func failureText(_ e: Error?) -> String {
        guard let ns = e as NSError? else { return "播放失败，请重试" }
        let under = (ns.userInfo[NSUnderlyingErrorKey] as? NSError).map { " / \($0.code)" } ?? ""
        return "\(plainText(ns) ?? ns.localizedDescription)（\(ns.code)\(under)）"
    }

    /// The system's wording follows the device language and is often just "The operation couldn't be completed".
    private static func plainText(_ ns: NSError) -> String? {
        let source = ns.domain == NSURLErrorDomain ? ns : (ns.userInfo[NSUnderlyingErrorKey] as? NSError) ?? ns
        switch (source.domain, source.code) {
        case (NSURLErrorDomain, NSURLErrorTimedOut): return "连接超时"
        case (NSURLErrorDomain, NSURLErrorNotConnectedToInternet): return "没有网络连接"
        case (NSURLErrorDomain, NSURLErrorCannotFindHost), (NSURLErrorDomain, NSURLErrorDNSLookupFailed): return "找不到片源服务器"
        case (NSURLErrorDomain, NSURLErrorCannotConnectToHost), (NSURLErrorDomain, NSURLErrorNetworkConnectionLost): return "片源服务器连接失败"
        case (NSURLErrorDomain, NSURLErrorFileDoesNotExist), ("CoreMediaErrorDomain", -12938): return "片源已失效"
        case (NSURLErrorDomain, NSURLErrorNoPermissionsToReadFile), ("CoreMediaErrorDomain", -12660): return "片源拒绝访问"
        case (NSURLErrorDomain, NSURLErrorCannotOpenFile), (AVFoundationErrorDomain, AVError.Code.fileFormatNotRecognized.rawValue): return "无法打开这个片源"
        case (NSURLErrorDomain, NSURLErrorSecureConnectionFailed), (NSURLErrorDomain, NSURLErrorServerCertificateUntrusted): return "片源证书无效"
        case (NSURLErrorDomain, _): return "网络请求失败"
        case (AVFoundationErrorDomain, AVError.Code.decodeFailed.rawValue), (AVFoundationErrorDomain, AVError.Code.decoderNotFound.rawValue): return "设备无法解码这个片源"
        case (AVFoundationErrorDomain, _): return "系统播放器无法播放这个片源"
        default: return nil
        }
    }

    static let rateSteps: [Double] = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0]

    let series: HGSeries
    @Published var detail: HGSeriesDetail?
    @Published private(set) var current: Int?
    @Published private(set) var readyIndex: Int?
    @Published private(set) var errors: [Int: String] = [:]
    /// Set when an episode failed on a Cloudflare interstitial (player shows 去验证).
    @Published private(set) var verifyTargets: [Int: VerifyTarget] = [:]
    @Published private(set) var streams: [Int: [HGStream]] = [:]
    @Published private(set) var chosen: [Int: HGStream] = [:]
    /// 4 Hz state: observed only by the views that draw it (see `PlaybackClock`).
    let clock = PlaybackClock()
    let telemetry = PlaybackTelemetry()
    var time: Double { clock.sample.time }
    var duration: Double { clock.sample.duration }
    var bufferedUntil: Double { clock.sample.bufferedUntil }
    /// User intent survives buffering, episode replacement and system interruptions.
    @Published private var wantsPlayback = true
    @Published private var transportPaused = false
    var paused: Bool { !wantsPlayback || interrupted || transportPaused || current.flatMap { errors[$0] } != nil }
    /// Active playback rate (persisted across sessions; 1.0 default). Locked rate persists after speed-gesture ends.
    @Published private(set) var playbackRate: Double = {
        let saved = UserDefaults.standard.double(forKey: "stv.player.rate")
        return saved > 0 ? PlaybackCoordinator.nearestStep(saved) : 1.0
    }()
    /// Non-nil when user locked a non-1x rate via speed gesture or speed panel.
    @Published private(set) var lockedRate: Double? = {
        let saved = UserDefaults.standard.double(forKey: "stv.player.rate")
        let r = saved > 0 ? PlaybackCoordinator.nearestStep(saved) : 1.0
        return r == 1.0 ? nil : r
    }()
    /// 0 = off, -1 = pause after current episode, >0 = countdown minutes.
    @Published private(set) var sleepMode: Int = 0
    private var sleepTimerTask: Task<Void, Never>?
    private var mediaPrefetchTask: Task<Void, Never>?
    private var onSleepTriggered: ((String) -> Void)?
    /// GPU sharpening level (0 = off, 1 = standard, 2 = strong), persisted across sessions.
    @Published var enhanceLevel: Int = UserDefaults.standard.integer(forKey: "stv.player.enhance") {
        didSet {
            UserDefaults.standard.set(enhanceLevel, forKey: "stv.player.enhance")
            engine.setSharpen(enhanceLevel)
        }
    }
    /// SDR-to-HDR inverse tone mapping, persisted across sessions (auto-bypassed for native HDR/DV).
    @Published var sdrToHdr: Bool = UserDefaults.standard.bool(forKey: "stv.player.sdrToHdr") {
        didSet {
            UserDefaults.standard.set(sdrToHdr, forKey: "stv.player.sdrToHdr")
            engine.setSdrToHdr(sdrToHdr && !nativeHDR)
        }
    }
    @Published private(set) var pipPossible = false
    /// Design v3 chrome: rendered video size, audio / subtitle options, auto-advance.
    @Published private(set) var videoSize: CGSize = .zero
    /// Full readout lives in `telemetry.tech`; these two change rarely and drive chrome.
    var tech: PlayerTech? { telemetry.tech }
    @Published private(set) var resolutionLabel = ""
    @Published private(set) var nativeHDR = false
    @Published private(set) var audioOptions: [MediaTrack] = []
    @Published private(set) var subtitleOptions: [MediaTrack] = []
    @Published private(set) var audioSelected: Int?
    @Published private(set) var subtitleSelected: Int?
    /// External subtitles of the current stream (drawn by the host, listed after the embedded tracks).
    private var externalSubtitles = ExternalSubtitles()
    /// Core playing the current episode (the view mounts its surface).
    @Published private(set) var engineKind: PlayerEngineKind = .avplayer
    @Published private(set) var muted = false
    @Published var autoNext: Bool = UserDefaults.standard.object(forKey: "stv.player.autoNext") as? Bool ?? true {
        didSet { UserDefaults.standard.set(autoNext, forKey: "stv.player.autoNext") }
    }
    /// 播完连刷推荐: auto-recommend and continue into the next drama after the finale ends.
    @Published var autoNextDrama: Bool = UserDefaults.standard.object(forKey: "stv.player.autoNextDrama") as? Bool ?? true {
        didSet { UserDefaults.standard.set(autoNextDrama, forKey: "stv.player.autoNextDrama") }
    }
    /// True once the final episode finished playing with `autoNextDrama` enabled (triggers countdown card).
    @Published private(set) var finaleEnded = false
    func clearFinale() { if finaleEnded { finaleEnded = false } }
    let thumbnails = PlayerThumbnailer()
    /// 播放线路 (multi-line titles). `episodes` is the chosen line's list.
    @Published private(set) var lineIndex = 0
    var lines: [HGPlayLine] { detail?.lines ?? [] }
    /// `episodes` are live channels.
    var channelList: Bool { detail?.isLive == true }
    /// 回看: the finished programme being played instead of the live signal.
    @Published private(set) var replay: LiveProgram?
    /// Live signal on screen: no timeline, no speed.
    var isLive: Bool { channelList && replay == nil }
    /// Short non-blocking message (automatic failover); the page shows it as an OSD.
    @Published var notice: String?
    /// 弹幕 address delivered by the source for an episode (TVBox).
    @Published private(set) var danmakuURL: [Int: String] = [:]
    /// LAN policy of the stream on screen, for what comes with it.
    var streamLan: Bool? { current.flatMap { chosen[$0]?.lan } }
    #if DEBUG
    /// Screenshot hook: frozen drag fraction (STV_SCRUB_DEMO), shared by whichever bar instance is on screen.
    @Published var scrubDemo: Double?
    #endif
    private var lastTech = Date.distantPast
    private var prefetchTokens: [Int: String] = [:]
    /// Episodes whose proxied playback failed once → played straight from the CDN.
    private var bypassProxy: Set<Int> = []
    private var lastPrefetchPoll = Date.distantPast

    let av: AVPlayerEngine
    private var mpvInstance: MPVPlayerEngine?
    private var mpvInitAttempted = false
    var mpv: MPVPlayerEngine? {
        if !mpvInitAttempted {
            mpvInitAttempted = true
            do { mpvInstance = try MPVPlayerEngine() } catch {
                #if DEBUG
                NSLog("%@", "[STV] MPVKit unavailable: \(error)")
                #endif
            }
        }
        return mpvInstance
    }
    private(set) var engine: PlayerEngine
    /// What has already been tried for an episode. Reset when the user picks something by hand.
    private struct Attempts {
        var engines: Set<PlayerEngineKind> = []
        var streams: Set<String> = []
        var lines: Set<Int> = []
        var switches = 0
        /// Every failure counts, so a source that plays a frame and dies again cannot loop between cores forever.
        var failures = 0
    }
    static let maxFailures = 6
    private var attempts: [Int: Attempts] = [:]
    static let maxAutoSwitches = 3
    /// Container found by reading the first bytes of an address that does not say what it is.
    private var probed: [String: MediaContainer] = [:]
    private var watchdog: Task<Void, Never>?
    /// True once the current item has shown a frame (or moved its clock: audio-only streams).
    private var started = false
    private var startPosition = 0.0
    /// Position to keep when the same episode is reopened from another 线路.
    private var carryPosition: (index: Int, seconds: Double)?
    /// When the streams of an episode were resolved: addresses of dynamic sources expire.
    private var resolvedAt: [Int: Date] = [:]
    static let addressLifetime: TimeInterval = 180
    /// When the current episode was opened (automatic retries not counted).
    private var openedAt = Date.distantPast
    /// Stream to pick again after a fresh resolve.
    private var wanted: [Int: String] = [:]
    /// Seconds played without a stall since the item opened.
    private var steady = 0.0
    /// Where a file stopped long before its end.
    private var earlyEnd: (index: Int, at: Double)?
    private var nextPrepared = false
    /// A seek in flight: ticks keep showing the target until the core gets there, so the knob never jumps back.
    private var seekHold: (target: Double, until: CFTimeInterval)?
    /// Serialized seek loop so rapid scrubber taps coalesce cleanly instead of firing overlapping core seeks.
    private var seekTask: Task<Void, Never>?
    private var pendingSeekTarget: Double?
    private var pipIdentity: ObjectIdentifier?
    private var timer: Timer?
    private var sharedCommandsRegistered = false
    /// Aspect fill (view toggle) applied to whichever core is mounted.
    var fill = false { didSet { engine.fill = fill } }
    /// Current playhead of the active core (danmaku clock).
    /// The core's clock only moves ~5×/s (Aether publishes at ~10 Hz), so drawing straight from it makes
    /// scrolling comments step. Between core ticks this extrapolates with wall time at the playback rate;
    /// a jump (seek, stall, new episode) re-anchors immediately, small drift is pulled in gently.
    var playhead: Double {
        let raw = engine.currentTime, now = CACurrentMediaTime()
        let rate = paused || readyIndex == nil ? 0 : min(playbackRate, engine.maxRate)
        guard let a = clockAnchor else { clockAnchor = (raw, now, raw); return raw }
        var est = a.time + (now - a.at) * rate
        if raw != a.raw {
            // New core sample: snap if far off, otherwise ease towards it so motion stays continuous.
            if abs(raw - est) > 0.35 { est = raw } else { est += (raw - est) * 0.25 }
            clockAnchor = (est, now, raw)
        } else if rate == 0 || est - raw > 0.6 {
            // Paused, or the core stopped advancing (buffering): hold at the core time.
            est = raw; clockAnchor = (raw, now, raw)
        }
        return est
    }
    private var clockAnchor: (time: Double, at: CFTimeInterval, raw: Double)?
    private var prepared: [Int: Source] = [:]
    private var tasks: [Int: Task<Source, Error>] = [:]
    private var observer: Any?
    private var endObserver: NSObjectProtocol?
    private var teardownCompleted = false
    private var lastSave = Date.distantPast
    private var activation = UUID()
    private var pip: AVPictureInPictureController?
    private var pipObservation: NSKeyValueObservation?
    private var itemObservation: NSKeyValueObservation?
    private var statusObservation: NSKeyValueObservation?
    @Published private(set) var waitingForMedia = false
    private var audioObservers: [NSObjectProtocol] = []
    private var nowPlaying: MPNowPlayingSession?
    @Published private(set) var pipActive = false
    @Published var pipError: String?
    @Published private(set) var restoringPiP = false
    /// Short post-PiP-stop grace window so a late AVKit `rate = 0` notification right after restore cannot pause playback.
    private var pipRestoreGraceUntil: Date = .distantPast
    /// True if `didEnterBackground()` auto-paused before an automatic PiP start callback arrived.
    private var pausedByBackground = false
    private var backgrounded = false
    @Published private var interrupted = false
    private var recoverInterruptedTransport = false
    private var audioSessionActive = false
    /// Active coordinators currently owning the shared `AVAudioSession` so an exiting player's `onDisappear`
    /// teardown cannot deactivate the session out from under a newly mounted player.
    static var activeAudioOwners: Set<ObjectIdentifier> = []
    private var continuePiP = false
    private let suppliedEngine: PlayerEngine?
    private let sourceResolver: ((Int) async throws -> Source)?

    init(series: HGSeries, engine: PlayerEngine? = nil, resolve: ((Int) async throws -> Source)? = nil) {
        self.series = series
        self.suppliedEngine = engine
        self.sourceResolver = resolve
        let a = AVPlayerEngine()
        self.av = a
        self.engine = engine ?? a
        super.init()
    }

    func start() {
        guard observer == nil else { return }
        teardownCompleted = false
        let player = av.player
        let session = MPNowPlayingSession(players: [player])
        session.automaticallyPublishesNowPlayingInfo = true
        nowPlaying = session
        let commands = session.remoteCommandCenter
        commands.playCommand.addTarget(self, action: #selector(remotePlay))
        commands.pauseCommand.addTarget(self, action: #selector(remotePause))
        commands.togglePlayPauseCommand.addTarget(self, action: #selector(remoteToggle))
        #if DEBUG
        // UI tests: an interruption that never ends (STV_FAKE_INTERRUPT=<seconds after start>).
        if let s = ProcessInfo.processInfo.environment["STV_FAKE_INTERRUPT"].flatMap(Double.init) {
            DispatchQueue.main.asyncAfter(deadline: .now() + s) { [weak self] in
                self?.engine.pause()
                NotificationCenter.default.post(name: AVAudioSession.interruptionNotification, object: AVAudioSession.sharedInstance(),
                    userInfo: [AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.began.rawValue])
            }
        }
        #endif
        let center = NotificationCenter.default
        audioObservers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: AVAudioSession.sharedInstance(), queue: .main) { [weak self] note in
            MainActor.assumeIsolated { self?.handleInterruption(note) }
        })
        audioObservers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: AVAudioSession.sharedInstance(), queue: .main) { [weak self] note in
            if note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue {
                MainActor.assumeIsolated { self?.setPaused(true) }
            }
        })
        bindTransportRequests(engine)
        // One clock for both cores (AVPlayer periodic observers do not exist on Aether's software route).
        let t = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
        observer = t
        registerSharedCommands()
    }

    /// Lock-screen / headset commands while Aether plays (AVPlayer's own MPNowPlayingSession covers the fallback).
    private func registerSharedCommands() {
        guard !sharedCommandsRegistered else { return }
        sharedCommandsRegistered = true
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget(self, action: #selector(remotePlay))
        c.pauseCommand.addTarget(self, action: #selector(remotePause))
        c.togglePlayPauseCommand.addTarget(self, action: #selector(remoteToggle))
    }

    private func publishNowPlaying() {
        guard engineKind == .mpv, let i = readyIndex, episodes.indices.contains(i) else { return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: detail?.title ?? series.title,
            MPMediaItemPropertyAlbumTitle: series.source.isLongVideo ? series.source.label : "第\(episodes[i].ep)集",
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: time,
            MPNowPlayingInfoPropertyPlaybackRate: paused ? 0.0 : playbackRate,
        ]
    }

    private func activateAudioSession() {
        Self.activeAudioOwners.insert(ObjectIdentifier(self))
        AppAudioArbiter.shared.videoHooks = AppAudioArbiter.VideoHooks(pauseForMusicTakeover: { [weak self] in
            self?.setPaused(true)
        })
        AppAudioArbiter.shared.claim(.video)
        guard !audioSessionActive else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .moviePlayback, options: [])
            try session.setActive(true)
            audioSessionActive = true
        } catch {
            // Best-effort; playback still works without background continuity.
        }
    }

    func teardown() {
        guard !teardownCompleted else { return }
        teardownCompleted = true
        activation = UUID()
        seekTask?.cancel()
        seekTask = nil
        pendingSeekTarget = nil
        watchdog?.cancel()
        watchdog = nil
        sleepTimerTask?.cancel()
        sleepTimerTask = nil
        mediaPrefetchTask?.cancel()
        mediaPrefetchTask = nil
        sleepMode = 0
        onSleepTriggered = nil
        save(force: true)
        pip?.delegate = nil
        pipObservation = nil
        statusObservation = nil
        itemObservation = nil
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        audioObservers.forEach(NotificationCenter.default.removeObserver)
        audioObservers.removeAll()
        timer?.invalidate(); timer = nil
        observer = nil
        engine.onPlaybackRequested = nil
        suppliedEngine?.stop()
        av.stop()
        mpvInstance?.shutdown()
        mpvInstance = nil
        if let commands = nowPlaying?.remoteCommandCenter {
            [commands.playCommand, commands.pauseCommand, commands.togglePlayPauseCommand].forEach { $0.removeTarget(self) }
        }
        if sharedCommandsRegistered {
            let c = MPRemoteCommandCenter.shared()
            [c.playCommand, c.pauseCommand, c.togglePlayPauseCommand].forEach { $0.removeTarget(self) }
            sharedCommandsRegistered = false
            if AppAudioArbiter.shared.canClearNowPlaying(for: .video) {
                MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            }
        }
        nowPlaying = nil
        if pip?.isPictureInPictureActive == true {
            pip?.stopPictureInPicture()
        }
        pip = nil
        pipIdentity = nil
        pipActive = false
        pipPossible = false
        readyIndex = nil
        current = nil
        finaleEnded = false
        backgrounded = false
        interrupted = false
        recoverInterruptedTransport = false
        continuePiP = false
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll()
        prefetchTokens.values.forEach { LocalHLSProxy.shared.release($0) }
        prefetchTokens.removeAll()
        prepared.removeAll()
        firstFrames.removeAll()
        telemetry.prebuffer = nil
        externalSubtitles.clear()
        LibraryStore.shared.flush()
        let removedOwner = Self.activeAudioOwners.remove(ObjectIdentifier(self)) != nil
        if removedOwner && Self.activeAudioOwners.isEmpty {
            AppAudioArbiter.shared.release(.video)
        }
        if audioSessionActive {
            if Self.activeAudioOwners.isEmpty && AppAudioArbiter.shared.activeOwner == .none {
                try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            }
            audioSessionActive = false
        }
    }

    var episodes: [HGEpisode] {
        guard let d = detail else { return [] }
        return d.lines.indices.contains(lineIndex) ? d.lines[lineIndex].episodes : d.episodes
    }

    /// Index of the line to open first: the one picked on the detail page / last time, else the default.
    func adoptPreferredLine() {
        guard lines.count > 1 else { return }
        let want = PlayLinePrefs.preferred(series)
        if let want, let i = lines.firstIndex(where: { $0.id == want || $0.name == want }) { lineIndex = i; return }
        // `detail.episodes` is the default line.
        if let i = lines.firstIndex(where: { $0.episodes == detail?.episodes }) { lineIndex = i }
    }

    /// 回看: plays a finished programme of the current channel (with a normal timeline).
    func playReplay(_ p: LiveProgram, video: HGResolvedVideo) {
        guard channelList, let i = current, let first = video.streams.first else { notice = "这个频道不支持回看"; return }
        replay = p
        attempts[i] = nil
        reopen(i, streams: video.streams, chosen: first)
        notice = "回看：\(p.title)"
    }

    func backToLive() {
        guard replay != nil, let i = current else { return }
        replay = nil
        attempts[i] = nil
        reopen(i, streams: nil, chosen: nil)
    }

    private func reopen(_ i: Int, streams list: [HGStream]?, chosen stream: HGStream?) {
        if let tok = prefetchTokens.removeValue(forKey: i) { LocalHLSProxy.shared.release(tok) }
        tasks.removeValue(forKey: i)?.cancel()
        prepared[i] = nil
        resolvedAt[i] = nil
        wanted[i] = nil
        streams[i] = list
        chosen[i] = stream
        readyIndex = nil
        wantsPlayback = true
        activate(i)
    }

    /// Switches 线路, keeping the episode (by position, or by title when the lists differ) and the playhead.
    func selectLine(_ index: Int, automatic: Bool = false) {
        guard lines.indices.contains(index), index != lineIndex || automatic else { return }
        let old = episodes
        let i = current ?? 0
        save(force: true)
        let target = lines[index].episodes
        guard !target.isEmpty else { return }
        var next = min(i, target.count - 1)
        if old.indices.contains(i), target.count != old.count,
           let same = target.firstIndex(where: { $0.title == old[i].title }) { next = same }
        if !automatic {
            attempts.removeAll()
            PlayLinePrefs.set(lines[index].id, for: series)
        }
        if started, !isLive { carryPosition = (next, seekHold?.target ?? engine.currentTime) }
        readyIndex = nil
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll()
        prefetchTokens.values.forEach { LocalHLSProxy.shared.release($0) }
        prefetchTokens.removeAll()
        prepared.removeAll(); streams.removeAll(); chosen.removeAll(); errors.removeAll(); verifyTargets.removeAll()
        firstFrames.removeAll(); danmakuURL.removeAll(); resolvedAt.removeAll(); wanted.removeAll()
        bypassProxy.removeAll()
        lineIndex = index
        if next != i { attempts[next] = attempts.removeValue(forKey: i) }
        current = next
        wantsPlayback = true
        activate(next)
    }

    // MARK: PiP

    /// (Re)build the PiP controller when the active core's picture route changes.
    private func refreshPiP() {
        let id = engine.pipIdentity
        guard id != pipIdentity else { return }
        guard !pipActive else { return }                 // never swap under a live PiP window
        pipIdentity = id
        pipObservation = nil
        pip?.delegate = nil
        pip = nil
        pipPossible = false
        guard id != nil, AVPictureInPictureController.isPictureInPictureSupported(),
              let controller = engine.makePiPController() else {
            cancelPiPHandover()
            return
        }
        controller.delegate = self
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        pip = controller
        pipObservation = controller.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] controller, _ in
            Task { @MainActor in
                guard self?.pip === controller else { return }
                self?.pipPossible = controller.isPictureInPicturePossible
                if let self, self.continuePiP, self.wantsPlayback, self.readyIndex != nil,
                   controller.isPictureInPicturePossible, self.backgrounded {
                    self.continuePiP = false
                    controller.startPictureInPicture()
                }
            }
        }
    }

    func togglePiP() {
        guard let pip else { return }
        if pip.isPictureInPictureActive {
            continuePiP = false
            restoringPiP = true
            pipActive = false
            pip.stopPictureInPicture()
        } else if pip.isPictureInPicturePossible {
            restoringPiP = false
            pip.startPictureInPicture()
        }
    }

    private func cancelPiPHandover() {
        guard continuePiP else { return }
        continuePiP = false
        engine.setPiPActive(false)
    }

    func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        guard controller === pip else { return }
        handlePiPWillStart()
    }

    func handlePiPWillStart() {
        restoringPiP = false
        pipRestoreGraceUntil = .distantPast
        pipActive = true
        engine.setPiPActive(true)
        if pausedByBackground {
            pausedByBackground = false
            setPaused(false)
        }
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        guard controller === pip else { return }
        pipActive = false
        restoringPiP = false
        engine.setPiPActive(false)
        continuePiP = false
        if !backgrounded { pipError = error.localizedDescription }
    }

    func pictureInPictureControllerWillStopPictureInPicture(_ controller: AVPictureInPictureController) {
        guard controller === pip else { return }
        if !continuePiP { pipActive = false }
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completion: @escaping (Bool) -> Void) {
        completion(handlePiPRestoreUI(matchesController: controller === pip))
    }

    @discardableResult
    func handlePiPRestoreUI(matchesController: Bool = true) -> Bool {
        restoringPiP = true
        pipActive = false
        return matchesController && observer != nil
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        guard controller === pip else { return }
        handlePiPDidStop()
    }

    func handlePiPDidStop() {
        let wasRestoring = restoringPiP
        pipActive = false
        restoringPiP = false
        clockAnchor = nil
        // A software source or fallback can replace the PiP content during an episode load.
        // That stop belongs to the handover, not to the user pressing the close button.
        if continuePiP {
            // Keep the replacement renderer alive until its own PiP controller starts. Clearing
            // this flag here can tear down the newly loaded video while still backgrounded.
            if readyIndex != nil { refreshPiP() }
        } else {
            engine.setPiPActive(false)
            if !wasRestoring {
                setPaused(true)
            } else {
                pipRestoreGraceUntil = Date().addingTimeInterval(0.35)
                resumeIfRequested()
                let token = activation
                Task { [weak self] in
                    try? await Task.sleep(for: .milliseconds(250))
                    guard let self, self.activation == token, !self.backgrounded else { return }
                    self.resumeIfRequested()
                }
            }
        }
    }

    func didEnterBackground() {
        backgrounded = true
        save(force: true)
        LibraryStore.shared.flush()
        if !pipActive && !continuePiP && pip?.isPictureInPictureActive != true && !paused {
            pausedByBackground = true
            setPaused(true)
        } else {
            pausedByBackground = false
        }
    }

    /// Fires before the foreground frame is composited so the inline player never flashes the PiP placeholder pill.
    func willEnterForeground() {
        pausedByBackground = false
        if pip?.isPictureInPictureActive == true || pipActive {
            continuePiP = false
            restoringPiP = true
            pipActive = false
            pip?.stopPictureInPicture()
        }
    }

    func didBecomeActive() {
        clockAnchor = nil
        pausedByBackground = false
        guard backgrounded else { return }
        backgrounded = false
        audioSessionActive = false
        continuePiP = false
        if !pipActive { engine.setPiPActive(false) }
        if pip?.isPictureInPictureActive == true {
            restoringPiP = true
            pipActive = false
            pip?.stopPictureInPicture()
        }
        // Keep `restoringPiP` intact if `willEnterForeground` / `restoreUserInterface` already started
        // stopping PiP; `pictureInPictureControllerDidStopPictureInPicture` will consume and clear it.
        resumeIfRequested()
        // Engine foreground restoration may itself be asynchronous. Reconcile once afterwards,
        // re-reading intent so a pause pressed in the meantime always wins.
        let token = activation
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            guard let self, self.activation == token, !self.backgrounded else { return }
            if self.restoringPiP, self.pip?.isPictureInPictureActive != true {
                self.restoringPiP = false
            }
            self.resumeIfRequested()
        }
    }

    private func handleInterruption(_ notification: Notification) {
        guard let type = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt else { return }
        if type == AVAudioSession.InterruptionType.began.rawValue {
            audioSessionActive = false
            interrupted = true
            recoverInterruptedTransport = false
            save(force: true)
        } else {
            interrupted = false
            let options = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            if !AVAudioSession.InterruptionOptions(rawValue: options).contains(.shouldResume) { setPaused(true) }
            else if wantsPlayback, readyIndex != nil { play() }
        }
    }

    @objc private func remotePlay(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        setPaused(false)
        return .success
    }

    @objc private func remotePause(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        setPaused(true)
        return .success
    }

    @objc private func remoteToggle(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        togglePause()
        return .success
    }

    // MARK: Rate

    func applyRate(_ rate: Double) {
        let r = Self.nearestStep(rate)
        let clamped = min(r, engine.maxRate)
        playbackRate = clamped
        if wantsPlayback, !interrupted, readyIndex != nil { engine.setRate(clamped) }
    }

    func lockRate(_ rate: Double) {
        let r = Self.nearestStep(rate)
        lockedRate = r == 1.0 ? nil : r
        UserDefaults.standard.set(r, forKey: "stv.player.rate")
        applyRate(r)
    }

    func unlockRate() {
        lockedRate = nil
        UserDefaults.standard.set(1.0, forKey: "stv.player.rate")
        applyRate(1.0)
    }

    func setSleepTimer(_ mode: Int, onTriggered: ((String) -> Void)? = nil) {
        sleepTimerTask?.cancel()
        sleepTimerTask = nil
        sleepMode = mode
        onSleepTriggered = onTriggered
        guard mode > 0 else { return }
        sleepTimerTask = Task { @MainActor [weak self] in
            do { try await Task.sleep(for: .seconds(mode * 60)) } catch { return }
            guard let self, self.sleepMode == mode else { return }
            self.sleepMode = 0
            self.sleepTimerTask = nil
            self.setPaused(true)
            self.onSleepTriggered?("定时关闭已暂停")
        }
    }

    func restoreLockedOrNormal() {
        applyRate(lockedRate ?? 1.0)
    }

    static func nearestStep(_ rate: Double) -> Double {
        rateSteps.min(by: { abs($0 - rate) < abs($1 - rate) }) ?? 1.0
    }

    static func stepIndex(of rate: Double) -> Int {
        let r = nearestStep(rate)
        return rateSteps.firstIndex(of: r) ?? rateSteps.firstIndex(of: 1.0) ?? 0
    }

    static func formatRate(_ rate: Double) -> String {
        if rate == Double(Int(rate)) { return "\(Int(rate))x" }
        let s = String(format: "%.2f", rate)
        return s.replacingOccurrences(of: "0+$", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\\.$", with: "", options: .regularExpression) + "x"
    }

    // MARK: Activation

    /// True when the latest episode change came from automatic next-episode advance (keeps playback chrome hidden).
    private(set) var lastAdvanceWasAuto = false

    func select(_ index: Int?, autoAdvance: Bool = false) {
        guard let index, index != current, episodes.indices.contains(index) else { return }
        if replay != nil {
            replay = nil
            if let i = current { streams[i] = nil; chosen[i] = nil; prepared[i] = nil }
        }
        lastAdvanceWasAuto = autoAdvance
        current = index
        wantsPlayback = true
        transportPaused = false
        activate(index)
    }

    /// Last frame of the episode being left, shown over the (cleared) video surface until the next one is
    /// ready, so switching never flashes black.
    @Published private(set) var heldFrame: (image: UIImage, index: Int)?
    /// Pre-decoded opening video frame (frame 0) of nearby episodes so swiping reveals real video frames
    /// (Douyin-style) instead of a drama poster cover, and bridges the ~30 ms local-file open with zero flash.
    @Published private(set) var firstFrames: [Int: UIImage] = [:]
    /// The feed calls this once the swipe has settled on a ready episode.
    func releaseHeldFrame() { if heldFrame != nil { heldFrame = nil } }

    func activate(_ i: Int) {
        guard i == current, episodes.indices.contains(i), observer != nil else { return }
        save(force: true)
        let token = UUID()
        activation = token
        seekTask?.cancel()
        seekTask = nil
        pendingSeekTarget = nil
        watchdog?.cancel()
        started = false
        steady = 0
        nextPrepared = false
        if (attempts[i]?.failures ?? 0) == 0 { openedAt = Date() }
        seekHold = nil
        if let r = readyIndex, heldFrame == nil, let img = Self.snapshot(engine.surface) { heldFrame = (img, r) }
        readyIndex = nil
        finaleEnded = false
        if pipActive { continuePiP = true }
        // A source/renderer reload can deactivate the shared session independently of this flag.
        // The next play must acquire it again rather than trust the previous episode's value.
        audioSessionActive = false
        engine.pause()
        clock.update { $0 = PlaybackSample(duration: isLive ? 0 : Double(episodes[i].duration)) }
        for k in tasks.keys where k != i && k != i + 1 {
            tasks.removeValue(forKey: k)?.cancel()
        }
        for (k, tok) in prefetchTokens where k != i {
            // Only the playing episode pre-buffers; others drop their proxy route + cache.
            LocalHLSProxy.shared.release(tok); prefetchTokens[k] = nil; prepared[k] = nil
        }
        telemetry.prebuffer = nil
        telemetry.subtitle = nil
        for k in prepared.keys where abs(k - i) > 2 {
            prepared[k] = nil
            streams[k] = nil
            chosen[k] = nil
            resolvedAt[k] = nil
        }
        for k in firstFrames.keys where abs(k - i) > 2 {
            firstFrames[k] = nil
        }
        errors[i] = nil
        verifyTargets[i] = nil
        let lease = backgrounded || pipActive ? PlaybackBackgroundLease() : nil
        Task { [self] in
            defer { lease?.end() }
            guard activation == token else { return }
            do {
                let src = try await prepare(i)
                #if DEBUG
                if let delay = ProcessInfo.processInfo.environment["STV_PLAYER_LOAD_DELAY"].flatMap(Double.init), delay > 0 {
                    try await Task.sleep(for: .seconds(delay))
                }
                if ProcessInfo.processInfo.environment["STV_PLAYER_LOAD_ERROR"] == "1" { throw URLError(.cannotOpenFile) }
                #endif
                guard activation == token else { return }
                // a line of a dynamic source has its time for probing and opening as well
                if series.source.isDynamic { armWatchdog(i, src: src, token: token) }
                let container = await self.container(of: src, stream: chosen[i])
                guard activation == token else { return }
                // Activate the playback audio session BEFORE initializing MPV (`ao=audiounit`) or loading/seeking
                // an AVPlayerItem at `start > 0` (e.g. resuming from Favorites/History) so audio routing is live.
                activateAudioSession()
                let next = chooseEngine(i, src, container)
                switchEngine(to: next)
                audioOptions = []; subtitleOptions = []; audioSelected = nil; subtitleSelected = nil
                externalSubtitles.attach(chosen[i]?.subtitles ?? [], lan: chosen[i]?.lan)
                if case .file(let localURL) = src, firstFrames[i] == nil {
                    Task { [weak self] in
                        if let frame = await Self.extractFirstFrame(from: localURL) {
                            await MainActor.run {
                                guard let self, self.activation == token else { return }
                                self.firstFrames[i] = frame
                            }
                        }
                    }
                }
                thumbnails.attach(Self.thumbnailAsset(src, stream: chosen[i]))
                var start: Double?
                if !channelList, let p = LibraryStore.shared.progress(for: series), p.episodeIndex == i, p.seconds > 3,
                   p.duration <= 0 || p.seconds < p.duration - 5 { start = p.seconds }
                if let c = carryPosition, c.index == i, c.seconds > 1 { start = c.seconds }
                carryPosition = nil
                startPosition = start ?? 0
                // Resuming: buffer from the resume point, not from the start of the file.
                if let start, let tok = prefetchTokens[i], chosen[i]?.kind.isProgressiveMP4 != true {
                    LocalHLSProxy.shared.setPosition(tok, start)
                }
                let title = detail?.title ?? series.title
                next.onFailed = { [weak self, weak next] err in
                    guard let self, let next, self.activation == token, self.engine === next else { return }
                    self.playbackFailed(i, src: src, container: container, engine: next.kind, error: err)
                }
                next.onEnded = { [weak self, weak next] in
                    guard let self, let next, self.activation == token, self.engine === next, self.current == i else { return }
                    if self.reopenAfterEarlyEnd(i, next) { return }
                    if self.replay != nil {
                        self.notice = "回看结束，已返回直播"
                        self.backToLive()
                        return
                    }
                    if self.isLive {
                        // A live source that "ends" dropped its connection: reconnect twice, then treat it as dead.
                        var a = self.attempts[i] ?? Attempts()
                        if a.failures < 2 {
                            a.failures += 1
                            self.attempts[i] = a
                            self.activate(i)
                        } else {
                            self.playbackFailed(i, src: src, container: container, engine: next.kind, error: URLError(.networkConnectionLost))
                        }
                        return
                    }
                    if self.sleepMode == -1 {
                        self.sleepMode = 0
                        self.setPaused(true)
                        self.onSleepTriggered?("本集已播完，定时暂停")
                        return
                    }
                    if self.wantsPlayback, i + 1 < self.episodes.count, self.autoNext {
                        self.select(i + 1, autoAdvance: true)
                    } else if self.wantsPlayback, i + 1 >= self.episodes.count, self.autoNextDrama, !self.series.source.isLongVideo, self.sourceResolver == nil {
                        self.setPaused(true)
                        self.finaleEnded = true
                    } else {
                        self.setPaused(true)
                    }
                }
                // Tell the core what a nameless address carries (the panel keeps comparing the untouched `chosen`).
                var opened = chosen[i]
                if case .remote(let u, _) = src, MediaContainer.of(u, stream: opened) == .unknown, let mime = container.mime { opened?.format = mime }
                if next.kind == .mpv { MPVPlayerEngine.loadTimeout = hasAlternative(i, src: src) ? (series.source.isDynamic ? patience : 10) : 25 }
                do {
                    try await next.load(src, stream: opened, start: start, title: title)
                } catch is CancellationError {
                    return
                } catch {
                    guard activation == token else { return }
                    var silent = false
                    if series.source.isDynamic, let f = error as? MPVPlayerEngine.Failure, case .timeout = f { silent = true }
                    playbackFailed(i, src: src, container: container, engine: next.kind, error: error, stalled: silent)
                    return
                }
                guard activation == token else { return }
                #if DEBUG
                NSLog("%@", "[STV] item prepared engine=\(next.kind.short) src=\(String(describing: src).prefix(120))")
                #endif
                next.muted = muted
                readyIndex = i
                if continuePiP { engine.setPiPActive(true) }
                refreshPiP()
                if wantsPlayback, !interrupted { play() }
                if !series.source.isDynamic { armWatchdog(i, src: src, token: token) }
                if pipActive, engine.pipIdentity == pipIdentity { continuePiP = false }
                scheduleShortDramaPrefetch(after: i, token: token)
                if series.source == .hongguo {
                    // Warm play URLs for the next few episodes (180 s cache) so swipes resolve instantly.
                    let next = episodes.indices.filter { $0 > i && $0 <= i + 3 }.map { episodes[$0].vid }
                    Task { await HongguoClient.shared.prefetch(vids: next) }
                }
                if engineKind == .avplayer { _ = await nowPlaying?.becomeActiveIfPossible() } else { publishNowPlaying() }
            } catch {
                guard activation == token, !(error is CancellationError) else { return }
                // Resolving failed (dead line, parser down): the same episode on another line may be fine.
                if error.verifyTarget == nil, switchLine(after: i) { return }
                errors[i] = (error as NSError).domain == NSURLErrorDomain ? Self.failureText(error) : error.localizedDescription
                verifyTargets[i] = error.verifyTarget
                cancelPiPHandover()
            }
        }
    }

    // MARK: Failover

    /// Says what a source carries; reads its first bytes only when neither the source nor the URL tells.
    private func container(of src: Source, stream: HGStream?) async -> MediaContainer {
        switch src {
        case .file(let u), .direct(let u):
            let c = MediaContainer.from(url: u)
            return c == .unknown ? .mp4 : c
        case .stream(_, let s): return URL(string: s).map { MediaContainer.of($0, stream: stream) } ?? .unknown
        case .remote(let u, let headers):
            let known = MediaContainer.of(u, stream: stream)
            if known != .unknown { return known }
            if let p = probed[u.absoluteString] { return p }
            let found = await MediaContainer.probe(u, headers: headers, lan: stream?.lan)
            probed[u.absoluteString] = found
            return found
        }
    }

    /// Seconds to the first frame. Short for dynamic sources (several lines, often dead ones).
    private var patience: Double { isLive ? 6 : (series.source.isDynamic ? 8 : 15) }

    /// No frame after a while: move on instead of leaving a spinner. A user pause, an interruption or background time never
    /// counts as a stall.
    private func armWatchdog(_ i: Int, src: Source, token: UUID) {
        watchdog?.cancel()
        let patience = patience
        watchdog = Task { @MainActor [weak self] in
            var waited = 0.0
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                guard let self, !Task.isCancelled, self.activation == token, !self.started else { return }
                guard self.wantsPlayback, !self.interrupted, !self.backgrounded else { continue }
                waited += 0.5
                guard waited >= patience else { continue }
                if waited >= 30 || self.hasAlternative(i, src: src) {
                    self.playbackFailed(i, src: src, container: .unknown, engine: self.engine.kind, error: URLError(.timedOut), stalled: true)
                    return
                }
            }
        }
    }

    private func hasAlternative(_ i: Int, src: Source) -> Bool {
        let a = attempts[i] ?? Attempts()
        guard a.switches < Self.maxAutoSwitches else { return false }
        return nextStream(i) != nil || nextLine(i) != nil || !series.source.isDynamic && Self.canBypassProxy(src, stream: chosen[i]) && !bypassProxy.contains(i)
    }

    /// The address is older than this opening (prepared ahead, another 画质) or than a minute: it may simply have expired, and a
    /// fresh one is worth a try before anything else is given up.
    private func refreshAddress(_ i: Int) -> Bool {
        guard series.source.isDynamic, sourceResolver == nil, replay == nil, let at = resolvedAt[i],
              at < openedAt || Date().timeIntervalSince(at) > 60 else { return false }
        dropAddress(i)
        attempts[i]?.engines = []
        activate(i)
        return true
    }

    /// Forgets where an episode was found, but not which of its streams was playing.
    private func dropAddress(_ i: Int) {
        if let tok = prefetchTokens.removeValue(forKey: i) { LocalHLSProxy.shared.release(tok) }
        tasks.removeValue(forKey: i)?.cancel()
        prepared[i] = nil
        resolvedAt[i] = nil
        wanted[i] = chosen[i]?.id ?? wanted[i]
        chosen[i] = nil
    }

    /// MPV reports a connection that died as the end of the file: it is reopened where it stopped instead of moving on. A file
    /// that really is shorter than it claims ends at the same place again.
    private func reopenAfterEarlyEnd(_ i: Int, _ core: PlayerEngine) -> Bool {
        let at = core.currentTime, end = core.duration
        guard core.kind == .mpv, !isLive, Self.endsEarly(at: at, of: end), earlyEnd.map({ $0.index != i || abs($0.at - at) > 5 }) ?? true else { return false }
        earlyEnd = (i, at)
        attempts[i, default: Attempts()].failures += 1
        if attempts[i, default: Attempts()].failures > Self.maxFailures {
            fail(i, error: URLError(.networkConnectionLost), stalled: false)
        } else {
            carryPosition = (i, at)
            activate(i)
        }
        return true
    }

    nonisolated static func endsEarly(at: Double, of end: Double) -> Bool { end > 0 && at.isFinite && at < end - max(10, end * 0.03) }

    private func nextStream(_ i: Int) -> HGStream? {
        guard let all = streams[i], all.count > 1 else { return nil }
        let tried = attempts[i]?.streams ?? []
        return all.first { $0 != chosen[i] && !tried.contains($0.id) }
    }

    private func nextLine(_ i: Int) -> Int? {
        guard lines.count > 1 else { return nil }
        let tried = attempts[i]?.lines ?? []
        return lines.indices.first { $0 != lineIndex && !tried.contains($0) && lines[$0].episodes.indices.contains(i) }
    }

    @discardableResult private func switchLine(after i: Int) -> Bool {
        var a = attempts[i] ?? Attempts()
        guard a.switches < Self.maxAutoSwitches, let next = nextLine(i) else { return false }
        a.switches += 1
        a.lines.insert(lineIndex)
        a.streams = []; a.engines = []
        attempts[i] = a
        notice = "「\(lines[lineIndex].name)」不可用，已切换到「\(lines[next].name)」"
        selectLine(next, automatic: true)
        return true
    }

    /// Pre-downloads and decrypts the next 1–2 short-drama episodes to local disk so episode transitions
    /// open from `.file(...)` immediately without network stall or loading flash.
    private func scheduleShortDramaPrefetch(after i: Int, token: UUID) {
        mediaPrefetchTask?.cancel()
        mediaPrefetchTask = Task { @MainActor [weak self] in
            guard let self else { return }
            if !self.series.source.isLongVideo, self.sourceResolver == nil {
                try? await Task.sleep(for: .milliseconds(350))
                guard !Task.isCancelled, self.activation == token else { return }
                for target in [i + 1, i + 2] where self.episodes.indices.contains(target) {
                    guard !Task.isCancelled, self.activation == token else { return }
                    _ = try? await self.prepare(target)
                    guard !Task.isCancelled, self.activation == token,
                          let stream = self.chosen[target],
                          stream.kind == .mp4Cenc || (stream.kind == .mp4Clear && !stream.url.hasPrefix("tdfile://") && !stream.url.hasPrefix("file://")) else {
                        continue
                    }
                    let vid = self.episodes[target].vid
                    if let local = try? await EpisodePlaybackLoader.playableFile(vid: vid, stream: stream) {
                        guard !Task.isCancelled, self.activation == token else { return }
                        if let oldTok = self.prefetchTokens.removeValue(forKey: target) {
                            LocalHLSProxy.shared.release(oldTok)
                        }
                        self.prepared[target] = .file(local)
                        if self.firstFrames[target] == nil, let frame = await Self.extractFirstFrame(from: local) {
                            guard !Task.isCancelled, self.activation == token else { return }
                            self.firstFrames[target] = frame
                        }
                    }
                }
            } else if !self.series.source.isTVBox, self.episodes.indices.contains(i + 1) {
                // TVBox waits until the end is near (`tick`): resolving may need a hidden browser
                _ = try? await self.prepare(i + 1)
            }
        }
    }

    private nonisolated static func extractFirstFrame(from url: URL) async -> UIImage? {
        let asset = AVURLAsset(url: url)
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        gen.requestedTimeToleranceBefore = .zero
        gen.requestedTimeToleranceAfter = CMTime(seconds: 0.5, preferredTimescale: 600)
        guard let cg = try? await gen.image(at: .zero).image else { return nil }
        return UIImage(cgImage: cg)
    }

    /// First core of the preferred order that takes the source and has not failed on it (see `PlayerEngineSettings.order`).
    private func chooseEngine(_ i: Int, _ src: Source, _ container: MediaContainer) -> PlayerEngine {
        if let suppliedEngine { return suppliedEngine }
        let failed = attempts[i]?.engines ?? []
        for kind in PlayerEngineSettings.order(container) where !failed.contains(kind) {
            if kind == .avplayer { return av }
            if let m = mpv, m.supports(src) { return m }
        }
        return av
    }

    private func canTryOtherEngine(_ i: Int, _ src: Source, _ container: MediaContainer) -> Bool {
        guard suppliedEngine == nil else { return false }
        let failed = attempts[i]?.engines ?? []
        return PlayerEngineSettings.order(container).contains { kind in
            guard !failed.contains(kind) else { return false }
            return kind == .avplayer || (mpv?.supports(src) ?? false)
        }
    }

    private func switchEngine(to next: PlayerEngine) {
        if next !== engine {
            engine.onFailed = nil; engine.onEnded = nil
            engine.onPlaybackRequested = nil
            engine.stop()
            engine = next
        }
        engine.fill = fill
        engine.setSharpen(enhanceLevel)
        engine.setSdrToHdr(sdrToHdr && !nativeHDR)
        bindTransportRequests(engine)
        if engineKind != next.kind { engineKind = next.kind }
    }

    /// Fallback ladder: the other core on the same source → a fresh address → straight from the CDN (loopback-proxied source,
    /// fresh resolve) → next stream (画质 / 播放源) → next 线路 → error with the code. A stall skips the core swap: a source
    /// that sends nothing to one core sends nothing to the other.
    private func playbackFailed(_ i: Int, src: Source, container: MediaContainer, engine kind: PlayerEngineKind, error: Error?, stalled: Bool = false) {
        readyIndex = nil
        watchdog?.cancel()
        var a = attempts[i] ?? Attempts()
        a.engines.insert(kind)
        a.failures += 1
        attempts[i] = a
        #if DEBUG
        NSLog("%@", "[STV] playback failed engine=\(kind.short) stalled=\(stalled) \(error?.localizedDescription ?? "-")")
        #endif
        guard a.failures <= Self.maxFailures else { return fail(i, error: error, stalled: stalled) }
        if !stalled, !Self.sourceIsDead(error), canTryOtherEngine(i, src, container) {
            activate(i)
            return
        }
        if !stalled, refreshAddress(i) { return }
        // a dynamic source that is silent or gone is so without the proxy as well
        let hopeless = series.source.isDynamic && (stalled || Self.sourceIsDead(error) && hasAlternative(i, src: src))
        if !hopeless, Self.canBypassProxy(src, stream: chosen[i]), !bypassProxy.contains(i) {
            bypassProxy.insert(i)
            dropAddress(i)
            attempts[i]?.engines = []
            activate(i)
            return
        }
        if a.switches < Self.maxAutoSwitches, let next = nextStream(i) {
            a.switches += 1
            a.engines = []
            if let c = chosen[i] { a.streams.insert(c.id) }
            attempts[i] = a
            notice = "播放失败，已切换到「\(next.quality ?? "其他画质")」"
            choose(next, for: i, automatic: true)
            return
        }
        if switchLine(after: i) { return }
        fail(i, error: error, stalled: stalled)
    }

    private func fail(_ i: Int, error: Error?, stalled: Bool) {
        activation = UUID()   // whatever is still opening does not matter any more
        readyIndex = nil
        if stalled { engine.stop() } else { engine.pause() }
        let at = resolvedAt[i]
        dropAddress(i)
        // another 画质 may be picked from the list that is still there
        resolvedAt[i] = at
        wanted[i] = nil
        errors[i] = stalled ? "长时间没有画面，请换一个画质或线路" : Self.failureText(error)
        cancelPiPHandover()
    }

    /// The address itself is gone (DNS, refused, 4xx): another core would ask the same server the same question.
    nonisolated static func sourceIsDead(_ e: Error?) -> Bool {
        guard let ns = e as NSError? else { return false }
        let dead: Set<Int> = [NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost, NSURLErrorFileDoesNotExist,
                              NSURLErrorNoPermissionsToReadFile, NSURLErrorBadServerResponse, NSURLErrorTimedOut,
                              -12660 /* HTTP 403 */, -12938 /* HTTP 404 */]
        if ns.domain == NSURLErrorDomain || ns.domain == "CoreMediaErrorDomain", dead.contains(ns.code) { return true }
        return (ns.userInfo[NSUnderlyingErrorKey] as? NSError).map { sourceIsDead($0) } ?? false
    }

    /// 重试 from the error card: start over with everything allowed again.
    func retry(_ i: Int) {
        attempts[i] = nil
        bypassProxy.remove(i)
        prepared[i] = nil
        activate(i)
    }

    /// What the video surface shows right now (Metal / sample-buffer layers included).
    static func snapshot(_ v: UIView) -> UIImage? {
        guard v.bounds.width > 0, v.bounds.height > 0, v.window != nil else { return nil }
        let f = UIGraphicsImageRendererFormat.preferred(); f.opaque = true
        return UIGraphicsImageRenderer(bounds: v.bounds, format: f).image { _ in
            _ = v.drawHierarchy(in: v.bounds, afterScreenUpdates: false)
        }
    }

    /// Scrub thumbnails come from a separate AVURLAsset (any core): files and progressive MP4s only.
    static func thumbnailAsset(_ src: Source, stream: HGStream?) -> AVAsset? {
        switch src {
        case .file(let u): return AVURLAsset(url: u)
        case .direct(let u):
            guard u.path.hasSuffix(".mp4") else { return nil }
            return AVURLAsset(url: u, options: mp4AssetOptions())
        case .remote:
            // Never open a competing second connection to a remote CDN MP4 (breaks single-connection rate-limited CDNs like 91Porn).
            return nil
        case .stream(let loader, let s):
            guard let u = URL(string: s), !MPVPlayerEngine.isPlaylist(u) else { return nil }
            return AVURLAsset(url: u, options: ["AVURLAssetHTTPHeaderFieldsKey": loader.headers])
        }
    }

    private func prepare(_ i: Int) async throws -> Source {
        if series.source.isDynamic, sourceResolver == nil, replay == nil, let at = resolvedAt[i], Date().timeIntervalSince(at) > Self.addressLifetime { dropAddress(i) }
        if let source = prepared[i] {
            if case .file(let url) = source, !FileManager.default.fileExists(atPath: url.path) {
                prepared[i] = nil
            } else { return source }
        }
        if let t = tasks[i] { return try await t.value }
        let ep = episodes[i]
        let series = self.series
        let want = chosen[i], wantedId = wanted[i]
        let preferredId = SitePrefs.preferredStream[series.id]
        let bypass = bypassProxy.contains(i)
        let task = Task<Source, Error> { [weak self] in
            if let resolve = self?.sourceResolver {
                let source = try await resolve(i)
                if case .direct(let url) = source, let token = LocalHLSProxy.token(of: url.absoluteString) {
                    guard !Task.isCancelled, let self else { LocalHLSProxy.shared.release(token); throw CancellationError() }
                    if let old = self.prefetchTokens[i], old != token { LocalHLSProxy.shared.release(old) }
                    self.prefetchTokens[i] = token
                }
                return source
            }
            var stream = want
            if stream == nil {
                let r = try await Catalog.resolve(series, ep)
                if let e = r.err, r.streams.isEmpty { throw ProviderError.upstream(e) }
                guard let best = r.streams.first(where: { $0.id == wantedId }) ?? r.streams.first(where: { $0.id == preferredId }) ?? r.streams.first else { throw ProviderError.upstream("没有可用的视频流") }
                try Task.checkCancellation()
                self?.streams[i] = r.streams
                self?.chosen[i] = best
                self?.resolvedAt[i] = Date()
                if let dm = r.danmaku, !dm.isEmpty { self?.danmakuURL[i] = dm }
                stream = best
            }
            guard let s = stream else { throw ProviderError.upstream("没有可用的视频流") }
            switch s.kind {
            case .mp4Cenc:
                // Already decrypted on disk → play the file. Otherwise stream-decrypt through the proxy; the
                // whole-file download + decrypt is only the fallback (not streamable, or streaming failed once).
                if let f = EpisodePlaybackLoader.cachedFile(vid: ep.vid, stream: s) { return .file(f) }
                if !bypass, let src = await self?.cencStream(s, index: i) { return src }
                return .file(try await EpisodePlaybackLoader.playableFile(vid: ep.vid, stream: s))
            case .mp4Clear where s.url.hasPrefix("tdfile://"):
                // TDLib video: plain progressive file from the loopback proxy, downloaded window by window.
                guard let ref = TDFileRef(s.url) else { throw ProviderError.invalid(s.url) }
                let source = try await TDLibService.shared.streamSource()
                guard let src = await self?.tdStream(ref, source: source, budget: BufferSettings.budget(for: series.source), index: i) else {
                    throw ProviderError.upstream("本地播放代理启动失败")
                }
                return src
            case .mp4Clear where s.url.hasPrefix("file://"):
                // Downloaded (offline) Telegram video.
                guard let u = URL(string: s.url) else { throw ProviderError.upstream("文件地址无效") }
                return .file(u)
            case .mp4Clear where bypass || series.source.pluginID != nil:
                // Plugin progressive MP4s play directly (single connection) so rate-limited CDNs
                // aren't hit by LocalHLSProxy's bytes=0-1 probe + 3-way concurrent prefetcher.
                guard let u = URL(string: s.url) else { throw ProviderError.invalid(s.url) }
                return .remote(u, s.headers)
            case _ where series.source.isTVBox:
                // Any container, any host, often token-bound to one connection: only 点播 HLS goes through the proxy
                // (ad filter, pre-buffer); everything else is opened directly by whichever core handles it.
                try await LanPolicy.check(s.url, lan: s.lan)
                guard let u = URL(string: s.url) ?? TVBoxUrl.url(s.url) else { throw ProviderError.invalid(s.url) }
                if !bypass, series.source.tvboxLiveUID == nil, s.key.isEmpty, MediaContainer.of(u, stream: s) == .hls {
                    if let proxied = await self?.prebuffered(s, budget: BufferSettings.budget(for: series.source), index: i) { return proxied }
                }
                return .remote(u, s.headers)
            case .mp4Clear, .hls, .hlsAES:
                if s.kind == .mp4Clear, !series.source.isLongVideo,
                   let f = EpisodePlaybackLoader.cachedFile(vid: ep.vid, stream: s) {
                    return .file(f)
                }
                let budget = bypass ? 0 : BufferSettings.budget(for: series.source)
                if budget > 0, let proxied = await self?.prebuffered(s, budget: budget, index: i) { return proxied }
                if !bypass, s.kind == .hls || s.kind == .hlsAES {
                    // Route HLS (clear & AES-128) through the loopback HTTP proxy so AVPlayer plays a standard
                    // http://127.0.0.1 playlist (avoiding iOS 16 AVAssetResourceLoaderDelegate + custom-scheme crashes)
                    // and MPVKit can also play encrypted HLS with custom headers.
                    if LocalHLSProxy.token(of: s.url) != nil, let u = URL(string: s.url) { return .direct(u) }
                    let keyData = s.key.isEmpty ? nil : Bytes.fromHex(s.key).map { Data($0) }
                    if let p = LocalHLSProxy.shared.playlistURL(s.url, headers: s.headers, strip: false, budget: 0, hlsKey: keyData),
                       let u = URL(string: p) {
                        if let tok = LocalHLSProxy.token(of: p) {
                            if let old = self?.prefetchTokens[i], old != tok { LocalHLSProxy.shared.release(old) }
                            self?.prefetchTokens[i] = tok
                        }
                        return .direct(u)
                    }
                }
                return .stream(HLSPlayback(headers: s.headers, keyHex: s.key), s.url)
            }
        }
        tasks[i] = task
        defer { if tasks[i] == task { tasks[i] = nil } }
        let src = try await task.value
        guard tasks[i] == task, !task.isCancelled else { throw CancellationError() }
        prepared[i] = src
        return src
    }

    /// Long-video sources go through the loopback proxy so segments / MP4 ranges come from the byte
    /// pre-buffer. AES-128 streams with an out-of-band key keep the resource-loader path.
    private func prebuffered(_ s: HGStream, budget: Int, index i: Int) -> Source? {
        if let tok = LocalHLSProxy.token(of: s.url) {           // SupJav: already proxied
            LocalHLSProxy.shared.enablePrefetch(tok, budget: budget)
            if let old = prefetchTokens[i], old != tok { LocalHLSProxy.shared.release(old) }
            prefetchTokens[i] = tok
            return nil
        }
        let proxied: String?
        let keyData = s.key.isEmpty ? nil : Bytes.fromHex(s.key).map { Data($0) }
        switch s.kind {
        case _ where series.source.isTVBox: proxied = LocalHLSProxy.shared.playlistURL(s.url, headers: s.headers, strip: false, budget: budget, lan: s.lan)
        case .hls: proxied = LocalHLSProxy.shared.playlistURL(s.url, headers: s.headers, strip: false, budget: budget)
        case .hlsAES: proxied = LocalHLSProxy.shared.playlistURL(s.url, headers: s.headers, strip: false, budget: budget, hlsKey: keyData)
        case .mp4Clear: proxied = LocalHLSProxy.shared.mp4URL(s.url, headers: s.headers, budget: budget)
        default: proxied = nil
        }
        guard let proxied, let u = URL(string: proxied), let tok = LocalHLSProxy.token(of: proxied) else { return nil }
        if let old = prefetchTokens[i], old != tok { LocalHLSProxy.shared.release(old) }
        prefetchTokens[i] = tok
        return .direct(u)
    }

    /// 红果 CENC MP4 served decrypted by the loopback proxy while it downloads (nil: not streamable).
    private func cencStream(_ s: HGStream, index i: Int) async -> Source? {
        guard let proxied = LocalHLSProxy.shared.cencMP4URL(s.url, headers: s.headers, key: s.key, budget: Self.cencBudget),
              let u = URL(string: proxied), let tok = LocalHLSProxy.token(of: proxied) else { return nil }
        do {
            try await LocalHLSProxy.shared.prepareCENC(proxied)
        } catch {
            #if DEBUG
            NSLog("%@", "[STV] cenc stream unavailable → full download: \(error.localizedDescription)")
            #endif
            LocalHLSProxy.shared.release(tok)
            return nil
        }
        if let old = prefetchTokens[i], old != tok { LocalHLSProxy.shared.release(old) }
        prefetchTokens[i] = tok
        return .direct(u)
    }

    /// Read-ahead for a streamed 红果 episode (a 1080p episode is roughly 5–40 MB).
    static let cencBudget = 64 * 1_048_576

    private func tdStream(_ ref: TDFileRef, source: TDFileStreamSource, budget: Int, index i: Int) -> Source? {
        guard let proxied = LocalHLSProxy.shared.tdURL(ref, budget: budget, source: source),
              let u = URL(string: proxied), let tok = LocalHLSProxy.token(of: proxied) else { return nil }
        if let old = prefetchTokens[i], old != tok { LocalHLSProxy.shared.release(old) }
        prefetchTokens[i] = tok
        return .direct(u)
    }

    func choose(_ s: HGStream, for i: Int, automatic: Bool = false) {
        guard chosen[i] != s else { return }
        if !automatic { attempts[i] = nil }
        if let tok = prefetchTokens.removeValue(forKey: i) { LocalHLSProxy.shared.release(tok) }
        tasks.removeValue(forKey: i)?.cancel()
        chosen[i] = s; prepared[i] = nil
        if current == i { activate(i) }
    }

    // MARK: Controls

    func togglePause() {
        // Interrupted: playback is already stopped whatever the button shows, so this tap means ▶.
        let failed = current.flatMap { errors[$0] } != nil
        setPaused(failed || interrupted || (readyIndex != nil && engine.isPaused && !engine.isWaiting) ? false : wantsPlayback)
    }

    private func setPaused(_ value: Bool) {
        let wasInterrupted = interrupted
        if !value {
            interrupted = false
            if wasInterrupted { recoverInterruptedTransport = true; audioSessionActive = false }
        } else { recoverInterruptedTransport = false }
        wantsPlayback = !value
        transportPaused = value
        if value {
            pipRestoreGraceUntil = .distantPast
            continuePiP = false
        }
        if value {
            engine.pause()
            save(force: true)
        } else if readyIndex != nil {
            // An explicit play wins over a stale interruption: iOS does not promise an `.ended` (another app's
            // audio, PiP interrupted by another app), and waiting for it left ▶ dead until the player was reopened.
            if duration > 0, engine.currentTime >= duration - 0.15, engine.kind == .avplayer { seek(fraction: 0) }
            play()
        } else if let current, errors[current] != nil {
            retry(current)
        }
        publishNowPlaying()
    }

    private func play() {
        activateAudioSession()
        let rate = min(playbackRate, engine.maxRate)
        let recover = recoverInterruptedTransport
        recoverInterruptedTransport = false
        engine.play(rate: rate)
    }

    private func bindTransportRequests(_ target: PlayerEngine) {
        target.onPlaybackRequested = { [weak self, weak target] playing in
            guard let self, let target, self.engine === target, !self.restoringPiP else { return }
            if !playing, Date() < self.pipRestoreGraceUntil {
                self.resumeIfRequested()
                return
            }
            guard self.readyIndex != nil else {
                // nothing of the current episode is open yet: what the core holds is the one before
                if playing { target.pause() }
                return
            }
            self.setPaused(!playing)
        }
    }

    private func resumeIfRequested() {
        guard wantsPlayback, !interrupted, readyIndex != nil, engine.isPaused else { return }
        play()
    }

    func setMuted(_ on: Bool) {
        muted = on
        engine.muted = on
    }

    /// ±10 s buttons / double tap.
    func seek(by seconds: Double) {
        guard duration > 0 else { return }
        let t = max(0, min(duration - 0.5, engine.currentTime + seconds))
        seek(fraction: t / duration)
    }

    private func refreshMediaOptions() {
        let a = engine.audioTracks, st = engine.subtitleTracks
        if a != audioOptions { audioOptions = a }
        let all = st + externalSubtitles.tracks
        if all != subtitleOptions { subtitleOptions = all }
        if engine.selectedAudio != audioSelected { audioSelected = engine.selectedAudio }
        let sel = externalSubtitles.selected ?? engine.selectedSubtitle
        if sel != subtitleSelected { subtitleSelected = sel }
    }

    /// Track ids are `MediaTrack.id`; nil subtitle = off.
    func selectAudio(_ id: Int) { engine.selectAudio(id); audioSelected = id }
    func selectSubtitle(_ id: Int?) {
        if let id, externalSubtitles.owns(id) {
            engine.selectSubtitle(nil)
            externalSubtitles.select(id) { [weak self] message in self?.notice = message }
        } else {
            externalSubtitles.select(nil)
            engine.selectSubtitle(id)
            telemetry.subtitle = nil
        }
        subtitleSelected = id
    }

    func seek(fraction: Double) {
        guard duration > 0, !isLive else { return }
        let target = max(0, min(duration, duration * fraction))
        clock.update { $0.time = target }
        seekHold = (target, CACurrentMediaTime() + 2.5)
        enqueueSeek(to: target)
        updatePrefetch(at: target, force: true)
    }

    /// Coalesces rapid scrubber taps so at most one engine seek is in flight at a time, preventing
    /// CoreMedia / AudioUnit audio-renderer drops caused by overlapping cancelled seeks.
    private func enqueueSeek(to target: Double) {
        pendingSeekTarget = target
        guard seekTask == nil else { return }
        let token = activation
        seekTask = Task { @MainActor [weak self] in
            defer { self?.seekTask = nil }
            while let self, let nextTarget = self.pendingSeekTarget {
                self.pendingSeekTarget = nil
                guard self.activation == token, self.readyIndex != nil else { return }
                let eng = self.engine
                await eng.seek(to: nextTarget)
                guard !Task.isCancelled, self.activation == token, self.engine === eng else { return }
                if self.pendingSeekTarget == nil, self.wantsPlayback, !self.interrupted {
                    self.activateAudioSession()
                    eng.muted = self.muted
                    if eng.isPaused && !eng.isWaiting {
                        self.play()
                    }
                }
            }
        }
    }

    /// Follow the playhead (seek restarts prefetch from the new position) and refresh the HUD figure.
    private func updatePrefetch(at t: Double, force: Bool = false) {
        guard let i = readyIndex, let tok = prefetchTokens[i] else { return }
        guard force || Date().timeIntervalSince(lastPrefetchPoll) > 1 else { return }
        lastPrefetchPoll = Date()
        if let td = LocalHLSProxy.shared.tdSession(tok) {
            // TDLib stream: seek moves the download window; the buffer layer = bytes TDLib has from here on.
            let d = duration, frac = d > 0 ? t / d : 0
            Task { [weak self] in
                if force { await td.seek(fraction: frac) }
                guard let b = await td.buffered(from: frac), d > 0 else { return }
                await MainActor.run {
                    guard let self, self.readyIndex == i, self.prefetchTokens[i] == tok else { return }
                    self.setPrebuffer(bytes: b.bytes, until: min(d, b.until * d), lowStorage: false)
                }
            }
            return
        }
        // MP4 units are chunk indices; map playback time to a chunk by fraction of the duration.
        let pos: Double
        if chosen[i]?.kind.isProgressiveMP4 == true, duration > 0, let st = LocalHLSProxy.shared.status(tok), st.total >= 0 {
            pos = (t / duration) * Double(LocalHLSProxy.shared.mp4Chunks(tok))
        } else { pos = t }
        LocalHLSProxy.shared.setPosition(tok, pos)
        if let st = LocalHLSProxy.shared.status(tok) {
            let until = chosen[i]?.kind.isProgressiveMP4 == true
                ? min(duration, st.until / Double(max(1, LocalHLSProxy.shared.mp4Chunks(tok))) * duration) : st.until
            setPrebuffer(bytes: st.bytes, until: until, lowStorage: st.lowStorage)
        }
    }

    private func setPrebuffer(bytes: Int, until: Double, lowStorage: Bool) {
        clock.update { $0.prebufferedUntil = until }
        // The HUD shows whole megabytes: do not publish every few kilobytes.
        let next = PlaybackTelemetry.Prebuffer(bytes: (bytes >> 20) << 20, until: until.rounded(), lowStorage: lowStorage)
        if next != telemetry.prebuffer { telemetry.prebuffer = next }
    }

    private func tick() {
        guard readyIndex != nil else { return }
        let t = engine.currentTime
        guard t.isFinite else { return }
        let d = engine.duration
        let buffered = engine.bufferedUntil
        var shown = t
        if let hold = seekHold {
            if abs(t - hold.target) < 1.2 || CACurrentMediaTime() > hold.until { seekHold = nil } else { shown = hold.target }
        }
        clock.update { s in
            s.time = shown
            s.loadedUntil = buffered
            if d.isFinite, d > 0, !isLive, abs(d - s.duration) > 0.01 { s.duration = d }
        }
        let waiting = engine.isWaiting
        let stopped = engine.isPaused && !waiting
        if stopped != transportPaused { transportPaused = stopped }
        if waiting != waitingForMedia { waitingForMedia = waiting }
        let s = engine.presentationSize
        if s.width > 0, s != videoSize { videoSize = s }
        if !started, (s.width > 0 && !waiting) || t > startPosition + 0.5 {
            started = true
            watchdog?.cancel()
            if let i = readyIndex { attempts[i]?.engines = [] }
        }
        // a minute without trouble earns a fresh failure budget
        if started, !stopped, !waiting { steady += 0.25 }
        if steady >= 60, let i = readyIndex, attempts[i] != nil { attempts[i] = nil }
        if started, !nextPrepared, autoNext, series.source.tvboxUID != nil, d.isFinite, d > 0, d - t <= 60, let i = readyIndex, episodes.indices.contains(i + 1) {
            nextPrepared = true
            Task { [weak self] in _ = try? await self?.prepare(i + 1) }
        }
        let sub = engine.subtitleText ?? externalSubtitles.text(at: shown)
        if sub != telemetry.subtitle { telemetry.subtitle = sub }
        if Date().timeIntervalSince(lastTech) > 2, let i = readyIndex {
            lastTech = Date()
            let stream = chosen[i]
            let eng = engine
            Task { [weak self] in
                let t = await eng.tech(stream: stream)
                guard let self, self.engine === eng else { return }
                if t != self.telemetry.tech { self.telemetry.tech = t }
                if t.resolution != self.resolutionLabel { self.resolutionLabel = t.resolution }
                if t.hdr != self.nativeHDR {
                    self.nativeHDR = t.hdr
                    self.engine.setSdrToHdr(self.sdrToHdr && !t.hdr)
                }
                self.refreshMediaOptions()
                self.refreshPiP()
                self.publishNowPlaying()
            }
        }
        updatePrefetch(at: t)
        save(force: false)
    }

    private func save(force: Bool) {
        guard sourceResolver == nil, !channelList else { return }
        guard let i = readyIndex, episodes.indices.contains(i) else { return }
        guard force || Date().timeIntervalSince(lastSave) > 4 else { return }
        // A seek in flight counts from its target; before the first frame the core still reports the old item's clock.
        guard started || !force else { return }
        let seconds = seekHold?.target ?? (force ? engine.currentTime : time)
        guard seconds.isFinite, seconds > 0 else { return }
        lastSave = Date()
        // Deep links / season switches may carry a bare series; fill title & cover from the loaded detail.
        var rec = series
        if let d = detail {
            if rec.title.isEmpty { rec.title = d.title }
            if rec.cover.isEmpty { rec.cover = d.cover }
            if rec.episodeCount == 0 { rec.episodeCount = max(d.totalEps, d.episodes.count) }
        }
        LibraryStore.shared.record(rec, index: i, ep: episodes[i].ep, seconds: seconds, duration: duration)
        if series.source == .hongguo {
            let posMs = Int64((seconds * 1000).rounded())
            HongguoWebAccount.shared.reportProgressAsync(
                seriesId: series.seriesId,
                vid: episodes[i].vid,
                episodeNumber: episodes[i].ep,
                positionMs: posMs,
                accumulatedMs: max(1500, posMs),
                force: force
            )
        }
    }
}
