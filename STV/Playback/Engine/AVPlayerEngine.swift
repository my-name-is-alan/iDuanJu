import AVFoundation
import AVKit
import MediaPlayer
import UIKit

/// The system player: every source shape (incl. the resource-loader AES path and CENC files), the
/// fallback when AetherEngine cannot open an episode.
@MainActor final class AVPlayerEngine: PlayerEngine {
    let kind = PlayerEngineKind.avplayer
    var onFailed: ((Error?) -> Void)?
    var onEnded: (() -> Void)?
    var onPlaybackRequested: ((Bool) -> Void)?
    private let transport = PlayerTransportObserver()

    final class Surface: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    let player: AVPlayer = {
        let p = AVPlayer()
        p.audiovisualBackgroundPlaybackPolicy = .continuesIfPossible
        return p
    }()
    private let view = Surface()
    var surface: UIView { view }
    private var statusObservation: NSKeyValueObservation?
    private var readyObservation: NSKeyValueObservation?
    private var readyContinuation: CheckedContinuation<Void, Never>?
    private var endObserver: NSObjectProtocol?
    private var generation = 0
    private var audioGroup: AVMediaSelectionGroup?
    private var legibleGroup: AVMediaSelectionGroup?
    private(set) var audioTracks: [MediaTrack] = []
    private(set) var subtitleTracks: [MediaTrack] = []
    private(set) var selectedAudio: Int?
    private(set) var selectedSubtitle: Int?

    init() {
        view.backgroundColor = .black
        view.isUserInteractionEnabled = false
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspect
        transport.bind(player)
        transport.onRequest = { [weak self] in self?.onPlaybackRequested?($0) }
    }

    func supports(_ src: PlaybackCoordinator.Source) -> Bool { true }

