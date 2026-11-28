import XCTest
@testable import STV

final class MusicModuleTests: XCTestCase {

    // MARK: - AppAudioArbiter Smart Resume Policy

    func testSmartResumeWithinThresholdAutoResumes() {
        XCTAssertTrue(
            AppAudioArbiter.shouldAutoResumeMusic(
                policy: .smart,
                wasPlayingBeforeInterrupt: true,
                elapsedSeconds: 45,
                appInForeground: true,
                headphoneUnplugged: false
            )
        )
        XCTAssertTrue(
            AppAudioArbiter.shouldAutoResumeMusic(
                policy: .smart,
                wasPlayingBeforeInterrupt: true,
                elapsedSeconds: 180,
                appInForeground: true,
                headphoneUnplugged: false
            )
        )
    }

    func testSmartResumeOverThresholdStaysPaused() {
        XCTAssertFalse(
            AppAudioArbiter.shouldAutoResumeMusic(
                policy: .smart,
                wasPlayingBeforeInterrupt: true,
                elapsedSeconds: 181,
                appInForeground: true,
                headphoneUnplugged: false
            )
        )
    }

    func testAlwaysAndManualPolicies() {
        XCTAssertTrue(
            AppAudioArbiter.shouldAutoResumeMusic(
                policy: .always,
                wasPlayingBeforeInterrupt: true,
                elapsedSeconds: 3600,
                appInForeground: true,
                headphoneUnplugged: false
            )
        )
        XCTAssertFalse(
            AppAudioArbiter.shouldAutoResumeMusic(
                policy: .manual,
                wasPlayingBeforeInterrupt: true,
                elapsedSeconds: 5,
                appInForeground: true,
                headphoneUnplugged: false
            )
        )
    }

    func testHeadphoneUnplugOrBackgroundNeverAutoResumes() {
        XCTAssertFalse(
            AppAudioArbiter.shouldAutoResumeMusic(
                policy: .always,
                wasPlayingBeforeInterrupt: true,
                elapsedSeconds: 10,
                appInForeground: true,
                headphoneUnplugged: true
            )
        )
        XCTAssertFalse(
            AppAudioArbiter.shouldAutoResumeMusic(
                policy: .smart,
                wasPlayingBeforeInterrupt: true,
                elapsedSeconds: 10,
                appInForeground: false,
                headphoneUnplugged: false
            )
        )
        XCTAssertFalse(
            AppAudioArbiter.shouldAutoResumeMusic(
                policy: .smart,
                wasPlayingBeforeInterrupt: false,
                elapsedSeconds: 10,
                appInForeground: true,
                headphoneUnplugged: false
            )
        )
    }

    // MARK: - LRC Lyrics Parser & Yueqian Hook

    func testLyricsParserMergesTranslationAndRomajiAndSkipsCredits() {
        let mainLrc = """
        [00:00.00]作词：黄伟文
        [00:01.00]作曲：雷颂德
        [00:12.50]谁都只得那双手 靠拥抱亦难任你拥有
        [00:20.00]要拥有必先懂失去怎接受
        """
        let transLrc = """
        [00:12.50]Everyone only has two hands
        [00:20.00]To own you must first learn to lose
        """
        let romaLrc = """
        [00:12.50]seoi4 dou1 zi2 dak1 naa5 soeng1 sau2
        """

        let lines = MusicLyricsParser.parse(lrc: mainLrc, translation: transLrc, romaji: romaLrc)
        XCTAssertEqual(lines.count, 4)
        XCTAssertEqual(lines[2].timeSec, 12.5, accuracy: 0.01)
        XCTAssertEqual(lines[2].text, "谁都只得那双手 靠拥抱亦难任你拥有")
        XCTAssertEqual(lines[2].translation, "Everyone only has two hands")
        XCTAssertEqual(lines[2].romaji, "seoi4 dou1 zi2 dak1 naa5 soeng1 sau2")

        let hook = MusicLyricsParser.pickYueqianHook(from: lines)
        XCTAssertNotNil(hook)
        XCTAssertFalse(hook?.contains("作词") ?? true)
        XCTAssertFalse(hook?.contains("作曲") ?? true)
    }

    // MARK: - Cross-Source Fuzzy Matching

    func testCrossSourceBestMatchPrefersExactTitleAndArtist() {
        let target = MusicTrack(
            id: "netease:1",
            source: .netease,
            sourceId: "1",
            title: "富士山下",
            artist: "陈奕迅",
            album: "What's Going On...?",
            coverURL: "",
            durationSec: 259,
            qualityLabel: "FLAC",
            entitlement: .full
        )
        let wrong = MusicTrack(
            id: "qq:2",
            source: .qq,
            sourceId: "2",
            title: "富士山下 (Live 翻唱版)",
            artist: "某翻唱歌手",
            album: "Cover",
            coverURL: "",
            durationSec: 240,
            qualityLabel: "HQ",
            entitlement: .full
        )
        let right = MusicTrack(
            id: "qq:3",
            source: .qq,
            sourceId: "3",
            title: "富士山下",
            artist: "陈奕迅",
            album: "What's Going On...?",
            coverURL: "",
            durationSec: 259,
            qualityLabel: "FLAC",
            entitlement: .full
        )
        let picked = MusicProviderHub.bestMatch(in: [wrong, right], target: target)
        XCTAssertEqual(picked?.id, "qq:3")
    }

