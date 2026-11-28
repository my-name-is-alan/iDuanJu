import SwiftUI

/// Unified native iOS cinema player color tokens:
/// Crisp luminous white on dark smoked glass, native brand blue (`STVTheme.accent`) only for active highlights.
enum PlayerPalette {
    static let signal = STVTheme.accent
    static let signalSoft = STVTheme.accent.opacity(0.20)
    static let ivory = Color.white
    static let hudSurface = Color.black.opacity(0.72)
}

/// Round Liquid Glass button floating over video (40 pt glass, 44 pt hit area).
struct PlayerIconButton: View {
    let symbol: String
    let label: String
    var tint: Color = .white
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(tint)
                .frame(width: 40, height: 40)
                .liquidGlass(Circle(), tone: .clear, interactive: true, onMedia: true)
                .frame(width: 44, height: 44)
                .contentShape(Circle())
        }.buttonStyle(PressScale()).accessibilityLabel(label)
    }
}

/// One icon of a `PlayerIconCluster` (the cluster owns the glass).
struct PlayerClusterIcon: View {
    let symbol: String
    let label: String
    var tint: Color = .white
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(tint)
                .frame(width: 44, height: 40)
                .contentShape(Rectangle())
        }.buttonStyle(PressScale()).accessibilityLabel(label)
    }
}

/// Neighbouring icon buttons share one glass capsule, like a system toolbar group.
struct PlayerIconCluster<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        HStack(spacing: 0) { content }
            .padding(.horizontal, 4)
            .frame(height: 40)
            .liquidGlassCapsule(tone: .clear, interactive: true, onMedia: true)
            .frame(height: 44)
    }
}

/// Small glass capsule with text (收起 / 展开选集 / 跳过片头 / notices).
struct PlayerGlassPill: View {
    let title: String
    var symbol: String? = nil
    var trailing: String? = nil
    var height: CGFloat = 34
    var body: some View {
        HStack(spacing: 5) {
            if let symbol { Image(systemName: symbol).font(.system(size: 12, weight: .semibold)) }
            Text(title).font(.system(size: 13, weight: .semibold)).lineLimit(1)
            if let trailing { Image(systemName: trailing).font(.system(size: 9, weight: .bold)) }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 13).frame(height: height)
        .liquidGlassCapsule(tone: .clear, interactive: true, onMedia: true)
        .contentShape(Capsule())
    }
}

// MARK: - Progress

/// Progress bar: full-width track (played white, buffered white 40 %, rest white 22 %) with a round knob. Dragging
/// grows it and shows a bubble (thumbnail when the asset allows it + "t / d" + ±delta). Drag or tap anywhere; the seek
/// happens on release. Observes the clock only: nothing else redraws while time moves.
struct PlayerProgressBar: View {
    let co: PlaybackCoordinator
    @ObservedObject private var clock: PlaybackClock
    @Binding var scrubbing: Bool
    var onInteract: () -> Void
    /// Non-interactive hairline shown when the chrome is hidden.
    var mini: Bool
    @State private var drag: Double?
    @State private var dragStart: Double = 0
    @State private var thumb: UIImage?
    @GestureState private var dragGestureActive = false

    init(co: PlaybackCoordinator, scrubbing: Binding<Bool>, onInteract: @escaping () -> Void = {}, mini: Bool = false) {
        self.co = co
        _clock = ObservedObject(wrappedValue: co.clock)
        _scrubbing = scrubbing
        self.onInteract = onInteract
        self.mini = mini
    }

    private static let thin: CGFloat = 3.5, thick: CGFloat = 7, miniH: CGFloat = 2