    func load(_ src: PlaybackCoordinator.Source, stream: HGStream?, start: Double?, title: String) async throws {
        generation += 1
        let gen = generation
        transport.acceptingRequests = false
        transport.expectPaused(true)
        statusObservation = nil
        cancelReadyWait()
        guard let item = PlaybackCoordinator.makeItem(src, stream: stream) else { throw URLError(.badURL) }
        // Only bypass buffering prediction when starting fresh at 0; resuming at `start > 0` lets AVPlayer
        // prime both audio and video buffers at the seek point so video never races ahead without sound.
        awaitingFirstPlay = (start ?? 0) <= 0
        player.volume = 1.0
        item.nowPlayingInfo = [MPMediaItemPropertyTitle: title]
        audioGroup = nil; legibleGroup = nil; audioTracks = []; subtitleTracks = []; selectedAudio = nil; selectedSubtitle = nil
        player.replaceCurrentItem(with: item)
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self, self.generation == gen, item.status == .failed else { return }
                #if DEBUG
                let ns = item.error as NSError?
                let under = (ns?.userInfo[NSUnderlyingErrorKey] as? NSError).map { "\($0.domain) \($0.code)" } ?? "-"
                NSLog("%@", "[STV] avplayer item failed \(ns?.domain ?? "-") \(ns?.code ?? 0) under=\(under)")
                #endif
                self.onFailed?(item.error)
            }
        }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.generation == gen else { return }
                self.onEnded?()
            }
        }
        if let start, start > 0 {
            await waitForReady(item, gen: gen, timeout: 4.0)
            guard generation == gen else { throw CancellationError() }
            if item.status == .failed { throw item.error ?? URLError(.cannotOpenFile) }
            let tol = CMTime(seconds: 0.25, preferredTimescale: 600)
            _ = await player.seek(to: CMTime(seconds: start, preferredTimescale: 600), toleranceBefore: tol, toleranceAfter: tol)
        }
        guard generation == gen else { throw CancellationError() }
        if item.status == .failed { throw item.error ?? URLError(.cannotOpenFile) }
        transport.acceptingRequests = true
        Task { [weak self] in await self?.refreshTracks(item) }
    }

    private func waitForReady(_ item: AVPlayerItem, gen: Int, timeout: Double) async {
        guard item.status == .unknown, generation == gen else { return }
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            readyContinuation = cont
            readyObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
                Task { @MainActor in
                    guard let self, self.generation == gen, item.status != .unknown else { return }
                    self.cancelReadyWait()
                }
            }
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(timeout))
                guard let self, self.generation == gen else { return }
                self.cancelReadyWait()
            }
        }
    }

    private func cancelReadyWait() {
        readyObservation = nil
        if let cont = readyContinuation {
            readyContinuation = nil
            cont.resume()
        }
    }

    /// Set by `load`: the first play of an item starts on the first decodable data instead of waiting until
    /// AVPlayer predicts it can play through (that prediction is what makes slow CDNs sit on a spinner).
    private var awaitingFirstPlay = false

    func play(rate: Double) {
        transport.expectPaused(false)
        player.defaultRate = Float(rate)
        if awaitingFirstPlay {
            awaitingFirstPlay = false
            player.playImmediately(atRate: Float(rate))
        } else {
            player.play()
        }
    }

    func pause() { transport.expectPaused(true); player.pause() }

    func setRate(_ rate: Double) {
        player.defaultRate = Float(rate)
        if player.rate != 0 { player.rate = Float(rate) }
    }

    func seek(to seconds: Double) async {
        player.volume = 1.0
        let tol = CMTime(seconds: 0.15, preferredTimescale: 600)
        _ = await player.seek(to: CMTime(seconds: max(0, seconds), preferredTimescale: 600), toleranceBefore: tol, toleranceAfter: tol)
    }

    func stop() {
        transport.acceptingRequests = false
        generation += 1
        statusObservation = nil
        cancelReadyWait()
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    var currentTime: Double { player.currentTime().seconds.isFinite ? player.currentTime().seconds : 0 }
    var duration: Double {
        guard let d = player.currentItem?.duration.seconds, d.isFinite, d > 0 else { return 0 }
        return d
    }
    var bufferedUntil: Double {
        guard let ranges = player.currentItem?.loadedTimeRanges.map(\.timeRangeValue) else { return 0 }
        let t = currentTime
        let r = ranges.first { $0.start.seconds <= t + 1 && ($0.start + $0.duration).seconds >= t } ?? ranges.first
        return r.map { ($0.start + $0.duration).seconds } ?? 0
    }
    var presentationSize: CGSize { player.currentItem?.presentationSize ?? .zero }
    var isWaiting: Bool { player.timeControlStatus == .waitingToPlayAtSpecifiedRate }
    var isPaused: Bool { player.timeControlStatus == .paused }
    var muted: Bool { get { player.isMuted } set { player.isMuted = newValue } }
    var maxRate: Double { 3.0 }
    var fill: Bool = false {
        didSet { view.playerLayer.videoGravity = fill ? .resizeAspectFill : .resizeAspect }
    }

    // MARK: Tracks (AVMediaSelection)

    /// `displayName` is "Unknown language" (in the system's language) for tracks without one.
    private static func title(_ o: AVMediaSelectionOption, _ fallback: String) -> String {
        let tag = o.extendedLanguageTag ?? o.locale?.identifier ?? ""
        let named = o.commonMetadata.contains { $0.commonKey == .commonKeyTitle }
        return (tag.isEmpty || tag == "und") && !named ? fallback : o.displayName(with: Locale(identifier: "zh-Hans"))
    }

    private func refreshTracks(_ item: AVPlayerItem) async {
        if let g = try? await item.asset.loadMediaSelectionGroup(for: .audible) {
            audioGroup = g
            audioTracks = g.options.enumerated().map { MediaTrack(id: $0.offset, title: Self.title($0.element, "音轨 \($0.offset + 1)"), language: $0.element.extendedLanguageTag) }
            selectedAudio = item.currentMediaSelection.selectedMediaOption(in: g).flatMap { g.options.firstIndex(of: $0) }
        }
        if let g = try? await item.asset.loadMediaSelectionGroup(for: .legible) {
            legibleGroup = g
            subtitleTracks = g.options.enumerated().map { MediaTrack(id: $0.offset, title: Self.title($0.element, "字幕 \($0.offset + 1)"), language: $0.element.extendedLanguageTag) }
            selectedSubtitle = item.currentMediaSelection.selectedMediaOption(in: g).flatMap { g.options.firstIndex(of: $0) }
        }
    }

    func selectAudio(_ id: Int) {
        guard let item = player.currentItem, let g = audioGroup, g.options.indices.contains(id) else { return }
        item.select(g.options[id], in: g); selectedAudio = id
    }

    func selectSubtitle(_ id: Int?) {
        guard let item = player.currentItem, let g = legibleGroup else { return }
        if let id, g.options.indices.contains(id) { item.select(g.options[id], in: g); selectedSubtitle = id }
        else { item.select(nil, in: g); selectedSubtitle = nil }
    }

    var subtitleText: String? { nil }   // AVPlayer renders legible output itself

    func tech(stream: HGStream?) async -> PlayerTech {
        guard let item = player.currentItem else { return PlayerTech() }
        var t = await PlayerTech.read(item, stream: stream)
        t.engine = kind.short
        if let item = player.currentItem { await refreshTracks(item) }
        return t
    }

    var avPlayer: AVPlayer? { player }
    var pipIdentity: ObjectIdentifier? { ObjectIdentifier(view.playerLayer) }
    func makePiPController() -> AVPictureInPictureController? { AVPictureInPictureController(playerLayer: view.playerLayer) }
    func setPiPActive(_ active: Bool) {}
    var routeLabel: String { "AVPlayer" }
}