    // MARK: - Store Chart Clone Deduplication

    @MainActor
    func testCloneChartAsPlaylistDeduplicatesTracks() {
        let store = MusicStore.shared
        let t1 = MusicTrack(id: "t1", source: .netease, sourceId: "1", title: "晴天", artist: "周杰伦", album: "叶惠美", coverURL: "", durationSec: 269, qualityLabel: "FLAC", entitlement: .full)
        let t1Dup = MusicTrack(id: "t1-qq", source: .qq, sourceId: "99", title: "晴天", artist: "周杰伦", album: "叶惠美", coverURL: "", durationSec: 269, qualityLabel: "HQ", entitlement: .full)
        let t2 = MusicTrack(id: "t2", source: .netease, sourceId: "2", title: "七里香", artist: "周杰伦", album: "七里香", coverURL: "", durationSec: 299, qualityLabel: "FLAC", entitlement: .full)

        let name = "单元测试榜单_\(UUID().uuidString.prefix(6))"
        let res = store.cloneChartAsPlaylist(name: name, tracks: [t1, t1Dup, t2])
        XCTAssertEqual(res.added, 2)
        XCTAssertEqual(res.skipped, 1)

        if let created = store.playlists.first(where: { $0.name == name }) {
            XCTAssertEqual(created.tracks.count, 2)
            store.deletePlaylist(id: created.id)
        } else {
            XCTFail("Expected cloned playlist to be saved")
        }
    }

    // MARK: - Home Feature Tab Toggles & Global Mini Bar Immersive Suppression

    @MainActor
    func testHomeFeatureTabsCanBeToggledAndRestored() {
        let gate = SourceGate.shared
        let orig = (gate.showChannelsTab, gate.showMangaTab, gate.showNovelTab, gate.showMusicTab)
        defer {
            gate.setShowChannelsTab(orig.0)
            gate.setShowMangaTab(orig.1)
            gate.setShowNovelTab(orig.2)
            gate.setShowMusicTab(orig.3)
        }
        // Music now lives in the bottom TabBar (not Home's top strip).
        XCTAssertFalse(HomeSource.defaultAll.contains { $0.label == "音乐" })

        gate.setShowMangaTab(false)
        XCTAssertFalse(HomeSource.defaultAll.contains(.manga))
        gate.setShowMangaTab(true)
        XCTAssertTrue(HomeSource.defaultAll.contains(.manga))

        gate.setShowMusicTab(false)
        XCTAssertEqual(RootTabView.visibleItems(showMusic: gate.showMusicTab).count, 4)
        XCTAssertFalse(RootTabView.visibleItems(showMusic: gate.showMusicTab).contains { $0.id == RootTabView.musicTabId })
        gate.setShowMusicTab(true)
        XCTAssertEqual(RootTabView.visibleItems(showMusic: gate.showMusicTab).count, 5)
        XCTAssertTrue(RootTabView.visibleItems(showMusic: gate.showMusicTab).contains { $0.id == RootTabView.musicTabId })

        // Verify direct no-proxy configuration (zero HTTP/HTTPS/SOCKS system proxy).
        let proxyDict = MusicDirectNet.session.configuration.connectionProxyDictionary
        XCTAssertEqual(proxyDict?["HTTPEnable"] as? Int, 0)
        XCTAssertEqual(proxyDict?["HTTPSEnable"] as? Int, 0)
        XCTAssertEqual(proxyDict?["SOCKSEnable"] as? Int, 0)
    }

    @MainActor
    func testGlobalMiniBarStaysVisibleOnSecondaryPagesAndHidesOnlyInImmersiveSurfaces() {
        let engine = MusicPlayerEngine.shared
        let track = MusicTrack(id: "netease:1973665667", source: .netease, sourceId: "1973665667", title: "海屿你", artist: "马也_Crabbit", album: "海屿你", coverURL: "", durationSec: 210, qualityLabel: "HQ 320k", entitlement: .full)
        engine.play(track: track, openFull: false)
        XCTAssertTrue(engine.shouldShowGlobalMiniBar)

        engine.enterImmersive("video")
        XCTAssertFalse(engine.shouldShowGlobalMiniBar)
        engine.leaveImmersive("video")
        XCTAssertTrue(engine.shouldShowGlobalMiniBar)

        engine.enterImmersive("novelReader")
        XCTAssertFalse(engine.shouldShowGlobalMiniBar)
        engine.leaveImmersive("novelReader")
        XCTAssertTrue(engine.shouldShowGlobalMiniBar)

        engine.dismissMiniBar()
    }

    // MARK: - Live AVPlayer Playback Progress (Zero 0:00 Stall Proof) & Artist/Album/QR APIs