    var body: some View {
        let sample = clock.sample
        let duration = max(0, sample.duration)
        let played = sample.fraction
        let shown = drag ?? played
        let buffered = duration > 0 ? min(1, max(shown, sample.bufferedUntil / duration)) : 0
        let active = !mini && drag != nil
        let h = mini ? Self.miniH : (active ? Self.thick : Self.thin)
        GeometryReader { g in
            let w = max(1, g.size.width)
            let thumbX = min(max(0, w * shown), w)
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(mini ? 0.16 : 0.22))
                if !mini {
                    Capsule().fill(.white.opacity(0.42)).frame(width: w * buffered)
                }
                Capsule().fill(.white.opacity(mini ? 0.85 : 1.0)).frame(width: max(h, thumbX))
            }
            .frame(height: h)
            .overlay(alignment: .leading) {
                if !mini {
                    let knob: CGFloat = active ? 16 : 10
                    Circle()
                        .fill(.white)
                        .frame(width: knob, height: knob)
                        .shadow(color: .black.opacity(0.42), radius: active ? 4 : 2, y: 1)
                        .offset(x: min(max(0, thumbX - knob / 2), w - knob))
                }
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .overlay(alignment: .topLeading) {
                if let f = drag, duration > 0 {
                    bubble(f * duration, duration, delta: (f - dragStart) * duration)
                        .fixedSize()
                        .position(x: min(max(w * f, 86), w - 86), y: thumbAvailable ? -84 : -28)
                        .allowsHitTesting(false)
                        .accessibilityIdentifier("player.progress.bubble")
                }
            }
            .gesture(mini || duration <= 0 ? nil : scrubGesture(width: w, duration: duration, played: played))
            .compatOnChange(of: dragGestureActive) { active in
                if !active { cancelInterruptedScrub() }
            }
            .onDisappear { cancelInterruptedScrub(animated: false) }
        }
        .frame(height: mini ? Self.miniH : 24)
        .task(id: drag.map { Int($0 * max(1, clock.sample.duration) / PlayerThumbnailer.bucket) }) {
            guard let f = drag, clock.sample.duration > 0, co.thumbnails.available else { return }
            let t = f * clock.sample.duration
            if let c = co.thumbnails.cached(at: t) { thumb = c; return }
            try? await Task.sleep(for: .milliseconds(60))
            if let img = await co.thumbnails.image(at: t), drag != nil { thumb = img }
        }
        #if DEBUG
        .task(id: co.scrubDemo) {   // screenshots only: `STV_SCRUB_DEMO=0.62` freezes the dragging look (no seek)
            if !mini, let f = co.scrubDemo {
                dragStart = 0.2; drag = f; scrubbing = true; clock.scrubPreview = f * clock.sample.duration
            }
        }
        .task {
            if !mini, co.scrubDemo == nil, let f = Double(ProcessInfo.processInfo.environment["STV_SCRUB_DEMO"] ?? "") {
                try? await Task.sleep(for: .seconds(Double(ProcessInfo.processInfo.environment["STV_SCRUB_DELAY"] ?? "") ?? 3))
                if co.scrubDemo == nil { co.scrubDemo = f }
            }
        }
        #endif
        .animation(.spring(response: 0.22, dampingFraction: 0.9), value: drag != nil)
        .accessibilityElement()
        .accessibilityLabel("播放进度")
        .accessibilityValue("\(Fmt.duration(Int(shown * duration))) / \(Fmt.duration(Int(duration)))")
        .accessibilityIdentifier(mini ? "player.progress.mini" : "player.progress")
        .accessibilityAdjustableAction { direction in
            let delta = direction == .increment ? 10.0 : -10.0
            if duration > 0 { co.seek(fraction: max(0, min(1, (clock.sample.time + delta) / duration))) }
            onInteract()
        }
    }

    private var thumbAvailable: Bool { thumb != nil && co.thumbnails.available }

    private var isScrubDemoActive: Bool {
        #if DEBUG
        return co.scrubDemo != nil
        #else
        return false
        #endif
    }

    private func cancelInterruptedScrub(animated: Bool = true) {
        guard drag != nil, !isScrubDemoActive else { return }
        if animated {
            withAnimation(.spring(response: 0.25, dampingFraction: 0.9)) { drag = nil }
        } else {
            drag = nil
        }
        clock.scrubPreview = nil
        scrubbing = false
    }

    private func scrubGesture(width w: CGFloat, duration: Double, played: Double) -> some Gesture {
        let dragPart = DragGesture(minimumDistance: 3)
            .updating($dragGestureActive) { _, active, _ in active = true }
            .onChanged { v in
                if drag == nil {
                    dragStart = played; scrubbing = true
                    UISelectionFeedbackGenerator().selectionChanged()
                }
                let next = min(1, max(0, v.location.x / max(1, w)))
                drag = next
                clock.scrubPreview = next * duration
                onInteract()
            }
            .onEnded { _ in
                if let f = drag { co.seek(fraction: f) }
                withAnimation(.spring(response: 0.25, dampingFraction: 0.9)) { drag = nil }
                clock.scrubPreview = nil
                scrubbing = false
                onInteract()
            }
        let tapPart = SpatialTapGesture().onEnded { v in
            co.seek(fraction: min(1, max(0, v.location.x / max(1, w))))
            onInteract()
        }
        return dragPart.exclusively(before: tapPart)
    }

    private func bubble(_ t: Double, _ d: Double, delta: Double) -> some View {
        VStack(spacing: 4) {
            if let thumb, co.thumbnails.available {
                Image(uiImage: thumb).resizable().aspectRatio(contentMode: .fill)
                    .frame(width: 168, height: 95).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay { RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(.white, lineWidth: 1.5) }
                    .padding(.bottom, 4)
                    .accessibilityIdentifier("player.progress.thumb")
            }
            (Text(Fmt.clock(Int(t))).foregroundColor(PlayerPalette.ivory) + Text(" / " + Fmt.clock(Int(d))).foregroundColor(.white.opacity(0.62)))
                .font(.system(size: 16, weight: .bold, design: .monospaced).monospacedDigit())
            Text(abs(delta) >= 1 ? (delta > 0 ? "+" : "−") + Fmt.clock(Int(abs(delta))) : " ")
                .font(.system(size: 12, weight: .semibold, design: .monospaced).monospacedDigit()).foregroundStyle(PlayerPalette.signal)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(PlayerPalette.hudSurface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(.white.opacity(0.14), lineWidth: 0.5))
        .shadow(color: .black.opacity(0.55), radius: 8, y: 4)
    }
}

