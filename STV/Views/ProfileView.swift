import SwiftUI

struct ProfileView: View {
    @State private var message: String?
    @State private var showHongguoLogin = false
    @State private var backupBusy: String?
    @State private var refreshTick = 0

    @ObservedObject private var webdav = WebDAVSync.shared
    @Environment(\.layoutMetrics) private var layout

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text("我的").dsFont(32, .heavy, relativeTo: .largeTitle).foregroundStyle(STVTheme.text)
                    .padding(.horizontal, layout.tier == .compact ? 20 : layout.pagePadding)
                    .accessibilityAddTraits(.isHeader)

                if layout.tier == .expanded {
                    HStack(alignment: .top, spacing: 24) {
                        VStack(alignment: .leading, spacing: 22) {
                            HongguoAccountCard(message: $message, login: $showHongguoLogin)
                            ProfileLibrary(compactGrid: true)
                        }
                        .frame(minWidth: 330, idealWidth: 390, maxWidth: 430)

                        VStack(alignment: .leading, spacing: 22) {
                            ProfileLinks(refreshTick: refreshTick)
                            backupAndMaintenance
                            versionFooter
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.horizontal, 12)
                    .frame(maxWidth: 1240)
                    .frame(maxWidth: .infinity)
                } else {
                    VStack(alignment: .leading, spacing: 24) {
                        HongguoAccountCard(message: $message, login: $showHongguoLogin)
                        ProfileLibrary()
                        ProfileLinks(refreshTick: refreshTick)
                        backupAndMaintenance
                        versionFooter
                    }
                    .frame(maxWidth: layout.tier == .medium ? 740 : .infinity)
                    .frame(maxWidth: .infinity)
                }
            }
            .padding(.top, 8).padding(.bottom, 24)
            .compatScrollContent()
        }
        .scrollIndicators(.hidden)
        .dsStatusFade()
        .dsAmbient(cool: true)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $showHongguoLogin) { HongguoLoginView() }
        .dsToast($message, icon: "checkmark.circle.fill", bottom: 16)
    }

    @ViewBuilder private var backupAndMaintenance: some View {
        DSSection(
            "数据备份与迁移",
            footer: "支持 WebDAV 多端同步进度与插件；换证书自签或换机前也可导出本地备份，退到后台会自动写入系统「文件 → 我的 iPhone → i短剧 → AutoBackup」防掉签。",
            separatorInset: 58
        ) {
            DSLinkRow("WebDAV 多端同步", route: Route.webdav, icon: "icloud.and.arrow.up", value: webdav.statusBadge)
            DSRow(
                backupBusy == "export" ? "正在打包备份…" : "导出全量备份",
                icon: "square.and.arrow.up",
                subtitle: "插件、片源、观看历史、收藏、书源与小说"
            ) { Task { await exportBackup() } }
            DSRow(
                backupBusy == "import" ? "正在恢复备份…" : "从备份文件恢复",
                icon: "arrow.down.doc",
                subtitle: "导入 .idjbackup 无损合并恢复"
            ) { Task { await importBackup() } }
        }
        DSSection("维护", separatorInset: 58) {
            maintenance("重置红果设备注册", "arrow.counterclockwise") { await HongguoClient.shared.resetDevice(); message = "已重置红果设备" }
            maintenance("重置黄果访客会话", "person.badge.key") { await HuangguoClient.shared.resetSession(); message = "已清除黄果会话" }
            maintenance("清空封面缓存", "photo.stack") { await CoverStore.shared.clear(); message = "封面缓存已清空" }
            maintenance("清空播放缓存", "trash") {
                await Task.detached { try? FileManager.default.removeItem(at: EpisodePlaybackLoader.cacheDir) }.value
                await HongguoClient.shared.clearResolveCache()
                message = "播放缓存已清空"
            }
        }
    }

    private var versionFooter: some View {
        Text("所有数据由本机直连上游，观看记录和收藏只保存在本机。\n\(Self.versionText)")
            .dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.tertiary).lineSpacing(3).padding(.horizontal, 20)
    }

    static var versionText: String {
        let i = Bundle.main.infoDictionary
        return "i短剧 \(i?["CFBundleShortVersionString"] as? String ?? "") (\(i?["CFBundleVersion"] as? String ?? ""))"
    }

    private func maintenance(_ t: String, _ icon: String, _ action: @escaping () async -> Void) -> some View {
        DSRow(t, icon: icon) { Task { await action() } }
    }

    private func exportBackup() async {
        guard backupBusy == nil else { return }
        backupBusy = "export"
        defer { backupBusy = nil }
        do {
            let url = try await AppBackup.exportTemporaryFile()
            await SystemFilePicker.shareFile(url)
        } catch {
            message = "备份失败：\(error.localizedDescription)"
        }
    }

    private func importBackup() async {
        guard backupBusy == nil else { return }
        let urls = await SystemFilePicker.pick(types: [.data, .json, .item])
        guard let url = urls.first else { return }
        backupBusy = "import"
        defer { backupBusy = nil }
        do {
            let loaded = try await Task.detached(priority: .userInitiated) {
                try SystemFilePicker.readAndClean(url, maxBytes: 128 << 20)
            }.value
            let summary = try await AppBackup.restore(from: loaded.data)
            refreshTick += 1
            message = summary.toastText
        } catch CocoaError.fileReadTooLarge {
            message = "备份文件过大（超过 128 MB）"
        } catch {
            message = error.localizedDescription
        }
    }
}

// Every block observes only what it shows: playback progress, plugin installs or a profile refresh re-render one block.