    @MainActor
    func testLiveAudioPlaybackAdvancesPastZeroAndFetchesArtistAlbumAndQR() async {
        let engine = MusicPlayerEngine.shared
        let track = MusicTrack(
            id: "netease:1973665667",
            source: .netease,
            sourceId: "1973665667",
            title: "海屿你",
            artist: "马也_Crabbit",
            album: "海屿你",
            coverURL: "",
            durationSec: 210,
            qualityLabel: "HQ 320k",
            entitlement: .full,
            artistId: "13288861",
            albumId: "150006421"
        )
        engine.play(track: track, openFull: false)
        var advanced = false
        for _ in 0..<24 {
            try? await Task.sleep(nanoseconds: 250_000_000)
            if engine.currentTime > 0.35 {
                advanced = true
                break
            }
        }
        let playedSec = engine.currentTime
        let diag = engine.debugPlaybackState()
        engine.dismissMiniBar()
        XCTAssertTrue(advanced, "Expected real AVPlayer playback to advance past 0.35s, got \(playedSec)s | diag=\(diag)")

        async let qrKey = MusicProviderHub.shared.createNetEaseQRKey()
        async let artist = MusicProviderHub.shared.fetchArtistDetail(ref: MusicArtistRef(name: "孙燕姿", artistId: "9272", source: .netease, coverURL: nil))
        async let album = MusicProviderHub.shared.fetchAlbumDetail(ref: MusicAlbumRef(name: "海屿你", albumId: "150006421", artist: "马也_Crabbit", artistId: "13288861", source: .netease, coverURL: nil))

        let (key, artDetail, albDetail) = await (qrKey, artist, album)
        XCTAssertNotNil(key)
        XCTAssertFalse(artDetail.hotSongs.isEmpty, "Artist detail should return hot songs")
        XCTAssertFalse(artDetail.albums.isEmpty, "Artist detail should return albums")
        XCTAssertFalse(albDetail.tracks.isEmpty, "Album detail should return tracks")
    }

    // MARK: - Search History Tombstone Merge (Deleted Entries Never Resurrect)

    func testSearchHistoryTombstonePreventsResurrectionOnSync() {
        // 1. User deleted "霸总" locally at t=2000; older remote backup still has ["霸总", "穿越"].
        let localMeta = SearchHistoryMeta(added: ["穿越": 1000], deleted: ["霸总": 2000], clearedAt: 0)
        let remoteMeta = SearchHistoryMeta(added: ["霸总": 900, "穿越": 1000], deleted: [:], clearedAt: 0)
        let (merged1, meta1) = LibraryStore.mergeSearchHistory(
            localItems: ["穿越"],
            localMeta: localMeta,
            incomingItems: ["霸总", "穿越"],
            incomingMeta: remoteMeta,
            limit: 10
        )
        XCTAssertEqual(merged1, ["穿越"], "Deleted term must not resurrect from older remote snapshot")
        XCTAssertEqual(meta1.deleted["霸总"], 2000)

        // 2. User clicked 全部删除 locally at t=3000; legacy remote backup (no metadata) still has old items.
        let clearedLocal = SearchHistoryMeta(added: [:], deleted: ["穿越": 3000], clearedAt: 3000)
        let legacyRemote = SearchHistoryMeta()
        let (merged2, _) = LibraryStore.mergeSearchHistory(
            localItems: [],
            localMeta: clearedLocal,
            incomingItems: ["霸总", "穿越", "重生"],
            incomingMeta: legacyRemote,
            limit: 10
        )
        XCTAssertTrue(merged2.isEmpty, "Cleared search history must stay empty against legacy remote backup")

        // 3. Re-searching a previously deleted term later (t=4000 > deletedAt=2000) restores it cleanly.
        let researchedRemote = SearchHistoryMeta(added: ["霸总": 4000], deleted: [:], clearedAt: 0)
        let (merged3, _) = LibraryStore.mergeSearchHistory(
            localItems: [],
            localMeta: clearedLocal,
            incomingItems: ["霸总"],
            incomingMeta: researchedRemote,
            limit: 10
        )
        XCTAssertEqual(merged3, ["霸总"])

        // 4. Fresh install restoring a legacy backup (no timestamps) preserves items.
        let (merged4, _) = LibraryStore.mergeSearchHistory(
            localItems: [],
            localMeta: SearchHistoryMeta(),
            incomingItems: ["古装", "悬疑"],
            incomingMeta: SearchHistoryMeta(),
            limit: 10
        )
        XCTAssertEqual(merged4, ["古装", "悬疑"])
    }

