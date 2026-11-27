import SwiftUI

/// Floating controls of 直播: search in channels, groups (locked ones ask for their password).
struct LiveBar: View {
    @ObservedObject var pane: LivePane
    @State private var unlocking: LockedGroup?

    private struct LockedGroup: Identifiable { let group: LiveGroup; var id: String { group.name } }

    var body: some View {
        VStack(spacing: 8) {
            TVBoxSearchField(placeholder: pane.total > 0 ? "搜索 \(pane.total) 个频道" : "搜索频道", text: $pane.query, id: "tvbox.live.search")
                .padding(.horizontal, 16)
            if pane.query.trimmed.isEmpty, pane.tabs.count > 2 {
                ChipRow(items: pane.tabs, selection: Binding(get: { pane.tab }, set: pick), label: title, neutral: { $0 == .all }, idPrefix: "tvbox.group")
                    .frame(height: 36)
            }
        }
        .sheet(item: $unlocking) { l in
            LiveUnlockSheet(pane: pane, group: l.group) { unlocking = nil }
        }
        .tvboxShot("unlock", id: ObjectIdentifier(pane)) { unlocking = pane.locked.values.first.map { LockedGroup(group: $0) } }
    }

    private func title(_ t: LivePane.Tab) -> String {
        switch t {
        case .all: return "全部"
        case .favorites: return "收藏"
        case .group(let g): return pane.locked[g] == nil ? g : "\(g)（已锁）"
        }
    }

    private func pick(_ t: LivePane.Tab) {
        if case .group(let name) = t, let g = pane.locked[name] { unlocking = LockedGroup(group: g) } else { pane.select(t) }
    }
}

/// Channels of one live source; a tap plays them in the shared player.
struct LivePage: View {
    @ObservedObject var pane: LivePane
    let live: TVBoxLiveRef
    @ObservedObject private var store = TVBoxStore.shared
    @Environment(\.layoutMetrics) private var layout

    var body: some View {
        let favorites = Set(store.favoriteChannels)
        let series = store.liveSeries(live)
        let colCount = layout.isUltra ? 3 : (layout.tier != .compact ? 2 : 1)
        ScrollView {
            if colCount > 1 {
                let gridCols = Array(repeating: GridItem(.flexible(), spacing: 18, alignment: .top), count: colCount)
                LazyVGrid(columns: gridCols, spacing: 2) {
                    ForEach(pane.shown) { r in
                        LiveChannelRow(row: r, pane: pane, series: series, logo: live.live.logo, favorite: favorites.contains(r.channel.name))
                    }
                }
                .padding(.horizontal, layout.pagePadding - 16)
                .padding(.top, 4).padding(.bottom, 24)
                status
            } else {
                LazyVStack(spacing: 0) {
                    ForEach(pane.shown) { r in
                        LiveChannelRow(row: r, pane: pane, series: series, logo: live.live.logo, favorite: favorites.contains(r.channel.name))
                    }
                    status
                }
                .padding(.top, 4).padding(.bottom, 24)
            }
        }
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.immediately)
        .refreshable { await pane.reload() }
        .task(id: ObjectIdentifier(pane)) { pane.ensure() }
        .compatOnChange(of: store.favoriteChannels) { pane.favoritesChanged() }
        .compatOnChange(of: store.unlocked(pane.uid)) { pane.rebuild() }
        .accessibilityIdentifier("tvbox.live")
    }

    @ViewBuilder private var status: some View {
        if pane.shown.isEmpty {
            if let e = pane.error {
                DSEmptyState("加载失败", systemImage: "wifi.exclamationmark", message: e, actionTitle: "重试") { pane.retry() }
            } else if pane.loading || !pane.loaded {
                VStack(spacing: 14) { ForEach(0..<8, id: \.self) { _ in skeleton } }.padding(.horizontal, 16).padding(.top, 6)
            } else if pane.tab == .favorites, pane.query.trimmed.isEmpty {
                DSEmptyState("还没有收藏的频道", systemImage: "star", message: "点频道右侧的星标，就会出现在这里。")
            } else {
                DSEmptyState("没有频道", systemImage: "antenna.radiowaves.left.and.right")
            }
        }
    }

    private var skeleton: some View {
        HStack(spacing: 12) {
            ShimmerBlock(corner: 10).frame(width: 56, height: 40)
            VStack(alignment: .leading, spacing: 7) {
                ShimmerBlock(corner: 5).frame(width: 120, height: 13)
                ShimmerBlock(corner: 5).frame(width: 180, height: 10)
            }
            Spacer(minLength: 0)
        }
    }
}

private struct LiveChannelRow: View {
    let row: LiveRow
    let pane: LivePane
    let series: HGSeries
    /// Template of the list, for channels without a logo of their own.
    let logo: String
    let favorite: Bool
    @State private var program = ""

