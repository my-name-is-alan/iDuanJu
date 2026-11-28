import Foundation
import AVFoundation
import MediaPlayer
import UIKit

/// Policy controlling whether background music automatically resumes when exiting a video / audiobook / TTS session.
enum MusicResumePolicy: String, Codable, CaseIterable, Identifiable {
    case smart
    case always
    case manual

    var id: String { rawValue }
    var label: String {
        switch self {
        case .smart: "智能恢复"
        case .always: "总是恢复"
        case .manual: "仅手动恢复"
        }
    }
    var detail: String {
        switch self {
        case .smart: "短看视频（≤ 3 分钟）退出自动淡入续播；长看保持悬浮条暂停"
        case .always: "退出视频或听书后一律淡入续播（拔出耳机除外）"
        case .manual: "看视频中断后保持暂停，仅手动点击续播"
        }
    }
}

/// Global single-app audio arbiter coordinating Music, Video (incl. PiP), Novel TTS, and Novel Audiobook.
/// Ensures:
/// 1. Last active user-initiated audio producer wins cleanly (no overlapping voices/music).
/// 2. Music fades out gracefully (180ms) on video/TTS interruption and resumes per `MusicResumePolicy`
///    (default `.smart`: <= 180s auto fade-in, > 180s stays paused on floating mini bar).
/// 3. Hardware headphone unplug (`oldDeviceUnavailable`) ALWAYS clears auto-resume (anti-speaker-blast guard).
/// 4. Lock screen / Control Center / Dynamic Island (`MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`)
///    ownership never clobbers an active sibling owner when another surface tears down.
@MainActor
final class AppAudioArbiter: ObservableObject {
    enum Owner: String, Equatable {
        case none
        case music
        case video
        case novelTTS
        case novelAudio
    }

    struct MusicHooks {
        var isPlaying: () -> Bool
        var fadeOutAndPause: () -> Void
        var fadeInAndResume: () -> Void
        var refreshLockScreen: () -> Void
    }

    struct VideoHooks {
        var pauseForMusicTakeover: () -> Void
    }

    static let shared = AppAudioArbiter()
    static let smartResumeThresholdSeconds: TimeInterval = 180
    private static let policyKey = "stv.music.resumePolicy"

    @Published var resumePolicy: MusicResumePolicy {
        didSet { UserDefaults.standard.set(resumePolicy.rawValue, forKey: Self.policyKey) }
    }
    @Published private(set) var activeOwner: Owner = .none {
        didSet { Self.setOwnerSnapshot(activeOwner) }
    }
    @Published private(set) var musicInterruptedAt: Date?
    @Published private(set) var musicInterruptedBy: Owner?

    private static let ownerLock = NSLock()
    private nonisolated(unsafe) static var _ownerSnapshot: Owner = .none

    nonisolated static var currentOwnerSnapshot: Owner {
        ownerLock.lock()
        defer { ownerLock.unlock() }
        return _ownerSnapshot
    }

    private nonisolated static func setOwnerSnapshot(_ owner: Owner) {
        ownerLock.lock()
        _ownerSnapshot = owner
        ownerLock.unlock()
    }

    var musicHooks: MusicHooks?
    var videoHooks: VideoHooks?

    private var headphoneUnpluggedDuringInterrupt = false
    private var routeObserver: NSObjectProtocol?

    private init() {
        let saved = UserDefaults.standard.string(forKey: Self.policyKey)
        resumePolicy = saved.flatMap(MusicResumePolicy.init(rawValue:)) ?? .smart
        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            guard let reasonRaw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  reasonRaw == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue else { return }
            Task { @MainActor [weak self] in
                self?.handleHeadphoneUnplugged()
            }
        }
    }

    deinit {
        if let routeObserver { NotificationCenter.default.removeObserver(routeObserver) }
    }

    /// Pure decision helper so unit tests can exhaustively verify resume behavior.
    nonisolated static func shouldAutoResumeMusic(
        policy: MusicResumePolicy,
        wasPlayingBeforeInterrupt: Bool,
        elapsedSeconds: TimeInterval,
        appInForeground: Bool,
        headphoneUnplugged: Bool
    ) -> Bool {
        guard wasPlayingBeforeInterrupt, appInForeground, !headphoneUnplugged else { return false }
        switch policy {
        case .manual:
            return false
        case .always:
            return true
        case .smart:
            return elapsedSeconds >= 0 && elapsedSeconds <= smartResumeThresholdSeconds
        }
    }

    /// Called when a foreground audio source starts playback.
    func claim(_ owner: Owner, now: Date = Date()) {
        if owner == .music {
            if activeOwner == .video {
                videoHooks?.pauseForMusicTakeover()
            }
            activeOwner = .music
            clearPendingResume()
            return
        }

        // Video, Novel TTS, or Novel Audiobook is claiming audio.
        if musicHooks?.isPlaying() == true {
            musicInterruptedAt = now
            musicInterruptedBy = owner
            headphoneUnpluggedDuringInterrupt = false
            musicHooks?.fadeOutAndPause()
        }
        activeOwner = owner
    }

    /// Called when Video / Novel TTS / Novel Audiobook finishes or exits.
    func release(_ owner: Owner, now: Date = Date(), appInForeground: Bool? = nil) {
        let fg = appInForeground ?? (UIApplication.shared.applicationState == .active)
        let shouldResume: Bool
        if let started = musicInterruptedAt, musicInterruptedBy == owner {
            let elapsed = now.timeIntervalSince(started)
            shouldResume = Self.shouldAutoResumeMusic(
                policy: resumePolicy,
                wasPlayingBeforeInterrupt: true,
                elapsedSeconds: elapsed,
                appInForeground: fg,
                headphoneUnplugged: headphoneUnpluggedDuringInterrupt
            )
            clearPendingResume()
        } else {
            shouldResume = false
        }

        if activeOwner == owner {
            activeOwner = shouldResume ? .music : .none
        }
        if shouldResume {
            musicHooks?.fadeInAndResume()
        } else if activeOwner == .none {
            // Restore music lock-screen metadata if music still holds a paused queue.
            musicHooks?.refreshLockScreen()
        }
    }

    /// Whether `owner` is allowed to clear `MPNowPlayingInfoCenter.default().nowPlayingInfo` on teardown.
    func canClearNowPlaying(for owner: Owner) -> Bool {
        activeOwner == owner || activeOwner == .none
    }

    func handleHeadphoneUnplugged() {
        headphoneUnpluggedDuringInterrupt = true
        clearPendingResume()
    }

    func clearPendingResume() {
        musicInterruptedAt = nil
        musicInterruptedBy = nil
    }
}
