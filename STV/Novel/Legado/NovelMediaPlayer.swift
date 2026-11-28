import SwiftUI
import AVKit

/// Playback position, polled four times per second. Its own object, so only the transport follows it; the page and the cover
/// used to be rebuilt on every tick.
@MainActor
private final class AudioClock: ObservableObject {
    @Published var elapsed = 0.0
    @Published var duration = 0.0
    @Published var playing = false
    @Published var failed = false
    weak var player: AVPlayer?

    func tick() {
        guard let player else { return }
        let now = player.timeControlStatus == .playing
        if now != playing { playing = now }
        let t = player.currentTime().seconds, d = player.currentItem?.duration.seconds ?? 0
        let e = t.isFinite ? max(t, 0) : 0, len = d.isFinite ? max(d, 0) : 0
        if abs(e - elapsed) > 0.05 { elapsed = e }
        if len != duration { duration = len }
        if player.currentItem?.status == .failed, !failed { failed = true }
    }
    func reset() { player = nil; elapsed = 0; duration = 0; playing = false; failed = false }
}

struct NovelMediaPlayer: View {
    let source: LegadoSource
    let chapters: [LegadoChapter]
    let startIndex: Int
    let book: [String: String]
    @Environment(\.dismiss) private var dismiss
    @State private var selected: Int?
    @State private var player: AVPlayer?
    @State private var token: String?
    @StateObject private var clock = AudioClock()
    @State private var showQueue = false
    @State private var failure: String?
    @State private var retry = 0
    private var index: Int { selected ?? startIndex }
    private var title: String { book["name"] ?? source.bookSourceName }
    private var chapterName: String { chapters.indices.contains(index) ? chapters[index].title : title }

    var body: some View {
        Group {
            if source.bookSourceType == 4 {
                let series = HGSeries(source: .legado, seriesId: source.id + "|" + (book["bookUrl"] ?? title), title: title, cover: book["cover"] ?? "", orientation: "landscape")
                let episodes = chapters.enumerated().map { HGEpisode(ep: $0.offset + 1, vid: String($0.offset), duration: 0, title: $0.element.title) }
                let detail = HGSeriesDetail(source: series.source, seriesId: series.seriesId, title: title, cover: series.cover, author: book["author"], actors: [], onlineTime: 0, totalEps: episodes.count, episodes: episodes)
                PlayerScreen(series: series, startIndex: startIndex, detail: detail, resolve: { i in .direct(try await mediaURL(i)) })
            } else { audio }
        }.accessibilityElement(children: .contain).accessibilityIdentifier("novel.media.player")
    }

    private var audio: some View {
        VStack(spacing: 16) {
            LiquidGlassGroup(spacing: 10) {
                HStack {
                    LiquidGlassIconButton(systemName: "chevron.down", label: "关闭播放", size: 44, onMedia: true) { dismiss() }
                    Spacer()
                    Text("有声书").dsFont(16, .semibold, relativeTo: .headline).foregroundStyle(.white)
                    Spacer()
                    LiquidGlassIconButton(systemName: "list.bullet", label: "章节列表", size: 44, onMedia: true) { showQueue = true }
                }
            }
            GeometryReader { geo in
                if geo.size.width > geo.size.height {
                    HStack(spacing: 26) {
                        artwork.frame(width: min(geo.size.width * 0.40, geo.size.height * 0.90), height: min(geo.size.width * 0.40, geo.size.height * 0.90))
                        panel.frame(maxWidth: .infinity)
                    }.frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    VStack(spacing: 22) {
                        let side = min(geo.size.width * 0.86, max(geo.size.height - 320, 160), 340)
                        artwork.frame(width: side, height: side).frame(maxWidth: .infinity, maxHeight: .infinity)
                        panel
                    }
                }
            }
        }
        .padding(.horizontal, 20).padding(.bottom, 12)
        .background {
            ZStack {
                LinearGradient(colors: [GeneratedBookCover.clothColors(title).0, .black], startPoint: .top, endPoint: .bottom)
                if let cover = book["cover"], !cover.isEmpty { CoverBackdrop(url: cover) }
                Color.black.opacity(0.34)
            }
            .ignoresSafeArea()
        }
        .sheet(isPresented: $showQueue) {
            DSSheet("章节列表", subtitle: "共 \(chapters.count) 章") {
                DSOptionList(options: Array(chapters.indices), selected: index, label: { chapters.indices.contains($0) ? chapters[$0].title : "" }) { i in selected = i; showQueue = false }
            }
            .dsSheetChrome()
        }
        .stvLocalColorScheme(.dark)
        .task(id: "\(index)-\(retry)") { await playAudio() }
        .onReceive(Timer.publish(every: 0.25, on: .main, in: .common).autoconnect()) { _ in clock.tick() }
        .onReceive(clock.$failed) { if $0 { failure = "本章暂时无法播放" } }
        .onReceive(NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime)) { notification in
            if let item = notification.object as? AVPlayerItem, item === player?.currentItem, index + 1 < chapters.count { selected = index + 1 }
        }
        .onAppear { MusicPlayerEngine.shared.enterImmersive("novelAudio") }
        .onDisappear {
            MusicPlayerEngine.shared.leaveImmersive("novelAudio")
            stop()
        }
    }

    private var artwork: some View {
        BookArt(url: book["cover"], title: title, author: book["author"], maxPixelSize: CoverPixels.hero)
            .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
            .background { RoundedRectangle(cornerRadius: 26, style: .continuous).fill(Color.black.shadow(.drop(color: .black.opacity(0.45), radius: 26, y: 18))).padding(1) }
    }

    /// Floating glass transport panel.
    private var panel: some View {
        VStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text(chapterName).dsFont(20, .bold, relativeTo: .title3).foregroundStyle(.white).lineLimit(2)
                Text(title).dsFont(14, relativeTo: .subheadline).foregroundStyle(.white.opacity(0.72)).lineLimit(1)
                if let failure {
                    Button { retry += 1 } label: { MiniButtonLabel(title: failure + " · 重试", tint: .white, ground: .white.opacity(0.16)) }.buttonStyle(PressScale()).padding(.top, 4)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            AudioScrubber(clock: clock)
            AudioTransport(clock: clock, loading: player == nil && failure == nil, usable: player != nil && failure == nil,
                           previous: index > 0 ? { selected = index - 1 } : nil,
                           next: index + 1 < chapters.count ? { selected = index + 1 } : nil)
        }
        .padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 14)
        .liquidGlass(corner: 34, tone: .panel, onMedia: true)
        .onGlassSurface()
    }

    private func mediaURL(_ i: Int) async throws -> URL {
        guard chapters.indices.contains(i) else { throw LegadoError.rule("章节不存在") }
        let chapter: LegadoChapter = { var item = chapters[i]; item.index = i; return item }()
        let next = chapters.indices.contains(i + 1) ? chapters[i + 1].url : nil
        let cancel = LegadoCancelToken()
        let media = try await withTaskCancellationHandler(operation: { try await LegadoClient.run { try cancel.bind { try LegadoMedia.resolve(source, chapter: chapter, book: book, nextChapterURL: next) } } }, onCancel: { cancel.cancel() })
        try Task.checkCancellation()
        let local = try await LegadoClient.run { try LocalHLSProxy.shared.legadoURL(media) }
        guard !Task.isCancelled, let url = URL(string: local) else { if let token = LocalHLSProxy.token(of: local) { LocalHLSProxy.shared.release(token) }; throw CancellationError() }
        return url
    }
    @MainActor private func playAudio() async {
        stop(releasingAudio: false); failure = nil
        do {
            let url = try await mediaURL(index)
            let nextToken = LocalHLSProxy.token(of: url.absoluteString)
            guard !Task.isCancelled else {
                if let nextToken { LocalHLSProxy.shared.release(nextToken) }
                return
            }
            token = nextToken
            AppAudioArbiter.shared.claim(.novelAudio)
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            let next = AVPlayer(url: url); player = next; clock.player = next; next.play()
        } catch { if !Task.isCancelled { failure = error.localizedDescription } }
    }
    @MainActor private func stop(releasingAudio: Bool = true) {
        let wasActive = player != nil
        player?.pause(); player = nil; clock.reset()
        if let token { LocalHLSProxy.shared.release(token) }; token = nil
        if releasingAudio && wasActive {
            AppAudioArbiter.shared.release(.novelAudio)
        }
    }
}