/// Full-width precision timeline row: current timecode | filmstrip scrubber | duration (no duplicate buttons crowding the bar).
struct PlayerTimelineV3: View {
    @ObservedObject var co: PlaybackCoordinator
    @ObservedObject private var clock: PlaybackClock
    @Binding var scrubbing: Bool
    var onInteract: () -> Void
    var onFullscreen: (() -> Void)?

    init(co: PlaybackCoordinator, scrubbing: Binding<Bool>, onInteract: @escaping () -> Void = {}, onFullscreen: (() -> Void)? = nil) {
        _co = ObservedObject(wrappedValue: co)
        _clock = ObservedObject(wrappedValue: co.clock)
        _scrubbing = scrubbing
        self.onInteract = onInteract
        self.onFullscreen = onFullscreen
    }

    var body: some View {
        let t = clock.scrubPreview ?? clock.sample.time
        HStack(spacing: 10) {
            Text(Fmt.clock(Int(t)))
                .foregroundStyle(PlayerPalette.ivory)
                .accessibilityIdentifier("player.time")
            PlayerProgressBar(co: co, scrubbing: $scrubbing, onInteract: onInteract)
            Text(Fmt.clock(Int(max(0, clock.sample.duration))))
                .foregroundStyle(.white.opacity(0.62))
                .accessibilityIdentifier("player.remaining")
            if let onFullscreen { PlayerFullscreenButton(action: onFullscreen).padding(.leading, 2) }
        }
        .font(.system(size: 11.5, weight: .medium, design: .monospaced).monospacedDigit())
    }
}

struct PlayerFullscreenButton: View {
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: "arrow.up.left.and.arrow.down.right").font(.system(size: 15, weight: .semibold))
                .foregroundStyle(PlayerPalette.ivory).frame(width: 30, height: 30).contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
        .accessibilityLabel("横屏全屏").accessibilityIdentifier("player.fullscreen")
    }
}

/// 跳过片头: shown while the playhead is inside the intro.
struct PlayerSkipIntro: View {
    @ObservedObject var clock: PlaybackClock
    let introEnd: Double
    let ready: Bool
    let onSkip: () -> Void
    var body: some View {
        if ready, clock.sample.time < introEnd - 1, clock.sample.duration > introEnd + 30 {
            Button(action: onSkip) { PlayerGlassPill(title: "跳过片头", trailing: "play.fill", height: 30) }
                .buttonStyle(PressScale()).accessibilityIdentifier("player.skipIntro")
        }
    }
}