    @MainActor
    func testHomeSourceFoldingPreservesHongguoAndSearchVisibility() {
        let gate = SourceGate.shared
        let prevHidden = gate.homeHidden
        defer {
            gate.clearHomeHidden()
            if !prevHidden.isEmpty { gate.setHomeHiddenBatch(Array(prevHidden), hidden: true) }
        }
        gate.clearHomeHidden()

        // Hongguo cannot be hidden so Home never becomes empty.
        gate.setHomeHidden("builtin:hongguo", hidden: true)
        XCTAssertFalse(gate.isHiddenOnHome("builtin:hongguo"))
        XCTAssertTrue(HomeSource.all.contains(.hongguo))

        // Folding AI剧 removes it from Home top bar while keeping it manageable and reversible.
        gate.setHomeHidden("builtin:ai", hidden: true)
        XCTAssertTrue(gate.isHiddenOnHome("builtin:ai"))
        XCTAssertFalse(HomeSource.all.contains(.ai))
        XCTAssertTrue(HomeSource.allManageable.contains(.ai))

        // Restoring brings it right back.
        gate.setHomeHidden("builtin:ai", hidden: false)
        XCTAssertTrue(HomeSource.all.contains(.ai))
    }

    @MainActor
    func testRemoveFromQueueAdjustsIndexAndAdvancesCleanly() {
        let engine = MusicPlayerEngine.shared
        let t1 = MusicTrack(id: "q-1", source: .netease, sourceId: "1", title: "第一首", artist: "A", album: "", coverURL: "", durationSec: 180, qualityLabel: "HQ", entitlement: .full)
        let t2 = MusicTrack(id: "q-2", source: .netease, sourceId: "2", title: "第二首", artist: "B", album: "", coverURL: "", durationSec: 200, qualityLabel: "HQ", entitlement: .full)
        let t3 = MusicTrack(id: "q-3", source: .netease, sourceId: "3", title: "第三首", artist: "C", album: "", coverURL: "", durationSec: 220, qualityLabel: "HQ", entitlement: .full)

        engine.play(track: t2, in: [t1, t2, t3], openFull: false)
        if engine.isPlaying { engine.togglePlayPause() }
        XCTAssertEqual(engine.queue.map(\.id), ["q-1", "q-2", "q-3"])
        XCTAssertEqual(engine.currentIndex, 1)

        // Removing an item BEFORE currentIndex decrements currentIndex without disturbing currentTrack.
        engine.removeFromQueue(trackId: "q-1")
        XCTAssertEqual(engine.queue.map(\.id), ["q-2", "q-3"])
        XCTAssertEqual(engine.currentIndex, 0)
        XCTAssertEqual(engine.currentTrack?.id, "q-2")

        // Removing the currently active item shifts cleanly to the next item.
        engine.removeFromQueue(trackId: "q-2")
        XCTAssertEqual(engine.queue.map(\.id), ["q-3"])
        XCTAssertEqual(engine.currentIndex, 0)
        XCTAssertEqual(engine.currentTrack?.id, "q-3")

        // Removing the last remaining track clears queue and dismisses mini bar cleanly.
        engine.removeFromQueue(trackId: "q-3")
        XCTAssertTrue(engine.queue.isEmpty)
        XCTAssertNil(engine.currentTrack)
        XCTAssertTrue(engine.miniBarDismissed)
    }

    // MARK: - Anti-H5 Desktop WebView Guard & Native Phone / Kugou QR Auth

    func testMusicWebLoginGuardBlocksAppSchemesAndAllowsDesktopAuth() {
        let blocked = [
            "orpheus://login",
            "qqmusic://qq.com/ui/openUrl",
            "kugou://start.weixin",
            "weixin://dl/business/",
            "mqqapi://forward/url",
            "wtloginmqq://ptlogin/qlogin",
            "intent://music.163.com#Intent;scheme=orpheus;end",
            "itms-apps://itunes.apple.com/app/id590338362",
            "https://music.163.com/m/applink?param=abc",
            "https://a.app.qq.com/o/simple.jsp?pkgname=com.tencent.qqmusic",
            "https://y.qq.com/m/down/index.html",
            "https://durl.kugou.com/download",
            "https://m.kugou.com/",
            "douyin://luna/open",
            "snssdk1128://aweme",
            "qishui://open",
            "luna://open",
            "https://lf-luna.douyin.com/download"
        ]
        for raw in blocked {
            let url = URL(string: raw)!
            XCTAssertTrue(MusicWebLoginGuard.shouldBlock(url: url), "Should block app jump / H5 redirect URL: \(raw)")
        }

        let allowed = [
            "https://music.163.com/#/login",
            "https://y.qq.com/",
            "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=716027609",
            "https://graph.qq.com/oauth2.0/authorize",
            "https://open.weixin.qq.com/connect/qrconnect",
            "https://www.kugou.com/",
            "https://login-user.kugou.com/v2/qrcode",
            "https://music.douyin.com/",
            "about:blank"
        ]
        for raw in allowed {
            let url = URL(string: raw)!
            XCTAssertFalse(MusicWebLoginGuard.shouldBlock(url: url), "Should allow legitimate desktop auth URL: \(raw)")
        }

        XCTAssertFalse(MusicWebLoginGuard.desktopUserAgent.contains("iPhone"))
        XCTAssertFalse(MusicWebLoginGuard.desktopUserAgent.contains("Mobile"))
        XCTAssertFalse(MusicWebLoginGuard.desktopLoginURL(for: .netease).absoluteString.contains("/m/"))
        XCTAssertFalse(MusicWebLoginGuard.desktopLoginURL(for: .kugou).absoluteString.contains("m.kugou.com"))
        XCTAssertEqual(MusicWebLoginGuard.desktopLoginURL(for: .qishui).host, "music.douyin.com")
        XCTAssertTrue(MusicWebLoginGuard.matchesLoggedInCookies(["sessionid": "abc123"], for: .qishui))
        XCTAssertFalse(MusicWebLoginGuard.matchesLoggedInCookies(["foo": "bar"], for: .qishui))
    }