    var body: some View {
        let ch = row.channel
        HStack(spacing: 4) {
            Button { pane.open(row, in: series) } label: {
                HStack(spacing: 12) {
                    LiveLogo(url: address, name: ch.name)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(ch.name).dsFont(16, .medium).foregroundStyle(STVTheme.text).lineLimit(1)
                        Text(program.isEmpty ? place : program).dsFont(12, relativeTo: .caption)
                            .foregroundStyle(program.isEmpty ? STVTheme.tertiary : STVTheme.secondary).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.leading, 16).frame(minHeight: 62)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressScale(scale: 0.985))
            .accessibilityIdentifier("tvbox.channel.\(row.number)")
            Button { DS.tick(); TVBoxStore.shared.toggleFavoriteChannel(ch.name) } label: {
                Image(systemName: favorite ? "star.fill" : "star").font(.system(size: 16, weight: .medium))
                    .foregroundStyle(favorite ? STVTheme.accentWarm : STVTheme.tertiary)
                    .frame(width: 44, height: 44).contentShape(Rectangle())
            }
            .buttonStyle(PressScale())
            .accessibilityLabel(favorite ? "取消收藏" : "收藏")
            .accessibilityIdentifier("tvbox.star.\(row.number)")
            .padding(.trailing, 8)
        }
        .overlay(alignment: .bottom) { Hairline().padding(.leading, 84).padding(.trailing, 16) }
        .onAppear { if let known = pane.knownProgram(row), known.text != program { program = known.text } }
        .task(id: "\(row.id)|\(ch.name)|\(pane.hasGuide)") { await follow() }
    }

    /// Asks again when the programme is over.
    private func follow() async {
        while pane.hasGuide, let p = await pane.program(row), !Task.isCancelled {
            if p.text != program { program = p.text }
            try? await Task.sleep(nanoseconds: UInt64(max(p.until - Catchup.nowMs, 1_000)) * 1_000_000)
            if Task.isCancelled { return }
        }
    }

    private var place: String { row.channel.urls.count > 1 ? "\(row.group) · \(row.channel.urls.count) 条线路" : row.group }

    private var address: String {
        if !row.channel.logo.isEmpty { return row.channel.logo }
        return logo.contains("{name}") ? EpgParser.url(logo, row.channel.guideName, "", row.channel.tvgId) : ""
    }
}

/// Channel logo fitted into its plate (artwork everywhere else fills its frame), the name until there is one.
private struct LiveLogo: View {
    let url: String
    let name: String
    @State private var image: UIImage?

    init(url: String, name: String) {
        self.url = url
        self.name = name
        _image = State(initialValue: url.isEmpty ? nil : CoverStore.shared.cachedImage(url: url, maxPixelSize: CoverPixels.thumb))
    }

    var body: some View {
        RoundedRectangle(cornerRadius: 10, style: .continuous).fill(STVTheme.glass)
            .overlay {
                if let image { Image(uiImage: image).resizable().scaledToFit().padding(5) }
                else { Text(name.prefix(4)).font(.system(size: 11, weight: .semibold)).foregroundStyle(STVTheme.secondary).lineLimit(1).minimumScaleFactor(0.7).padding(.horizontal, 4) }
            }
            .overlay { RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(STVTheme.hairline, lineWidth: 1) }
            .frame(width: 56, height: 40)
            .accessibilityHidden(true)
            .task(id: url) {
                guard !url.isEmpty else { if image != nil { image = nil }; return }
                let next = CoverStore.shared.cachedImage(url: url, maxPixelSize: CoverPixels.thumb)
                if image !== next { image = next }
                if next != nil { return }
                let loaded = try? await CoverStore.shared.image(url: url, maxPixelSize: CoverPixels.thumb)
                if !Task.isCancelled { image = loaded }
            }
    }
}

/// Password of a locked group; stays open until it is right.
private struct LiveUnlockSheet: View {
    @ObservedObject var pane: LivePane
    let group: LiveGroup
    let done: () -> Void
    @State private var pass = ""
    @State private var wrong = false

    var body: some View {
        DSSheet("「\(group.name)」已加锁", subtitle: "\(group.channels.count) 个频道") {
            VStack(alignment: .leading, spacing: 8) {
                DSTextField("输入分组密码", text: $pass, icon: "lock", id: "tvbox.unlock.pass")
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
                    .submitLabel(.done)
                    .onSubmit(open)
                if wrong { Text("密码不正确").dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.accentWarm) }
            }
            .padding(.horizontal, 20)
            .compatOnChange(of: pass) { if wrong { wrong = false } }
        } footer: {
            DSSheetFooter {
                Button(action: open) { PillButtonLabel(title: "打开", expand: true) }
                    .buttonStyle(PressScale(scale: 0.98))
                    .disabled(pass.trimmed.isEmpty).opacity(pass.trimmed.isEmpty ? 0.4 : 1)
                    .accessibilityIdentifier("tvbox.unlock.open")
            }
        }
        .dsSheetChrome([.height(270)])
    }

    private func open() {
        guard pass.trimmed == group.pass else {
            withAnimation(DS.press) { wrong = true }
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            return
        }
        TVBoxStore.shared.unlock(pane.uid, group.name)
        pane.rebuild()
        pane.select(.group(group.name))
        done()
    }
}