private struct AudioScrubber: View {
    @ObservedObject var clock: AudioClock
    @State private var drag: Double?
    private func text(_ seconds: Double) -> String { String(format: "%d:%02d", Int(seconds) / 60, Int(seconds) % 60) }
    var body: some View {
        VStack(spacing: 2) {
            DSSlider(value: Binding(get: { drag ?? min(clock.elapsed, clock.duration) }, set: { drag = $0 }), range: 0...max(clock.duration, 1), step: nil,
                     tint: .white, track: .white.opacity(0.24), valueLabel: { text($0) }) { editing in
                if !editing, let target = drag { clock.player?.seek(to: CMTime(seconds: target, preferredTimescale: 600)); clock.elapsed = target; drag = nil }
            }
            .disabled(clock.duration <= 0).accessibilityLabel("播放进度")
            HStack {
                Text(text(drag ?? clock.elapsed)).accessibilityValue(String(format: "已播放 %.2f 秒", clock.elapsed)).accessibilityIdentifier("novel.media.status")
                Spacer()
                Text(text(clock.duration))
            }
            .font(.caption.monospacedDigit()).foregroundStyle(.white.opacity(0.72))
        }
    }
}

private struct AudioTransport: View {
    @ObservedObject var clock: AudioClock
    let loading: Bool
    let usable: Bool
    let previous: (() -> Void)?
    let next: (() -> Void)?
    var body: some View {
        HStack(spacing: 28) {
            step("backward.end.fill", "上一章", previous)
            Button {
                if clock.playing { clock.player?.pause() } else {
                    if clock.duration > 0 && clock.elapsed >= clock.duration { clock.player?.seek(to: .zero) }
                    clock.player?.play()
                }
            } label: {
                ZStack {
                    Circle().fill(.white).frame(width: 66, height: 66)
                    if loading { DSSpinner(size: 26, tint: .black) }
                    else { Image(systemName: clock.playing ? "pause.fill" : "play.fill").font(.system(size: 26, weight: .bold)).foregroundStyle(.black).offset(x: clock.playing ? 0 : 2).compatSymbolReplace() }
                }
                .contentShape(Circle())
            }
            .buttonStyle(PressScale()).disabled(!usable).opacity(usable || loading ? 1 : 0.5)
            .accessibilityLabel(clock.playing ? "暂停" : "播放")
            step("forward.end.fill", "下一章", next)
        }
    }

    private func step(_ icon: String, _ label: String, _ action: (() -> Void)?) -> some View {
        Button { action?() } label: { Image(systemName: icon).font(.system(size: 22, weight: .semibold)).foregroundStyle(.white).frame(width: 56, height: 56).contentShape(Circle()) }
            .buttonStyle(PressScale()).disabled(action == nil).opacity(action == nil ? 0.35 : 1).accessibilityLabel(label)
    }
}