    func testNetEasePhoneNormalizationEAPIEncryptionAndCookieMatching() {
        let n1 = MusicProviderHub.normalizePhone("+86 138-0013-8000", countryCode: "+86")
        XCTAssertEqual(n1.phone, "13800138000")
        XCTAssertEqual(n1.ctcode, "86")

        let n2 = MusicProviderHub.normalizePhone("13912345678", countryCode: "")
        XCTAssertEqual(n2.phone, "13912345678")
        XCTAssertEqual(n2.ctcode, "86")

        let enc = MusicProviderHub.eapiEncrypt(apiPath: "/api/login/cellphone", payload: ["phone": "13800138000", "countrycode": "86"])
        XCTAssertNotNil(enc)
        XCTAssertTrue((enc?.count ?? 0) >= 32 && (enc?.count ?? 1) % 32 == 0, "AES-128-ECB hex output must be 16-byte block aligned")

        XCTAssertTrue(MusicWebLoginGuard.matchesLoggedInCookies(["MUSIC_U": "abc"], for: .netease))
        XCTAssertFalse(MusicWebLoginGuard.matchesLoggedInCookies(["NMTID": "abc"], for: .netease))
        XCTAssertTrue(MusicWebLoginGuard.matchesLoggedInCookies(["qm_keyst": "Q_H_L_123"], for: .qq))
        XCTAssertTrue(MusicWebLoginGuard.matchesLoggedInCookies(["uin": "o123", "skey": "@xyz"], for: .qq))
        XCTAssertFalse(MusicWebLoginGuard.matchesLoggedInCookies(["uin": "o123"], for: .qq))
        XCTAssertTrue(MusicWebLoginGuard.matchesLoggedInCookies(["KugooID": "12345"], for: .kugou))
    }

    func testLiveKugouQRCreateAndNetEaseEAPIDirectWithoutProxy() async {
        let kg = await MusicProviderHub.shared.createKugouQR()
        XCTAssertNotNil(kg, "Kugou serverless QR should succeed directly without proxy")
        XCTAssertFalse(kg?.key.isEmpty ?? true)
        XCTAssertNotNil(kg?.imageData)

        // Verify NetEase EAPI login rejects dummy invalid captcha cleanly (business error 503, NOT network failure or -460)
        let neRes = await MusicProviderHub.shared.loginNetEaseByPhone(phone: "10000000000", countryCode: "86", captcha: "0000")
        switch neRes {
        case .failed(let msg):
            XCTAssertFalse(msg.isEmpty)
        case .requiresWebOrQR(let msg):
            XCTAssertFalse(msg.isEmpty)
        case .authorized:
            XCTFail("Dummy invalid phone/captcha must never authorize")
        }
    }

    func testQQMusicSigningAndLiveQRAndSMSDirectWithoutProxy() async {
        // 1. Verify hash33 handles long 128-char qrsig without overflow and matches 31-bit mask.
        let longSig = String(repeating: "a67a2d9f", count: 16)
        let h = MusicProviderHub.qqHash33(longSig)
        XCTAssertTrue(h >= 0 && h <= 0x7fff_ffff)
        let gtk = MusicProviderHub.qqHash33("Q_H_L_5test", seed: 5381)
        XCTAssertTrue(gtk >= 0 && gtk <= 0x7fff_ffff)

        // 2. Verify zzc_sign format.
        let sig = MusicProviderHub.qqZZCSign("{\"comm\":{\"ct\":\"11\"}}")
        XCTAssertTrue(sig.hasPrefix("zzc"))
        XCTAssertFalse(sig.contains("/"))
        XCTAssertFalse(sig.contains("+"))
        XCTAssertFalse(sig.contains("="))

        // 3. Verify QQ cookie parser.
        let parsed = MusicProviderHub.parseQQCookie("uin=123456; qm_keyst=W_X_token999")
        XCTAssertEqual(parsed.uin, "123456")
        XCTAssertEqual(parsed.musickey, "W_X_token999")
        XCTAssertEqual(parsed.loginType, 1)

        // 4. Live direct no-proxy QQ QR + WeChat QR + QQ SMS validation check (dummy phone, zero real SMS sent).
        async let qqQR = MusicProviderHub.shared.createQQQR(kind: .qq)
        async let wxQR = MusicProviderHub.shared.createQQQR(kind: .wechat)
        async let smsRes = MusicProviderHub.shared.sendQQSMSCode(phone: "10000000000", countryCode: "86")
        let (qq, wx, sms) = await (qqQR, wxQR, smsRes)
        XCTAssertNotNil(qq, "QQ QR should succeed directly without proxy")
        XCTAssertFalse(qq?.imageData.isEmpty ?? true)
        XCTAssertNotNil(wx, "WeChat QR for QQ Music should succeed directly without proxy")
        XCTAssertFalse(wx?.imageData.isEmpty ?? true)
        switch sms {
        case .failed(let msg):
            XCTAssertFalse(msg.isEmpty)
        case .captchaRequired:
            break
        case .sent:
            XCTFail("Dummy invalid phone 10000000000 must not send real SMS")
        }
    }