private struct HongguoAccountCard: View {
    @Binding var message: String?
    @Binding var login: Bool
    @ObservedObject private var account = HongguoWebAccount.shared

    var body: some View {
        HStack(spacing: 12) {
            if let p = account.profile {
                if !p.avatarUrl.isEmpty {
                    CoverView(url: p.avatarUrl, corner: 24, maxPixelSize: CoverPixels.thumb).frame(width: 48, height: 48)
                } else {
                    Circle().fill(STVTheme.accentSoft).frame(width: 48, height: 48)
                        .overlay(Text(String(p.nickname.prefix(1))).font(.headline).foregroundStyle(STVTheme.accent))
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(p.nickname).dsFont(17, .semibold, relativeTo: .headline).foregroundStyle(STVTheme.text).lineLimit(1)
                    Text(account.lastSyncNote ?? "已连接红果短剧，观看进度自动同步")
                        .dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
                Button {
                    account.logout()
                    message = "已退出红果短剧账号"
                } label: { MiniButtonLabel(title: "退出", tint: STVTheme.secondary, ground: STVTheme.glass) }
                    .buttonStyle(PressScale())
            } else {
                IconWell(systemName: "qrcode.viewfinder", size: 48)
                VStack(alignment: .leading, spacing: 3) {
                    Text("红果短剧扫码登录").dsFont(17, .semibold, relativeTo: .headline).foregroundStyle(STVTheme.text)
                    Text("扫码连接账号，同步短剧观看进度").dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary)
                }
                Spacer(minLength: 0)
                Button { login = true } label: { PillButtonLabel(title: "去扫码", small: true) }.buttonStyle(PressScale())
            }
        }
        .padding(14)
        .dsCard(20)
        .padding(.horizontal, 16)
        .task { if account.isLoggedIn { _ = await account.refreshProfile() } }
    }
}

private struct ProfileLibrary: View {
    var compactGrid = false
    @ObservedObject private var library = LibraryStore.shared
    @Environment(\.layoutMetrics) private var layout
    var body: some View {
        let watching = Array(library.continueWatching.prefix(10)), favorites = library.favorites
        let preview = Array(favorites.prefix(6))
        let landscape = preview.first?.isLandscapeCover ?? false
        let cols: [GridItem] = compactGrid
            ? Array(repeating: GridItem(.flexible(), spacing: 10, alignment: .top), count: landscape ? 2 : 3)
            : (landscape ? layout.wideGridItems(spacing: 10) : layout.posterGridItems(spacing: 10))
        if !watching.isEmpty { ContinueRow(items: watching) }
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "我的收藏") {
                NavigationLink(value: Route.favorites) { MoreLabel(title: favorites.isEmpty ? "管理" : "管理 · \(favorites.count)") }.buttonStyle(.plain)
            }
            if favorites.isEmpty {
                Text("在详情页或播放页点 ♥ 收藏").dsFont(14, relativeTo: .subheadline).foregroundStyle(STVTheme.secondary).padding(.horizontal, 16)
            } else {
                LazyVGrid(columns: cols, spacing: 14) {
                    ForEach(preview) { s in
                        FavoriteManageCell(series: s, landscape: landscape) {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            withAnimation(DS.select) { library.removeFavorite(s) }
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        DSSection(separatorInset: 58) {
            DSLinkRow("观看历史", route: Route.history, icon: "clock.arrow.circlepath", value: watching.isEmpty ? nil : "\(library.continueWatching.count)")
            DSLinkRow("剧星榜", route: Route.actorRank, icon: "star.circle")
            DSLinkRow("我的收藏", route: Route.favorites, icon: "heart", value: "\(favorites.count)")
        }
    }
}

private struct ProfileLinks: View {
    var refreshTick: Int = 0
    @ObservedObject private var gate = SourceGate.shared
    /// Counts of stores that load from disk: read after the first frame instead of while 我的 is being built.
    @State private var counts = Counts()
    private struct Counts: Equatable { var channels: String?; var manga: String?; var downloads: String?; var plugins: String? }

    var body: some View {
        Group {
            DSSection(separatorInset: 58) {
                DSLinkRow("Telegram 频道", route: Route.channels, icon: "paperplane", value: counts.channels)
                DSLinkRow("漫画", route: Route.mangaHome, icon: "book", value: counts.manga)
                DSLinkRow("下载管理", route: Route.downloads, icon: "arrow.down.circle", value: counts.downloads)
            }
            DSSection(separatorInset: 58) {
                DSLinkRow("片源管理", route: Route.sources, icon: "square.stack.3d.down.right", value: gate.enabledCount > 0 ? "已启用 \(gate.enabledCount) 个" : "红果 · 黄果")
                DSLinkRow("插件", route: Route.plugins, icon: "puzzlepiece.extension", value: counts.plugins)
                DSLinkRow("开源许可", route: Route.licenses, icon: "doc.text")
            }
        }
        .task(id: refreshTick) {
            await Task.yield()
            let plugins = PluginStore.shared.visiblePlugins.count + CMSStore.shared.visibleSites.count
            counts = Counts(channels: "\(TGStore.shared.visibleChannels.count) 个",
                            manga: MangaStore.shared.favorites.isEmpty ? nil : "收藏 \(MangaStore.shared.favorites.count)",
                            downloads: TGDownloads.shared.visible.isEmpty ? nil : "\(TGDownloads.shared.visible.count)",
                            plugins: plugins > 0 ? "\(plugins) 个" : nil)
        }
    }
}
