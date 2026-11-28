import SwiftUI

// MARK: - Floating Global Mini Player Bar (above iDuanJu 4-Tab) & Slim Reader Menu Bar (44pt)

/// 54pt circular-art optical liquid-glass MiniPlayer floating 10pt above iDuanJu's 4-Tab bar (or bottom-right on iPad).
struct MusicMiniPlayerBar: View {
    @ObservedObject private var player = MusicPlayerEngine.shared
    @Environment(\.colorScheme) private var scheme
    @State private var dragX: CGFloat = 0

    var body: some View {
        if let track = player.currentTrack, !player.miniBarDismissed {
            HStack(spacing: 10) {
                Button {
                    player.showFullPlayer = true
                } label: {
                    HStack(spacing: 10) {
                        ZStack(alignment: .bottomTrailing) {
                            YMCoverImage(url: track.coverURL, isCircle: true)
                                .frame(width: 40, height: 40)
                                .overlay(Circle().stroke(Color.white.opacity(0.24), lineWidth: 1.2))
                            if player.isPlaying {
                                Circle()
                                    .fill(YMTheme.lime)
                                    .frame(width: 8, height: 8)
                                    .overlay(Circle().stroke(YMTheme.base(scheme), lineWidth: 1.5))
                            }
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(track.title)
                                .font(.system(size: 13.5, weight: .semibold))
                                .foregroundStyle(YMTheme.fg(scheme))
                                .lineLimit(1)
                                .truncationMode(.middle)
                            Text("\(track.artist) · \(track.sourceDisplayLabel)")
                                .font(.system(size: 11))
                                .foregroundStyle(YMTheme.secondary(scheme))
                                .lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if player.isLoading {
                    ProgressView().scaleEffect(0.8).tint(YMTheme.accent(scheme)).frame(width: 32, height: 32)
                } else {
                    Button { player.togglePlayPause() } label: {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(YMTheme.fg(scheme))
                            .frame(width: 34, height: 34)
                    }
                    .buttonStyle(.plain)
                }

                Button { player.nextTrack() } label: {
                    Image(systemName: "forward.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(YMTheme.fg(scheme).opacity(0.85))
                        .frame(width: 30, height: 30)
                }
                .buttonStyle(.plain)
            }
            .padding(.leading, 8)
            .padding(.trailing, 12)
            .frame(height: 54)
            .background(YMDockSurface(shape: Capsule(style: .continuous)))
            .offset(x: dragX)
            .gesture(
                DragGesture(minimumDistance: 16)
                    .onChanged { v in
                        if v.translation.width < 0 { dragX = v.translation.width }
                    }
                    .onEnded { v in
                        if v.translation.width < -90 {
                            withAnimation(.easeOut(duration: 0.2)) { player.dismissMiniBar() }
                            dragX = 0
                        } else {
                            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { dragX = 0 }
                        }
                    }
            )
        }
    }
}

/// Ultra-slim 44pt music control pill shown ONLY when Novel (`menu == true`) or Manga (`chrome == true`)
/// reading menu is visible; 100% hidden during immersive reading.
struct ReaderMusicMiniBar: View {
    @ObservedObject private var player = MusicPlayerEngine.shared

    var body: some View {
        if let track = player.currentTrack, !player.miniBarDismissed {
            HStack(spacing: 10) {
                Button { player.showFullPlayer = true } label: {
                    HStack(spacing: 8) {
                        YMCoverImage(url: track.coverURL, isCircle: true)
                            .frame(width: 30, height: 30)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(track.title)
                                .font(.system(size: 12.5, weight: .semibold))
                                .foregroundStyle(.white)
                                .lineLimit(1)
                                .truncationMode(.middle)
                            Text(track.artist)
                                .font(.system(size: 10.5))
                                .foregroundStyle(.white.opacity(0.68))
                                .lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)

                Button { player.togglePlayPause() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(YMTheme.lime)
                        .frame(width: 30, height: 30)
                }
                .buttonStyle(.plain)

                Button { player.nextTrack() } label: {
                    Image(systemName: "forward.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.86))
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 10)
            .frame(height: 44)
            .background(
                Capsule()
                    .fill(.ultraThinMaterial)
                    .overlay(Capsule().fill(Color.black.opacity(0.50)))
                    .overlay(Capsule().stroke(Color.white.opacity(0.16), lineWidth: 0.7))
            )
            .padding(.horizontal, 18)
        }
    }
}

// MARK: - Full-Screen Yimeng Now Playing + LiveLyrics + SignalPath + Alternatives + iPad Vinyl Stage

struct MusicNowPlayingView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var player = MusicPlayerEngine.shared
    @ObservedObject private var store = MusicStore.shared

    @State private var showLyrics = false
    @State private var showTranslation = true
    @State private var showRomaji = false
    @State private var showSignalSheet = false
    @State private var showAlternativesSheet = false
    @State private var showQueueSheet = false
    @State private var showSleepSheet = false
    @State private var alternatives: [MusicAlternative] = []
    @State private var loadingAlts = false
    @State private var scrubbing = false
    @State private var scrubValue: Double = 0

    var body: some View {
        GeometryReader { geo in
            let isPadLandscape = geo.size.width >= 880 && geo.size.width > geo.size.height
            let track = player.currentTrack

            ZStack {
                YMDreamField(seed: track?.seed ?? 7, dimForLyrics: showLyrics && !isPadLandscape)
                    .equatable()

                if let track {
                    if isPadLandscape {
                        iPadVinylStage(track: track)
                    } else {
                        phoneNowPlaying(track: track)
                    }
                } else {
                    VStack(spacing: 12) {
                        Text("暂无正在播放的歌曲").foregroundStyle(.white.opacity(0.7))
                        Button("关闭") { dismiss() }
                            .foregroundStyle(.black)
                            .padding(.horizontal, 20)
                            .frame(height: 40)
                            .background(Capsule().fill(.white))
                    }
                }
            }
            .preferredColorScheme(.dark)
        }
        .sheet(isPresented: $showSignalSheet) {
            signalPathSheet
        }
        .sheet(isPresented: $showAlternativesSheet) {
            alternativesSheet
        }
        .sheet(isPresented: $showQueueSheet) {
            queueSheet
        }
        .sheet(isPresented: $showSleepSheet) {
            sleepTimerSheet
        }
    }

    private func phoneNowPlaying(track: MusicTrack) -> some View {
        VStack(spacing: 0) {
            // Top bar: Dismiss | 译梦 · 梦境 CLOUD | Heart
            HStack {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(Color.white.opacity(0.14)))
                }
                .buttonStyle(.plain)

                Spacer()
                Text("译梦 · 梦境 CLOUD")
                    .font(.system(size: 11.5, weight: .medium))
                    .tracking(2.0)
                    .foregroundStyle(.white.opacity(0.72))
                Spacer()

                Button { store.toggleFavorite(track) } label: {
                    let fav = store.isFavorite(track)
                    Image(systemName: fav ? "heart.fill" : "heart")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(fav ? YMTheme.heart : .white)
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(Color.white.opacity(0.14)))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)

            ZStack {
                VStack {
                    Spacer(minLength: 10)
                    ZStack {
                        YMDoodleCoverHalo(seed: track.seed)
                            .frame(width: 274, height: 274)
                        YMCoverImage(url: track.coverURL, corner: 14)
                            .frame(width: 266, height: 266)
                            .shadow(color: .black.opacity(0.42), radius: 20, y: 10)
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        showLyrics = true
                    }
                    Spacer(minLength: 12)
                }
                .opacity(showLyrics ? 0 : 1)
                .allowsHitTesting(!showLyrics)

                NowPlayingLyricsStage(
                    trackId: track.id,
                    trackTitle: track.title,
                    trackArtist: track.artist,
                    lyrics: player.lyrics,
                    activeIndex: player.activeLyricIndex,
                    isVisible: showLyrics,
                    showTranslation: showTranslation,
                    showRomaji: showRomaji,
                    onToggleTranslation: { showTranslation.toggle() },
                    onToggleRomaji: { showRomaji.toggle() },
                    onCloseToCover: { showLyrics = false },
                    onSeek: { player.seek(to: $0) }
                )
                .equatable()
                .padding(.top, 10)
                .opacity(showLyrics ? 1 : 0)
                .allowsHitTesting(showLyrics)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .animation(.easeOut(duration: 0.16), value: showLyrics)

            // Track Info + QualityBadge
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(track.title)
                            .font(.system(size: 22, weight: .bold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        HStack(spacing: 6) {
                            Button {
                                dismiss()
                                DispatchQueue.main.asyncAfter(deadline: .now() + 0.22) {
                                    Router.push(.musicArtist(MusicArtistRef(
                                        name: track.primaryArtistName,
                                        artistId: track.artistId,
                                        source: track.source,
                                        coverURL: track.coverURL
                                    )))
                                }
                            } label: {
                                HStack(spacing: 3) {
                                    Text(track.artist)
                                        .font(.system(size: 15))
                                        .foregroundStyle(.white.opacity(0.82))
                                        .lineLimit(1)
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 10, weight: .bold))
                                        .foregroundStyle(.white.opacity(0.5))
                                }
                            }
                            .buttonStyle(.plain)

                            if !track.album.isEmpty {
                                Text("·").foregroundStyle(.white.opacity(0.4))
                                Button {
                                    dismiss()
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.22) {
                                        Router.push(.musicAlbum(MusicAlbumRef(
                                            name: track.album,
                                            albumId: track.albumId,
                                            artist: track.primaryArtistName,
                                            artistId: track.artistId,
                                            source: track.source,
                                            coverURL: track.coverURL
                                        )))
                                    }
                                } label: {
                                    Text(track.album)
                                        .font(.system(size: 13.5))
                                        .foregroundStyle(.white.opacity(0.62))
                                        .lineLimit(1)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    Spacer(minLength: 8)
                    Button { showSignalSheet = true } label: {
                        let qLabel = player.activeQualityLabel.isEmpty ? track.qualityLabel : player.activeQualityLabel
                        YMQualityBadge(
                            label: "\(qLabel) ▾",
                            lossless: player.signalPath?.isLossless == true
                        )
                    }
                    .buttonStyle(.plain)
                }

                if let msg = player.statusMessage {
                    Text(msg)
                        .font(.system(size: 11.5, weight: .medium))
                        .foregroundStyle(YMTheme.lime)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 14)

            // Scrubber
            scrubberView
                .padding(.horizontal, 24)
                .padding(.bottom, 14)

            // Action Row (歌词 / 音质 / 换源 / 定时 / 队列)
            HStack(spacing: 0) {
                actionBtn(icon: "quote.bubble", label: "歌词", active: showLyrics) {
                    showLyrics.toggle()
                }
                actionBtn(icon: "waveform", label: store.settings.preferredQuality.shortBadge, active: player.signalPath?.isLossless == true) {
                    showSignalSheet = true
                }
                actionBtn(icon: "arrow.triangle.2.circlepath", label: "换源", active: false) {
                    openAlternatives(for: track)
                }
                actionBtn(icon: "moon.zzz", label: player.sleepRemainingSec != nil ? "\(max(1, (player.sleepRemainingSec ?? 0) / 60))分" : "定时", active: player.sleepRemainingSec != nil) {
                    showSleepSheet = true
                }
                actionBtn(icon: "list.bullet", label: "队列", active: false) {
                    showQueueSheet = true
                }
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 14)

            // Transport Pill (opaque white Play/Pause circle)
            transportCapsule
                .padding(.horizontal, 20)
                .padding(.bottom, 22)
        }
    }

    private func iPadVinylStage(track: MusicTrack) -> some View {
        VStack(spacing: 0) {
            HStack {
                Button { dismiss() } label: {
                    Label("返回音乐", systemImage: "chevron.down")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .frame(height: 36)
                        .background(Capsule().fill(Color.white.opacity(0.14)))
                }
                .buttonStyle(.plain)
                Spacer()
                Text("i短剧 × 译梦 · 黑胶唱机台")
                    .font(.system(size: 12.5, weight: .medium))
                    .tracking(1.6)
                    .foregroundStyle(.white.opacity(0.72))
                Spacer()
                Button { store.toggleFavorite(track) } label: {
                    Image(systemName: store.isFavorite(track) ? "heart.fill" : "heart")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(store.isFavorite(track) ? YMTheme.heart : .white)
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(Color.white.opacity(0.14)))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 32)
            .padding(.top, 20)

            HStack(spacing: 40) {
                VStack(spacing: 20) {
                    YMVinylTurntable(coverURL: track.coverURL, isPlaying: player.isPlaying, seed: track.seed)
                    HStack(spacing: 12) {
                        Button { showSignalSheet = true } label: {
                            let qLabel = player.activeQualityLabel.isEmpty ? track.qualityLabel : player.activeQualityLabel
                            YMQualityBadge(label: "\(qLabel) ▾", lossless: player.signalPath?.isLossless == true)
                        }.buttonStyle(.plain)
                        Button { openAlternatives(for: track) } label: {
                            Text("跨源换源")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(YMTheme.lime)
                                .padding(.horizontal, 12)
                                .frame(height: 24)
                                .overlay(Capsule().stroke(YMTheme.lime.opacity(0.75), lineWidth: 1))
                        }.buttonStyle(.plain)
                    }
                }
                .frame(maxWidth: .infinity)

                VStack(alignment: .leading, spacing: 14) {
                    Text(track.title)
                        .font(.system(size: 28, weight: .bold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    HStack(spacing: 8) {
                        Button {
                            dismiss()
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.22) {
                                Router.push(.musicArtist(MusicArtistRef(
                                    name: track.primaryArtistName,
                                    artistId: track.artistId,
                                    source: track.source,
                                    coverURL: track.coverURL
                                )))
                            }
                        } label: {
                            HStack(spacing: 4) {
                                Text(track.artist)
                                    .font(.system(size: 16, weight: .medium))
                                    .foregroundStyle(.white.opacity(0.86))
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundStyle(.white.opacity(0.5))
                            }
                        }
                        .buttonStyle(.plain)

                        if !track.album.isEmpty {
                            Text("·").foregroundStyle(.white.opacity(0.45))
                            Button {
                                dismiss()
                                DispatchQueue.main.asyncAfter(deadline: .now() + 0.22) {
                                    Router.push(.musicAlbum(MusicAlbumRef(
                                        name: track.album,
                                        albumId: track.albumId,
                                        artist: track.primaryArtistName,
                                        artistId: track.artistId,
                                        source: track.source,
                                        coverURL: track.coverURL
                                    )))
                                }
                            } label: {
                                Text(track.album)
                                    .font(.system(size: 15))
                                    .foregroundStyle(.white.opacity(0.68))
                                    .lineLimit(1)
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    NowPlayingLyricsStage(
                        trackId: track.id,
                        trackTitle: track.title,
                        trackArtist: track.artist,
                        lyrics: player.lyrics,
                        activeIndex: player.activeLyricIndex,
                        isVisible: true,
                        showTranslation: showTranslation,
                        showRomaji: showRomaji,
                        onToggleTranslation: { showTranslation.toggle() },
                        onToggleRomaji: { showRomaji.toggle() },
                        onCloseToCover: nil,
                        onSeek: { player.seek(to: $0) }
                    )
                    .equatable()
                    .frame(maxHeight: 280)

                    scrubberView
                    transportCapsule
                }
                .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 40)
            .padding(.vertical, 20)
        }
    }

    private var scrubberView: some View {
        let dur = max(1, player.duration)
        let shown = scrubbing ? scrubValue : player.currentTime
        return VStack(spacing: 6) {
            Slider(
                value: Binding(
                    get: { shown },
                    set: { v in scrubbing = true; scrubValue = v }
                ),
                in: 0...dur,
                onEditingChanged: { editing in
                    if !editing {
                        player.seek(to: scrubValue)
                        scrubbing = false
                    }
                }
            )
            .tint(.white)

            HStack {
                Text(fmtTime(shown))
                Spacer()
                Text("-\(fmtTime(max(0, dur - shown)))")
            }
            .font(.system(size: 11.5).monospacedDigit())
            .foregroundStyle(.white.opacity(0.65))
        }
    }

    private var transportCapsule: some View {
        HStack {
            Button { player.playMode = player.playMode.next } label: {
                Image(systemName: player.playMode.icon)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.78))
                    .frame(width: 40, height: 40)
            }
            .buttonStyle(.plain)

            Spacer()
            Button { player.previousTrack() } label: {
                Image(systemName: "backward.fill")
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)

            Spacer()
            Button { player.togglePlayPause() } label: {
                Group {
                    if player.isLoading {
                        ProgressView().tint(.black)
                    } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(.black)
                    }
                }
                .frame(width: 52, height: 52)
                .background(Circle().fill(Color.white))
            }
            .buttonStyle(.plain)

            Spacer()
            Button { player.nextTrack() } label: {
                Image(systemName: "forward.fill")
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)

            Spacer()
            Button {
                let rates: [Float] = [0.75, 1.0, 1.25, 1.5, 2.0]
                let idx = rates.firstIndex(where: { abs($0 - player.playbackRate) < 0.05 }) ?? 1
                player.playbackRate = rates[(idx + 1) % rates.count]
            } label: {
                Text(String(format: "%g×", player.playbackRate))
                    .font(.system(size: 12.5, weight: .bold).monospacedDigit())
                    .foregroundStyle(abs(player.playbackRate - 1.0) > 0.05 ? YMTheme.lime : .white.opacity(0.78))
                    .frame(width: 40, height: 40)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
        .frame(height: 66)
        .background(
            Capsule()
                .fill(Color.black.opacity(0.36))
                .overlay(Capsule().stroke(Color.white.opacity(0.12), lineWidth: 0.8))
        )
    }

    private func actionBtn(icon: String, label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(active ? YMTheme.lime : .white.opacity(0.82))
                Text(label)
                    .font(.system(size: 10.5, weight: .medium))
                    .foregroundStyle(active ? YMTheme.lime : .white.opacity(0.62))
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
    }

    private func openAlternatives(for track: MusicTrack) {
        showAlternativesSheet = true
        loadingAlts = true
        let enabled = store.enabledOnlineSources
        let nas = store.nasConfigs
        var cookies: [MusicSourceKind: String] = [:]
        for p in store.sourcePrefs { if let c = p.customCookie { cookies[p.kind] = c } }
        Task {
            alternatives = await MusicProviderHub.shared.findAlternatives(for: track, cookies: cookies, enabledSources: enabled, nasConfigs: nas)
            loadingAlts = false
        }
    }

    private var signalPathSheet: some View {
        DSSheet("音质切换与信号链路", subtitle: "点击档位实时无感切换 · 零服务端直连") {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("首选播放音质")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.secondary)
                    VStack(spacing: 8) {
                        ForEach(MusicAudioQuality.allCases) { q in
                            let selected = store.settings.preferredQuality == q
                            Button {
                                player.switchQuality(to: q)
                                showSignalSheet = false
                            } label: {
                                HStack(spacing: 12) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        HStack(spacing: 6) {
                                            Text(q.label)
                                                .font(.system(size: 14.5, weight: .bold))
                                                .foregroundStyle(selected ? YMTheme.lime : .primary)
                                            if q == .hires || q == .lossless {
                                                Text(q == .hires ? "HR" : "SQ")
                                                    .font(.system(size: 9.5, weight: .black))
                                                    .foregroundStyle(.black)
                                                    .padding(.horizontal, 5)
                                                    .padding(.vertical, 1.5)
                                                    .background(Capsule().fill(YMTheme.lime))
                                            }
                                        }
                                        Text(q.detail)
                                            .font(.system(size: 11.5))
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if selected {
                                        Image(systemName: "checkmark.circle.fill")
                                            .font(.system(size: 18))
                                            .foregroundStyle(YMTheme.lime)
                                    }
                                }
                                .padding(12)
                                .ymCard(radius: 14)
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    Text("当前音频信号链路")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.secondary)
                        .padding(.top, 4)
                    if let sp = player.signalPath {
                        signalRow("01 音源解析", sp.sourceStage, accent: true)
                        signalRow("02 流媒体传输", sp.streamStage, accent: sp.isCached)
                        signalRow("03 本机解码", sp.decodeStage, accent: sp.isLossless)
                        signalRow("04 声学输出", sp.outputStage, accent: false)
                    } else {
                        Text("等待音频流初始化…").foregroundStyle(.secondary)
                    }
                }
                .padding(20)
            }
        }
    }

    private func signalRow(_ title: String, _ detail: String, accent: Bool) -> some View {
        HStack(spacing: 12) {
            Circle().fill(accent ? YMTheme.lime : Color.white.opacity(0.35)).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 12, weight: .semibold)).foregroundStyle(.secondary)
                Text(detail).font(.system(size: 14.5, weight: .medium))
            }
            Spacer()
        }
        .padding(12)
        .ymCard(radius: 14)
    }

    private var alternativesSheet: some View {
        DSSheet("换个平台播放", subtitle: "客户端无服务端并行检索可用音源") {
            ScrollView {
                VStack(spacing: 10) {
                    if loadingAlts {
                        ProgressView().tint(YMTheme.lime).padding(.vertical, 36)
                    } else if alternatives.isEmpty {
                        Text("未在其他已启用音源找到可播版本").foregroundStyle(.secondary).padding(.vertical, 36)
                    } else {
                        ForEach(alternatives) { alt in
                            Button {
                                player.switchStream(to: alt)
                                showAlternativesSheet = false
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text("\(alt.track.source.fullName) · \(alt.qualityLabel)")
                                            .font(.system(size: 14.5, weight: .semibold))
                                        Text("\(alt.track.title) — \(alt.track.artist) · \(alt.latencyMs)ms")
                                            .font(.system(size: 12))
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                    }
                                    Spacer()
                                    if alt.resolvedURL != nil {
                                        Text(alt.entitlement == .full ? "直连" : "试听")
                                            .font(.system(size: 11.5, weight: .bold))
                                            .foregroundStyle(alt.entitlement == .full ? .black : YMTheme.lime)
                                            .padding(.horizontal, 10)
                                            .frame(height: 24)
                                            .background(Capsule().fill(alt.entitlement == .full ? YMTheme.lime : Color.clear))
                                            .overlay(Capsule().stroke(YMTheme.lime, lineWidth: 1))
                                    } else {
                                        Text("不可播").font(.system(size: 11.5)).foregroundStyle(.secondary)
                                    }
                                }
                                .padding(12)
                                .ymCard(radius: 14)
                            }
                            .disabled(alt.resolvedURL == nil)
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(18)
            }
        }
    }

    private var queueSheet: some View {
        DSSheet("播放队列", subtitle: "共 \(player.queue.count) 首 · \(player.playMode.label) · 左滑移除") {
            List {
                ForEach(player.queue) { t in
                    MusicTrackRow(track: t, isCurrent: player.currentTrack?.id == t.id) {
                        player.play(track: t, in: player.queue)
                        showQueueSheet = false
                    }
                    .listRowInsets(EdgeInsets(top: 2, leading: 18, bottom: 2, trailing: 18))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button(role: .destructive) {
                            DS.tick()
                            withAnimation(.easeInOut(duration: 0.24)) {
                                player.removeFromQueue(trackId: t.id)
                            }
                            if player.queue.isEmpty { showQueueSheet = false }
                        } label: {
                            Label("移除", systemImage: "trash")
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    private var sleepTimerSheet: some View {
        DSSheet("睡眠定时", subtitle: "到期前 30 秒平滑淡出，后台安全停止并释放音频会话") {
            VStack(spacing: 10) {
                ForEach([0, 15, 30, 45, 60], id: \.self) { mins in
                    Button {
                        player.setSleepTimer(minutes: mins == 0 ? nil : mins)
                        showSleepSheet = false
                    } label: {
                        HStack {
                            Text(mins == 0 ? "关闭定时" : "\(mins) 分钟后停止")
                                .font(.system(size: 15, weight: .medium))
                            Spacer()
                            if mins > 0, let rem = player.sleepRemainingSec, abs(rem / 60 - mins) <= 1 {
                                Circle().fill(YMTheme.lime).frame(width: 8, height: 8)
                            }
                        }
                        .padding(14)
                        .ymCard(radius: 14)
                    }
                    .buttonStyle(.plain)
                }
                Spacer()
            }
            .padding(18)
        }
    }

    private func fmtTime(_ s: Double) -> String {
        let i = max(0, Int(s.rounded()))
        return String(format: "%d:%02d", i / 60, i % 60)
    }
}

/// Equatable isolated lyrics stage:
/// - Never re-renders on `currentTime` 0.25s ticks (only updates when `activeIndex`, `lyrics`, or visibility changes).
/// - Uses zero per-row `.blur()` passes (pure opacity/weight hierarchy) for locked 60/120fps scrolling & transitions.
/// - Immediately snaps centered onto `activeIndex` when opened or when lyrics arrive.
private struct NowPlayingLyricsStage: View, Equatable {
    let trackId: String
    let trackTitle: String
    let trackArtist: String
    let lyrics: [MusicLyricLine]
    let activeIndex: Int
    let isVisible: Bool
    let showTranslation: Bool
    let showRomaji: Bool
    let onToggleTranslation: () -> Void
    let onToggleRomaji: () -> Void
    let onCloseToCover: (() -> Void)?
    let onSeek: (Double) -> Void

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.trackId == rhs.trackId
            && lhs.activeIndex == rhs.activeIndex
            && lhs.isVisible == rhs.isVisible
            && lhs.showTranslation == rhs.showTranslation
            && lhs.showRomaji == rhs.showRomaji
            && lhs.lyrics.count == rhs.lyrics.count
            && lhs.lyrics.first?.text == rhs.lyrics.first?.text
    }

    private func jumpToActive(_ proxy: ScrollViewProxy, animated: Bool) {
        guard !lyrics.isEmpty else { return }
        let target = min(max(0, activeIndex), lyrics.count - 1)
        let targetId = lyrics[target].id
        if animated {
            withAnimation(.easeOut(duration: 0.30)) {
                proxy.scrollTo(targetId, anchor: .center)
            }
        } else {
            var tx = Transaction()
            tx.disablesAnimations = true
            withTransaction(tx) {
                proxy.scrollTo(targetId, anchor: .center)
            }
        }
    }

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                if let onCloseToCover {
                    Button(action: onCloseToCover) {
                        HStack(spacing: 4) {
                            Image(systemName: "photo")
                                .font(.system(size: 11, weight: .semibold))
                            Text("封面")
                                .font(.system(size: 11.5, weight: .semibold))
                        }
                        .foregroundStyle(.white.opacity(0.75))
                        .padding(.horizontal, 10)
                        .frame(height: 24)
                        .background(Capsule().fill(Color.white.opacity(0.14)))
                    }
                    .buttonStyle(.plain)
                }
                Spacer()
                Button(action: onToggleTranslation) {
                    Text("译")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(showTranslation ? .black : .white.opacity(0.75))
                        .frame(width: 28, height: 22)
                        .background(Capsule().fill(showTranslation ? YMTheme.lime : Color.white.opacity(0.14)))
                }
                .buttonStyle(.plain)
                Button(action: onToggleRomaji) {
                    Text("罗")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(showRomaji ? .black : .white.opacity(0.75))
                        .frame(width: 28, height: 22)
                        .background(Capsule().fill(showRomaji ? YMTheme.lime : Color.white.opacity(0.14)))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 24)

            if lyrics.isEmpty {
                VStack(spacing: 8) {
                    Spacer()
                    Text("暂无同步歌词")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(.white.opacity(0.62))
                    Text("\(trackTitle) — \(trackArtist)")
                        .font(.system(size: 13))
                        .foregroundStyle(.white.opacity(0.45))
                    Spacer()
                }
                .contentShape(Rectangle())
                .onTapGesture { onCloseToCover?() }
            } else {
                ScrollViewReader { proxy in
                    ScrollView(showsIndicators: false) {
                        VStack(alignment: .leading, spacing: 18) {
                            ForEach(lyrics) { line in
                                let dist = abs(line.id - activeIndex)
                                let isCurrent = dist == 0
                                let textAlpha: Double = isCurrent ? 1.0 : (dist == 1 ? 0.66 : (dist == 2 ? 0.42 : 0.24))
                                Button { onSeek(line.timeSec) } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(line.text)
                                            .font(.system(size: isCurrent ? 21 : 17.5, weight: isCurrent ? .bold : .medium))
                                            .foregroundStyle(isCurrent ? YMTheme.lime : .white.opacity(textAlpha))
                                        if showTranslation, let tr = line.translation, !tr.isEmpty {
                                            Text(tr)
                                                .font(.system(size: 13))
                                                .foregroundStyle(.white.opacity(isCurrent ? 0.80 : textAlpha * 0.82))
                                        }
                                        if showRomaji, let ro = line.romaji, !ro.isEmpty {
                                            Text(ro)
                                                .font(.system(size: 11.5))
                                                .foregroundStyle(.white.opacity(isCurrent ? 0.68 : textAlpha * 0.72))
                                        }
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                }
                                .buttonStyle(.plain)
                                .id(line.id)
                            }
                        }
                        .padding(.horizontal, 24)
                        .padding(.vertical, 120)
                    }
                    .onAppear {
                        if isVisible {
                            jumpToActive(proxy, animated: false)
                            DispatchQueue.main.async { jumpToActive(proxy, animated: false) }
                        }
                    }
                    .compatOnChange(of: isVisible) { nowVisible in
                        if nowVisible {
                            jumpToActive(proxy, animated: false)
                            DispatchQueue.main.async { jumpToActive(proxy, animated: false) }
                        }
                    }
                    .compatOnChange(of: trackId) { _ in
                        if isVisible {
                            jumpToActive(proxy, animated: false)
                            DispatchQueue.main.async { jumpToActive(proxy, animated: false) }
                        }
                    }
                    .compatOnChange(of: lyrics.count) { _ in
                        if isVisible {
                            jumpToActive(proxy, animated: false)
                        }
                    }
                    .compatOnChange(of: activeIndex) { _ in
                        if isVisible {
                            jumpToActive(proxy, animated: true)
                        }
                    }
                }
            }
        }
    }
}