    func testKugouCryptoNetEaseAnonQQQimeiAndQishuiDirectWithoutProxy() async {
        // 1. Kugou AES-256-CBC round-trip + Raw Textbook RSA-1024 crypto checks
        let enc = MusicProviderHub.kugouAesEncrypt(["mobile": "13800138000", "code": "000000"])
        XCTAssertNotNil(enc)
        XCTAssertFalse(enc?.hex.isEmpty ?? true)
        XCTAssertEqual(enc?.tempKey.count, 16)
        if let enc {
            let dec = MusicProviderHub.kugouAesDecrypt(hex: enc.hex, tempKey: enc.tempKey)
            XCTAssertEqual(dec?["mobile"] as? String, "13800138000")
            XCTAssertEqual(dec?["code"] as? String, "000000")
        }
        let rsaHex = MusicProviderHub.kugouRawRSAEncryptHex(Data("{\"p2\":\"test\"}".utf8))
        XCTAssertNotNil(rsaHex)
        XCTAssertEqual(rsaHex?.count, 256, "Raw textbook RSA-1024 output must be 128 bytes (256 hex chars)")

        // 2. Live direct no-proxy checks across all 4 sources concurrently
        async let kgPhone = MusicProviderHub.shared.loginKugouByPhone(phone: "13800138000", countryCode: "86", code: "000000")
        async let kgWX = MusicProviderHub.shared.createKugouWeChatQR()
        async let neAnon = MusicProviderHub.shared.ensureNetEaseAnonCookie()
        async let qqQimei = MusicProviderHub.shared.ensureQQQimei()
        async let qsCharts = MusicProviderHub.shared.fetchQishuiCharts()
        async let qsQR = MusicProviderHub.shared.createQishuiQR()
        let (kgPhoneRes, kgWXRes, neAnonRes, qqQimeiRes, qsChartsRes, qsQRRes) = await (kgPhone, kgWX, neAnon, qqQimei, qsCharts, qsQR)

        switch kgPhoneRes {
        case .failed(let msg):
            XCTAssertFalse(msg.isEmpty)
        case .requiresWebOrQR(let msg):
            XCTAssertFalse(msg.isEmpty)
        case .authorized:
            XCTFail("Dummy Kugou SMS code 000000 must not authorize")
        }
        XCTAssertNotNil(kgWXRes, "Kugou WeChat QR should succeed directly without proxy")
        XCTAssertFalse(kgWXRes?.uuid.isEmpty ?? true)
        XCTAssertNotNil(neAnonRes, "NetEase anonymous visitor cookie should succeed directly")
        XCTAssertTrue(neAnonRes?.contains("MUSIC_A=") ?? false)
        XCTAssertFalse(qqQimeiRes.isEmpty, "QQ Music QIMEI36 fingerprint should succeed directly")
        XCTAssertGreaterThanOrEqual(qqQimeiRes.count, 16)

        XCTAssertFalse(qsChartsRes.isEmpty, "Qishui discover charts should return playlists directly without login")
        let firstTrack = qsChartsRes.first?.tracks.first
        XCTAssertNotNil(firstTrack, "Qishui chart should contain tracks")
        XCTAssertTrue(firstTrack?.coverURL.contains("~c5_500x500.jpeg") ?? false, "Qishui cover URL must include image resize suffix")
        if let tid = firstTrack?.sourceId {
            let resolved = await MusicProviderHub.shared.resolveQishuiStream(trackId: tid, cookie: nil)
            XCTAssertNotNil(resolved, "Qishui SEO direct playback should resolve a stream URL without login")
            XCTAssertEqual(resolved?.url.scheme, "https")
        }
        XCTAssertNotNil(qsQRRes, "Qishui QR should succeed directly without proxy")
        XCTAssertFalse(qsQRRes?.token.isEmpty ?? true)
        XCTAssertNil(qsQRRes?.imageData, "Qishui QR must render scan_login URL locally instead of Passport's 404 sdk-next PNG")
        XCTAssertTrue(qsQRRes?.qrText.hasPrefix("https://bff-pc.qishui.com/light/invoke/scan_login?token=") ?? false, "Qishui QR text must point to light/invoke/scan_login")
        if let qsQRRes {
            let qsPoll = await MusicProviderHub.shared.pollQishuiQR(session: qsQRRes)
            if case .waiting = qsPoll {
                // Fresh QR must poll as .waiting
            } else {
                XCTFail("Expected fresh Qishui QR poll to return .waiting, got \(qsPoll)")
            }
        }

        // 3. NetEase PC EAPI QR session must include chainId tied to its random deviceId and poll 801 waiting
        let neQR = await MusicProviderHub.shared.createNetEaseQRSession()
        XCTAssertNotNil(neQR, "NetEase PC EAPI QR session should succeed directly")
        if let neQR {
            XCTAssertEqual(neQR.deviceId.count, 32)
            XCTAssertTrue(neQR.url.contains("codekey=\(neQR.key)"))
            XCTAssertTrue(neQR.url.contains("chainId=v1_\(neQR.deviceId)_pc_login_"), "NetEase QR URL must include PC chainId to prevent 8821 refusal")
            let poll = await MusicProviderHub.shared.pollNetEaseQR(session: neQR)
            if case .waiting = poll {
                // 801 waiting as expected
            } else {
                XCTFail("Expected fresh NetEase PC EAPI QR poll to return .waiting (801), got \(poll)")
            }
        }
    }