/// Cue text the host draws over the video.
struct PlayerSubtitleOverlay: View {
    @ObservedObject var telemetry: PlaybackTelemetry
    let landscape: Bool
    let bottom: CGFloat
    var body: some View {
        if let sub = telemetry.subtitle {
            VStack {
                Spacer()
                Text(sub).font(.system(size: landscape ? 18 : 16, weight: .semibold)).foregroundStyle(.white)
                    .multilineTextAlignment(.center).shadow(color: .black, radius: 2).shadow(color: .black.opacity(0.8), radius: 4)
                    .padding(.horizontal, 24).padding(.bottom, bottom)
                    .accessibilityIdentifier("player.subtitle")
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .allowsHitTesting(false)
        }
    }
}

/// Playback time as an accessibility value (novel media tests), refreshed by the clock without touching the page.
struct PlayerClockValue: ViewModifier {
    @ObservedObject var clock: PlaybackClock
    func body(content: Content) -> some View {
        content.accessibilityValue(String(format: "已播放 %.2f 秒", clock.sample.time))
    }
}

/// Ultra-compact top-edge speed pill while long-pressing (never blocks the center video frame).
struct PlayerSpeedHUD: View {
    var rate: Double
    var pendingLock: Bool
    var pendingCancel: Bool
    var hasLockedRate: Bool
    @State private var pulse = false

    private var statusTag: String {
        if pendingCancel { return "松手恢复 1×" }
        if pendingLock { return "松手锁定" }
        return "倍速播放中"
    }

    private var tint: Color {
        if pendingCancel { return Color(red: 1, green: 0.38, blue: 0.36) }
        return pendingLock ? STVTheme.accent : .white
    }

    var body: some View {
        HStack(spacing: 8) {
            if pendingCancel {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(tint)
            } else if pendingLock {
                Image(systemName: "lock.fill")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(tint)
            } else {
                HStack(spacing: -2) {
                    Image(systemName: "play.fill").opacity(pulse ? 0.35 : 1)
                    Image(systemName: "play.fill").opacity(pulse ? 1 : 0.45)
                }
                .font(.system(size: 9, weight: .bold))
                .foregroundStyle(.white)
            }

            Text(SpeedPopover.shortLabel(rate))
                .font(.system(size: 14, weight: .bold).monospacedDigit())
                .foregroundStyle(.white)

            Rectangle()
                .fill(.white.opacity(0.18))
                .frame(width: 1, height: 11)

            Text(statusTag)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.white.opacity(0.76))
        }
        .padding(.horizontal, 14)
        .frame(height: 34)
        .liquidGlassCapsule(tone: .clear, onMedia: true)
        .shadow(color: .black.opacity(0.35), radius: 10, y: 4)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.55).repeatForever(autoreverses: true)) { pulse = true }
        }
        .onDisappear { pulse = false }
    }
}

/// Headless chrome states for simulator screenshots (`STV_SHOTS=1`).
struct PlayerChromeShotHost: View {
    enum Phase: Int, CaseIterable { case rightMenu, speedHUD, locked }

    @State private var phase: Phase = .rightMenu

    var body: some View {
        ZStack {
            LinearGradient(colors: [STVTheme.surface, .black], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            VStack(alignment: .leading) {
                HStack {
                    PlayerIconButton(symbol: "chevron.left", label: "返回") {}
                    Spacer()
                    PlayerIconCluster {
                        PlayerClusterIcon(symbol: "heart", label: "收藏") {}
                        PlayerClusterIcon(symbol: "pip.enter", label: "画中画") {}
                    }
                }
                Spacer()
                Text("霸总的秘密助理").font(.title2.weight(.bold)).foregroundStyle(.white)
                Capsule().fill(.white.opacity(0.2)).frame(height: 3)
                    .overlay(alignment: .leading) { Capsule().fill(STVTheme.accent).frame(width: 140, height: 3) }.padding(.vertical)
            }.padding(.horizontal, 20)
            if phase != .rightMenu {
                PlayerSpeedHUD(rate: phase == .locked ? 2 : 1.5, pendingLock: phase == .locked,
                               pendingCancel: false, hasLockedRate: phase == .locked)
            }
        }
        .preferredColorScheme(.dark)
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .task {
            // Timed phases so an external script can screenshot each state.
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            phase = .speedHUD
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            phase = .locked
            try? await Task.sleep(nanoseconds: 8_000_000_000)
        }
    }
}