    func testMusicAudioQualitySettingsBackwardsCompatibility() throws {
        // Legacy saved JSON without preferredQuality must decode cleanly and default to .hires
        let legacyJSON = #"{"lrclibEnabled":true,"musicBrainzEnabled":true,"wifiOnlyCache":false,"maxCacheMB":1024,"autoFallback":true}"#
        let decoded = try JSONDecoder().decode(MusicStore.Settings.self, from: Data(legacyJSON.utf8))
        XCTAssertEqual(decoded.preferredQuality, .hires)
        XCTAssertEqual(decoded.maxCacheMB, 1024)

        var updated = decoded
        updated.preferredQuality = .lossless
        let roundTrip = try JSONDecoder().decode(MusicStore.Settings.self, from: JSONEncoder().encode(updated))
        XCTAssertEqual(roundTrip.preferredQuality, .lossless)
    }

    func testCachedFlacNeverReportsPreviewEntitlementAndDerivesLosslessLabel() async throws {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("test_cached_287398.flac")
        try Data(repeating: 0x42, count: 64_000).write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let vipCatalogTrack = MusicTrack(
            id: "netease:287398",
            source: .netease,
            sourceId: "287398",
            title: "我不难过",
            artist: "孙燕姿",
            album: "未完成",
            coverURL: "",
            durationSec: 320,
            qualityLabel: "320k / 自动换源",
            entitlement: .preview
        )
        let resolved = await MusicProviderHub.shared.resolveStream(
            for: vipCatalogTrack,
            cachedFile: tmp,
            cookies: [.netease: "MUSIC_U=dummy"],
            enabledSources: [.netease],
            nasConfigs: [],
            autoFallback: true,
            preferredQuality: .hires
        )
        XCTAssertNotNil(resolved)
        XCTAssertEqual(resolved?.entitlement, .full, "Cached full FLAC must never inherit stale catalog .preview entitlement")
        XCTAssertTrue(resolved?.qualityLabel.contains("FLAC 无损") ?? false, "Cached .flac must show FLAC 无损 label, got \(resolved?.qualityLabel ?? "nil")")
    }

    func testDailyRecommendationsDifferFromHotChart() async {
        async let hotCharts = MusicProviderHub.shared.fetchCharts(for: .netease, forceRefresh: false)
        async let daily = MusicProviderHub.shared.fetchDailyRecommendations(cookies: [:], enabledSources: [.netease, .qq, .kugou, .qishui], forceRefresh: true)
        let (charts, dailyRes) = await (hotCharts, daily)
        XCTAssertGreaterThanOrEqual(dailyRes.tracks.count, 10, "Daily recommendations should return a rich mix of tracks")
        if let hot = charts.first(where: { $0.id == "ne-3778678" || $0.name.contains("热歌") }) {
            let hotTop10 = hot.tracks.prefix(10).map(\.id)
            let dailyTop10 = dailyRes.tracks.prefix(10).map(\.id)
            XCTAssertNotEqual(dailyTop10, hotTop10, "Daily recommendations must not simply copy the Hot Chart")
        }
    }

    func testQishuiKRCAndQQEntitiesAndMultiPlatformNativeLyricsDirectWithoutProxy() async {
        // 1. Qishui / Douyin KRC word-timed syntax `[startMs,durMs]<wStart,wDur,0>word` must parse cleanly
        let krcSample = """
        [16580,15260]<0,1920,0>You <1920,750,0>ain&apos;t <2670,650,0>been <3320,4210,0>blue
        [31840,8540]<0,2180,0>You <2180,1750,0>ain&apos;t <3930,410,0>been <4340,3400,0>blue &amp; gold
        """
        let parsedKRC = MusicLyricsParser.parse(lrc: krcSample)
        XCTAssertEqual(parsedKRC.count, 2)
        XCTAssertEqual(parsedKRC[0].timeSec, 16.58, accuracy: 0.01)
        XCTAssertEqual(parsedKRC[0].text, "You ain't been blue")
        XCTAssertEqual(parsedKRC[1].timeSec, 31.84, accuracy: 0.01)
        XCTAssertEqual(parsedKRC[1].text, "You ain't been blue & gold")

        // 2. Live direct no-proxy synced lyrics across QQ Music, Kugou, and Qishui
        let qqTrack = MusicTrack(id: "qq:0039MnYb0qxYhV", source: .qq, sourceId: "0039MnYb0qxYhV", title: "晴天", artist: "周杰伦", album: "叶惠美", coverURL: "", durationSec: 269, qualityLabel: "HQ", entitlement: .full)
        async let qqLyrics = MusicProviderHub.shared.fetchLyrics(for: qqTrack, lrclibEnabled: false)
        async let kgCharts = MusicProviderHub.shared.fetchCharts(for: .kugou, forceRefresh: false)
        async let qsCharts = MusicProviderHub.shared.fetchCharts(for: .qishui, forceRefresh: false)
        let (qqLrc, kgList, qsList) = await (qqLyrics, kgCharts, qsCharts)
        XCTAssertGreaterThan(qqLrc.count, 10, "QQ Music native synced lyrics must return parsed LRC lines")

        if let kgTrack = kgList.first?.tracks.first {
            let kgLrc = await MusicProviderHub.shared.fetchLyrics(for: kgTrack, lrclibEnabled: false)
            XCTAssertGreaterThan(kgLrc.count, 5, "Kugou native synced lyrics must return parsed LRC lines for chart track \(kgTrack.title)")
        } else {
            XCTFail("Expected Kugou chart to contain tracks")
        }

        let qsVocalTrack = MusicTrack(id: "qishui:6826684108123408385", source: .qishui, sourceId: "6826684108123408385", title: "Mood Indigo", artist: "Nina Simone", album: "", coverURL: "", durationSec: 240, qualityLabel: "汽水直连", entitlement: .full)
        let qsLrc = await MusicProviderHub.shared.fetchLyrics(for: qsVocalTrack, lrclibEnabled: false)
        XCTAssertGreaterThan(qsLrc.count, 3, "Qishui KRC synced lyrics must parse into MusicLyricLines for \(qsVocalTrack.title)")
        XCTAssertFalse(qsList.isEmpty, "Expected Qishui chart to contain playlists")
    }

    func testRecommendedPlaylistsRemoteDetailAndCloudPlaylistCodableDirectWithoutProxy() async throws {
        // 1. Legacy saved local MusicPlaylist JSON without new cloud fields must decode cleanly
        let legacyPlJSON = #"{"id":"local-1","name":"我的收藏","subtitle":"1 首","createdAt":0,"tracks":[]}"#
        let decoded = try JSONDecoder().decode(MusicPlaylist.self, from: Data(legacyPlJSON.utf8))
        XCTAssertEqual(decoded.id, "local-1")
        XCTAssertFalse(decoded.isCloud)

        // 2. Live direct zero-proxy recommended playlists across NetEase, QQ, Qishui
        let rec = await MusicProviderHub.shared.fetchRecommendedPlaylists(cookies: [:], enabledSources: [.netease, .qq, .qishui], forceRefresh: true)
        XCTAssertGreaterThanOrEqual(rec.playlists.count, 4, "Discover recommended playlists must return multi-source curated playlists")

        // 3. Live direct zero-proxy remote playlist detail on NetEase (must hydrate >10 tracks via trackIds + v3/song/detail) & QQ (musicu.fcg)
        if let nePl = rec.playlists.first(where: { $0.source == .netease && ($0.trackCountHint ?? 0) > 10 }), let rid = nePl.remoteId {
            let neTracks = await MusicProviderHub.shared.fetchRemotePlaylistTracks(source: .netease, remoteId: rid, cookie: nil)
            XCTAssertGreaterThan(neTracks.count, 10, "NetEase playlist detail must fetch full tracks via v3/song/detail, not truncate at 10")
        }
        if let qqPl = rec.playlists.first(where: { $0.source == .qq }), let rid = qqPl.remoteId {
            let qqTracks = await MusicProviderHub.shared.fetchRemotePlaylistTracks(source: .qq, remoteId: rid, cookie: nil)
            XCTAssertGreaterThan(qqTracks.count, 5, "QQ playlist detail via musicu.fcg uniform_get_Dissinfo must return tracks")
        }

        // 4. Disabled sources must NEVER leak into charts when caller restricts enabledSources
        let onlyNE = await MusicProviderHub.shared.fetchCharts(for: nil, enabledSources: [.netease], forceRefresh: false)
        XCTAssertFalse(onlyNE.isEmpty)
        XCTAssertTrue(onlyNE.allSatisfy { $0.source == .netease }, "Charts must strictly filter out disabled sources")
        let disabledQQ = await MusicProviderHub.shared.fetchCharts(for: .qq, enabledSources: [.netease], forceRefresh: false)
        XCTAssertTrue(disabledQQ.isEmpty, "Explicitly requesting a disabled source chart must return empty")
    }
}
