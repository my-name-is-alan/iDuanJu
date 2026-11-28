import Foundation
import CryptoKit
import CommonCrypto
import Security

/// Shared direct-connection networking that explicitly disables HTTP/HTTPS/SOCKS system proxies
/// (exact parity with `HongguoClient.playerSession`) so Clash/Shadowrocket/VPN proxies never break direct music CDNs.
enum MusicDirectNet {
    static let noProxyDictionary: [AnyHashable: Any] = [
        "HTTPEnable": 0, "HTTPSEnable": 0, "SOCKSEnable": 0,
        "HTTPProxy": "", "HTTPSProxy": "", "SOCKSProxy": ""
    ]

    static func makeConfiguration(timeout: TimeInterval = 12, resourceTimeout: TimeInterval = 25) -> URLSessionConfiguration {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = timeout
        c.timeoutIntervalForResource = resourceTimeout
        c.httpCookieStorage = nil
        c.httpShouldSetCookies = false
        c.connectionProxyDictionary = noProxyDictionary
        return c
    }

    static let session = URLSession(configuration: makeConfiguration(timeout: 10, resourceTimeout: 20))
    static let longPollSession = URLSession(configuration: makeConfiguration(timeout: 35, resourceTimeout: 42))
    static let mediaSession = URLSession(configuration: makeConfiguration(timeout: 15, resourceTimeout: 90))
}

private final class MusicNoRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

/// 100% serverless client-side music aggregator (direct upstream only, zero proxy, zero relay server).
/// Directly queries NetEase, QQ Music, Kugou, LRCLIB, iTunes, installed `.idjplugin` music plugins,
/// and optional self-hosted NAS connectors (Navidrome / Emby / 飞牛音乐 fnOS).
actor MusicProviderHub {
    static let shared = MusicProviderHub()

    private let ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private let session: URLSession = MusicDirectNet.session

    private var chartCache: [String: (fetchedAt: Date, charts: [MusicChart])] = [:]
    private let chartTTL: TimeInterval = 10 * 60

    struct DailyRecResult {
        let tracks: [MusicTrack]
        let isPersonalized: Bool
        let subtitle: String
    }

    private var dailyRecCache: (key: String, fetchedAt: Date, result: DailyRecResult)?

    /// Fetches personalized daily recommendations when logged into NetEase (`/eapi/v3/discovery/recommend/songs`),
    /// otherwise builds a daily-seeded multi-source interleaved mix (distinct from NetEase Hot Chart).
    func fetchDailyRecommendations(
        cookies: [MusicSourceKind: String],
        enabledSources: [MusicSourceKind],
        forceRefresh: Bool = false
    ) async -> DailyRecResult {
        let cal = Calendar.current
        let now = Date()
        let dayKey = "\(cal.component(.year, from: now))-\(cal.component(.month, from: now))-\(cal.component(.day, from: now))"
        let allowed = Set(enabledSources)
        let allowNE = allowed.isEmpty || allowed.contains(.netease)
        let allowKG = allowed.isEmpty || allowed.contains(.kugou)
        let enabledKey = enabledSources.map(\.rawValue).joined(separator: ",")
        let neCookie = allowNE ? (cookies[.netease]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "") : ""
        let kgCookie = allowKG ? (cookies[.kugou]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "") : ""
        let cacheKey = "\(dayKey)|\(enabledKey)|\(Self.md5Hex(neCookie).prefix(10))|\(Self.md5Hex(kgCookie).prefix(10))"

        if !forceRefresh, let hit = dailyRecCache, hit.key == cacheKey, now.timeIntervalSince(hit.fetchedAt) < 30 * 60 {
            return hit.result
        }

        // 1) Real personalized NetEase account daily recommendations (only when NetEase source is enabled)
        if allowNE, !neCookie.isEmpty,
           let res = await postNetEaseEAPI(
               apiPath: "/api/v3/discovery/recommend/songs",
               payload: [:],
               extraCookie: neCookie
           ),
           (res.json["code"] as? Int) == 200,
           let dataObj = res.json["data"] as? [String: Any],
           let dailySongs = dataObj["dailySongs"] as? [[String: Any]],
           !dailySongs.isEmpty {
            let tracks: [MusicTrack] = dailySongs.compactMap { t in
                guard let idNum = t["id"] as? Int else { return nil }
                let title = (t["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                guard !title.isEmpty else { return nil }
                let artists = (t["ar"] as? [[String: Any]] ?? [])
                let artistStr = artists.compactMap { $0["name"] as? String }.joined(separator: " / ")
                let firstArtistId = (artists.first?["id"] as? Int).map(String.init)
                let al = t["al"] as? [String: Any]
                let albumName = al?["name"] as? String ?? ""
                let albumId = (al?["id"] as? Int).map(String.init)
                let cover = Self.httpsURL(al?["picUrl"] as? String ?? "")
                let dt = Double(t["dt"] as? Int ?? 210_000) / 1000.0
                return MusicTrack(
                    id: "netease:\(idNum)",
                    source: .netease,
                    sourceId: "\(idNum)",
                    title: title,
                    artist: artistStr.isEmpty ? "未知歌手" : artistStr,
                    album: albumName,
                    coverURL: cover,
                    durationSec: max(dt, 30),
                    qualityLabel: "HQ 320k",
                    entitlement: .full,
                    artistId: firstArtistId,
                    albumId: albumId
                )
            }
            if !tracks.isEmpty {
                let result = DailyRecResult(
                    tracks: tracks,
                    isPersonalized: true,
                    subtitle: "网易云账号私人日推 · 共 \(tracks.count) 首"
                )
                dailyRecCache = (cacheKey, now, result)
                return result
            }
        }

        // 2) Real personalized Kugou account daily recommendations when Kugou is enabled & logged in
        if allowKG, !kgCookie.isEmpty {
            let kgTracks = await fetchKugouDailyRecommendations(cookie: kgCookie)
            if !kgTracks.isEmpty {
                let result = DailyRecResult(
                    tracks: kgTracks,
                    isPersonalized: true,
                    subtitle: "酷狗账号私人日推 · 共 \(kgTracks.count) 首"
                )
                dailyRecCache = (cacheKey, now, result)
                return result
            }
        }

        // 3) Logged-out / fallback: diverse daily-seeded round-robin mix strictly from enabled non-Hot charts
        let allCharts = await fetchCharts(for: nil, enabledSources: enabledSources, forceRefresh: false)
        let poolCharts = allCharts.filter { allowed.isEmpty || allowed.contains($0.source) }
        let nonHot = poolCharts.filter { $0.id != "ne-3778678" && !$0.name.contains("热歌") }
        let buckets = (nonHot.isEmpty ? poolCharts : nonHot).map(\.tracks).filter { !$0.isEmpty }
        let daySeed = cal.ordinality(of: .day, in: .year, for: now) ?? 1

        var picked: [MusicTrack] = []
        var seenKey = Set<String>()
        if !buckets.isEmpty {
            let maxDepth = buckets.map(\.count).max() ?? 0
            for depth in 0..<maxDepth where picked.count < 30 {
                for bIdx in 0..<buckets.count where picked.count < 30 {
                    let list = buckets[(bIdx + daySeed) % buckets.count]
                    let pos = (depth + (daySeed * 3 + bIdx * 5)) % list.count
                    let t = list[pos]
                    let dedupKey = "\(t.title.lowercased())|\(t.primaryArtistName.lowercased())"
                    if seenKey.insert(dedupKey).inserted {
                        picked.append(t)
                    }
                }
            }
        }
        let result = DailyRecResult(
            tracks: picked,
            isPersonalized: false,
            subtitle: "多源今日混排精选 · 共 \(picked.count) 首（登录网易云或酷狗解锁私人日推）"
        )
        dailyRecCache = (cacheKey, now, result)
        return result
    }

    private func fetchKugouDailyRecommendations(cookie: String) async -> [MusicTrack] {
        let parsed = Self.parseKugouCookie(cookie)
        guard !parsed.token.isEmpty else { return [] }
        let mid = Self.stableMusicDeviceId()
        let ts = "\(Int(Date().timeIntervalSince1970))"
        let bodyStr = "{\"platform\":\"ios\"}"
        var qp: [String: String] = [
            "dfid": parsed.dfid, "mid": mid, "uuid": "-", "appid": "1005",
            "clientver": "11430", "clienttime": ts, "token": parsed.token,
            "userid": parsed.userid, "platform": "ios"
        ]
        qp["signature"] = Self.kugouAndroidSign(qp, body: bodyStr)
        guard let url = URL(string: "https://gateway.kugou.com/everyday_song_recommend?\(Self.kugouQueryString(qp))") else { return [] }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = Data(bodyStr.utf8)
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi", forHTTPHeaderField: "User-Agent")
        req.setValue("everydayrec.service.kugou.com", forHTTPHeaderField: "x-router")
        req.setValue(parsed.dfid, forHTTPHeaderField: "dfid")
        req.setValue(mid, forHTTPHeaderField: "mid")
        req.setValue(ts, forHTTPHeaderField: "clienttime")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any],
              let list = d["song_list"] as? [[String: Any]] else { return [] }
        return list.compactMap { s in
            let hash = (s["hash"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let name = (s["songname"] as? String ?? s["filename"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !hash.isEmpty, !name.isEmpty else { return nil }
            let singer = (s["author_name"] as? String ?? s["singername"] as? String ?? "未知歌手").trimmingCharacters(in: .whitespacesAndNewlines)
            let album = (s["album_name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let rawCover = (s["sizable_cover"] as? String ?? "").replacingOccurrences(of: "{size}", with: "400")
            let rawDur = (s["time_length"] as? Double) ?? Double(s["time_length"] as? Int ?? 210_000)
            let durSec = rawDur > 1000 ? rawDur / 1000.0 : rawDur
            return MusicTrack(
                id: "kugou:\(hash)",
                source: .kugou,
                sourceId: hash,
                title: name,
                artist: singer.isEmpty ? "未知歌手" : singer,
                album: album,
                coverURL: Self.httpsURL(rawCover),
                durationSec: max(30, durSec),
                qualityLabel: "HQ 320k",
                entitlement: .full,
                extraHash: hash
            )
        }
    }

    // MARK: - Personal FM, Recommended Playlists & User Cloud Playlist Sync (100% Direct Zero-Proxy)

    struct PersonalFMResult {
        let tracks: [MusicTrack]
        let isPersonalized: Bool
        let sourceLabel: String
    }

    struct RecommendPlaylistsResult {
        let playlists: [MusicPlaylist]
        let isPersonalized: Bool
    }

    private var recommendPlaylistsCache: (key: String, fetchedAt: Date, result: RecommendPlaylistsResult)?

    private static func formatPlayCount(_ count: Double) -> String {
        if count >= 100_000_000 { return String(format: "%.1f亿", count / 100_000_000) }
        if count >= 10_000 { return "\(Int(count / 10_000))万" }
        if count > 0 { return "\(Int(count))" }
        return ""
    }

    private static func parseNetEaseSongObject(_ t: [String: Any], loggedIn: Bool = false) -> MusicTrack? {
        guard let tid = t["id"] else { return nil }
        let sid = String(describing: tid)
        let name = (t["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !sid.isEmpty, !name.isEmpty else { return nil }
        let arList = (t["ar"] as? [[String: Any]]) ?? (t["artists"] as? [[String: Any]]) ?? []
        let artists = arList.compactMap { $0["name"] as? String }.joined(separator: " / ")
        let arId = arList.first?["id"].map { String(describing: $0) }
        let al = (t["al"] as? [String: Any]) ?? (t["album"] as? [String: Any])
        let album = al?["name"] as? String ?? ""
        let alId = al?["id"].map { String(describing: $0) }
        let pic = httpsURL((al?["picUrl"] as? String) ?? (al?["blurPicUrl"] as? String) ?? "")
        let rawDt = (t["dt"] as? Double) ?? Double(t["dt"] as? Int ?? 0)
        let rawDur = (t["duration"] as? Double) ?? Double(t["duration"] as? Int ?? 0)
        let ms = rawDt > 0 ? rawDt : (rawDur > 0 ? rawDur : 210_000)
        let fee = t["fee"] as? Int ?? 0
        let ent: MusicEntitlement = (!loggedIn && (fee == 1 || fee == 4)) ? .preview : .full
        return MusicTrack(
            id: "netease:\(sid)",
            source: .netease,
            sourceId: sid,
            title: name,
            artist: artists.isEmpty ? "未知歌手" : artists,
            album: album,
            coverURL: pic,
            durationSec: max(30, ms / 1000.0),
            qualityLabel: ent == .full ? "HQ 320k" : "320k / 自动换源",
            entitlement: ent,
            artistId: arId,
            albumId: alId
        )
    }

    /// Real Personal FM: NetEase `/api/v1/radio/get` when logged in, else Kugou daily mix or multi-source discovery radio.
    func fetchPersonalFM(
        cookies: [MusicSourceKind: String],
        enabledSources: [MusicSourceKind]
    ) async -> PersonalFMResult {
        let allowed = Set(enabledSources)
        let allowNE = allowed.isEmpty || allowed.contains(.netease)
        let allowKG = allowed.isEmpty || allowed.contains(.kugou)

        let neCookie = allowNE ? (cookies[.netease]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "") : ""
        if !neCookie.isEmpty, let url = URL(string: "https://music.163.com/api/v1/radio/get") {
            var collected: [MusicTrack] = []
            var seen = Set<String>()
            for _ in 0..<3 {
                var req = URLRequest(url: url)
                req.setValue(ua, forHTTPHeaderField: "User-Agent")
                req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
                req.setValue(neCookie, forHTTPHeaderField: "Cookie")
                guard let (data, resp) = try? await session.data(for: req),
                      (resp as? HTTPURLResponse)?.statusCode == 200,
                      let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                      let list = root["data"] as? [[String: Any]] else { break }
                for item in list {
                    if let track = Self.parseNetEaseSongObject(item, loggedIn: true),
                       seen.insert(track.id).inserted {
                        collected.append(track)
                    }
                }
            }
            if !collected.isEmpty {
                return PersonalFMResult(tracks: collected, isPersonalized: true, sourceLabel: "网易云私人 FM")
            }
        }

        let kgCookie = allowKG ? (cookies[.kugou]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "") : ""
        if !kgCookie.isEmpty {
            let kg = await fetchKugouDailyRecommendations(cookie: kgCookie)
            if !kg.isEmpty {
                return PersonalFMResult(tracks: kg.shuffled(), isPersonalized: true, sourceLabel: "酷狗私人漫游 FM")
            }
        }

        let daily = await fetchDailyRecommendations(cookies: cookies, enabledSources: enabledSources, forceRefresh: false)
        return PersonalFMResult(tracks: daily.tracks.shuffled(), isPersonalized: daily.isPersonalized, sourceLabel: "多源探索 FM")
    }

    /// Curated & personalized recommended playlists across NetEase (personalized + public), QQ Music, and Qishui.
    func fetchRecommendedPlaylists(
        cookies: [MusicSourceKind: String],
        enabledSources: [MusicSourceKind],
        forceRefresh: Bool = false
    ) async -> RecommendPlaylistsResult {
        let neCookie = cookies[.netease]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let cacheKey = "\(Self.md5Hex(neCookie).prefix(10))|\(enabledSources.map(\.rawValue).joined(separator: ","))"
        let now = Date()
        if !forceRefresh, let hit = recommendPlaylistsCache, hit.key == cacheKey, now.timeIntervalSince(hit.fetchedAt) < 20 * 60 {
            return hit.result
        }

        let allowSet = Set(enabledSources)
        let allowNE = allowSet.isEmpty || allowSet.contains(.netease)
        let allowQQ = allowSet.isEmpty || allowSet.contains(.qq)
        let allowQS = allowSet.isEmpty || allowSet.contains(.qishui)

        async let nePersonalTask: [MusicPlaylist] = (allowNE && !neCookie.isEmpty) ? fetchNetEasePersonalizedPlaylists(cookie: neCookie) : []
        async let nePublicTask: [MusicPlaylist] = allowNE ? fetchNetEasePublicRecommendPlaylists() : []
        async let qqTask: [MusicPlaylist] = allowQQ ? fetchQQRecommendPlaylists() : []
        async let qsTask: [MusicChart] = allowQS ? fetchQishuiCharts() : []

        let (nePersonal, nePublic, qqList, qsCharts) = await (nePersonalTask, nePublicTask, qqTask, qsTask)

        var merged: [MusicPlaylist] = []
        var seen = Set<String>()
        func appendUnique(_ list: [MusicPlaylist]) {
            for pl in list where seen.insert(pl.id).inserted {
                merged.append(pl)
            }
        }
        appendUnique(nePersonal)
        let qsPlaylists: [MusicPlaylist] = qsCharts.map { c in
            let rid = c.id.replacingOccurrences(of: "qishui:", with: "")
            return MusicPlaylist(
                id: "rec:qishui:\(rid)",
                name: c.name,
                subtitle: "汽水精选 · \(c.tracks.count) 首",
                tracks: c.tracks,
                coverURL: c.coverURLs.first,
                source: .qishui,
                remoteId: rid,
                trackCountHint: c.tracks.count,
                playCountText: "汽水精选"
            )
        }
        let maxLen = max(nePublic.count, max(qqList.count, qsPlaylists.count))
        for i in 0..<maxLen {
            if i < nePublic.count { appendUnique([nePublic[i]]) }
            if i < qqList.count { appendUnique([qqList[i]]) }
            if i < qsPlaylists.count { appendUnique([qsPlaylists[i]]) }
        }
        let result = RecommendPlaylistsResult(playlists: Array(merged.prefix(18)), isPersonalized: !nePersonal.isEmpty)
        if !result.playlists.isEmpty {
            recommendPlaylistsCache = (cacheKey, now, result)
        }
        return result
    }

    private func fetchNetEasePersonalizedPlaylists(cookie: String) async -> [MusicPlaylist] {
        guard let url = URL(string: "https://music.163.com/api/v1/discovery/recommend/resource") else { return [] }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        req.setValue(cookie, forHTTPHeaderField: "Cookie")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (root["code"] as? Int) == 200,
              let arr = root["recommend"] as? [[String: Any]] else { return [] }
        return arr.compactMap { item in
            guard let idVal = item["id"] else { return nil }
            let rid = String(describing: idVal)
            let name = (item["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !rid.isEmpty, !name.isEmpty else { return nil }
            let pic = Self.httpsURL(item["picUrl"] as? String ?? "")
            let cnt = item["trackCount"] as? Int ?? 0
            let pc = (item["playcount"] as? Double) ?? Double(item["playcount"] as? Int ?? 0)
            let copy = (item["copywriter"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let sub = !copy.isEmpty ? copy : "网易云个性推荐 · \(cnt > 0 ? "\(cnt) 首" : "精选")"
            return MusicPlaylist(
                id: "rec:netease:\(rid)",
                name: name,
                subtitle: sub,
                coverURL: pic,
                source: .netease,
                remoteId: rid,
                trackCountHint: cnt,
                playCountText: Self.formatPlayCount(pc).nilIfEmpty ?? "个性推荐"
            )
        }
    }

    private func fetchNetEasePublicRecommendPlaylists() async -> [MusicPlaylist] {
        guard let url = URL(string: "https://music.163.com/api/personalized/playlist?limit=10") else { return [] }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let arr = root["result"] as? [[String: Any]] else { return [] }
        return arr.compactMap { item in
            guard let idVal = item["id"] else { return nil }
            let rid = String(describing: idVal)
            let name = (item["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !rid.isEmpty, !name.isEmpty else { return nil }
            let pic = Self.httpsURL(item["picUrl"] as? String ?? "")
            let cnt = item["trackCount"] as? Int ?? 0
            let pc = (item["playCount"] as? Double) ?? Double(item["playCount"] as? Int ?? 0)
            let pText = Self.formatPlayCount(pc)
            return MusicPlaylist(
                id: "rec:netease:\(rid)",
                name: name,
                subtitle: "网易云推荐歌单\(cnt > 0 ? " · \(cnt) 首" : "")",
                coverURL: pic,
                source: .netease,
                remoteId: rid,
                trackCountHint: cnt,
                playCountText: pText.isEmpty ? nil : pText
            )
        }
    }

    private func fetchQQRecommendPlaylists() async -> [MusicPlaylist] {
        guard let url = URL(string: "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg?picmid=1&rnd=0.5&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0&categoryId=10000000&sortId=5&sin=0&ein=5") else { return [] }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://y.qq.com/", forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any],
              let list = d["list"] as? [[String: Any]] else { return [] }
        return list.compactMap { item in
            guard let idVal = item["dissid"] else { return nil }
            let rid = String(describing: idVal)
            let name = MusicLyricsParser.decodeHTMLEntities((item["dissname"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines))
            guard !rid.isEmpty, !name.isEmpty else { return nil }
            let pic = Self.httpsURL(item["imgurl"] as? String ?? "")
            let pc = (item["listennum"] as? Double) ?? Double(item["listennum"] as? Int ?? 0)
            let pText = Self.formatPlayCount(pc)
            return MusicPlaylist(
                id: "rec:qq:\(rid)",
                name: name,
                subtitle: "QQ 音乐精选歌单",
                coverURL: pic,
                source: .qq,
                remoteId: rid,
                trackCountHint: nil,
                playCountText: pText.isEmpty ? nil : pText
            )
        }
    }

    /// Syncs cloud playlists across all logged-in accounts (NetEase, QQ Music, Kugou, Qishui) directly without proxy.
    func fetchUserCloudPlaylists(cookies: [MusicSourceKind: String]) async -> [MusicPlaylist] {
        await withTaskGroup(of: (Int, [MusicPlaylist]).self) { group in
            if let ck = cookies[.netease], !ck.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                group.addTask { (0, await self.fetchNetEaseUserPlaylists(cookie: ck)) }
            }
            if let ck = cookies[.qq], !ck.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                group.addTask { (1, await self.fetchQQUserPlaylists(cookie: ck)) }
            }
            if let ck = cookies[.kugou], !ck.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                group.addTask { (2, await self.fetchKugouUserPlaylists(cookie: ck)) }
            }
            if let ck = cookies[.qishui], !ck.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                group.addTask { (3, await self.fetchQishuiUserPlaylists(cookie: ck)) }
            }
            var buckets: [(Int, [MusicPlaylist])] = []
            for await pair in group { buckets.append(pair) }
            return buckets.sorted { $0.0 < $1.0 }.flatMap(\.1)
        }
    }

    private func fetchNetEaseUserPlaylists(cookie: String) async -> [MusicPlaylist] {
        guard let accURL = URL(string: "https://music.163.com/api/nuser/account/get") else { return [] }
        var accReq = URLRequest(url: accURL)
        accReq.setValue(ua, forHTTPHeaderField: "User-Agent")
        accReq.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        accReq.setValue(cookie, forHTTPHeaderField: "Cookie")
        guard let (aData, aResp) = try? await session.data(for: accReq),
              (aResp as? HTTPURLResponse)?.statusCode == 200,
              let aRoot = (try? JSONSerialization.jsonObject(with: aData)) as? [String: Any] else { return [] }
        let uidVal = (aRoot["account"] as? [String: Any])?["id"] ?? (aRoot["profile"] as? [String: Any])?["userId"]
        guard let uidVal else { return [] }
        let uid = String(describing: uidVal)
        guard !uid.isEmpty, uid != "0",
              let url = URL(string: "https://music.163.com/api/user/playlist?uid=\(Self.urlValueEnc(uid))&limit=60&offset=0&includeVideo=true") else { return [] }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        req.setValue(cookie, forHTTPHeaderField: "Cookie")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let arr = root["playlist"] as? [[String: Any]] else { return [] }
        return arr.compactMap { item in
            guard let idVal = item["id"] else { return nil }
            let rid = String(describing: idVal)
            let name = (item["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !rid.isEmpty, !name.isEmpty else { return nil }
            let pic = Self.httpsURL(item["coverImgUrl"] as? String ?? "")
            let cnt = item["trackCount"] as? Int ?? 0
            let subscribed = item["subscribed"] as? Bool ?? false
            let specialType = item["specialType"] as? Int ?? 0
            let tag = specialType == 5 ? "我喜欢的音乐" : (subscribed ? "收藏歌单" : "自建歌单")
            return MusicPlaylist(
                id: "cloud:netease:\(rid)",
                name: name,
                subtitle: "网易云 · \(tag) · \(cnt) 首",
                coverURL: pic,
                source: .netease,
                remoteId: rid,
                trackCountHint: cnt,
                playCountText: specialType == 5 ? "红心" : nil,
                isSubscribed: subscribed
            )
        }
    }

    private func fetchQQUserPlaylists(cookie: String) async -> [MusicPlaylist] {
        let parsed = Self.parseQQCookie(cookie)
        guard !parsed.uin.isEmpty, parsed.uin != "0",
              let url = URL(string: "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?format=json&cid=205360838&userid=\(Self.urlValueEnc(parsed.uin))&reqfrom=1") else { return [] }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://y.qq.com/", forHTTPHeaderField: "Referer")
        req.setValue(cookie, forHTTPHeaderField: "Cookie")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any] else { return [] }
        var out: [MusicPlaylist] = []
        if let mymusic = d["mymusic"] as? [[String: Any]] {
            for item in mymusic {
                guard let idVal = item["id"] else { continue }
                let rid = String(describing: idVal)
                guard !rid.isEmpty, rid != "0" else { continue }
                let name = (item["title"] as? String ?? "QQ 我喜欢").trimmingCharacters(in: .whitespacesAndNewlines)
                let cnt = Int(String(describing: item["num0"] ?? 0)) ?? 0
                let pic = Self.httpsURL(item["laypic"] as? String ?? item["picurl"] as? String ?? "")
                out.append(MusicPlaylist(
                    id: "cloud:qq:\(rid)",
                    name: name,
                    subtitle: "QQ 音乐 · 我喜欢 · \(cnt) 首",
                    coverURL: pic,
                    source: .qq,
                    remoteId: rid,
                    trackCountHint: cnt,
                    playCountText: "红心",
                    isSubscribed: false
                ))
            }
        }
        if let mydiss = (d["mydiss"] as? [String: Any])?["list"] as? [[String: Any]] {
            for item in mydiss {
                guard let idVal = item["dissid"] else { continue }
                let rid = String(describing: idVal)
                let name = MusicLyricsParser.decodeHTMLEntities((item["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines))
                guard !rid.isEmpty, !name.isEmpty else { continue }
                let pic = Self.httpsURL(item["picurl"] as? String ?? "")
                let sub = (item["subtitle"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                out.append(MusicPlaylist(
                    id: "cloud:qq:\(rid)",
                    name: name,
                    subtitle: sub.isEmpty ? "QQ 音乐 · 自建歌单" : "QQ 音乐 · \(sub)",
                    coverURL: pic,
                    source: .qq,
                    remoteId: rid,
                    trackCountHint: nil,
                    isSubscribed: false
                ))
            }
        }
        return out
    }

    private func fetchKugouUserPlaylists(cookie: String) async -> [MusicPlaylist] {
        let parsed = Self.parseKugouCookie(cookie)
        guard !parsed.token.isEmpty else { return [] }
        let mid = Self.stableMusicDeviceId()
        let ts = "\(Int(Date().timeIntervalSince1970))"
        let bodyStr = "{\"userid\":\(Int(parsed.userid) ?? 0),\"token\":\"\(parsed.token)\",\"total_ver\":979,\"type\":2,\"page\":1,\"pagesize\":50}"
        var qp: [String: String] = [
            "dfid": parsed.dfid, "mid": mid, "uuid": "-", "appid": "1005",
            "clientver": "11430", "clienttime": ts, "token": parsed.token, "userid": parsed.userid
        ]
        qp["signature"] = Self.kugouAndroidSign(qp, body: bodyStr)
        guard let url = URL(string: "https://gateway.kugou.com/v7/get_all_list?\(Self.kugouQueryString(qp))") else { return [] }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = Data(bodyStr.utf8)
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi", forHTTPHeaderField: "User-Agent")
        req.setValue("cloudlist.service.kugou.com", forHTTPHeaderField: "x-router")
        req.setValue(parsed.dfid, forHTTPHeaderField: "dfid")
        req.setValue(mid, forHTTPHeaderField: "mid")
        req.setValue(ts, forHTTPHeaderField: "clienttime")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any],
              let list = d["info"] as? [[String: Any]] else { return [] }
        return list.compactMap { item in
            let gid = (item["global_collection_id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let lid = item["listid"].map { String(describing: $0) } ?? ""
            let rid = !gid.isEmpty ? gid : lid
            let name = (item["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !rid.isEmpty, !name.isEmpty else { return nil }
            let cnt = item["count"] as? Int ?? 0
            let pic = Self.httpsURL((item["pic"] as? String ?? "").replacingOccurrences(of: "{size}", with: "400"))
            return MusicPlaylist(
                id: "cloud:kugou:\(rid)",
                name: name,
                subtitle: "酷狗 · 云端歌单 · \(cnt) 首",
                coverURL: pic,
                source: .kugou,
                remoteId: rid,
                trackCountHint: cnt,
                isSubscribed: (item["type"] as? Int ?? 0) == 1
            )
        }
    }

    private func fetchQishuiUserPlaylists(cookie: String) async -> [MusicPlaylist] {
        guard let url = URL(string: "https://beta-luna.douyin.com/luna/me/playlists") else { return [] }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = Data("{\"count\":50}".utf8)
        req.setValue("Luna/19.1.0 Android", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue(cookie, forHTTPHeaderField: "Cookie")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [] }
        let rawList = (root["playlists"] as? [[String: Any]]) ?? (root["user_playlists"] as? [[String: Any]]) ?? []
        return rawList.compactMap { pl in
            guard let idVal = pl["id"] else { return nil }
            let rid = String(describing: idVal)
            let name = (pl["title"] as? String ?? pl["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !rid.isEmpty, !name.isEmpty else { return nil }
            let pic = Self.qishuiCoverURL(from: pl["url_cover"])
            let cnt = pl["count_tracks"] as? Int ?? pl["track_count"] as? Int ?? 0
            return MusicPlaylist(
                id: "cloud:qishui:\(rid)",
                name: name,
                subtitle: "汽水音乐 · 云端歌单 · \(cnt) 首",
                coverURL: pic,
                source: .qishui,
                remoteId: rid,
                trackCountHint: cnt
            )
        }
    }

    /// Fetches full tracklist for any remote/cloud/recommended playlist across NetEase, QQ Music, Kugou, and Qishui.
    func fetchRemotePlaylistTracks(source: MusicSourceKind, remoteId: String, cookie: String?) async -> [MusicTrack] {
        let rid = remoteId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !rid.isEmpty else { return [] }
        switch source {
        case .netease:
            return await fetchNetEasePlaylistTracks(id: rid, cookie: cookie)
        case .qq:
            return await fetchQQPlaylistTracks(dissid: rid, cookie: cookie)
        case .kugou:
            return await fetchKugouPlaylistTracks(globalCollectionId: rid, cookie: cookie)
        case .qishui:
            return (await fetchQishuiPlaylistChart(id: rid, title: "汽水歌单", fallbackCover: ""))?.tracks ?? []
        default:
            return []
        }
    }

    private func fetchNetEasePlaylistTracks(id: String, cookie: String?) async -> [MusicTrack] {
        let loggedIn = !(cookie?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
        guard let url = URL(string: "https://music.163.com/api/v6/playlist/detail?id=\(Self.urlValueEnc(id))&n=100&s=0") else { return [] }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        if let cookie, !cookie.isEmpty { req.setValue(cookie, forHTTPHeaderField: "Cookie") }
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let pl = root["playlist"] as? [String: Any] else { return [] }

        let directTracks = (pl["tracks"] as? [[String: Any]] ?? []).compactMap { Self.parseNetEaseSongObject($0, loggedIn: loggedIn) }
        let trackIds = (pl["trackIds"] as? [[String: Any]] ?? []).compactMap { $0["id"].map { String(describing: $0) } }
        if trackIds.count <= directTracks.count || trackIds.isEmpty {
            return directTracks
        }
        let targetIds = Array(trackIds.prefix(150))
        var byId: [String: MusicTrack] = Dictionary(uniqueKeysWithValues: directTracks.map { ($0.sourceId, $0) })
        let missing = targetIds.filter { byId[$0] == nil }
        let chunkSize = 50
        for start in stride(from: 0, to: missing.count, by: chunkSize) {
            let chunk = Array(missing[start..<min(start + chunkSize, missing.count)])
            let cPayload = "[" + chunk.map { "{\"id\":\($0)}" }.joined(separator: ",") + "]"
            guard let dURL = URL(string: "https://music.163.com/api/v3/song/detail?c=\(Self.urlValueEnc(cPayload))") else { continue }
            var dReq = URLRequest(url: dURL)
            dReq.setValue(ua, forHTTPHeaderField: "User-Agent")
            dReq.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
            if let cookie, !cookie.isEmpty { dReq.setValue(cookie, forHTTPHeaderField: "Cookie") }
            if let (dData, dResp) = try? await session.data(for: dReq),
               (dResp as? HTTPURLResponse)?.statusCode == 200,
               let dRoot = (try? JSONSerialization.jsonObject(with: dData)) as? [String: Any],
               let songs = dRoot["songs"] as? [[String: Any]] {
                for s in songs {
                    if let parsed = Self.parseNetEaseSongObject(s, loggedIn: loggedIn) {
                        byId[parsed.sourceId] = parsed
                    }
                }
            }
        }
        let ordered = targetIds.compactMap { byId[$0] }
        return ordered.isEmpty ? directTracks : ordered
    }

    private func fetchQQPlaylistTracks(dissid: String, cookie: String?) async -> [MusicTrack] {
        guard let didNum = Int64(dissid),
              let url = URL(string: "https://u.y.qq.com/cgi-bin/musicu.fcg") else { return [] }
        let body: [String: Any] = [
            "comm": ["ct": 24, "cv": 0, "format": "json"],
            "req": [
                "module": "music.srfDissInfo.aiDissInfo",
                "method": "uniform_get_Dissinfo",
                "param": ["disstid": didNum, "song_begin": 0, "song_num": 100, "onlysonglist": 0]
            ]
        ]
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("https://y.qq.com/", forHTTPHeaderField: "Referer")
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        if let cookie, !cookie.isEmpty { req.setValue(cookie, forHTTPHeaderField: "Cookie") }
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let reqObj = root["req"] as? [String: Any],
              let d = reqObj["data"] as? [String: Any],
              let list = d["songlist"] as? [[String: Any]] else { return [] }
        return list.compactMap { s in
            let mid = (s["mid"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let name = MusicLyricsParser.decodeHTMLEntities((s["name"] as? String ?? s["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines))
            guard !mid.isEmpty, !name.isEmpty else { return nil }
            let singers = (s["singer"] as? [[String: Any]] ?? [])
            let artistStr = singers.compactMap { $0["name"] as? String }.joined(separator: " / ")
            let singerMid = singers.first?["mid"] as? String
            let al = s["album"] as? [String: Any]
            let albumName = MusicLyricsParser.decodeHTMLEntities(al?["name"] as? String ?? "")
            let albumMid = al?["mid"] as? String ?? ""
            let cover = !albumMid.isEmpty ? "https://y.gtimg.cn/music/photo_new/T002R300x300M000\(albumMid).jpg" : ""
            let dur = Double(s["interval"] as? Int ?? 210)
            return MusicTrack(
                id: "qq:\(mid)",
                source: .qq,
                sourceId: mid,
                title: name,
                artist: artistStr.isEmpty ? "未知歌手" : artistStr,
                album: albumName,
                coverURL: cover,
                durationSec: max(30, dur),
                qualityLabel: "SQ / 换源",
                entitlement: .full,
                artistId: singerMid,
                albumId: albumMid.isEmpty ? nil : albumMid
            )
        }
    }

    private func fetchKugouPlaylistTracks(globalCollectionId: String, cookie: String?) async -> [MusicTrack] {
        let parsed = Self.parseKugouCookie(cookie ?? "")
        let mid = Self.stableMusicDeviceId()
        let ts = "\(Int(Date().timeIntervalSince1970))"
        let bodyStr = "{\"global_collection_id\":\"\(globalCollectionId)\",\"page\":1,\"pagesize\":100,\"type\":1}"
        var qp: [String: String] = [
            "dfid": parsed.dfid, "mid": mid, "uuid": "-", "appid": "1005",
            "clientver": "11430", "clienttime": ts
        ]
        if !parsed.token.isEmpty { qp["token"] = parsed.token; qp["userid"] = parsed.userid }
        qp["signature"] = Self.kugouAndroidSign(qp, body: bodyStr)
        guard let url = URL(string: "https://gateway.kugou.com/v4/get_list_info?\(Self.kugouQueryString(qp))") else { return [] }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = Data(bodyStr.utf8)
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi", forHTTPHeaderField: "User-Agent")
        req.setValue("cloudlist.service.kugou.com", forHTTPHeaderField: "x-router")
        req.setValue(parsed.dfid, forHTTPHeaderField: "dfid")
        req.setValue(mid, forHTTPHeaderField: "mid")
        req.setValue(ts, forHTTPHeaderField: "clienttime")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any],
              let songs = d["songs"] as? [[String: Any]] else { return [] }
        return songs.compactMap { s in
            let hash = (s["hash"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let rawName = (s["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !hash.isEmpty, !rawName.isEmpty else { return nil }
            let parts = rawName.components(separatedBy: " - ")
            let artist = parts.count >= 2 ? parts[0].trimmingCharacters(in: .whitespaces) : ((s["singerinfo"] as? [[String: Any]])?.compactMap { $0["name"] as? String }.joined(separator: " / ") ?? "未知歌手")
            let title = parts.count >= 2 ? parts.dropFirst().joined(separator: " - ").trimmingCharacters(in: .whitespaces) : rawName
            let cover = Self.httpsURL((s["cover"] as? String ?? "").replacingOccurrences(of: "{size}", with: "400"))
            let dur = (s["timelen"] as? Double ?? Double(s["timelen"] as? Int ?? 210_000)) / 1000.0
            return MusicTrack(
                id: "kugou:\(hash)",
                source: .kugou,
                sourceId: hash,
                title: title,
                artist: artist.isEmpty ? "未知歌手" : artist,
                album: (s["albuminfo"] as? [String: Any])?["name"] as? String ?? "",
                coverURL: cover,
                durationSec: max(30, dur),
                qualityLabel: "HQ 320k",
                entitlement: .full,
                extraHash: hash
            )
        }
    }

    // MARK: - Charts (榜单)

    func fetchCharts(
        for source: MusicSourceKind?,
        enabledSources: [MusicSourceKind]? = nil,
        forceRefresh: Bool = false
    ) async -> [MusicChart] {
        let allOnline: [MusicSourceKind] = [.netease, .qq, .kugou, .qishui]
        let allowedOnline: [MusicSourceKind] = {
            guard let enabledSources else { return allOnline }
            return enabledSources.filter { allOnline.contains($0) }
        }()
        if let source {
            if enabledSources != nil && !allowedOnline.contains(source) { return [] }
        } else if allowedOnline.isEmpty {
            return []
        }

        let cacheKey: String = {
            if let source { return source.rawValue }
            return "all:\(allowedOnline.map(\.rawValue).joined(separator: ","))"
        }()
        if !forceRefresh, let hit = chartCache[cacheKey], Date().timeIntervalSince(hit.fetchedAt) < chartTTL {
            return hit.charts
        }

        let result: [MusicChart]
        switch source {
        case .netease:
            result = await fetchNetEaseCharts()
        case .qq:
            result = await fetchQQCharts()
        case .kugou:
            result = await fetchKugouCharts()
        case .qishui:
            result = await fetchQishuiCharts()
        case .none:
            let allowSet = Set(allowedOnline)
            async let ne: [MusicChart] = allowSet.contains(.netease) ? fetchNetEaseCharts() : []
            async let qq: [MusicChart] = allowSet.contains(.qq) ? fetchQQCharts() : []
            async let kg: [MusicChart] = allowSet.contains(.kugou) ? fetchKugouCharts() : []
            async let qs: [MusicChart] = allowSet.contains(.qishui) ? fetchQishuiCharts() : []
            let (a, b, c, d) = await (ne, qq, kg, qs)
            let byKind: [MusicSourceKind: [MusicChart]] = [.netease: a, .qq: b, .kugou: c, .qishui: d]
            let orderedBuckets = allowedOnline.compactMap { byKind[$0] }.filter { !$0.isEmpty }
            var merged: [MusicChart] = []
            let maxDepth = orderedBuckets.map(\.count).max() ?? 0
            for i in 0..<maxDepth {
                for bucket in orderedBuckets where i < bucket.count {
                    merged.append(bucket[i])
                }
            }
            result = merged
        default:
            result = []
        }

        if !result.isEmpty {
            chartCache[cacheKey] = (Date(), result)
        }
        return result
    }

    private func fetchNetEaseCharts() async -> [MusicChart] {
        let specs: [(id: String, name: String, freq: String)] = [
            ("3778678", "热歌榜", "每天更新"),
            ("19723756", "飙升榜", "每天更新"),
            ("3779629", "新歌榜", "每天更新"),
            ("2884035", "原创榜", "每周四更新")
        ]
        return await withTaskGroup(of: (Int, MusicChart?).self) { group in
            for (idx, sp) in specs.enumerated() {
                group.addTask {
                    let chart = await self.fetchNetEasePlaylistChart(id: sp.id, fallbackName: sp.name, freq: sp.freq)
                    return (idx, chart)
                }
            }
            var pairs: [(Int, MusicChart)] = []
            for await (i, c) in group { if let c { pairs.append((i, c)) } }
            return pairs.sorted { $0.0 < $1.0 }.map(\.1)
        }
    }

    private func fetchNetEasePlaylistChart(id: String, fallbackName: String, freq: String) async -> MusicChart? {
        guard let url = URL(string: "https://music.163.com/api/v6/playlist/detail?id=\(id)&n=30") else { return nil }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let pl = root["playlist"] as? [String: Any],
              let rawTracks = pl["tracks"] as? [[String: Any]], !rawTracks.isEmpty else { return nil }

        let tracks: [MusicTrack] = rawTracks.prefix(40).compactMap { t in
            guard let tid = t["id"] else { return nil }
            let sid = String(describing: tid)
            let name = (t["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty else { return nil }
            let arList = t["ar"] as? [[String: Any]] ?? []
            let artists = arList.compactMap { $0["name"] as? String }.joined(separator: " / ")
            let arId = arList.first?["id"].map { String(describing: $0) }
            let al = t["al"] as? [String: Any]
            let album = al?["name"] as? String ?? ""
            let alId = al?["id"].map { String(describing: $0) }
            let pic = Self.httpsURL(al?["picUrl"] as? String ?? "")
            let dt = (t["dt"] as? Double ?? Double(t["dt"] as? Int ?? 210_000)) / 1000.0
            let fee = t["fee"] as? Int ?? 0
            let entitlement: MusicEntitlement = (fee == 1 || fee == 4) ? .preview : .full
            return MusicTrack(
                id: "netease:\(sid)",
                source: .netease,
                sourceId: sid,
                title: name,
                artist: artists.isEmpty ? "未知歌手" : artists,
                album: album,
                coverURL: pic,
                durationSec: max(30, dt),
                qualityLabel: entitlement == .full ? "HQ 320k" : "320k / 自动换源",
                entitlement: entitlement,
                streamURL: nil,
                streamHeaders: nil,
                pluginId: nil,
                extraHash: nil,
                artistId: arId,
                albumId: alId
            )
        }
        guard !tracks.isEmpty else { return nil }
        let covers = tracks.prefix(3).map(\.coverURL).filter { !$0.isEmpty }
        let chartName = (pl["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? fallbackName
        return MusicChart(
            id: "netease:\(id)",
            source: .netease,
            name: chartName,
            updateFrequency: freq,
            updatedLabel: "今日更新 · \(freq)",
            coverURLs: covers,
            previewTracks: Array(tracks.prefix(3)),
            tracks: tracks
        )
    }

    private func fetchQQCharts() async -> [MusicChart] {
        let specs: [(topId: Int, name: String, freq: String)] = [
            (26, "QQ 热歌榜", "每天更新"),
            (62, "QQ 飙升榜", "每天更新"),
            (4, "QQ 流行指数榜", "每天更新"),
            (27, "QQ 新歌榜", "每天更新")
        ]
        return await withTaskGroup(of: (Int, MusicChart?).self) { group in
            for (idx, sp) in specs.enumerated() {
                group.addTask {
                    let chart = await self.fetchQQTopDetail(topId: sp.topId, fallbackName: sp.name, freq: sp.freq)
                    return (idx, chart)
                }
            }
            var pairs: [(Int, MusicChart)] = []
            for await (i, c) in group { if let c { pairs.append((i, c)) } }
            return pairs.sorted { $0.0 < $1.0 }.map(\.1)
        }
    }

    private func fetchQQTopDetail(topId: Int, fallbackName: String, freq: String) async -> MusicChart? {
        guard let url = URL(string: "https://u.y.qq.com/cgi-bin/musicu.fcg") else { return nil }
        let payload: [String: Any] = [
            "req_1": [
                "module": "musicToplist.ToplistInfoServer",
                "method": "GetDetail",
                "param": ["topid": topId, "offset": 0, "num": 30, "period": ""]
            ]
        ]
        guard let body = try? JSONSerialization.data(withJSONObject: payload) else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = body
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://y.qq.com/", forHTTPHeaderField: "Referer")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let r1 = root["req_1"] as? [String: Any],
              let d = r1["data"] as? [String: Any],
              let list = d["songInfoList"] as? [[String: Any]], !list.isEmpty else { return nil }

        let meta = d["data"] as? [String: Any]
        let title = (meta?["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? fallbackName
        let updateTime = (meta?["updateTime"] as? String) ?? "今日"

        let tracks: [MusicTrack] = list.compactMap { s in
            let mid = s["mid"] as? String ?? ""
            let name = (s["name"] as? String ?? s["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !mid.isEmpty, !name.isEmpty else { return nil }
            let singerList = s["singer"] as? [[String: Any]] ?? []
            let singers = singerList.compactMap { $0["name"] as? String }.joined(separator: " / ")
            let singerMid = singerList.first?["mid"] as? String
            let al = s["album"] as? [String: Any]
            let alMid = al?["mid"] as? String ?? ""
            let alName = al?["name"] as? String ?? ""
            let pic = alMid.isEmpty ? "" : "https://y.gtimg.cn/music/photo_new/T002R500x500M000\(alMid).jpg"
            let dur = Double(s["interval"] as? Int ?? 210)
            let pay = (s["pay"] as? [String: Any])?["pay_play"] as? Int ?? 0
            let ent: MusicEntitlement = pay == 1 ? .preview : .full
            return MusicTrack(
                id: "qq:\(mid)",
                source: .qq,
                sourceId: mid,
                title: name,
                artist: singers.isEmpty ? "未知歌手" : singers,
                album: alName,
                coverURL: pic,
                durationSec: max(30, dur),
                qualityLabel: ent == .full ? "SQ 无损" : "自动换源",
                entitlement: ent,
                streamURL: nil,
                streamHeaders: nil,
                pluginId: nil,
                extraHash: nil,
                artistId: singerMid,
                albumId: alMid.isEmpty ? nil : alMid
            )
        }
        guard !tracks.isEmpty else { return nil }
        return MusicChart(
            id: "qq:\(topId)",
            source: .qq,
            name: title.hasPrefix("QQ") ? title : "QQ \(title)",
            updateFrequency: freq,
            updatedLabel: "更新于 \(updateTime) · \(freq)",
            coverURLs: tracks.prefix(3).map(\.coverURL).filter { !$0.isEmpty },
            previewTracks: Array(tracks.prefix(3)),
            tracks: tracks
        )
    }

    private func fetchKugouCharts() async -> [MusicChart] {
        let specs: [(rankId: Int, name: String, freq: String)] = [
            (8888, "酷狗 TOP500", "每天更新"),
            (6666, "酷狗飙升榜", "每天更新"),
            (31308, "华语新歌榜", "每天更新")
        ]
        return await withTaskGroup(of: (Int, MusicChart?).self) { group in
            for (idx, sp) in specs.enumerated() {
                group.addTask {
                    let chart = await self.fetchKugouRankSongs(rankId: sp.rankId, name: sp.name, freq: sp.freq)
                    return (idx, chart)
                }
            }
            var pairs: [(Int, MusicChart)] = []
            for await (i, c) in group { if let c { pairs.append((i, c)) } }
            return pairs.sorted { $0.0 < $1.0 }.map(\.1)
        }
    }

    private func fetchKugouRankSongs(rankId: Int, name: String, freq: String) async -> MusicChart? {
        guard let url = URL(string: "http://mobilecdnbj.kugou.com/api/v3/rank/song?rankid=\(rankId)&page=1&pagesize=30&plat=0&version=9108") else { return nil }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any],
              let info = d["info"] as? [[String: Any]], !info.isEmpty else { return nil }

        let tracks: [MusicTrack] = info.compactMap { item in
            let hash = item["hash"] as? String ?? ""
            let filename = item["filename"] as? String ?? ""
            let parts = filename.components(separatedBy: " - ")
            let artist = parts.count >= 2 ? parts[0].trimmingCharacters(in: .whitespaces) : "未知歌手"
            let title = parts.count >= 2 ? parts.dropFirst().joined(separator: " - ").trimmingCharacters(in: .whitespaces) : filename
            guard !hash.isEmpty, !title.isEmpty else { return nil }
            let rawCover = (item["album_sizable_cover"] as? String ?? item["union_cover"] as? String ?? "")
                .replacingOccurrences(of: "{size}", with: "400")
            let dur = Double(item["duration"] as? Int ?? 210)
            let album = item["remark"] as? String ?? ""
            return MusicTrack(
                id: "kugou:\(hash)",
                source: .kugou,
                sourceId: hash,
                title: title,
                artist: artist,
                album: album,
                coverURL: Self.httpsURL(rawCover),
                durationSec: max(30, dur),
                qualityLabel: "HQ / 换源",
                entitlement: .full,
                streamURL: nil,
                streamHeaders: nil,
                pluginId: nil,
                extraHash: hash
            )
        }
        guard !tracks.isEmpty else { return nil }
        return MusicChart(
            id: "kugou:\(rankId)",
            source: .kugou,
            name: name,
            updateFrequency: freq,
            updatedLabel: "今日更新 · \(freq)",
            coverURLs: tracks.prefix(3).map(\.coverURL).filter { !$0.isEmpty },
            previewTracks: Array(tracks.prefix(3)),
            tracks: tracks
        )
    }

    // MARK: - Multi-Source Search (聚合搜歌)

    func search(query: String, enabledSources: [MusicSourceKind], nasConfigs: [MusicNASConfig]) async -> [MusicTrack] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }

        return await withTaskGroup(of: [MusicTrack].self) { group in
            if enabledSources.contains(.netease) {
                group.addTask { await self.searchNetEase(query: q, limit: 15) }
            }
            if enabledSources.contains(.kugou) {
                group.addTask { await self.searchKugou(query: q, limit: 12) }
            }
            if enabledSources.contains(.qq) {
                group.addTask { await self.searchQQSmartbox(query: q) }
            }
            if enabledSources.contains(.qishui) {
                let qsCookie = await MainActor.run { MusicStore.shared.cookie(for: .qishui) }
                group.addTask { await self.searchQishui(query: q, cookie: qsCookie) }
            }
            if enabledSources.contains(.itunes) {
                group.addTask { await self.searchITunes(query: q, limit: 10) }
            }
            for nas in nasConfigs where nas.enabled {
                group.addTask { await self.searchNAS(config: nas, query: q) }
            }

            var buckets: [[MusicTrack]] = []
            for await arr in group {
                if !arr.isEmpty { buckets.append(arr) }
            }
            // Interleave buckets so user immediately sees multi-source coverage without clutter.
            var merged: [MusicTrack] = []
            var seen = Set<String>()
            let maxLen = buckets.map(\.count).max() ?? 0
            for i in 0..<maxLen {
                for b in buckets where i < b.count {
                    let t = b[i]
                    if seen.insert(t.id).inserted { merged.append(t) }
                }
            }
            return merged
        }
    }

    func searchNetEase(query: String, limit: Int = 15) async -> [MusicTrack] {
        guard let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "https://music.163.com/api/search/get/web?s=\(encoded)&type=1&limit=\(limit)") else { return [] }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let res = root["result"] as? [String: Any],
              let songs = res["songs"] as? [[String: Any]] else { return [] }

        return songs.compactMap { s in
            guard let tid = s["id"] else { return nil }
            let sid = String(describing: tid)
            let name = (s["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty else { return nil }
            let arList = s["artists"] as? [[String: Any]] ?? []
            let artists = arList.compactMap { $0["name"] as? String }.joined(separator: " / ")
            let arId = arList.first?["id"].map { String(describing: $0) }
            let al = s["album"] as? [String: Any]
            let album = al?["name"] as? String ?? ""
            let alId = al?["id"].map { String(describing: $0) }
            let pic = Self.httpsURL((al?["artist"] as? [String: Any])?["img1v1Url"] as? String ?? al?["picUrl"] as? String ?? "")
            let dur = (s["duration"] as? Double ?? Double(s["duration"] as? Int ?? 210_000)) / 1000.0
            let fee = s["fee"] as? Int ?? 0
            let ent: MusicEntitlement = (fee == 1 || fee == 4) ? .preview : .full
            return MusicTrack(
                id: "netease:\(sid)",
                source: .netease,
                sourceId: sid,
                title: name,
                artist: artists.isEmpty ? "未知歌手" : artists,
                album: album,
                coverURL: pic,
                durationSec: max(30, dur),
                qualityLabel: ent == .full ? "HQ 320k" : "320k / 自动换源",
                entitlement: ent,
                streamURL: nil,
                streamHeaders: nil,
                pluginId: nil,
                extraHash: nil,
                artistId: arId,
                albumId: alId
            )
        }
    }

    func searchKugou(query: String, limit: Int = 12) async -> [MusicTrack] {
        guard let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=\(encoded)&page=1&pagesize=\(limit)") else { return [] }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any],
              let info = d["info"] as? [[String: Any]] else { return [] }

        return info.compactMap { s in
            let hash = s["hash"] as? String ?? ""
            let name = (s["songname"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !hash.isEmpty, !name.isEmpty else { return nil }
            let singer = s["singername"] as? String ?? "未知歌手"
            let album = s["album_name"] as? String ?? ""
            let dur = Double(s["duration"] as? Int ?? 210)
            return MusicTrack(
                id: "kugou:\(hash)",
                source: .kugou,
                sourceId: hash,
                title: name,
                artist: singer,
                album: album,
                coverURL: "",
                durationSec: max(30, dur),
                qualityLabel: "HQ / 换源",
                entitlement: .full,
                streamURL: nil,
                streamHeaders: nil,
                pluginId: nil,
                extraHash: hash
            )
        }
    }

    func searchQQSmartbox(query: String) async -> [MusicTrack] {
        guard let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=\(encoded)&format=json") else { return [] }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://y.qq.com/", forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any],
              let songBox = d["song"] as? [String: Any],
              let items = songBox["itemlist"] as? [[String: Any]] else { return [] }

        return items.compactMap { s in
            let mid = s["mid"] as? String ?? ""
            let name = (s["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !mid.isEmpty, !name.isEmpty else { return nil }
            let singer = s["singer"] as? String ?? "未知歌手"
            return MusicTrack(
                id: "qq:\(mid)",
                source: .qq,
                sourceId: mid,
                title: name,
                artist: singer,
                album: "",
                coverURL: "",
                durationSec: 220,
                qualityLabel: "SQ / 换源",
                entitlement: .full,
                streamURL: nil,
                streamHeaders: nil,
                pluginId: nil,
                extraHash: nil
            )
        }
    }

    func searchITunes(query: String, limit: Int = 8) async -> [MusicTrack] {
        guard let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "https://itunes.apple.com/search?term=\(encoded)&country=TW&entity=song&limit=\(limit)") else { return [] }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let items = root["results"] as? [[String: Any]] else { return [] }

        return items.compactMap { s in
            guard let tid = s["trackId"] else { return nil }
            let sid = String(describing: tid)
            let name = (s["trackName"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty else { return nil }
            let artist = s["artistName"] as? String ?? "未知歌手"
            let album = s["collectionName"] as? String ?? ""
            let art = (s["artworkUrl100"] as? String ?? "").replacingOccurrences(of: "100x100bb", with: "600x600bb")
            let preview = s["previewUrl"] as? String
            let dur = (s["trackTimeMillis"] as? Double ?? Double(s["trackTimeMillis"] as? Int ?? 210_000)) / 1000.0
            return MusicTrack(
                id: "itunes:\(sid)",
                source: .itunes,
                sourceId: sid,
                title: name,
                artist: artist,
                album: album,
                coverURL: art,
                durationSec: max(30, dur),
                qualityLabel: "AAC 256k 试听",
                entitlement: .preview,
                streamURL: preview,
                streamHeaders: nil,
                pluginId: nil,
                extraHash: nil
            )
        }
    }

    // MARK: - Stream Resolution & Cross-Source Auto Fallback (直连与自动换源)

    struct ResolvedAudio {
        let url: URL
        let headers: [String: String]
        let qualityLabel: String
        let entitlement: MusicEntitlement
        let actualSource: MusicSourceKind
        let isCached: Bool
    }

    func resolveStream(
        for track: MusicTrack,
        cachedFile: URL?,
        cookies: [MusicSourceKind: String],
        enabledSources: [MusicSourceKind],
        nasConfigs: [MusicNASConfig],
        autoFallback: Bool,
        preferredQuality: MusicAudioQuality = .hires
    ) async -> ResolvedAudio? {
        if let cachedFile {
            let ext = cachedFile.pathExtension.lowercased()
            let isCachedLossless = (ext == "flac" || ext == "wav")
            let wantsLossless = (preferredQuality == .hires || preferredQuality == .lossless)
            let isLoggedInOnline = (track.source == .netease || track.source == .qq || track.source == .kugou)
                && !(cookies[track.source]?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
            let shouldUpgradeLossyCache = isLoggedInOnline && wantsLossless && !isCachedLossless
            if !shouldUpgradeLossyCache {
                let fileBytes = (try? FileManager.default.attributesOfItem(atPath: cachedFile.path)[.size] as? Int64) ?? 0
                let estKbps = (track.durationSec > 10 && fileBytes > 0) ? Int(Double(fileBytes * 8) / (track.durationSec * 1000.0)) : 0
                let qLabel: String
                if isCachedLossless {
                    qLabel = estKbps >= 1200 ? "Hi-Res 母带 · 离线缓存" : "FLAC 无损 · 离线缓存"
                } else if ext == "mp3" {
                    qLabel = estKbps >= 260 ? "HQ 320k · 离线缓存" : "MP3 · 离线缓存"
                } else if ext == "m4a" || ext == "aac" {
                    qLabel = "AAC · 离线缓存"
                } else {
                    qLabel = "\(track.qualityLabel) · 离线缓存"
                }
                return ResolvedAudio(
                    url: cachedFile,
                    headers: [:],
                    qualityLabel: qLabel,
                    entitlement: track.source == .itunes ? .preview : .full,
                    actualSource: track.source,
                    isCached: true
                )
            }
        }

        // 1) Direct stream URL already attached (e.g., NAS or iTunes preview)
        if let direct = track.streamURL, let u = URL(string: direct) {
            return ResolvedAudio(
                url: u,
                headers: track.streamHeaders ?? [:],
                qualityLabel: track.qualityLabel,
                entitlement: track.entitlement,
                actualSource: track.source,
                isCached: false
            )
        }

        // 2) Try primary source resolver
        if let primary = await resolveSingleSource(track: track, cookies: cookies, preferredQuality: preferredQuality) {
            return primary
        }

        guard autoFallback else { return nil }

        // 3) Cross-source automatic fallback (NAS lossless first -> NetEase free -> iTunes AAC preview)
        let q = "\(track.title) \(track.artist.components(separatedBy: "/").first ?? track.artist)"
        for nas in nasConfigs where nas.enabled {
            let hits = await searchNAS(config: nas, query: track.title)
            if let best = Self.bestMatch(in: hits, target: track),
               let direct = best.streamURL, let u = URL(string: direct) {
                return ResolvedAudio(
                    url: u,
                    headers: best.streamHeaders ?? [:],
                    qualityLabel: best.qualityLabel,
                    entitlement: .full,
                    actualSource: nas.kind,
                    isCached: false
                )
            }
        }

        if track.source != .netease && enabledSources.contains(.netease) {
            let neHits = await searchNetEase(query: q, limit: 5)
            if let best = Self.bestMatch(in: neHits, target: track),
               let resolved = await resolveSingleSource(track: best, cookies: cookies, preferredQuality: preferredQuality) {
                return resolved
            }
        }

        if enabledSources.contains(.itunes) {
            let itHits = await searchITunes(query: q, limit: 5)
            if let best = Self.bestMatch(in: itHits, target: track) ?? itHits.first,
               let direct = best.streamURL, let u = URL(string: direct) {
                return ResolvedAudio(
                    url: u,
                    headers: [:],
                    qualityLabel: "AAC 256k · 试听换源",
                    entitlement: .preview,
                    actualSource: .itunes,
                    isCached: false
                )
            }
        }
        return nil
    }

    func findAlternatives(
        for track: MusicTrack,
        cookies: [MusicSourceKind: String],
        enabledSources: [MusicSourceKind],
        nasConfigs: [MusicNASConfig]
    ) async -> [MusicAlternative] {
        let q = "\(track.title) \(track.artist.components(separatedBy: "/").first ?? track.artist)"
        var out: [MusicAlternative] = []
        let t0 = Date()

        // NAS matches
        for nas in nasConfigs where nas.enabled {
            let s0 = Date()
            let hits = await searchNAS(config: nas, query: track.title)
            if let m = Self.bestMatch(in: hits, target: track) {
                let ms = Int(Date().timeIntervalSince(s0) * 1000)
                out.append(MusicAlternative(id: m.id, track: m, latencyMs: max(8, ms), qualityLabel: m.qualityLabel, entitlement: .full, resolvedURL: m.streamURL, headers: m.streamHeaders))
            }
        }
        // NetEase match
        if enabledSources.contains(.netease) {
            let s0 = Date()
            let neTrack: MusicTrack?
            if track.source == .netease { neTrack = track }
            else { neTrack = Self.bestMatch(in: await searchNetEase(query: q, limit: 5), target: track) }
            if let cand = neTrack {
                let res = await resolveSingleSource(track: cand, cookies: cookies)
                let ms = Int(Date().timeIntervalSince(s0) * 1000)
                out.append(MusicAlternative(
                    id: cand.id,
                    track: cand,
                    latencyMs: max(15, ms),
                    qualityLabel: res?.qualityLabel ?? "需 Cookie / VIP",
                    entitlement: res?.entitlement ?? .unavailable,
                    resolvedURL: res?.url.absoluteString,
                    headers: res?.headers
                ))
            }
        }
        // iTunes guaranteed preview fallback
        if enabledSources.contains(.itunes) {
            let s0 = Date()
            let hits = await searchITunes(query: q, limit: 4)
            if let m = Self.bestMatch(in: hits, target: track) ?? hits.first {
                let ms = Int(Date().timeIntervalSince(s0) * 1000)
                out.append(MusicAlternative(id: m.id, track: m, latencyMs: max(20, ms), qualityLabel: "AAC 256k 试听", entitlement: .preview, resolvedURL: m.streamURL, headers: [:]))
            }
        }
        _ = t0
        return out
    }

    func resolveFallbackStream(
        for track: MusicTrack,
        excludingURL: URL,
        cookies: [MusicSourceKind: String],
        enabledSources: [MusicSourceKind],
        nasConfigs: [MusicNASConfig]
    ) async -> ResolvedAudio? {
        let q = "\(track.title) \(track.primaryArtistName)"
        if track.source == .netease {
            if let outer = URL(string: "https://music.163.com/song/media/outer/url?id=\(track.sourceId).mp3"),
               let loc = await resolveRedirectLocation(for: outer),
               !loc.absoluteString.contains("/404"),
               let https = URL(string: Self.httpsURL(loc.absoluteString)),
               https != excludingURL {
                return ResolvedAudio(url: https, headers: [:], qualityLabel: "MP3 直连", entitlement: .full, actualSource: .netease, isCached: false)
            }
        }
        let itHits = await searchITunes(query: q, limit: 5)
        if let best = Self.bestMatch(in: itHits, target: track) ?? itHits.first,
           let direct = best.streamURL, let u = URL(string: direct), u != excludingURL {
            return ResolvedAudio(url: u, headers: [:], qualityLabel: "AAC 256k 试听", entitlement: .preview, actualSource: .itunes, isCached: false)
        }
        return nil
    }

    private func resolveSingleSource(
        track: MusicTrack,
        cookies: [MusicSourceKind: String],
        preferredQuality: MusicAudioQuality = .hires
    ) async -> ResolvedAudio? {
        switch track.source {
        case .netease:
            let tiers: [(String, String)] = switch preferredQuality {
            case .hires: [("hires", "flac"), ("lossless", "flac"), ("exhigh", "mp3"), ("standard", "mp3")]
            case .lossless: [("lossless", "flac"), ("exhigh", "mp3"), ("standard", "mp3")]
            case .exhigh: [("exhigh", "mp3"), ("standard", "mp3")]
            case .standard: [("standard", "mp3")]
            }
            // 1) Logged-in NetEase account cookie -> respect chosen quality tier with automatic degradation
            if let cookie = cookies[.netease], !cookie.isEmpty {
                var trialCandidate: ResolvedAudio? = nil
                for (level, enc) in tiers {
                    if let res = await fetchNetEaseEnhance(songId: track.sourceId, level: level, encodeType: enc, cookie: cookie) {
                        if res.entitlement == .full { return res }
                        if trialCandidate == nil { trialCandidate = res }
                    }
                }
                if let trialCandidate { return trialCandidate }
            }
            // 2) Zero-auth NetEase enhance direct HTTPS MP3 (using anonymous visitor cookie when available)
            let anonCookie = await ensureNetEaseAnonCookie()
            let guestLevel = preferredQuality == .standard ? "standard" : "exhigh"
            let enhanceNoCookie = await fetchNetEaseEnhance(songId: track.sourceId, level: guestLevel, encodeType: "mp3", cookie: anonCookie)
            if let enhanceNoCookie, enhanceNoCookie.entitlement == .full {
                return enhanceNoCookie
            }
            // 3) Direct outer HTTPS redirect or official enhance preview
            if let outer = URL(string: "https://music.163.com/song/media/outer/url?id=\(track.sourceId).mp3"),
               let resolved = await resolveRedirectLocation(for: outer),
               !resolved.absoluteString.contains("/404"),
               let https = URL(string: Self.httpsURL(resolved.absoluteString)) {
                return ResolvedAudio(
                    url: https,
                    headers: [:],
                    qualityLabel: "MP3 直连",
                    entitlement: .full,
                    actualSource: .netease,
                    isCached: false
                )
            }
            if let enhanceNoCookie {
                return enhanceNoCookie
            }
            return nil
        case .qq:
            if let cookie = cookies[.qq], !cookie.isEmpty {
                return await resolveQQStream(songMid: track.sourceId, cookie: cookie, preferredQuality: preferredQuality)
            }
            return nil
        case .kugou:
            if let cookie = cookies[.kugou], !cookie.isEmpty {
                return await resolveKugouStream(hash: track.extraHash ?? track.sourceId, cookie: cookie, preferredQuality: preferredQuality)
            }
            return nil
        case .qishui:
            return await resolveQishuiStream(trackId: track.sourceId, cookie: cookies[.qishui])
        default:
            return nil
        }
    }

    private var cachedNetEaseAnonCookie: (cookie: String, fetchedAt: Date)?

    func ensureNetEaseAnonCookie() async -> String? {
        if let hit = cachedNetEaseAnonCookie, Date().timeIntervalSince(hit.fetchedAt) < 12 * 3600 {
            return hit.cookie
        }
        let devId = Self.stableMusicDeviceId()
        let xorKey = Array("3go8&$8*3*3h0k(2)2".utf8)
        let devBytes = Array(devId.utf8)
        let xored = devBytes.enumerated().map { idx, b in b ^ xorKey[idx % xorKey.count] }
        let md5B64 = Data(Insecure.MD5.hash(data: Data(xored))).base64EncodedString()
        let encodedId = Data("\(devId) \(md5B64)".utf8).base64EncodedString()
        guard let res = await postNetEaseEAPI(apiPath: "/api/register/anonimous", payload: ["username": encodedId]),
              (res.json["code"] as? Int) == 200 else { return nil }
        var hdrs: [String: String] = [:]
        for (k, v) in res.http.allHeaderFields {
            if let ks = k as? String, let vs = v as? String { hdrs[ks] = vs }
        }
        let cookies = HTTPCookie.cookies(withResponseHeaderFields: hdrs, for: res.url)
        let merged = cookies.filter { !$0.value.isEmpty }.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
        guard !merged.isEmpty else { return nil }
        cachedNetEaseAnonCookie = (merged, Date())
        return merged
    }

    private func fetchNetEaseEnhance(songId: String, level: String, encodeType: String, cookie: String?) async -> ResolvedAudio? {
        let extraCookie = (cookie?.isEmpty == false) ? cookie! : ""
        if let eapiRes = await postNetEaseEAPI(
            apiPath: "/api/song/enhance/player/url/v1",
            payload: ["ids": "[\(songId)]", "level": level, "encodeType": encodeType],
            extraCookie: extraCookie
        ),
           let arr = eapiRes.json["data"] as? [[String: Any]],
           let first = arr.first,
           let urlStr = first["url"] as? String, !urlStr.isEmpty,
           let audioURL = URL(string: Self.httpsURL(urlStr)) {
            let br = (first["br"] as? Int ?? 320_000) / 1000
            let typeStr = (first["type"] as? String ?? "mp3").uppercased()
            let actualLevel = (first["level"] as? String ?? level).lowercased()
            let isTrial = (first["freeTrialInfo"] is [String: Any])
            let qLabel: String
            if isTrial {
                qLabel = "30秒试听 · \(br)k"
            } else if actualLevel == "hires" || actualLevel == "jymaster" || br >= 1200 {
                qLabel = "Hi-Res 母带"
            } else if actualLevel == "lossless" || typeStr == "FLAC" || br >= 700 {
                qLabel = "FLAC 无损"
            } else if br >= 300 {
                qLabel = "HQ 320k"
            } else {
                qLabel = "标准 \(br)k"
            }
            return ResolvedAudio(
                url: audioURL,
                headers: [:],
                qualityLabel: qLabel,
                entitlement: isTrial ? .preview : .full,
                actualSource: .netease,
                isCached: false
            )
        }
        guard let url = URL(string: "https://music.163.com/api/song/enhance/player/url/v1?ids=%5B\(songId)%5D&level=\(level)&encodeType=\(encodeType)") else { return nil }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        if let cookie, !cookie.isEmpty { req.setValue(cookie, forHTTPHeaderField: "Cookie") }
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let arr = root["data"] as? [[String: Any]],
              let first = arr.first,
              let urlStr = first["url"] as? String, !urlStr.isEmpty,
              let audioURL = URL(string: Self.httpsURL(urlStr)) else { return nil }
        let br = (first["br"] as? Int ?? 320_000) / 1000
        let typeStr = (first["type"] as? String ?? "mp3").uppercased()
        let isTrial = (first["freeTrialInfo"] is [String: Any])
        return ResolvedAudio(
            url: audioURL,
            headers: [:],
            qualityLabel: isTrial ? "30秒试听 · \(br)k" : (br >= 700 ? "FLAC 无损" : "\(typeStr) \(br)k"),
            entitlement: isTrial ? .preview : .full,
            actualSource: .netease,
            isCached: false
        )
    }

    private func resolveRedirectLocation(for url: URL) async -> URL? {
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        guard let (_, resp) = try? await session.data(for: req, delegate: MusicNoRedirectDelegate()),
              let http = resp as? HTTPURLResponse else { return nil }
        if (300..<400).contains(http.statusCode),
           let loc = http.value(forHTTPHeaderField: "Location"),
           let target = URL(string: loc) {
            return target
        }
        return (200..<300).contains(http.statusCode) ? url : nil
    }

    // MARK: - NetEase Serverless Native Phone + SMS / Password / QR Login & Account Info

    enum NetEaseSMSResult: Equatable {
        case sent
        case failed(String)
    }

    enum NetEasePhoneLoginResult: Equatable {
        case authorized(cookie: String, nickname: String?, avatarURL: String?)
        case requiresWebOrQR(String)
        case failed(String)
    }

    struct NetEaseQRSession {
        let key: String
        let url: String
        let deviceId: String
    }

    enum NetEaseQRPoll {
        case waiting
        case scanned(nickname: String?)
        case expired
        case refused(String)
        case authorized(cookie: String)
        case failed(String)
    }

    struct KugouQRSession {
        let key: String
        let imageData: Data?
        let qrText: String
    }

    enum KugouQRPoll {
        case waiting
        case scanned(nickname: String?)
        case expired
        case authorized(cookie: String, nickname: String?, avatarURL: String?)
        case failed(String)
    }

    static func normalizePhone(_ raw: String, countryCode: String = "86") -> (phone: String, ctcode: String) {
        let ct = countryCode.filter(\.isNumber)
        let resolvedCt = ct.isEmpty ? "86" : ct
        var digits = raw.filter(\.isNumber)
        if resolvedCt == "86", digits.hasPrefix("86"), digits.count > 11 {
            digits = String(digits.dropFirst(2))
        } else if digits.hasPrefix(resolvedCt), digits.count > resolvedCt.count + 7 {
            digits = String(digits.dropFirst(resolvedCt.count))
        }
        return (digits, resolvedCt)
    }

    static func md5Hex(_ s: String) -> String {
        Insecure.MD5.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func eapiEncrypt(apiPath: String, payload: [String: Any]) -> String? {
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload, options: [.withoutEscapingSlashes]),
              let text = String(data: jsonData, encoding: .utf8) else { return nil }
        let digest = md5Hex("nobody\(apiPath)use\(text)md5forencrypt")
        let message = "\(apiPath)-36cd479b6b5-\(text)-36cd479b6b5-\(digest)"
        let keyBytes = Array("e82ckenh8dichen8".utf8)
        let inputBytes = Array(message.utf8)
        let outCapacity = inputBytes.count + kCCBlockSizeAES128
        var outBytes = [UInt8](repeating: 0, count: outCapacity)
        var outLen: Int = 0
        let status = CCCrypt(
            CCOperation(kCCEncrypt),
            CCAlgorithm(kCCAlgorithmAES),
            CCOptions(kCCOptionECBMode | kCCOptionPKCS7Padding),
            keyBytes, keyBytes.count,
            nil,
            inputBytes, inputBytes.count,
            &outBytes, outCapacity,
            &outLen
        )
        guard status == kCCSuccess else { return nil }
        return outBytes.prefix(outLen).map { String(format: "%02X", $0) }.joined()
    }

    private static func stableMusicDeviceId() -> String {
        let key = "stv.music.client.deviceid"
        if let existing = UserDefaults.standard.string(forKey: key), !existing.isEmpty {
            return existing
        }
        let fresh = md5Hex("iduanju-ios-\(UUID().uuidString)")
        UserDefaults.standard.set(fresh, forKey: key)
        return fresh
    }

    private static let neteasePCAppVersion = "3.1.17.204416"
    private static let neteasePCUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.17.204416 Safari/537.36"

    private static func randomHexUpper(length: Int) -> String {
        let alphabet = Array("0123456789ABCDEF")
        return String((0..<length).map { _ in alphabet[Int.random(in: 0..<alphabet.count)] })
    }

    private func buildNetEaseEAPIHeader(
        deviceId: String,
        musicU: String? = nil,
        musicA: String? = nil,
        csrf: String = ""
    ) -> [String: String] {
        let nowMs = Int(Date().timeIntervalSince1970 * 1000)
        var hdr: [String: String] = [
            "osver": "Microsoft-Windows-10-Professional-build-19045-64bit",
            "deviceId": deviceId,
            "appver": Self.neteasePCAppVersion,
            "versioncode": "140",
            "mobilename": "",
            "buildver": "\(nowMs / 1000)",
            "resolution": "1920x1080",
            "__csrf": csrf,
            "os": "pc",
            "channel": "netease",
            "requestId": "\(nowMs)_\(String(format: "%04d", Int.random(in: 0...9999)))"
        ]
        if let musicU, !musicU.isEmpty {
            hdr["MUSIC_U"] = musicU
        }
        if let musicA, !musicA.isEmpty {
            hdr["MUSIC_A"] = musicA
        }
        return hdr
    }

    private func postNetEaseEAPI(
        apiPath: String,
        payload: [String: Any],
        extraCookie: String = "",
        deviceId: String? = nil,
        usePCDomain: Bool = false
    ) async -> (json: [String: Any], http: HTTPURLResponse, url: URL)? {
        let suffix = apiPath.hasPrefix("/api") ? String(apiPath.dropFirst(4)) : apiPath
        let host = usePCDomain ? "https://interfacepc.music.163.com" : "https://interface.music.163.com"
        guard let url = URL(string: "\(host)/eapi\(suffix)") else { return nil }
        let devId = deviceId ?? Self.stableMusicDeviceId().uppercased()
        var musicU: String? = nil
        var musicA: String? = nil
        var csrf = ""
        if !extraCookie.isEmpty {
            for seg in extraCookie.components(separatedBy: ";") {
                let t = seg.trimmingCharacters(in: .whitespacesAndNewlines)
                if t.hasPrefix("MUSIC_U=") {
                    musicU = String(t.dropFirst("MUSIC_U=".count))
                } else if t.hasPrefix("MUSIC_A=") {
                    musicA = String(t.dropFirst("MUSIC_A=".count))
                } else if t.hasPrefix("__csrf=") {
                    csrf = String(t.dropFirst("__csrf=".count))
                }
            }
        }
        let headerDict = buildNetEaseEAPIHeader(deviceId: devId, musicU: musicU, musicA: musicA, csrf: csrf)
        var body = payload
        body["header"] = headerDict
        body["e_r"] = false
        guard let paramsHex = Self.eapiEncrypt(apiPath: apiPath, payload: body) else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = Data("params=\(paramsHex)".utf8)
        req.setValue(Self.neteasePCUserAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let baseCookie = headerDict.keys.sorted().map { k in
            "\(Self.urlValueEnc(k))=\(Self.urlValueEnc(headerDict[k] ?? ""))"
        }.joined(separator: "; ")
        req.setValue(extraCookie.isEmpty ? baseCookie : "\(baseCookie); \(extraCookie)", forHTTPHeaderField: "Cookie")
        guard let (data, resp) = try? await session.data(for: req),
              let http = resp as? HTTPURLResponse,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        return (root, http, url)
    }

    func sendNetEaseSMSCode(phone: String, countryCode: String = "86") async -> NetEaseSMSResult {
        let norm = Self.normalizePhone(phone, countryCode: countryCode)
        guard norm.phone.count >= 6 else { return .failed("请输入有效的手机号") }
        guard let res = await postNetEaseEAPI(
            apiPath: "/api/sms/captcha/sent",
            payload: ["cellphone": norm.phone, "ctcode": norm.ctcode, "secrete": "music_middleuser_pclogin"]
        ) else {
            return .failed("无法直连网易云短信接口，请检查网络或改用扫码 / 桌面网页登录")
        }
        let code = res.json["code"] as? Int ?? 0
        if code == 200 { return .sent }
        let msg = (res.json["message"] as? String) ?? (res.json["msg"] as? String) ?? "发送失败（状态码 \(code)）"
        return .failed(msg)
    }

    private func parseNetEaseLoginResponse(_ res: (json: [String: Any], http: HTTPURLResponse, url: URL)) -> NetEasePhoneLoginResult {
        let code = res.json["code"] as? Int ?? 0
        if code == 200 {
            var headerDict: [String: String] = [:]
            for (k, v) in res.http.allHeaderFields {
                if let ks = k as? String, let vs = v as? String {
                    headerDict[ks] = vs
                }
            }
            let httpCookies = HTTPCookie.cookies(withResponseHeaderFields: headerDict, for: res.url)
            var pairs: [String: String] = [:]
            for c in httpCookies where !c.value.isEmpty {
                pairs[c.name] = c.value
            }
            if let rawCk = res.json["cookie"] as? String, !rawCk.isEmpty {
                let metaKeys: Set<String> = ["path", "domain", "expires", "max-age", "httponly", "secure", "samesite"]
                for segment in rawCk.components(separatedBy: ";") {
                    let trimmed = segment.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard let eq = trimmed.firstIndex(of: "=") else { continue }
                    let k = String(trimmed[..<eq]).trimmingCharacters(in: .whitespaces)
                    let v = String(trimmed[trimmed.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
                    if !k.isEmpty, !v.isEmpty, !metaKeys.contains(k.lowercased()), pairs[k] == nil {
                        pairs[k] = v
                    }
                }
            }
            let merged = pairs.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
            guard !merged.isEmpty else {
                return .requiresWebOrQR("登录已通过但未返回 Cookie，请改用扫码或桌面网页登录")
            }
            let profile = res.json["profile"] as? [String: Any]
            let nick = (profile?["nickname"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            let avatar = (profile?["avatarUrl"] as? String).map { Self.httpsURL($0) }
            return .authorized(cookie: merged, nickname: nick?.isEmpty == false ? nick : nil, avatarURL: avatar)
        }
        let msg = (res.json["message"] as? String) ?? (res.json["msg"] as? String) ?? (code == 502 ? "账号或密码错误" : (code == 501 ? "账号不存在" : "登录失败（状态码 \(code)）"))
        if code == -460 || code == -462 || code == 509 || code == 8821 || msg.contains("风险") || msg.contains("安全验证") || msg.contains("滑块") || msg.contains("升级") {
            return .requiresWebOrQR("\(msg)，建议改用「扫码」或「桌面网页」完成验证")
        }
        return .failed(msg)
    }

    func loginNetEaseByPhone(
        phone: String,
        countryCode: String = "86",
        captcha: String? = nil,
        password: String? = nil
    ) async -> NetEasePhoneLoginResult {
        let norm = Self.normalizePhone(phone, countryCode: countryCode)
        guard norm.phone.count >= 6 else { return .failed("请输入有效的手机号") }
        var payload: [String: Any] = [
            "type": "1",
            "https": "true",
            "phone": norm.phone,
            "countrycode": norm.ctcode,
            "remember": "true",
            "rememberLogin": "true"
        ]
        if let cap = captcha?.trimmingCharacters(in: .whitespacesAndNewlines), !cap.isEmpty {
            payload["captcha"] = cap
        } else if let pwd = password, !pwd.isEmpty {
            payload["password"] = Self.md5Hex(pwd)
        } else {
            return .failed("请输入验证码或密码")
        }
        guard let res = await postNetEaseEAPI(apiPath: "/api/w/login/cellphone", payload: payload) else {
            return .failed("无法直连网易云登录接口，请检查网络后重试")
        }
        return parseNetEaseLoginResponse(res)
    }

    func loginNetEaseByEmail(email: String, password: String) async -> NetEasePhoneLoginResult {
        let cleanEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleanEmail.contains("@"), !password.isEmpty else { return .failed("请输入有效的邮箱和密码") }
        let payload: [String: Any] = [
            "type": "0",
            "https": "true",
            "username": cleanEmail,
            "password": Self.md5Hex(password),
            "rememberLogin": "true"
        ]
        guard let res = await postNetEaseEAPI(apiPath: "/api/w/login", payload: payload) else {
            return .failed("无法直连网易云邮箱登录接口，请检查网络后重试")
        }
        return parseNetEaseLoginResponse(res)
    }

    func createNetEaseQRSession() async -> NetEaseQRSession? {
        let device = Self.randomHexUpper(length: 32)
        let anon = await ensureNetEaseAnonCookie() ?? ""
        guard let res = await postNetEaseEAPI(
            apiPath: "/api/login/qrcode/unikey",
            payload: ["type": 3],
            extraCookie: anon,
            deviceId: device,
            usePCDomain: true
        ),
              let key = res.json["unikey"] as? String, !key.isEmpty else {
            return nil
        }
        let ts = Int(Date().timeIntervalSince1970 * 1000)
        let chainId = "v1_\(device)_pc_login_\(ts)"
        let qrUrl = "https://music.163.com/login?codekey=\(Self.urlValueEnc(key))&chainId=\(Self.urlValueEnc(chainId))"
        return NetEaseQRSession(key: key, url: qrUrl, deviceId: device)
    }

    func createNetEaseQRKey() async -> String? {
        await createNetEaseQRSession()?.key
    }

    func pollNetEaseQR(session qr: NetEaseQRSession) async -> NetEaseQRPoll {
        let anon = await ensureNetEaseAnonCookie() ?? ""
        guard let res = await postNetEaseEAPI(
            apiPath: "/api/login/qrcode/client/login",
            payload: ["key": qr.key, "type": 3],
            extraCookie: anon,
            deviceId: qr.deviceId,
            usePCDomain: true
        ) else {
            return .failed("网络波动，等待重试…")
        }
        let root = res.json
        let code = root["code"] as? Int ?? 0
        switch code {
        case 800: return .expired
        case 801: return .waiting
        case 802: return .scanned(nickname: root["nickname"] as? String)
        case 803:
            var headers: [String: String] = [:]
            for (k, v) in res.http.allHeaderFields {
                if let ks = k as? String, let vs = v as? String { headers[ks] = vs }
            }
            let cookies = HTTPCookie.cookies(withResponseHeaderFields: headers, for: res.url)
            var pairs: [String: String] = [:]
            for c in cookies where !c.value.isEmpty { pairs[c.name] = c.value }
            if let raw = root["cookie"] as? String, !raw.isEmpty {
                let metaKeys: Set<String> = ["path", "domain", "expires", "max-age", "httponly", "secure", "samesite"]
                for seg in raw.components(separatedBy: ";") {
                    let t = seg.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard let eq = t.firstIndex(of: "=") else { continue }
                    let k = String(t[..<eq]).trimmingCharacters(in: .whitespaces)
                    let v = String(t[t.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
                    if !k.isEmpty, !v.isEmpty, !metaKeys.contains(k.lowercased()), pairs[k] == nil {
                        pairs[k] = v
                    }
                }
            }
            let merged = pairs.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
            if !merged.isEmpty { return .authorized(cookie: merged) }
            return .failed("登录成功但未收到 Cookie，请改用桌面网页登录")
        default:
            let msg = (root["message"] as? String) ?? (root["msg"] as? String) ?? ""
            if code == 8821 || code == -462 || code == -460 || msg.contains("升级") || msg.contains("风险") || msg.contains("验证") {
                let reason = msg.isEmpty ? "\(code)" : "\(code)：\(msg)"
                return .refused("网易云拒绝了本次扫码（\(reason)），请切换「桌面网页」或「手机号」登录")
            }
            return .failed(msg.isEmpty ? "状态码 \(code)" : msg)
        }
    }

    func pollNetEaseQR(key: String) async -> NetEaseQRPoll {
        let fallback = NetEaseQRSession(key: key, url: "https://music.163.com/login?codekey=\(key)", deviceId: Self.stableMusicDeviceId().uppercased())
        return await pollNetEaseQR(session: fallback)
    }

    func fetchNetEaseAccount(cookie: String) async -> (nickname: String, avatarURL: String)? {
        guard let url = URL(string: "https://music.163.com/api/nuser/account/get") else { return nil }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        req.setValue(cookie, forHTTPHeaderField: "Cookie")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let profile = root["profile"] as? [String: Any],
              let nick = profile["nickname"] as? String, !nick.isEmpty else { return nil }
        let avatar = Self.httpsURL(profile["avatarUrl"] as? String ?? "")
        return (nick, avatar)
    }

    // MARK: - Kugou Serverless Native QR Login

    private static func kugouSign(_ params: [String: String], body: String = "") -> String {
        let salt = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
        let joined = params.keys.sorted().map { "\($0)=\(params[$0] ?? "")" }.joined()
        return md5Hex(salt + joined + body + salt).uppercased()
    }

    private static func kugouQueryString(_ params: [String: String]) -> String {
        params.keys.sorted().map { "\($0)=\(urlValueEnc(params[$0] ?? ""))" }.joined(separator: "&")
    }

    func createKugouQR() async -> KugouQRSession? {
        let mid = Self.stableMusicDeviceId()
        let ts = "\(Int(Date().timeIntervalSince1970 * 1000))"
        let baseQR = "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=1001&"
        var params: [String: String] = [
            "appid": "1001",
            "clienttime": ts,
            "clientver": "1000",
            "dfid": "-",
            "mid": mid,
            "plat": "4",
            "qrcode_txt": baseQR,
            "srcappid": "2919",
            "type": "1",
            "uuid": mid
        ]
        params["signature"] = Self.kugouSign(params)
        guard let url = URL(string: "https://login-user.kugou.com/v2/qrcode?\(Self.kugouQueryString(params))") else { return nil }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://www.kugou.com/", forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any],
              let key = d["qrcode"] as? String, !key.isEmpty else { return nil }
        var imgData: Data?
        if let rawImg = d["qrcode_img"] as? String {
            let b64 = rawImg.components(separatedBy: ",").last ?? rawImg
            imgData = Data(base64Encoded: b64)
        }
        return KugouQRSession(key: key, imageData: imgData, qrText: "\(baseQR)qrcode=\(key)")
    }

    func pollKugouQR(key: String) async -> KugouQRPoll {
        let mid = Self.stableMusicDeviceId()
        let ts = "\(Int(Date().timeIntervalSince1970 * 1000))"
        var params: [String: String] = [
            "appid": "1001",
            "clienttime": ts,
            "clientver": "1000",
            "dfid": "-",
            "mid": mid,
            "plat": "4",
            "qrcode": key,
            "srcappid": "2919",
            "uuid": mid
        ]
        params["signature"] = Self.kugouSign(params)
        guard let url = URL(string: "https://login-user.kugou.com/v2/get_userinfo_qrcode?\(Self.kugouQueryString(params))") else {
            return .failed("请求地址无效")
        }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://www.kugou.com/", forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await session.data(for: req),
              let http = resp as? HTTPURLResponse,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any] else {
            return .failed("网络波动，等待重试…")
        }
        let st = d["status"] as? Int ?? -1
        switch st {
        case 0: return .expired
        case 1: return .waiting
        case 2: return .scanned(nickname: (d["nickname"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines))
        case 4:
            let token = (d["token"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let uid = d["userid"].map { String(describing: $0) } ?? ""
            var pairs: [String: String] = [:]
            if !token.isEmpty {
                pairs["t"] = token
                pairs["token"] = token
            }
            if !uid.isEmpty { pairs["KugooID"] = uid }
            var headers: [String: String] = [:]
            for (k, v) in http.allHeaderFields {
                if let ks = k as? String, let vs = v as? String { headers[ks] = vs }
            }
            for c in HTTPCookie.cookies(withResponseHeaderFields: headers, for: url) where !c.value.isEmpty {
                pairs[c.name] = c.value
            }
            let merged = pairs.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
            guard !merged.isEmpty else { return .failed("未收到酷狗凭证，请改用桌面网页登录") }
            let nick = (d["nickname"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            let pic = (d["pic"] as? String).map { Self.httpsURL($0) }
            return .authorized(cookie: merged, nickname: nick?.isEmpty == false ? nick : nil, avatarURL: pic)
        default:
            return .failed("等待酷狗 App 扫码…")
        }
    }

    // MARK: - Kugou Native Phone SMS, WeChat QR, Token Refresh & Direct /v5/url Playback

    enum KugouQRKind: String, CaseIterable {
        case kugou = "酷狗扫码"
        case wechat = "微信扫码"
    }

    struct KugouWeChatQRSession {
        let uuid: String
        let imageData: Data?
        let qrText: String
    }

    private static let kugouPubKeyB64 = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB"
    private static let kugouAndroidSalt = "OIlwieks28dk2k092lksi2UIkp"
    private static let kugouWXAppId = "wx79f2c4418704b4f8"
    private static let kugouWXSecret = "4efcab88b700769e376e3f6087b8abc9"

    static func makeSecPublicKey(fromSPKIBase64 b64: String) -> SecKey? {
        guard let spki = Data(base64Encoded: b64) else { return nil }
        let oidPrefix: [UInt8] = [0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00]
        var keyDer = spki
        let bytes = [UInt8](spki)
        if let oidRange = spki.range(of: Data(oidPrefix)) {
            var idx = oidRange.upperBound
            if idx < bytes.count, bytes[idx] == 0x03 {
                idx += 1
                if idx < bytes.count {
                    let lenByte = bytes[idx]
                    idx += 1
                    if lenByte & 0x80 != 0 { idx += Int(lenByte & 0x7F) }
                    if idx < bytes.count, bytes[idx] == 0x00 {
                        idx += 1
                        keyDer = spki.subdata(in: idx..<spki.count)
                    }
                }
            }
        }
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass as String: kSecAttrKeyClassPublic
        ]
        return SecKeyCreateWithData(keyDer as CFData, attrs as CFDictionary, nil)
            ?? SecKeyCreateWithData(spki as CFData, attrs as CFDictionary, nil)
    }

    static func aesCBCCrypt(data: Data, key: Data, iv: Data, encrypt: Bool) -> Data? {
        let outCap = data.count + kCCBlockSizeAES128
        var out = [UInt8](repeating: 0, count: outCap)
        var outLen: Int = 0
        let status = CCCrypt(
            CCOperation(encrypt ? kCCEncrypt : kCCDecrypt),
            CCAlgorithm(kCCAlgorithmAES),
            CCOptions(kCCOptionPKCS7Padding),
            [UInt8](key), key.count,
            [UInt8](iv),
            [UInt8](data), data.count,
            &out, outCap,
            &outLen
        )
        guard status == kCCSuccess else { return nil }
        return Data(out.prefix(outLen))
    }

    static func kugouRawRSAEncryptHex(_ plaintext: Data) -> String? {
        guard let secKey = makeSecPublicKey(fromSPKIBase64: kugouPubKeyB64) else { return nil }
        let blockSize = SecKeyGetBlockSize(secKey)
        guard plaintext.count <= blockSize else { return nil }
        var padded = Data(count: blockSize)
        padded.replaceSubrange(0..<plaintext.count, with: plaintext)
        var err: Unmanaged<CFError>?
        guard let encrypted = SecKeyCreateEncryptedData(secKey, .rsaEncryptionRaw, padded as CFData, &err) as Data? else {
            return nil
        }
        return encrypted.map { String(format: "%02x", $0) }.joined()
    }

    static func kugouAesEncrypt(_ jsonObj: [String: Any], fixedKey: String? = nil, fixedIv: String? = nil) -> (hex: String, tempKey: String)? {
        guard let plain = try? JSONSerialization.data(withJSONObject: jsonObj, options: [.withoutEscapingSlashes]) else { return nil }
        let tempKey = fixedKey ?? String(md5Hex(UUID().uuidString).prefix(16))
        let keyStr = (fixedKey != nil && fixedIv != nil) ? fixedKey! : String(md5Hex(tempKey).prefix(32))
        let ivStr = fixedIv ?? String(keyStr.suffix(16))
        guard let ct = aesCBCCrypt(data: plain, key: Data(keyStr.utf8), iv: Data(ivStr.utf8), encrypt: true) else { return nil }
        return (ct.map { String(format: "%02x", $0) }.joined(), tempKey)
    }

    static func kugouAesDecrypt(hex: String, tempKey: String) -> [String: Any]? {
        let keyStr = String(md5Hex(tempKey).prefix(32))
        let ivStr = String(keyStr.suffix(16))
        let chars = Array(hex)
        guard chars.count % 2 == 0 else { return nil }
        var ct = Data(capacity: chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let b = UInt8(String(chars[i...i + 1]), radix: 16) else { return nil }
            ct.append(b)
            i += 2
        }
        guard let plain = aesCBCCrypt(data: ct, key: Data(keyStr.utf8), iv: Data(ivStr.utf8), encrypt: false) else { return nil }
        if let obj = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any] { return obj }
        if let text = String(data: plain, encoding: .utf8), !text.isEmpty { return ["token": text] }
        return nil
    }

    private static func kugouAndroidSign(_ params: [String: String], body: String = "") -> String {
        let joined = params.keys.sorted().map { "\($0)=\(params[$0] ?? "")" }.joined()
        return md5Hex(kugouAndroidSalt + joined + body + kugouAndroidSalt)
    }

    private static func parseKugouCookie(_ cookie: String) -> (token: String, userid: String, t1: String, dfid: String) {
        var map: [String: String] = [:]
        for seg in cookie.components(separatedBy: ";") {
            let t = seg.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let eq = t.firstIndex(of: "=") else { continue }
            let k = String(t[..<eq]).trimmingCharacters(in: .whitespaces)
            let v = String(t[t.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
            if !k.isEmpty, !v.isEmpty { map[k] = v }
        }
        let token = map["token"] ?? map["t"] ?? ""
        let userid = map["userid"] ?? map["KugooID"] ?? "0"
        let t1 = map["t1"] ?? ""
        let dfid = map["dfid"] ?? "-"
        return (token, userid, t1, dfid)
    }

    func sendKugouSMSCode(phone: String, countryCode: String = "86") async -> NetEaseSMSResult {
        let norm = Self.normalizePhone(phone, countryCode: countryCode)
        guard norm.phone.count >= 6 else { return .failed("请输入有效的手机号") }
        let mid = Self.stableMusicDeviceId()
        let ts = "\(Int(Date().timeIntervalSince1970))"
        let bodyObj: [String: Any] = ["businessid": 5, "mobile": norm.phone, "plat": 3]
        guard let bodyData = try? JSONSerialization.data(withJSONObject: bodyObj, options: [.withoutEscapingSlashes]),
              let bodyStr = String(data: bodyData, encoding: .utf8) else { return .failed("参数编码失败") }
        var qp: [String: String] = [
            "dfid": "-", "mid": mid, "uuid": "-",
            "appid": "1005", "clientver": "20489", "clienttime": ts
        ]
        qp["signature"] = Self.kugouAndroidSign(qp, body: bodyStr)
        guard let url = URL(string: "https://login-user.kugou.com/v7/send_mobile_code?\(Self.kugouQueryString(qp))") else {
            return .failed("请求地址无效")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = bodyData
        req.setValue("Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("-", forHTTPHeaderField: "dfid")
        req.setValue(mid, forHTTPHeaderField: "mid")
        req.setValue(ts, forHTTPHeaderField: "clienttime")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return .failed("无法直连酷狗短信接口，请检查网络或改用扫码登录")
        }
        if (root["status"] as? Int) == 1 { return .sent }
        let msg = (root["data"] as? String) ?? (root["error"] as? String) ?? "发送失败（状态码 \(root["error_code"] ?? 0)）"
        return .failed(msg)
    }

    func loginKugouByPhone(phone: String, countryCode: String = "86", code: String) async -> NetEasePhoneLoginResult {
        let norm = Self.normalizePhone(phone, countryCode: countryCode)
        let cleanCode = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard norm.phone.count >= 6 else { return .failed("请输入有效的手机号") }
        guard !cleanCode.isEmpty else { return .failed("请输入短信验证码") }
        let nowMs = Int(Date().timeIntervalSince1970 * 1000)
        let ts = "\(nowMs / 1000)"
        let mid = Self.stableMusicDeviceId()
        guard let enc = Self.kugouAesEncrypt(["mobile": norm.phone, "code": cleanCode]),
              let pkPlain = try? JSONSerialization.data(withJSONObject: ["clienttime_ms": nowMs, "key": enc.tempKey], options: [.withoutEscapingSlashes]),
              let pkHex = Self.kugouRawRSAEncryptHex(pkPlain)?.uppercased() else {
            return .failed("加密参数初始化失败")
        }
        let maskedMobile = norm.phone.count >= 11
            ? "\(norm.phone.prefix(2))*****\(norm.phone.suffix(1))"
            : norm.phone
        let signParamsKey = Self.md5Hex("1005\(Self.kugouAndroidSalt)20489\(nowMs)")
        let dataMap: [String: Any] = [
            "plat": 1,
            "support_multi": 1,
            "t1": 0,
            "t2": 0,
            "t3": "MCwwLDAsMCwwLDAsMCwwLDA=",
            "clienttime_ms": nowMs,
            "mobile": maskedMobile,
            "key": signParamsKey,
            "pk": pkHex,
            "params": enc.hex
        ]
        guard let bodyData = try? JSONSerialization.data(withJSONObject: dataMap, options: [.withoutEscapingSlashes]),
              let bodyStr = String(data: bodyData, encoding: .utf8) else { return .failed("参数构建失败") }
        var qp: [String: String] = [
            "dfid": "-", "mid": mid, "uuid": "-",
            "appid": "1005", "clientver": "20489", "clienttime": ts
        ]
        qp["signature"] = Self.kugouAndroidSign(qp, body: bodyStr)
        guard let url = URL(string: "https://loginserviceretry.kugou.com/v7/login_by_verifycode?\(Self.kugouQueryString(qp))") else {
            return .failed("请求地址无效")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = bodyData
        req.setValue("Android16-1070-11440-130-0-LOGIN-wifi", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("1", forHTTPHeaderField: "support-calm")
        req.setValue("-", forHTTPHeaderField: "dfid")
        req.setValue(mid, forHTTPHeaderField: "mid")
        req.setValue(ts, forHTTPHeaderField: "clienttime")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return .failed("无法直连酷狗登录接口，请检查网络或改用扫码")
        }
        guard (root["status"] as? Int) == 1, var d = root["data"] as? [String: Any] else {
            let msg = (root["data"] as? String) ?? (root["error"] as? String) ?? "登录失败（状态码 \(root["error_code"] ?? 0)）"
            if msg.contains("验证") || (root["error_code"] as? Int) == 20028 {
                return .requiresWebOrQR(msg)
            }
            return .failed(msg)
        }
        if let secu = d["secu_params"] as? String, !secu.isEmpty,
           let dec = Self.kugouAesDecrypt(hex: secu, tempKey: enc.tempKey) {
            for (k, v) in dec { d[k] = v }
        }
        let token = (d["token"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let uid = d["userid"].map { String(describing: $0) } ?? ""
        let t1 = (d["t1"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else { return .requiresWebOrQR("登录成功但未解析到 token，请改用酷狗扫码登录") }
        var parts = ["t=\(token)", "token=\(token)"]
        if !uid.isEmpty && uid != "0" {
            parts.append("KugooID=\(uid)")
            parts.append("userid=\(uid)")
        }
        if !t1.isEmpty { parts.append("t1=\(t1)") }
        let nick = (d["nickname"] as? String ?? d["username"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let pic = (d["pic"] as? String).map { Self.httpsURL($0) }
        return .authorized(cookie: parts.joined(separator: "; "), nickname: nick?.isEmpty == false ? nick : nil, avatarURL: pic)
    }

    func createKugouWeChatQR() async -> KugouWeChatQRSession? {
        guard let tokURL = URL(string: "https://api.weixin.qq.com/cgi-bin/token?appid=\(Self.kugouWXAppId)&secret=\(Self.kugouWXSecret)&grant_type=client_credential") else { return nil }
        guard let (td, tr) = try? await session.data(for: URLRequest(url: tokURL)),
              (tr as? HTTPURLResponse)?.statusCode == 200,
              let tj = (try? JSONSerialization.jsonObject(with: td)) as? [String: Any],
              let accessToken = tj["access_token"] as? String, !accessToken.isEmpty else { return nil }
        guard let tickURL = URL(string: "https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token=\(Self.urlValueEnc(accessToken))&type=2") else { return nil }
        guard let (kd, kr) = try? await session.data(for: URLRequest(url: tickURL)),
              (kr as? HTTPURLResponse)?.statusCode == 200,
              let kj = (try? JSONSerialization.jsonObject(with: kd)) as? [String: Any],
              let ticket = kj["ticket"] as? String, !ticket.isEmpty else { return nil }
        let ts = "\(Int(Date().timeIntervalSince1970 * 1000))"
        let nonce = Self.md5Hex(UUID().uuidString)
        let rawSig = "appid=\(Self.kugouWXAppId)&noncestr=\(nonce)&sdk_ticket=\(ticket)&timestamp=\(ts)"
        let sig = Insecure.SHA1.hash(data: Data(rawSig.utf8)).map { String(format: "%02x", $0) }.joined()
        guard let qrURL = URL(string: "https://open.weixin.qq.com/connect/sdk/qrconnect?appid=\(Self.kugouWXAppId)&noncestr=\(nonce)&timestamp=\(ts)&scope=snsapi_userinfo&signature=\(sig)") else { return nil }
        guard let (qd, qr) = try? await session.data(for: URLRequest(url: qrURL)),
              (qr as? HTTPURLResponse)?.statusCode == 200,
              let qj = (try? JSONSerialization.jsonObject(with: qd)) as? [String: Any],
              (qj["errcode"] as? Int) == 0,
              let uuid = qj["uuid"] as? String, !uuid.isEmpty else { return nil }
        let qrDict = qj["qrcode"] as? [String: Any]
        let b64 = qrDict?["qrcodebase64"] as? String
        let imgData = b64.flatMap { Data(base64Encoded: $0) }
        return KugouWeChatQRSession(uuid: uuid, imageData: imgData, qrText: "https://open.weixin.qq.com/connect/confirm?uuid=\(uuid)")
    }

    func pollKugouWeChatQR(uuid: String) async -> KugouQRPoll {
        guard let url = URL(string: "https://long.open.weixin.qq.com/connect/l/qrconnect?f=json&uuid=\(Self.urlValueEnc(uuid))") else {
            return .failed("请求地址无效")
        }
        do {
            let (data, resp) = try await MusicDirectNet.longPollSession.data(for: URLRequest(url: url))
            guard (resp as? HTTPURLResponse)?.statusCode == 200,
                  let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                return .waiting
            }
            let code = j["wx_errcode"] as? Int ?? 408
            switch code {
            case 408: return .waiting
            case 404: return .scanned(nickname: nil)
            case 402: return .expired
            case 403: return .failed("已取消微信授权，请刷新二维码重试")
            case 405:
                guard let wxCode = j["wx_code"] as? String, !wxCode.isEmpty else {
                    return .failed("未获取到微信授权 code")
                }
                return await authorizeKugouWeChat(code: wxCode)
            default:
                return .waiting
            }
        } catch {
            return .waiting
        }
    }

    private func authorizeKugouWeChat(code: String) async -> KugouQRPoll {
        guard let tokURL = URL(string: "https://api.weixin.qq.com/sns/oauth2/access_token?appid=\(Self.kugouWXAppId)&secret=\(Self.kugouWXSecret)&code=\(Self.urlValueEnc(code))&grant_type=authorization_code") else {
            return .failed("授权地址无效")
        }
        var tokReq = URLRequest(url: tokURL)
        tokReq.httpMethod = "POST"
        guard let (td, tr) = try? await session.data(for: tokReq),
              (tr as? HTTPURLResponse)?.statusCode == 200,
              let tj = (try? JSONSerialization.jsonObject(with: td)) as? [String: Any],
              let accessToken = tj["access_token"] as? String, !accessToken.isEmpty,
              let openid = tj["openid"] as? String, !openid.isEmpty else {
            return .failed("微信授权换取 token 失败，请重试")
        }
        let nowMs = Int(Date().timeIntervalSince1970 * 1000)
        let ts = "\(nowMs / 1000)"
        let mid = Self.stableMusicDeviceId()
        guard let enc = Self.kugouAesEncrypt(["access_token": accessToken]),
              let pkPlain = try? JSONSerialization.data(withJSONObject: ["clienttime_ms": nowMs, "key": enc.tempKey], options: [.withoutEscapingSlashes]),
              let pkHex = Self.kugouRawRSAEncryptHex(pkPlain)?.uppercased() else {
            return .failed("酷狗加密失败")
        }
        let dataMap: [String: Any] = [
            "force_login": 1,
            "partnerid": 36,
            "clienttime_ms": nowMs,
            "t1": 0,
            "t2": 0,
            "t3": "MCwwLDAsMCwwLDAsMCwwLDA=",
            "openid": openid,
            "params": enc.hex,
            "pk": pkHex
        ]
        guard let bodyData = try? JSONSerialization.data(withJSONObject: dataMap, options: [.withoutEscapingSlashes]),
              let bodyStr = String(data: bodyData, encoding: .utf8) else { return .failed("参数失败") }
        var qp: [String: String] = [
            "dfid": "-", "mid": mid, "uuid": "-",
            "appid": "1005", "clientver": "20489", "clienttime": ts
        ]
        qp["signature"] = Self.kugouAndroidSign(qp, body: bodyStr)
        guard let url = URL(string: "https://gateway.kugou.com/v6/login_by_openplat?\(Self.kugouQueryString(qp))") else {
            return .failed("请求地址无效")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = bodyData
        req.setValue("Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("login.user.kugou.com", forHTTPHeaderField: "x-router")
        req.setValue("-", forHTTPHeaderField: "dfid")
        req.setValue(mid, forHTTPHeaderField: "mid")
        req.setValue(ts, forHTTPHeaderField: "clienttime")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (root["status"] as? Int) == 1,
              var d = root["data"] as? [String: Any] else {
            return .failed("酷狗微信登录换票失败，请改用酷狗 App 扫码或手机号")
        }
        if let secu = d["secu_params"] as? String, !secu.isEmpty,
           let dec = Self.kugouAesDecrypt(hex: secu, tempKey: enc.tempKey) {
            for (k, v) in dec { d[k] = v }
        }
        let token = (d["token"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let uid = d["userid"].map { String(describing: $0) } ?? ""
        let t1 = (d["t1"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else { return .failed("未收到酷狗 token") }
        var parts = ["t=\(token)", "token=\(token)"]
        if !uid.isEmpty && uid != "0" { parts.append("KugooID=\(uid)"); parts.append("userid=\(uid)") }
        if !t1.isEmpty { parts.append("t1=\(t1)") }
        let nick = (d["nickname"] as? String ?? d["username"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let pic = (d["pic"] as? String).map { Self.httpsURL($0) }
        return .authorized(cookie: parts.joined(separator: "; "), nickname: nick?.isEmpty == false ? nick : nil, avatarURL: pic)
    }

    func refreshKugouCookie(_ cookie: String) async -> String? {
        let parsed = Self.parseKugouCookie(cookie)
        guard !parsed.token.isEmpty, !parsed.userid.isEmpty, parsed.userid != "0" else { return nil }
        let nowMs = Int(Date().timeIntervalSince1970 * 1000)
        let ts = nowMs / 1000
        let mid = Self.stableMusicDeviceId()
        guard let p3 = Self.kugouAesEncrypt(["clienttime": ts, "token": parsed.token], fixedKey: "90b8382a1bb4ccdcf063102053fd75b8", fixedIv: "f063102053fd75b8"),
              let encParams = Self.kugouAesEncrypt([:]),
              let pkPlain = try? JSONSerialization.data(withJSONObject: ["clienttime_ms": nowMs, "key": encParams.tempKey], options: [.withoutEscapingSlashes]),
              let pkHex = Self.kugouRawRSAEncryptHex(pkPlain) else { return nil }
        let dataMap: [String: Any] = [
            "dfid": parsed.dfid, "p3": p3.hex, "plat": 1, "t1": 0, "t2": 0,
            "t3": "MCwwLDAsMCwwLDAsMCwwLDA=", "pk": pkHex, "params": encParams.hex,
            "userid": parsed.userid, "clienttime_ms": nowMs
        ]
        guard let bodyData = try? JSONSerialization.data(withJSONObject: dataMap, options: [.withoutEscapingSlashes]),
              let bodyStr = String(data: bodyData, encoding: .utf8) else { return nil }
        var qp: [String: String] = [
            "dfid": parsed.dfid, "mid": mid, "uuid": "-",
            "appid": "1005", "clientver": "20489", "clienttime": "\(ts)",
            "token": parsed.token, "userid": parsed.userid
        ]
        qp["signature"] = Self.kugouAndroidSign(qp, body: bodyStr)
        guard let url = URL(string: "https://login-user.kugou.com/v5/login_by_token?\(Self.kugouQueryString(qp))") else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = bodyData
        req.setValue("Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (root["status"] as? Int) == 1,
              var d = root["data"] as? [String: Any] else { return nil }
        if let secu = d["secu_params"] as? String, !secu.isEmpty,
           let dec = Self.kugouAesDecrypt(hex: secu, tempKey: encParams.tempKey) {
            for (k, v) in dec { d[k] = v }
        }
        let newToken = (d["token"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !newToken.isEmpty else { return nil }
        let uid = d["userid"].map { String(describing: $0) } ?? parsed.userid
        let newT1 = (d["t1"] as? String) ?? parsed.t1
        var parts = ["t=\(newToken)", "token=\(newToken)", "KugooID=\(uid)", "userid=\(uid)"]
        if !newT1.isEmpty { parts.append("t1=\(newT1)") }
        if parsed.dfid != "-" { parts.append("dfid=\(parsed.dfid)") }
        let refreshed = parts.joined(separator: "; ")
        await MainActor.run {
            let store = MusicStore.shared
            if let pref = store.sourcePrefs.first(where: { $0.kind == .kugou }) {
                store.setSourceAccount(pref.id, cookie: refreshed, nickname: pref.accountNickname, avatarURL: pref.accountAvatarURL)
            }
        }
        return refreshed
    }

    private func resolveKugouStream(
        hash: String,
        cookie: String,
        allowRefresh: Bool = true,
        preferredQuality: MusicAudioQuality = .hires
    ) async -> ResolvedAudio? {
        let cleanHash = hash.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let parsed = Self.parseKugouCookie(cookie)
        guard !cleanHash.isEmpty, !parsed.token.isEmpty else { return nil }
        let mid = Self.stableMusicDeviceId()
        let ts = "\(Int(Date().timeIntervalSince1970))"
        let skey = Self.md5Hex("\(cleanHash)57ae12eb6890223e355ccfcb74edf70d1005\(mid)\(parsed.userid)")
        let specs: [(q: String, label: String)] = switch preferredQuality {
        case .hires, .lossless: [("flac", "FLAC 无损"), ("320", "HQ 320k"), ("128", "标准 128k")]
        case .exhigh: [("320", "HQ 320k"), ("128", "标准 128k")]
        case .standard: [("128", "标准 128k")]
        }
        for spec in specs {
            var qp: [String: String] = [
                "dfid": parsed.dfid, "mid": mid, "uuid": "-", "appid": "1005",
                "clientver": "11430", "clienttime": ts, "token": parsed.token, "userid": parsed.userid,
                "album_id": "0", "area_code": "1", "hash": cleanHash, "ssa_flag": "is_fromtrack",
                "version": "11430", "page_id": "151369488", "quality": spec.q, "album_audio_id": "0",
                "behavior": "play", "pid": "2", "cmd": "26", "pidversion": "3001",
                "IsFreePart": "0", "ppage_id": "463467626,350369493,788954147",
                "cdnBackup": "1", "module": "", "key": skey
            ]
            qp["signature"] = Self.kugouAndroidSign(qp)
            guard let url = URL(string: "https://gateway.kugou.com/v5/url?\(Self.kugouQueryString(qp))") else { continue }
            var req = URLRequest(url: url)
            req.setValue("Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi", forHTTPHeaderField: "User-Agent")
            req.setValue("trackercdn.kugou.com", forHTTPHeaderField: "x-router")
            req.setValue(parsed.dfid, forHTTPHeaderField: "dfid")
            req.setValue(mid, forHTTPHeaderField: "mid")
            req.setValue(ts, forHTTPHeaderField: "clienttime")
            guard let (data, resp) = try? await session.data(for: req),
                  (resp as? HTTPURLResponse)?.statusCode == 200,
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { continue }
            let urls = (root["url"] as? [String])?.filter { !$0.isEmpty } ?? []
            if let firstStr = urls.first, let streamURL = URL(string: Self.httpsURL(firstStr)) {
                return ResolvedAudio(
                    url: streamURL,
                    headers: [:],
                    qualityLabel: spec.label,
                    entitlement: .full,
                    actualSource: .kugou,
                    isCached: false
                )
            }
            if allowRefresh, (root["errcode"] as? Int) == 20010 || (root["status"] as? Int) == 0 {
                if let refreshed = await refreshKugouCookie(cookie) {
                    return await resolveKugouStream(hash: cleanHash, cookie: refreshed, allowRefresh: false, preferredQuality: preferredQuality)
                }
            }
        }
        return nil
    }

    // MARK: - QQ Music Serverless Native Phone SMS / QQ QR / WeChat QR Login & Vkey Playback

    enum QQSMSResult: Equatable {
        case sent
        case captchaRequired(securityURL: String?)
        case failed(String)
    }

    enum QQPhoneLoginResult: Equatable {
        case authorized(cookie: String, nickname: String?, avatarURL: String?)
        case failed(String)
    }

    enum QQQRKind: String, CaseIterable {
        case qq = "QQ 扫码"
        case wechat = "微信扫码"
    }

    struct QQQRSession {
        let kind: QQQRKind
        let identifier: String
        let imageData: Data
    }

    enum QQQRPoll {
        case waiting
        case scanned
        case expired
        case refused
        case authorized(cookie: String, nickname: String?, avatarURL: String?)
        case failed(String)
    }

    static func qqHash33(_ s: String, seed: Int = 0) -> Int {
        var h: UInt64 = UInt64(seed) & 0x7fff_ffff
        for scalar in s.unicodeScalars {
            h = ((h &<< 5) &+ h &+ UInt64(scalar.value)) & 0x7fff_ffff
        }
        return Int(h)
    }

    static func qqZZCSign(_ payload: String) -> String {
        let digestBytes = Array(Insecure.SHA1.hash(data: Data(payload.utf8)))
        let hex = digestBytes.map { String(format: "%02X", $0) }.joined()
        let chars = Array(hex)
        let part1Idx = [23, 14, 6, 36, 16, 7, 19]
        let part2Idx = [16, 1, 32, 12, 19, 27, 8, 5]
        let scramble: [UInt8] = [89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179]
        let p1 = String(part1Idx.map { chars[$0] })
        let p2 = String(part2Idx.map { chars[$0] })
        var xored = [UInt8](repeating: 0, count: 20)
        for i in 0..<20 {
            xored[i] = scramble[i] ^ digestBytes[i]
        }
        let b64 = Data(xored).base64EncodedString()
            .replacingOccurrences(of: "/", with: "")
            .replacingOccurrences(of: "+", with: "")
            .replacingOccurrences(of: "=", with: "")
        return "zzc\(p1)\(b64)\(p2)".lowercased()
    }

    static func parseQQCookie(_ cookie: String) -> (uin: String, musickey: String, loginType: Int) {
        var map: [String: String] = [:]
        for seg in cookie.components(separatedBy: ";") {
            let t = seg.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let eq = t.firstIndex(of: "=") else { continue }
            let k = String(t[..<eq]).trimmingCharacters(in: .whitespaces)
            let v = String(t[t.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
            if !k.isEmpty, !v.isEmpty { map[k] = v }
        }
        let rawUin = map["qqmusic_uin"] ?? map["musicid"] ?? map["uin"] ?? ""
        let uin = rawUin.filter(\.isNumber)
        let musickey = map["musickey"] ?? map["qm_keyst"] ?? map["qqmusic_key"] ?? ""
        let lt: Int
        if let rawLt = map["tmeLoginType"], let parsed = Int(rawLt) {
            lt = parsed
        } else if musickey.hasPrefix("W_X") {
            lt = 1
        } else if !musickey.isEmpty {
            lt = 2
        } else {
            lt = 0
        }
        return (uin, musickey, lt)
    }

    private static let qqQimeiPubKeyB64 = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDEIxgwoutfwoJxcGQeedgP7FG9qaIuS0qzfR8gWkrkTZKM2iWHn2ajQpBRZjMSoSf6+KJGvar2ORhBfpDXyVtZCKpqLQ+FLkpncClKVIrBwv6PHyUvuCb0rIarmgDnzkfQAqVufEtR64iazGDKatvJ9y6B9NMbHddGSAUmRTCrHQIDAQAB"
    private var cachedQimei36: String?

    func ensureQQQimei() async -> String {
        if let hit = cachedQimei36, !hit.isEmpty { return hit }
        let defaults = UserDefaults.standard
        let saved = defaults.string(forKey: "stv.music.qq.qimei36") ?? ""
        let savedAt = defaults.double(forKey: "stv.music.qq.qimei36.ts")
        if !saved.isEmpty, Date().timeIntervalSince1970 - savedAt < 86400 {
            cachedQimei36 = saved
            return saved
        }
        guard let secKey = Self.makeSecPublicKey(fromSPKIBase64: Self.qqQimeiPubKeyB64) else { return "" }
        let aid = String(Self.stableMusicDeviceId().prefix(16))
        let cryptKey = String(Self.md5Hex(UUID().uuidString).prefix(16))
        let nonce = String(Self.md5Hex("\(UUID().uuidString)-n").prefix(16))
        guard let ozData = Self.aesCBCCrypt(data: Data(aid.utf8), key: Data("lvcwmSYVr2Axv1gn".utf8), iv: Data("Zs0ntDqG2jyhKN0c".utf8), encrypt: true),
              let ooData = Self.aesCBCCrypt(data: Data("Pixel 8".utf8), key: Data("lvcwmSYVr2Axv1gn".utf8), iv: Data("Zs0ntDqG2jyhKN0c".utf8), encrypt: true) else { return "" }
        let reserved: [String: Any] = [
            "harmony": "0", "clone": "0", "containe": "",
            "oz": ozData.base64EncodedString(), "oo": ooData.base64EncodedString(),
            "kelong": "0", "ip": "192.168.1.25", "uptimes": "2026-11-28 04:00:00",
            "multiUser": "0", "bod": "shiba", "brd": "Google", "dv": "shiba",
            "firstLevel": "34", "manufact": "Google", "name": "shiba", "host": "abfarm",
            "kernel": "Linux", "pre": "0", "av": "20.9.0.8", "ch": ""
        ]
        guard let resData = try? JSONSerialization.data(withJSONObject: reserved, options: [.withoutEscapingSlashes]),
              let resStr = String(data: resData, encoding: .utf8) else { return "" }
        let payload: [String: Any] = [
            "androidId": aid, "platformId": 1, "appKey": "0AND0HD6FE4HY80F",
            "appVersion": "20.9.0.8", "beaconIdSrc": "k1:2026-11-01123456.123456789;k3:0000000000000000;",
            "brand": "Google", "channelId": "10003505", "cid": "", "imei": "861234567890123",
            "imsi": "", "mac": "", "model": "Pixel 8", "networkType": "wifi", "oaid": "",
            "osVersion": "Android 14,level 34", "qimei": "", "qimei36": "", "sdkVersion": "1.2.13.6",
            "targetSdkVersion": "30", "audit": "", "userId": "{}", "packageId": "com.tencent.qqmusic",
            "deviceType": "Phone", "sdkName": "", "reserved": resStr
        ]
        var rsaErr: Unmanaged<CFError>?
        guard let payloadData = try? JSONSerialization.data(withJSONObject: payload, options: [.withoutEscapingSlashes]),
              let rsaCT = SecKeyCreateEncryptedData(secKey, .rsaEncryptionPKCS1, Data(cryptKey.utf8) as CFData, &rsaErr) as Data?,
              let paramsCT = Self.aesCBCCrypt(data: payloadData, key: Data(cryptKey.utf8), iv: Data(cryptKey.utf8), encrypt: true) else { return "" }
        let keyB64 = rsaCT.base64EncodedString()
        let paramsB64 = paramsCT.base64EncodedString()
        let ts = Int(Date().timeIntervalSince1970)
        let extra = "{\"appKey\":\"0AND0HD6FE4HY80F\"}"
        let reqSign = Self.md5Hex("\(keyB64)\(paramsB64)\(ts * 1000)\(nonce)ZdJqM15EeO2zWc08\(extra)")
        let hdrSign = Self.md5Hex("qimei_qq_androidpzAuCmaFAaFaHrdakPjLIEqKrGnSOOvH\(ts)")
        let qBody: [String: Any] = [
            "app": 0, "os": 1,
            "qimeiParams": [
                "key": keyB64, "params": paramsB64, "time": "\(ts)",
                "nonce": nonce, "sign": reqSign, "extra": extra
            ]
        ]
        guard let qBodyData = try? JSONSerialization.data(withJSONObject: qBody, options: [.withoutEscapingSlashes]),
              let url = URL(string: "https://api.tencentmusic.com/tme/trpc/proxy") else { return "" }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = qBodyData
        req.setValue("api.tencentmusic.com", forHTTPHeaderField: "Host")
        req.setValue("GetQimei", forHTTPHeaderField: "method")
        req.setValue("trpc.tme_datasvr.qimeiproxy.QimeiProxy", forHTTPHeaderField: "service")
        req.setValue("qimei_qq_android", forHTTPHeaderField: "appid")
        req.setValue(hdrSign, forHTTPHeaderField: "sign")
        req.setValue("QQMusic", forHTTPHeaderField: "user-agent")
        req.setValue("\(ts)", forHTTPHeaderField: "timestamp")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let innerStr = root["data"] as? String,
              let innerObj = (try? JSONSerialization.jsonObject(with: Data(innerStr.utf8))) as? [String: Any],
              let d = innerObj["data"] as? [String: Any],
              let q36 = d["q36"] as? String, !q36.isEmpty else { return "" }
        cachedQimei36 = q36
        defaults.set(q36, forKey: "stv.music.qq.qimei36")
        defaults.set(Date().timeIntervalSince1970, forKey: "stv.music.qq.qimei36.ts")
        return q36
    }

    private func postQQMusicCGI(
        module: String,
        method: String,
        param: [String: Any],
        commExtra: [String: Any] = [:],
        cookie: String? = nil
    ) async -> (code: Int, data: [String: Any])? {
        let guid = Self.stableMusicDeviceId()
        let q36 = await ensureQQQimei()
        var comm: [String: Any] = [
            "ct": "11",
            "cv": "20090008",
            "v": "20090008",
            "chid": "10003505",
            "tmeAppID": "qqmusic",
            "OpenUDID": guid,
            "udid": guid,
            "os_ver": "14",
            "phonetype": "iPhone"
        ]
        if !q36.isEmpty { comm["QIMEI36"] = q36 }
        if let cookie, !cookie.isEmpty {
            let parsed = Self.parseQQCookie(cookie)
            if !parsed.uin.isEmpty { comm["qq"] = parsed.uin }
            if !parsed.musickey.isEmpty { comm["authst"] = parsed.musickey }
            if parsed.loginType > 0 { comm["tmeLoginType"] = "\(parsed.loginType)" }
        }
        for (k, v) in commExtra {
            comm[k] = "\(v)"
        }
        let bodyObj: [String: Any] = [
            "comm": comm,
            "req_0": [
                "module": module,
                "method": method,
                "param": param
            ]
        ]
        guard let bodyData = try? JSONSerialization.data(withJSONObject: bodyObj, options: [.withoutEscapingSlashes]),
              let bodyText = String(data: bodyData, encoding: .utf8) else { return nil }
        let sig = Self.qqZZCSign(bodyText)
        guard let url = URL(string: "https://u.y.qq.com/cgi-bin/musics.fcg?sign=\(sig)") else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = bodyData
        req.setValue("QQMusic 20090008(android 14)", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("https://y.qq.com/", forHTTPHeaderField: "Referer")
        if let cookie, !cookie.isEmpty {
            req.setValue(cookie, forHTTPHeaderField: "Cookie")
        }
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let req0 = root["req_0"] as? [String: Any] else { return nil }
        let code = req0["code"] as? Int ?? -1
        let d = req0["data"] as? [String: Any] ?? [:]
        return (code, d)
    }

    private static func buildQQCookie(from data: [String: Any], defaultLoginType: Int) -> String? {
        let strId = (data["str_musicid"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let numId = data["musicid"].map { String(describing: $0) } ?? ""
        let musicid = (strId?.isEmpty == false && strId != "0") ? strId! : (numId != "0" ? numId : "")
        let musickey = (data["musickey"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !musicid.isEmpty, !musickey.isEmpty else { return nil }
        let loginType = (data["loginType"] as? Int).flatMap { $0 > 0 ? $0 : nil }
            ?? (musickey.hasPrefix("W_X") ? 1 : defaultLoginType)
        var parts = [
            "uin=\(musicid)",
            "qqmusic_uin=\(musicid)",
            "qm_keyst=\(musickey)",
            "qqmusic_key=\(musickey)",
            "musickey=\(musickey)",
            "tmeLoginType=\(loginType)"
        ]
        if let openid = data["openid"] as? String, !openid.isEmpty { parts.append("openid=\(openid)") }
        if let unionid = data["unionid"] as? String, !unionid.isEmpty { parts.append("unionid=\(unionid)") }
        if let euin = data["encryptUin"] as? String, !euin.isEmpty { parts.append("euin=\(euin)") }
        if let rk = data["refresh_key"] as? String, !rk.isEmpty { parts.append("refresh_key=\(rk)") }
        if let rt = data["refresh_token"] as? String, !rt.isEmpty { parts.append("refresh_token=\(rt)") }
        return parts.joined(separator: "; ")
    }

    private static func qqLoginErrorMessage(code: Int, data: [String: Any]) -> String {
        switch code {
        case 20271: return "验证码错误，请核对后重试"
        case 20272, 20274: return "该手机号尚未绑定 QQ 音乐账号，请先使用 QQ / 微信扫码登录"
        case 20277, 20278, 20450: return "账号受限或已封禁"
        case 20279: return "登录设备数量已达上限"
        case 104604, 100001: return "操作过于频繁，请稍后再试"
        case 1000, 104400, 104401: return "验证码已过期或参数无效，请重新获取"
        default:
            let tip = (data["errTip"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !tip.isEmpty { return tip }
            let msg = (data["errMsg"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return msg.isEmpty ? "登录失败（状态码 \(code)）" : msg
        }
    }

    func sendQQSMSCode(phone: String, countryCode: String = "86") async -> QQSMSResult {
        let norm = Self.normalizePhone(phone, countryCode: countryCode)
        guard norm.phone.count >= 6 else { return .failed("请输入有效的手机号") }
        guard let res = await postQQMusicCGI(
            module: "music.login.LoginServer",
            method: "SendPhoneAuthCode",
            param: ["tmeAppid": "qqmusic", "areaCode": norm.ctcode, "phoneNo": norm.phone],
            commExtra: ["tmeLoginMethod": "3"]
        ) else {
            return .failed("无法直连 QQ 音乐短信接口，请检查网络或改用扫码登录")
        }
        switch res.code {
        case 0:
            return .sent
        case 20276:
            let sec = (res.data["securityURL"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            return .captchaRequired(securityURL: sec?.isEmpty == false ? sec : nil)
        case 100001:
            return .failed("发送过于频繁，请稍后再试")
        default:
            return .failed(Self.qqLoginErrorMessage(code: res.code, data: res.data))
        }
    }

    func loginQQByPhone(phone: String, countryCode: String = "86", authCode: String) async -> QQPhoneLoginResult {
        let norm = Self.normalizePhone(phone, countryCode: countryCode)
        let codeStr = authCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard norm.phone.count >= 6 else { return .failed("请输入有效的手机号") }
        guard !codeStr.isEmpty else { return .failed("请输入短信验证码") }
        guard let res = await postQQMusicCGI(
            module: "music.login.LoginServer",
            method: "Login",
            param: ["code": codeStr, "loginMode": 1, "phoneNo": norm.phone],
            commExtra: ["tmeLoginMethod": "3", "tmeLoginType": "0"]
        ) else {
            return .failed("无法直连 QQ 音乐登录接口，请检查网络后重试")
        }
        guard res.code == 0 else {
            return .failed(Self.qqLoginErrorMessage(code: res.code, data: res.data))
        }
        guard let cookie = Self.buildQQCookie(from: res.data, defaultLoginType: 0) else {
            return .failed("登录成功但未收到凭证，请改用 QQ / 微信扫码登录")
        }
        let acc = await fetchQQAccount(cookie: cookie)
        return .authorized(cookie: cookie, nickname: acc?.nickname, avatarURL: acc?.avatarURL)
    }

    func createQQQR(kind: QQQRKind) async -> QQQRSession? {
        switch kind {
        case .qq:
            let t = String(Double.random(in: 0.01...0.99))
            guard let url = URL(string: "https://ssl.ptlogin2.qq.com/ptqrshow?appid=716027609&e=2&l=M&s=3&d=72&v=4&t=\(t)&daid=383&pt_3rd_aid=100497308") else { return nil }
            var req = URLRequest(url: url)
            req.setValue(ua, forHTTPHeaderField: "User-Agent")
            req.setValue("https://xui.ptlogin2.qq.com/", forHTTPHeaderField: "Referer")
            guard let (data, resp) = try? await session.data(for: req),
                  let http = resp as? HTTPURLResponse,
                  http.statusCode == 200,
                  !data.isEmpty else { return nil }
            var hdrs: [String: String] = [:]
            for (k, v) in http.allHeaderFields {
                if let ks = k as? String, let vs = v as? String { hdrs[ks] = vs }
            }
            let cookies = HTTPCookie.cookies(withResponseHeaderFields: hdrs, for: url)
            guard let qrsig = cookies.first(where: { $0.name == "qrsig" })?.value, !qrsig.isEmpty else { return nil }
            return QQQRSession(kind: .qq, identifier: qrsig, imageData: data)

        case .wechat:
            let redirect = Self.urlValueEnc("https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/")
            let href = Self.urlValueEnc("https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect")
            guard let pageURL = URL(string: "https://open.weixin.qq.com/connect/qrconnect?appid=wx48db31d50e334801&redirect_uri=\(redirect)&response_type=code&scope=snsapi_login&state=STATE&href=\(href)") else { return nil }
            var req = URLRequest(url: pageURL)
            req.setValue(ua, forHTTPHeaderField: "User-Agent")
            guard let (htmlData, resp) = try? await session.data(for: req),
                  (resp as? HTTPURLResponse)?.statusCode == 200,
                  let html = String(data: htmlData, encoding: .utf8),
                  let uuidRange = html.range(of: #"uuid=([^"&']+)"#, options: .regularExpression) else { return nil }
            let uuid = String(html[uuidRange]).replacingOccurrences(of: "uuid=", with: "")
            guard !uuid.isEmpty, let imgURL = URL(string: "https://open.weixin.qq.com/connect/qrcode/\(uuid)") else { return nil }
            var imgReq = URLRequest(url: imgURL)
            imgReq.setValue(ua, forHTTPHeaderField: "User-Agent")
            imgReq.setValue("https://open.weixin.qq.com/connect/qrconnect", forHTTPHeaderField: "Referer")
            guard let (imgData, imgResp) = try? await session.data(for: imgReq),
                  (imgResp as? HTTPURLResponse)?.statusCode == 200,
                  !imgData.isEmpty else { return nil }
            return QQQRSession(kind: .wechat, identifier: uuid, imageData: imgData)
        }
    }

    func pollQQQR(session qr: QQQRSession) async -> QQQRPoll {
        switch qr.kind {
        case .qq:
            let token = Self.qqHash33(qr.identifier)
            let nowMs = Int(Date().timeIntervalSince1970 * 1000)
            let urlStr = "https://ssl.ptlogin2.qq.com/ptqrlogin?u1=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump&ptqrtoken=\(token)&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052&action=0-0-\(nowMs)&js_ver=20102616&js_type=1&pt_uistyle=40&aid=716027609&daid=383&pt_3rd_aid=100497308&has_onekey=1"
            guard let url = URL(string: urlStr) else { return .failed("请求地址无效") }
            var req = URLRequest(url: url)
            req.setValue(ua, forHTTPHeaderField: "User-Agent")
            req.setValue("https://xui.ptlogin2.qq.com/", forHTTPHeaderField: "Referer")
            req.setValue("qrsig=\(qr.identifier)", forHTTPHeaderField: "Cookie")
            guard let (data, resp) = try? await self.session.data(for: req),
                  (resp as? HTTPURLResponse)?.statusCode == 200,
                  let text = String(data: data, encoding: .utf8) else {
                return .failed("网络波动，等待重试…")
            }
            let args = Self.extractPTUIArgs(from: text)
            guard let codeStr = args.first, let code = Int(codeStr) else {
                return .failed("二维码状态解析失败")
            }
            switch code {
            case 66: return .waiting
            case 67: return .scanned
            case 65: return .expired
            case 68: return .refused
            case 0:
                guard args.count >= 3 else { return .failed("缺少 QQ 授权跳转参数") }
                let jump = args[2]
                let uin = Self.queryParam(named: "uin", in: jump)
                let sigx = Self.queryParam(named: "ptsigx", in: jump)
                guard let uin, let sigx else { return .failed("无法解析 QQ 授权票据") }
                return await authorizeQQQR(uin: uin, sigx: sigx)
            default:
                return .failed(args.count > 4 ? args[4] : "等待扫码…")
            }

        case .wechat:
            let nowMs = Int(Date().timeIntervalSince1970 * 1000)
            guard let url = URL(string: "https://lp.open.weixin.qq.com/connect/l/qrconnect?uuid=\(Self.urlValueEnc(qr.identifier))&_=\(nowMs)") else {
                return .failed("请求地址无效")
            }
            var req = URLRequest(url: url)
            req.setValue(ua, forHTTPHeaderField: "User-Agent")
            req.setValue("https://open.weixin.qq.com/", forHTTPHeaderField: "Referer")
            do {
                let (data, resp) = try await MusicDirectNet.longPollSession.data(for: req)
                guard (resp as? HTTPURLResponse)?.statusCode == 200,
                      let text = String(data: data, encoding: .utf8) else {
                    return .waiting
                }
                guard let errRange = text.range(of: #"wx_errcode=(\d+)"#, options: .regularExpression) else {
                    return .waiting
                }
                let codeStr = String(text[errRange]).replacingOccurrences(of: "wx_errcode=", with: "")
                guard let code = Int(codeStr) else { return .waiting }
                switch code {
                case 408: return .waiting
                case 404: return .scanned
                case 402: return .expired
                case 403: return .refused
                case 405:
                    guard let codeRange = text.range(of: #"wx_code='([^']+)'"#, options: .regularExpression) else {
                        return .failed("未获取到微信授权 code")
                    }
                    let wxCode = String(text[codeRange])
                        .replacingOccurrences(of: "wx_code='", with: "")
                        .replacingOccurrences(of: "'", with: "")
                    return await authorizeWeChatQR(code: wxCode)
                default:
                    return .waiting
                }
            } catch {
                return .waiting
            }
        }
    }

    private func authorizeQQQR(uin: String, sigx: String) async -> QQQRPoll {
        let checkURLStr = "https://ssl.ptlogin2.graph.qq.com/check_sig?uin=\(Self.urlValueEnc(uin))&pttype=1&service=ptqrlogin&nodirect=0&ptsigx=\(Self.urlValueEnc(sigx))&s_url=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump&ptlang=2052&ptredirect=100&aid=716027609&daid=383&j_later=0&low_login_hour=0&regmaster=0&pt_login_type=3&pt_aid=0&pt_aaid=16&pt_light=0&pt_3rd_aid=100497308"
        guard let checkURL = URL(string: checkURLStr) else { return .failed("授权地址无效") }
        var checkReq = URLRequest(url: checkURL)
        checkReq.setValue(ua, forHTTPHeaderField: "User-Agent")
        checkReq.setValue("https://xui.ptlogin2.qq.com/", forHTTPHeaderField: "Referer")
        guard let (_, checkResp) = try? await session.data(for: checkReq, delegate: MusicNoRedirectDelegate()),
              let checkHTTP = checkResp as? HTTPURLResponse else {
            return .failed("QQ 授权校验失败，请重试")
        }
        var hdrs: [String: String] = [:]
        for (k, v) in checkHTTP.allHeaderFields {
            if let ks = k as? String, let vs = v as? String { hdrs[ks] = vs }
        }
        let cookies = HTTPCookie.cookies(withResponseHeaderFields: hdrs, for: checkURL)
        guard let pSkey = cookies.first(where: { $0.name == "p_skey" })?.value, !pSkey.isEmpty else {
            return .failed("获取 p_skey 失败，请重试或改用桌面网页登录")
        }
        let cookieHeader = cookies.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")

        guard let authURL = URL(string: "https://graph.qq.com/oauth2.0/authorize") else { return .failed("OAuth 地址无效") }
        let formFields: [(String, String)] = [
            ("response_type", "code"),
            ("client_id", "100497308"),
            ("redirect_uri", "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/"),
            ("scope", "get_user_info,get_app_friends"),
            ("state", "state"),
            ("switch", ""),
            ("from_ptlogin", "1"),
            ("src", "1"),
            ("update_auth", "1"),
            ("openapi", "1010_1030"),
            ("g_tk", "\(Self.qqHash33(pSkey, seed: 5381))"),
            ("auth_time", "\(Int(Date().timeIntervalSince1970 * 1000))"),
            ("ui", UUID().uuidString)
        ]
        let formBody = formFields.map { "\($0.0)=\(Self.urlValueEnc($0.1))" }.joined(separator: "&")
        var authReq = URLRequest(url: authURL)
        authReq.httpMethod = "POST"
        authReq.httpBody = Data(formBody.utf8)
        authReq.setValue(ua, forHTTPHeaderField: "User-Agent")
        authReq.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        authReq.setValue(cookieHeader, forHTTPHeaderField: "Cookie")
        guard let (_, authResp) = try? await session.data(for: authReq, delegate: MusicNoRedirectDelegate()),
              let authHTTP = authResp as? HTTPURLResponse,
              let loc = authHTTP.value(forHTTPHeaderField: "Location"),
              let code = Self.queryParam(named: "code", in: loc) else {
            return .failed("QQ 授权未返回 code，请重试或改用桌面网页登录")
        }

        guard let res = await postQQMusicCGI(
            module: "QQConnectLogin.LoginServer",
            method: "QQLogin",
            param: ["code": code],
            commExtra: ["tmeLoginType": "2"]
        ) else {
            return .failed("换取 QQ 音乐凭证失败，请重试")
        }
        guard res.code == 0 else {
            return .failed(Self.qqLoginErrorMessage(code: res.code, data: res.data))
        }
        guard let cookie = Self.buildQQCookie(from: res.data, defaultLoginType: 2) else {
            return .failed("未收到 QQ 音乐凭证")
        }
        let acc = await fetchQQAccount(cookie: cookie)
        return .authorized(cookie: cookie, nickname: acc?.nickname, avatarURL: acc?.avatarURL)
    }

    private func authorizeWeChatQR(code: String) async -> QQQRPoll {
        guard let res = await postQQMusicCGI(
            module: "music.login.LoginServer",
            method: "Login",
            param: ["code": code, "strAppid": "wx48db31d50e334801"],
            commExtra: ["tmeLoginType": "1"]
        ) else {
            return .failed("换取微信登录凭证失败，请重试")
        }
        guard res.code == 0 else {
            return .failed(Self.qqLoginErrorMessage(code: res.code, data: res.data))
        }
        guard let cookie = Self.buildQQCookie(from: res.data, defaultLoginType: 1) else {
            return .failed("未收到 QQ 音乐凭证")
        }
        let acc = await fetchQQAccount(cookie: cookie)
        return .authorized(cookie: cookie, nickname: acc?.nickname, avatarURL: acc?.avatarURL)
    }

    func fetchQQAccount(cookie: String) async -> (nickname: String, avatarURL: String)? {
        let parsed = Self.parseQQCookie(cookie)
        if let res = await postQQMusicCGI(
            module: "music.UserInfo.userInfoServer",
            method: "GetLoginUserInfo",
            param: [:],
            cookie: cookie
        ), res.code == 0 {
            let info = (res.data["userInfo"] as? [String: Any]) ?? res.data
            let nick = ((info["nick"] as? String) ?? (info["nickname"] as? String) ?? (res.data["nick"] as? String) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let head = (info["headurl"] as? String) ?? (info["headpic"] as? String) ?? (res.data["headurl"] as? String) ?? ""
            if !nick.isEmpty {
                return (nick, Self.httpsURL(head))
            }
        }
        if !parsed.uin.isEmpty {
            return ("QQ音乐 \(parsed.uin)", "")
        }
        return nil
    }

    func refreshQQCookie(_ cookie: String) async -> String? {
        var map: [String: String] = [:]
        for seg in cookie.components(separatedBy: ";") {
            let t = seg.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let eq = t.firstIndex(of: "=") else { continue }
            let k = String(t[..<eq]).trimmingCharacters(in: .whitespaces)
            let v = String(t[t.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
            if !k.isEmpty, !v.isEmpty { map[k] = v }
        }
        let parsed = Self.parseQQCookie(cookie)
        let rk = map["refresh_key"] ?? ""
        let rt = map["refresh_token"] ?? ""
        let openid = map["openid"] ?? ""
        guard !parsed.uin.isEmpty, !parsed.musickey.isEmpty, (!rk.isEmpty || !rt.isEmpty) else { return nil }
        let param: [String: Any] = [
            "openid": openid,
            "refresh_token": rt,
            "str_musicid": parsed.uin,
            "musicid": Int(parsed.uin) ?? 0,
            "musickey": parsed.musickey,
            "unionid": map["unionid"] ?? "",
            "refresh_key": rk,
            "loginMode": 2
        ]
        guard let res = await postQQMusicCGI(
            module: "music.login.LoginServer",
            method: "Login",
            param: param,
            commExtra: ["tmeLoginType": "\(parsed.loginType)"],
            cookie: cookie
        ), res.code == 0,
           let refreshed = Self.buildQQCookie(from: res.data, defaultLoginType: parsed.loginType) else {
            return nil
        }
        await MainActor.run {
            let store = MusicStore.shared
            if let pref = store.sourcePrefs.first(where: { $0.kind == .qq }) {
                store.setSourceAccount(pref.id, cookie: refreshed, nickname: pref.accountNickname, avatarURL: pref.accountAvatarURL)
            }
        }
        return refreshed
    }

    private func resolveQQStream(
        songMid: String,
        cookie: String,
        allowRefresh: Bool = true,
        preferredQuality: MusicAudioQuality = .hires
    ) async -> ResolvedAudio? {
        let parsed = Self.parseQQCookie(cookie)
        guard !parsed.uin.isEmpty, !parsed.musickey.isEmpty, !songMid.isEmpty else { return nil }
        let specs: [(prefix: String, ext: String, label: String)] = switch preferredQuality {
        case .hires, .lossless:
            [("F000", ".flac", "FLAC 无损"), ("M800", ".mp3", "HQ 320k"), ("M500", ".mp3", "标准 128k"), ("C400", ".m4a", "AAC 96k")]
        case .exhigh:
            [("M800", ".mp3", "HQ 320k"), ("M500", ".mp3", "标准 128k"), ("C400", ".m4a", "AAC 96k")]
        case .standard:
            [("M500", ".mp3", "标准 128k"), ("C400", ".m4a", "AAC 96k")]
        }
        for spec in specs {
            let filename = "\(spec.prefix)\(songMid)\(songMid)\(spec.ext)"
            guard let res = await postQQMusicCGI(
                module: "music.vkey.GetVkey",
                method: "UrlGetVkey",
                param: [
                    "uin": parsed.uin,
                    "filename": [filename],
                    "guid": Self.stableMusicDeviceId(),
                    "songmid": [songMid],
                    "songtype": [0],
                    "ctx": 0
                ],
                cookie: cookie
            ) else { continue }
            if allowRefresh, [1000, 104400, 104401].contains(res.code) {
                if let refreshed = await refreshQQCookie(cookie) {
                    return await resolveQQStream(songMid: songMid, cookie: refreshed, allowRefresh: false, preferredQuality: preferredQuality)
                }
                return nil
            }
            guard res.code == 0,
                  let infoList = res.data["midurlinfo"] as? [[String: Any]],
                  let first = infoList.first,
                  let purl = first["purl"] as? String, !purl.isEmpty else { continue }
            let sipList = (res.data["sip"] as? [String])?.filter { !$0.isEmpty } ?? []
            let hostBase = Self.httpsURL(sipList.first ?? "https://ws.stream.qqmusic.qq.com/")
            let fullStr = purl.hasPrefix("http") ? Self.httpsURL(purl) : (hostBase.hasSuffix("/") ? "\(hostBase)\(purl)" : "\(hostBase)/\(purl)")
            guard let streamURL = URL(string: fullStr) else { continue }
            return ResolvedAudio(
                url: streamURL,
                headers: ["Referer": "https://y.qq.com/"],
                qualityLabel: spec.label,
                entitlement: .full,
                actualSource: .qq,
                isCached: false
            )
        }
        return nil
    }

    // MARK: - Qishui Music (汽水音乐 / Soda Music — Serverless Direct Charts, Search, Playback, Lyrics & Douyin QR)

    struct QishuiQRSession {
        let token: String
        let imageData: Data?
        let qrText: String
        let initialCookie: String
    }

    enum QishuiQRPoll {
        case waiting
        case scanned
        case expired
        case refused
        case authorized(cookie: String, nickname: String?, avatarURL: String?)
        case failed(String)
    }

    private var cachedQishuiDiscoverTracks: [MusicTrack] = []

    private static func qishuiCoverURL(from coverObj: Any?) -> String {
        guard let dict = coverObj as? [String: Any] else {
            return httpsURL(coverObj as? String ?? "")
        }
        let uri = (dict["uri"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if let urls = dict["urls"] as? [String], let first = urls.first, !first.isEmpty, !uri.isEmpty {
            let base = httpsURL(first + uri)
            return base.contains("~") ? base : "\(base)~c5_500x500.jpeg"
        }
        return httpsURL((dict["url"] as? String) ?? "")
    }

    private static func parseQishuiTrack(_ trackDict: [String: Any]) -> MusicTrack? {
        let tid = trackDict["id"].map { String(describing: $0) } ?? ""
        let name = (trackDict["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !tid.isEmpty, !name.isEmpty else { return nil }
        let artistsArr = trackDict["artists"] as? [[String: Any]] ?? []
        let artists = artistsArr.compactMap { a -> String? in
            if let n = a["name"] as? String, !n.isEmpty { return n }
            return (a["user_info"] as? [String: Any])?["nickname"] as? String
        }.joined(separator: " / ")
        let albumObj = trackDict["album"] as? [String: Any]
        let albumName = albumObj?["name"] as? String ?? ""
        let cover = qishuiCoverURL(from: albumObj?["url_cover"] ?? trackDict["url_cover"])
        let rawDur = (trackDict["duration"] as? Double) ?? Double(trackDict["duration"] as? Int ?? 210_000)
        let durSec = rawDur > 1000 ? rawDur / 1000.0 : rawDur
        return MusicTrack(
            id: "qishui:\(tid)",
            source: .qishui,
            sourceId: tid,
            title: name,
            artist: artists.isEmpty ? "汽水音乐人" : artists,
            album: albumName,
            coverURL: cover,
            durationSec: max(20, durSec),
            qualityLabel: "汽水直连",
            entitlement: .full,
            streamURL: nil,
            streamHeaders: nil,
            pluginId: nil,
            extraHash: nil
        )
    }

    func fetchQishuiCharts() async -> [MusicChart] {
        guard let mixURL = URL(string: "https://beta-luna.douyin.com/luna/discover/mix") else { return [] }
        var req = URLRequest(url: mixURL)
        req.httpMethod = "POST"
        req.httpBody = Data("{\"count\":10}".utf8)
        req.setValue("Luna/19.1.0 Android", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let blocks = root["inner_block"] as? [[String: Any]] else { return [] }
        var playlistSpecs: [(id: String, title: String, cover: String)] = []
        for b in blocks {
            guard let resources = b["resources"] as? [[String: Any]] else { continue }
            for r in resources {
                guard let pl = (r["entity"] as? [String: Any])?["playlist"] as? [String: Any],
                      let pid = pl["id"].map({ String(describing: $0) }), !pid.isEmpty else { continue }
                let title = (pl["title"] as? String ?? b["title"] as? String ?? "汽水精选").trimmingCharacters(in: .whitespacesAndNewlines)
                let cover = Self.qishuiCoverURL(from: pl["url_cover"])
                if !playlistSpecs.contains(where: { $0.id == pid }) {
                    playlistSpecs.append((pid, title, cover))
                }
                if playlistSpecs.count >= 4 { break }
            }
            if playlistSpecs.count >= 4 { break }
        }
        guard !playlistSpecs.isEmpty else { return [] }
        let charts: [MusicChart] = await withTaskGroup(of: (Int, MusicChart?).self) { group in
            for (idx, sp) in playlistSpecs.enumerated() {
                group.addTask {
                    let chart = await self.fetchQishuiPlaylistChart(id: sp.id, title: sp.title, fallbackCover: sp.cover)
                    return (idx, chart)
                }
            }
            var pairs: [(Int, MusicChart)] = []
            for await (i, c) in group { if let c { pairs.append((i, c)) } }
            return pairs.sorted { $0.0 < $1.0 }.map(\.1)
        }
        let allTracks = charts.flatMap(\.tracks)
        if !allTracks.isEmpty { cachedQishuiDiscoverTracks = allTracks }
        return charts
    }

    private func fetchQishuiPlaylistChart(id: String, title: String, fallbackCover: String) async -> MusicChart? {
        guard let url = URL(string: "https://beta-luna.douyin.com/luna/playlist/detail") else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["playlist_id": id, "count": 25])
        req.setValue("Luna/19.1.0 Android", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let mres = root["media_resources"] as? [[String: Any]] else { return nil }
        let tracks: [MusicTrack] = mres.compactMap { item in
            let ent = item["entity"] as? [String: Any]
            let tr = ((ent?["track_wrapper"] as? [String: Any])?["track"] as? [String: Any])
                ?? (ent?["track"] as? [String: Any])
                ?? (item["track"] as? [String: Any])
            return tr.flatMap(Self.parseQishuiTrack)
        }
        guard !tracks.isEmpty else { return nil }
        var covers = tracks.prefix(3).map(\.coverURL).filter { !$0.isEmpty }
        if covers.isEmpty, !fallbackCover.isEmpty { covers = [fallbackCover] }
        return MusicChart(
            id: "qishui:\(id)",
            source: .qishui,
            name: title,
            updateFrequency: "汽水发现推荐",
            updatedLabel: "今日精选 · 汽水音乐",
            coverURLs: covers,
            previewTracks: Array(tracks.prefix(3)),
            tracks: tracks
        )
    }

    func searchQishui(query: String, cookie: String?) async -> [MusicTrack] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }
        if let cookie, !cookie.isEmpty {
            let dev = Self.stableMusicDeviceId().filter(\.isNumber)
            let devId = dev.count >= 16 ? String(dev.prefix(16)) : "7392817263541209"
            let params: [String: String] = [
                "aid": "386088", "app_name": "luna_pc", "region": "cn", "geo_region": "cn", "os_region": "cn",
                "device_id": devId, "iid": devId, "version_name": "3.0.0", "version_code": "30000000",
                "channel": "official", "build_mode": "master", "ac": "wifi", "tz_name": "Asia/Shanghai",
                "device_platform": "windows", "device_type": "Windows", "os_version": "Windows 11",
                "fp": devId, "q": q, "cursor": "0", "search_method": "input"
            ]
            if let url = URL(string: "https://api.qishui.com/luna/pc/search/track?\(Self.kugouQueryString(params))") {
                var req = URLRequest(url: url)
                req.setValue("LunaPC/3.0.0(290101097)", forHTTPHeaderField: "User-Agent")
                req.setValue(cookie, forHTTPHeaderField: "Cookie")
                if let (data, resp) = try? await session.data(for: req),
                   (resp as? HTTPURLResponse)?.statusCode == 200,
                   let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                    var found: [MusicTrack] = []
                    let groups = (root["result_groups"] as? [[String: Any]]) ?? []
                    for g in groups {
                        for item in (g["data"] as? [[String: Any]] ?? []) {
                            let tr = (item["entity"] as? [String: Any])?["track"] as? [String: Any]
                                ?? ((item["entity"] as? [String: Any])?["track_wrapper"] as? [String: Any])?["track"] as? [String: Any]
                                ?? (item["track"] as? [String: Any])
                            if let parsed = tr.flatMap(Self.parseQishuiTrack) { found.append(parsed) }
                        }
                    }
                    if !found.isEmpty { return found }
                }
            }
        }
        let lower = q.lowercased()
        return cachedQishuiDiscoverTracks.filter {
            $0.title.lowercased().contains(lower) || $0.artist.lowercased().contains(lower) || $0.album.lowercased().contains(lower)
        }
    }

    private func fetchQishuiSEOTrack(trackId: String, cookie: String?) async -> (audioURL: String?, lyric: String?)? {
        guard let url = URL(string: "https://beta-luna.douyin.com/luna/h5/seo_track?track_id=\(Self.urlValueEnc(trackId))&device_platform=web") else { return nil }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        if let cookie, !cookie.isEmpty { req.setValue(cookie, forHTTPHeaderField: "Cookie") }
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        let lyric = (root["lyric"] as? [String: Any])?["content"] as? String
        var audioStr: String?
        if let vmStr = (root["track_player"] as? [String: Any])?["video_model"] as? String,
           let vm = (try? JSONSerialization.jsonObject(with: Data(vmStr.utf8))) as? [String: Any],
           let vlist = vm["video_list"] as? [[String: Any]] {
            for item in vlist {
                let u = (item["main_url"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
                    ?? (item["backup_url"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
                if let u, !u.isEmpty { audioStr = u; break }
            }
        }
        return (audioStr, lyric)
    }

    func resolveQishuiStream(trackId: String, cookie: String?) async -> ResolvedAudio? {
        guard !trackId.isEmpty else { return nil }
        if let cookie, !cookie.isEmpty,
           let mpURL = URL(string: "https://beta-luna.douyin.com/luna/media-player") {
            var req = URLRequest(url: mpURL)
            req.httpMethod = "POST"
            req.httpBody = try? JSONSerialization.data(withJSONObject: [
                "media_id": trackId, "media_type": "track", "enable_dash": false, "enable_refresh_api": false
            ])
            req.setValue("Luna/19.1.0 Android", forHTTPHeaderField: "User-Agent")
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.setValue(cookie, forHTTPHeaderField: "Cookie")
            if let (data, resp) = try? await session.data(for: req),
               (resp as? HTTPURLResponse)?.statusCode == 200,
               let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
               let playerInfo = root["url_player_info"] as? [String: Any],
               let urlStr = (playerInfo["main_url"] as? String ?? playerInfo["url"] as? String),
               !urlStr.isEmpty,
               let streamURL = URL(string: Self.httpsURL(urlStr)) {
                return ResolvedAudio(
                    url: streamURL,
                    headers: [:],
                    qualityLabel: "汽水高音质",
                    entitlement: .full,
                    actualSource: .qishui,
                    isCached: false
                )
            }
        }
        if let seo = await fetchQishuiSEOTrack(trackId: trackId, cookie: cookie),
           let rawUrl = seo.audioURL, !rawUrl.isEmpty,
           let streamURL = URL(string: Self.httpsURL(rawUrl)) {
            return ResolvedAudio(
                url: streamURL,
                headers: [:],
                qualityLabel: "汽水直连",
                entitlement: .full,
                actualSource: .qishui,
                isCached: false
            )
        }
        return nil
    }

    func createQishuiQR() async -> QishuiQRSession? {
        let params: [String: String] = [
            "passport_jssdk_version": "2.4.13",
            "passport_jssdk_type": "normal",
            "is_from_ttaccountsdk": "1",
            "aid": "386088",
            "next": "https://api.qishui.com",
            "need_logo": "false",
            "need_short_url": "false",
            "is_new_login": "1"
        ]
        guard let url = URL(string: "https://api.qishui.com/passport/web/get_qrcode/?\(Self.kugouQueryString(params))") else { return nil }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await session.data(for: req),
              let http = resp as? HTTPURLResponse,
              http.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any],
              let token = d["token"] as? String, !token.isEmpty else { return nil }
        // Official Qishui PC client NEVER renders Passport's raw `qrcode` PNG (its `sdk-next` URL returns 404);
        // instead it extracts `token` and generates a QR pointing to `bff-pc.qishui.com/light/invoke/scan_login`.
        let qrIdxUrl = (d["qrcode_index_url"] as? String) ?? ""
        let tokenFromUrl = URLComponents(string: qrIdxUrl)?.queryItems?.first(where: { $0.name == "token" })?.value
        let scanToken = (tokenFromUrl?.isEmpty == false) ? tokenFromUrl! : token
        let scanLoginURL = "https://bff-pc.qishui.com/light/invoke/scan_login?token=\(Self.urlValueEnc(scanToken))&os=Mac&computer_name=iDuanJu"
        var hdrs: [String: String] = [:]
        for (k, v) in http.allHeaderFields {
            if let ks = k as? String, let vs = v as? String { hdrs[ks] = vs }
        }
        let initCk = HTTPCookie.cookies(withResponseHeaderFields: hdrs, for: url)
            .filter { !$0.value.isEmpty }
            .map { "\($0.name)=\($0.value)" }
            .joined(separator: "; ")
        return QishuiQRSession(token: token, imageData: nil, qrText: scanLoginURL, initialCookie: initCk)
    }

    private static func parseCookiePairs(_ raw: String) -> [String: String] {
        var map: [String: String] = [:]
        for part in raw.components(separatedBy: ";") {
            let trimmed = part.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let eq = trimmed.firstIndex(of: "=") else { continue }
            let k = String(trimmed[..<eq]).trimmingCharacters(in: .whitespaces)
            let v = String(trimmed[trimmed.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
            if !k.isEmpty, !v.isEmpty { map[k] = v }
        }
        return map
    }

    private func followQishuiRedirectsCollectingCookies(startURL: String, initialJar: [String: String]) async -> [String: String] {
        var jar = initialJar
        let cfg = MusicDirectNet.makeConfiguration(timeout: 15, resourceTimeout: 20)
        let noRedir = URLSession(configuration: cfg, delegate: MusicNoRedirectDelegate(), delegateQueue: nil)
        defer { noRedir.finishTasksAndInvalidate() }
        var current = startURL.trimmingCharacters(in: .whitespacesAndNewlines)
        for _ in 0..<8 {
            guard !current.isEmpty, let url = URL(string: current) else { break }
            var req = URLRequest(url: url)
            req.setValue(ua, forHTTPHeaderField: "User-Agent")
            let ckHeader = jar.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
            if !ckHeader.isEmpty { req.setValue(ckHeader, forHTTPHeaderField: "Cookie") }
            guard let (_, resp) = try? await noRedir.data(for: req),
                  let http = resp as? HTTPURLResponse else { break }
            var hdrs: [String: String] = [:]
            for (k, v) in http.allHeaderFields {
                if let ks = k as? String, let vs = v as? String { hdrs[ks] = vs }
            }
            for c in HTTPCookie.cookies(withResponseHeaderFields: hdrs, for: url) where !c.value.isEmpty {
                jar[c.name] = c.value
            }
            if (300..<400).contains(http.statusCode),
               let loc = http.value(forHTTPHeaderField: "Location"),
               let nextURL = URL(string: loc, relativeTo: url)?.absoluteString {
                current = nextURL
            } else {
                break
            }
        }
        return jar
    }

    func pollQishuiQR(session qr: QishuiQRSession) async -> QishuiQRPoll {
        let qp: [String: String] = [
            "passport_jssdk_version": "2.4.13",
            "passport_jssdk_type": "normal",
            "is_from_ttaccountsdk": "1",
            "aid": "386088",
            "iid": "27960026095955"
        ]
        guard let postURL = URL(string: "https://api.qishui.com/passport/web/check_qrconnect/?\(Self.kugouQueryString(qp))") else {
            return .failed("请求地址无效")
        }
        let bodyFields: [String: String] = [
            "need_logo": "false",
            "need_short_url": "false",
            "is_frontier": "false",
            "token": qr.token,
            "is_new_login": "1",
            "next": "https://api.qishui.com"
        ]
        var req = URLRequest(url: postURL)
        req.httpMethod = "POST"
        req.httpBody = Data(Self.kugouQueryString(bodyFields).utf8)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        if !qr.initialCookie.isEmpty { req.setValue(qr.initialCookie, forHTTPHeaderField: "Cookie") }

        var rawData: Data?
        var httpResp: HTTPURLResponse?
        var reqURL = postURL
        if let (d0, r0) = try? await self.session.data(for: req),
           let h0 = r0 as? HTTPURLResponse, h0.statusCode == 200,
           (try? JSONSerialization.jsonObject(with: d0)) is [String: Any] {
            rawData = d0
            httpResp = h0
        } else {
            // GET fallback matching official ByteDance passport SDK
            var getParams = qp
            for (k, v) in bodyFields { getParams[k] = v }
            if let getURL = URL(string: "https://api.qishui.com/passport/web/check_qrconnect/?\(Self.kugouQueryString(getParams))") {
                var gReq = URLRequest(url: getURL)
                gReq.setValue(ua, forHTTPHeaderField: "User-Agent")
                if !qr.initialCookie.isEmpty { gReq.setValue(qr.initialCookie, forHTTPHeaderField: "Cookie") }
                if let (d1, r1) = try? await self.session.data(for: gReq), let h1 = r1 as? HTTPURLResponse, h1.statusCode == 200 {
                    rawData = d1
                    httpResp = h1
                    reqURL = getURL
                }
            }
        }
        guard let data = rawData,
              let http = httpResp,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any] else {
            return .failed("网络波动，等待重试…")
        }
        let errCode = d["error_code"] as? Int ?? 0
        if errCode == 2046 { return .scanned }
        if errCode == 7 { return .waiting }

        let rawStatus: String
        if let s = d["status"] as? String { rawStatus = s }
        else if let n = d["status"] as? Int { rawStatus = "\(n)" }
        else { rawStatus = "" }
        let st = rawStatus.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        switch st {
        case "new", "waiting", "":
            return .waiting
        case "1", "scanned":
            return .scanned
        case "2", "5", "expired":
            return .expired
        case "refused", "canceled":
            return .refused
        case "3", "confirmed", "success":
            var jar = Self.parseCookiePairs(qr.initialCookie)
            var hdrs: [String: String] = [:]
            for (k, v) in http.allHeaderFields {
                if let ks = k as? String, let vs = v as? String { hdrs[ks] = vs }
            }
            for c in HTTPCookie.cookies(withResponseHeaderFields: hdrs, for: reqURL) where !c.value.isEmpty {
                jar[c.name] = c.value
            }
            if let sid = d["sessionid"] as? String, !sid.isEmpty { jar["sessionid"] = sid }
            // ByteDance often sets the real sessionid/sid_tt cookies on `redirect_url`, not on `check_qrconnect` itself.
            let redir = ((d["redirect_url"] as? String) ?? (d["redirectUrl"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !redir.isEmpty {
                jar = await followQishuiRedirectsCollectingCookies(startURL: redir, initialJar: jar)
            }
            if (jar["sessionid"] ?? "").isEmpty, let ss = jar["sessionid_ss"], !ss.isEmpty {
                jar["sessionid"] = ss
            }
            let merged = jar.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
            guard !merged.isEmpty else { return .failed("已确认但未收到汽水 Cookie，请重试或改用桌面网页") }
            let acc = await fetchQishuiAccount(cookie: merged)
            return .authorized(cookie: merged, nickname: acc?.nickname, avatarURL: acc?.avatarURL)
        default:
            return .waiting
        }
    }

    func fetchQishuiAccount(cookie: String) async -> (nickname: String, avatarURL: String)? {
        guard let url = URL(string: "https://api.qishui.com/luna/pc/me?aid=386088") else { return nil }
        var req = URLRequest(url: url)
        req.setValue("LunaPC/3.0.0(290101097)", forHTTPHeaderField: "User-Agent")
        req.setValue(cookie, forHTTPHeaderField: "Cookie")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        let info = (root["my_info"] as? [String: Any]) ?? (root["user"] as? [String: Any]) ?? root
        let nick = ((info["nickname"] as? String) ?? (info["name"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let avatar = Self.qishuiCoverURL(from: info["avatar_url"] ?? info["medium_avatar_url"] ?? info["thumb_avatar_url"])
        return nick.isEmpty ? nil : (nick, avatar)
    }

    private static func extractPTUIArgs(from text: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: #"'((?:\\.|[^'])*)'"#) else { return [] }
        let ns = text as NSString
        return regex.matches(in: text, range: NSRange(location: 0, length: ns.length)).compactMap { m in
            guard m.numberOfRanges > 1 else { return nil }
            return ns.substring(with: m.range(at: 1))
        }
    }

    private static func queryParam(named name: String, in rawURL: String) -> String? {
        if let comps = URLComponents(string: rawURL),
           let val = comps.queryItems?.first(where: { $0.name == name })?.value,
           !val.isEmpty {
            return val
        }
        let pattern = "(?:\\?|&)\(NSRegularExpression.escapedPattern(for: name))=([^&]+)"
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: rawURL, range: NSRange(location: 0, length: (rawURL as NSString).length)),
              match.numberOfRanges > 1 else { return nil }
        return (rawURL as NSString).substring(with: match.range(at: 1))
    }

    // MARK: - Serverless Artist Detail & Album Detail

    func fetchArtistDetail(ref: MusicArtistRef) async -> MusicArtistDetail {
        var resolvedId: String? = (ref.source == .netease) ? ref.artistId : nil
        if resolvedId == nil || resolvedId?.isEmpty == true {
            let hits = await searchNetEase(query: ref.name, limit: 6)
            resolvedId = hits.first(where: { $0.primaryArtistName.localizedCaseInsensitiveContains(ref.name) })?.artistId ?? hits.first?.artistId
        }
        if let aid = resolvedId, !aid.isEmpty {
            async let topRes = fetchNetEaseArtistTop(artistId: aid)
            async let albRes = fetchNetEaseArtistAlbums(artistId: aid, artistName: ref.name)
            let (artistMeta, hotSongs) = await topRes
            let albums = await albRes
            if !hotSongs.isEmpty || !albums.isEmpty || !artistMeta.bio.isEmpty {
                return MusicArtistDetail(
                    id: aid,
                    name: artistMeta.name.isEmpty ? ref.name : artistMeta.name,
                    source: .netease,
                    coverURL: artistMeta.coverURL.isEmpty ? (ref.coverURL ?? "") : artistMeta.coverURL,
                    bio: artistMeta.bio,
                    hotSongs: hotSongs,
                    albums: albums
                )
            }
        }
        // Fallback: multi-source search by artist name
        let hits = await searchNetEase(query: ref.name, limit: 20)
        return MusicArtistDetail(
            id: ref.id,
            name: ref.name,
            source: ref.source,
            coverURL: ref.coverURL ?? hits.first?.coverURL ?? "",
            bio: "",
            hotSongs: hits,
            albums: []
        )
    }

    private func fetchNetEaseArtistTop(artistId: String) async -> ((name: String, coverURL: String, bio: String), [MusicTrack]) {
        guard let url = URL(string: "https://music.163.com/api/v1/artist/\(artistId)") else { return (("", "", ""), []) }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return (("", "", ""), []) }
        let artist = root["artist"] as? [String: Any]
        let name = artist?["name"] as? String ?? ""
        let pic = Self.httpsURL(artist?["picUrl"] as? String ?? artist?["img1v1Url"] as? String ?? "")
        let bio = (artist?["briefDesc"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let rawSongs = root["hotSongs"] as? [[String: Any]] ?? []
        let tracks: [MusicTrack] = rawSongs.prefix(30).compactMap { t in
            guard let tid = t["id"] else { return nil }
            let sid = String(describing: tid)
            let title = (t["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty else { return nil }
            let arList = t["ar"] as? [[String: Any]] ?? []
            let artists = arList.compactMap { $0["name"] as? String }.joined(separator: " / ")
            let arId = arList.first?["id"].map { String(describing: $0) } ?? artistId
            let al = t["al"] as? [String: Any]
            let album = al?["name"] as? String ?? ""
            let alId = al?["id"].map { String(describing: $0) }
            let cover = Self.httpsURL(al?["picUrl"] as? String ?? pic)
            let dt = (t["dt"] as? Double ?? Double(t["dt"] as? Int ?? 210_000)) / 1000.0
            let fee = t["fee"] as? Int ?? 0
            return MusicTrack(
                id: "netease:\(sid)",
                source: .netease,
                sourceId: sid,
                title: title,
                artist: artists.isEmpty ? name : artists,
                album: album,
                coverURL: cover,
                durationSec: max(30, dt),
                qualityLabel: (fee == 1 || fee == 4) ? "320k / 自动换源" : "HQ 320k",
                entitlement: (fee == 1 || fee == 4) ? .preview : .full,
                streamURL: nil,
                streamHeaders: nil,
                pluginId: nil,
                extraHash: nil,
                artistId: arId,
                albumId: alId
            )
        }
        return ((name, pic, bio), tracks)
    }

    private func fetchNetEaseArtistAlbums(artistId: String, artistName: String) async -> [MusicAlbumSummary] {
        guard let url = URL(string: "https://music.163.com/api/artist/albums/\(artistId)?limit=18") else { return [] }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let hotAlbums = root["hotAlbums"] as? [[String: Any]] else { return [] }
        let df = DateFormatter()
        df.dateFormat = "yyyy"
        return hotAlbums.compactMap { al in
            guard let idVal = al["id"] else { return nil }
            let id = String(describing: idVal)
            let name = al["name"] as? String ?? ""
            guard !name.isEmpty else { return nil }
            let pic = Self.httpsURL(al["picUrl"] as? String ?? "")
            let size = al["size"] as? Int ?? 0
            let pubMs = al["publishTime"] as? Double ?? Double(al["publishTime"] as? Int64 ?? 0)
            let year = pubMs > 0 ? df.string(from: Date(timeIntervalSince1970: pubMs / 1000.0)) : nil
            return MusicAlbumSummary(id: id, name: name, artist: artistName, artistId: artistId, source: .netease, coverURL: pic, yearText: year, trackCount: size)
        }
    }

    func fetchAlbumDetail(ref: MusicAlbumRef) async -> MusicAlbumDetail {
        var resolvedId: String? = (ref.source == .netease) ? ref.albumId : nil
        if resolvedId == nil || resolvedId?.isEmpty == true {
            let hits = await searchNetEase(query: "\(ref.name) \(ref.artist)", limit: 8)
            resolvedId = hits.first(where: { $0.album.localizedCaseInsensitiveContains(ref.name) })?.albumId ?? hits.first?.albumId
        }
        if let alId = resolvedId, !alId.isEmpty,
           let url = URL(string: "https://music.163.com/api/v1/album/\(alId)") {
            var req = URLRequest(url: url)
            req.setValue(ua, forHTTPHeaderField: "User-Agent")
            req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
            if let (data, resp) = try? await session.data(for: req),
               (resp as? HTTPURLResponse)?.statusCode == 200,
               let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                let alb = root["album"] as? [String: Any]
                let name = alb?["name"] as? String ?? ref.name
                let cover = Self.httpsURL(alb?["picUrl"] as? String ?? ref.coverURL ?? "")
                let artistObj = alb?["artist"] as? [String: Any]
                let artistName = artistObj?["name"] as? String ?? ref.artist
                let artistId = artistObj?["id"].map { String(describing: $0) } ?? ref.artistId
                let company = alb?["company"] as? String
                let desc = (alb?["description"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                let pubMs = alb?["publishTime"] as? Double ?? Double(alb?["publishTime"] as? Int64 ?? 0)
                let df = DateFormatter()
                df.dateFormat = "yyyy-MM-dd"
                let releaseDate = pubMs > 0 ? df.string(from: Date(timeIntervalSince1970: pubMs / 1000.0)) : nil
                let rawSongs = root["songs"] as? [[String: Any]] ?? []
                let tracks: [MusicTrack] = rawSongs.compactMap { t in
                    guard let tid = t["id"] else { return nil }
                    let sid = String(describing: tid)
                    let title = (t["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !title.isEmpty else { return nil }
                    let arList = t["ar"] as? [[String: Any]] ?? []
                    let artists = arList.compactMap { $0["name"] as? String }.joined(separator: " / ")
                    let arId = arList.first?["id"].map { String(describing: $0) } ?? artistId
                    let dt = (t["dt"] as? Double ?? Double(t["dt"] as? Int ?? 210_000)) / 1000.0
                    let fee = t["fee"] as? Int ?? 0
                    return MusicTrack(
                        id: "netease:\(sid)",
                        source: .netease,
                        sourceId: sid,
                        title: title,
                        artist: artists.isEmpty ? artistName : artists,
                        album: name,
                        coverURL: cover,
                        durationSec: max(30, dt),
                        qualityLabel: (fee == 1 || fee == 4) ? "320k / 自动换源" : "HQ 320k",
                        entitlement: (fee == 1 || fee == 4) ? .preview : .full,
                        streamURL: nil,
                        streamHeaders: nil,
                        pluginId: nil,
                        extraHash: nil,
                        artistId: arId,
                        albumId: alId
                    )
                }
                if !tracks.isEmpty {
                    return MusicAlbumDetail(
                        id: alId,
                        name: name,
                        artist: artistName,
                        artistId: artistId,
                        source: .netease,
                        coverURL: cover,
                        releaseDate: releaseDate,
                        company: company,
                        description: desc,
                        tracks: tracks
                    )
                }
            }
        }
        let fallbackTracks = await searchNetEase(query: "\(ref.name) \(ref.artist)", limit: 15)
        return MusicAlbumDetail(
            id: ref.id,
            name: ref.name,
            artist: ref.artist,
            artistId: ref.artistId,
            source: ref.source,
            coverURL: ref.coverURL ?? fallbackTracks.first?.coverURL ?? "",
            releaseDate: nil,
            company: nil,
            description: "",
            tracks: fallbackTracks
        )
    }

    // MARK: - Synced Lyrics (Qishui KRC / NetEase / QQ / Kugou -> LRCLIB -> NetEase Search)

    func fetchLyrics(for track: MusicTrack, lrclibEnabled: Bool) async -> [MusicLyricLine] {
        // 1) Qishui native KRC / LRC directly from h5/seo_track.
        if track.source == .qishui,
           let seo = await fetchQishuiSEOTrack(trackId: track.sourceId, cookie: nil),
           let lrc = seo.lyric, !lrc.isEmpty {
            let parsed = MusicLyricsParser.parse(lrc: lrc)
            if !parsed.isEmpty { return parsed }
        }

        // 2) NetEase native LRC + translation + romaji directly.
        if track.source == .netease {
            let ne = await fetchNetEaseLyrics(songId: track.sourceId)
            if !ne.isEmpty { return ne }
        }

        // 3) QQ Music native LRC + translation directly (zero-auth HTTPS).
        if track.source == .qq {
            let qq = await fetchQQLyrics(songMid: track.sourceId)
            if !qq.isEmpty { return qq }
        }

        // 4) Kugou native LRC directly via lyrics.kugou.com search + base64 download (zero-auth HTTPS).
        if track.source == .kugou {
            let hash = track.extraHash?.nilIfEmpty ?? track.sourceId
            let kg = await fetchKugouLyrics(hash: hash, title: track.title, artist: track.primaryArtistName, durationSec: track.durationSec)
            if !kg.isEmpty { return kg }
        }

        // 5) Public zero-auth LRCLIB fallback (ported from yimeng).
        if lrclibEnabled {
            let primaryArtist = track.artist.components(separatedBy: "/").first?.trimmingCharacters(in: .whitespaces) ?? track.artist
            var comps = URLComponents(string: "https://lrclib.net/api/get")
            comps?.queryItems = [
                URLQueryItem(name: "track_name", value: track.title),
                URLQueryItem(name: "artist_name", value: primaryArtist)
            ]
            if let url = comps?.url {
                var req = URLRequest(url: url)
                req.setValue("iDuanJu/1.6 (https://github.com/my-name-is-alan/iDuanJu)", forHTTPHeaderField: "User-Agent")
                if let (data, resp) = try? await session.data(for: req),
                   (resp as? HTTPURLResponse)?.statusCode == 200,
                   let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                    if let synced = root["syncedLyrics"] as? String, !synced.isEmpty {
                        let parsed = MusicLyricsParser.parse(lrc: synced)
                        if !parsed.isEmpty { return parsed }
                    }
                    if let plain = root["plainLyrics"] as? String, !plain.isEmpty {
                        let lines = plain.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
                        let step = max(2.5, track.durationSec / Double(max(lines.count, 1)))
                        return lines.enumerated().map { i, txt in MusicLyricLine(id: i, timeSec: Double(i) * step, text: txt) }
                    }
                }
            }
        }

        // 6) Cross-source synced lyrics fallback via NetEase match (parity with Android, covers NAS/iTunes/rare misses).
        if track.source != .netease {
            let hits = await searchNetEase(query: "\(track.title) \(track.primaryArtistName)", limit: 5)
            if let match = Self.bestMatch(in: hits, target: track) {
                let ne = await fetchNetEaseLyrics(songId: match.sourceId)
                if !ne.isEmpty { return ne }
            }
        }
        return []
    }

    private func fetchNetEaseLyrics(songId: String) async -> [MusicLyricLine] {
        guard !songId.isEmpty,
              let url = URL(string: "https://music.163.com/api/song/lyric?id=\(Self.urlValueEnc(songId))&lv=1&tv=1&rv=1") else { return [] }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [] }
        let lrc = (root["lrc"] as? [String: Any])?["lyric"] as? String ?? ""
        let trans = (root["tlyric"] as? [String: Any])?["lyric"] as? String
        let roma = (root["romalrc"] as? [String: Any])?["lyric"] as? String
        return MusicLyricsParser.parse(lrc: lrc, translation: trans, romaji: roma)
    }

    func fetchQQLyrics(songMid: String) async -> [MusicLyricLine] {
        let mid = songMid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !mid.isEmpty,
              let url = URL(string: "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=\(Self.urlValueEnc(mid))&nobase64=1&format=json&g_tk=5381") else { return [] }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://y.qq.com/", forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [] }
        let rawLrc = root["lyric"] as? String ?? ""
        guard !rawLrc.isEmpty else { return [] }
        let rawTrans = root["trans"] as? String
        return MusicLyricsParser.parse(lrc: rawLrc, translation: rawTrans)
    }

    func fetchKugouLyrics(hash: String, title: String, artist: String, durationSec: Double) async -> [MusicLyricLine] {
        let cleanHash = hash.trimmingCharacters(in: .whitespacesAndNewlines)
        let durMs = max(1000, Int(durationSec * 1000))
        let kw = Self.urlValueEnc("\(artist)-\(title)")
        let searchStr = !cleanHash.isEmpty
            ? "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=\(Self.urlValueEnc(cleanHash))&duration=\(durMs)&keyword=\(kw)"
            : "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&duration=\(durMs)&keyword=\(kw)"
        guard let searchURL = URL(string: searchStr) else { return [] }
        var sReq = URLRequest(url: searchURL)
        sReq.setValue(ua, forHTTPHeaderField: "User-Agent")
        guard let (sData, sResp) = try? await session.data(for: sReq),
              (sResp as? HTTPURLResponse)?.statusCode == 200,
              let sRoot = (try? JSONSerialization.jsonObject(with: sData)) as? [String: Any],
              let cands = sRoot["candidates"] as? [[String: Any]],
              let first = cands.first,
              let cid = first["id"].map({ String(describing: $0) }),
              let key = first["accesskey"] as? String,
              !cid.isEmpty, !key.isEmpty,
              let dlURL = URL(string: "https://lyrics.kugou.com/download?ver=1&client=pc&id=\(Self.urlValueEnc(cid))&accesskey=\(Self.urlValueEnc(key))&fmt=lrc&charset=utf8") else { return [] }
        var dReq = URLRequest(url: dlURL)
        dReq.setValue(ua, forHTTPHeaderField: "User-Agent")
        guard let (dData, dResp) = try? await session.data(for: dReq),
              (dResp as? HTTPURLResponse)?.statusCode == 200,
              let dRoot = (try? JSONSerialization.jsonObject(with: dData)) as? [String: Any],
              let b64 = dRoot["content"] as? String,
              let decoded = Data(base64Encoded: b64, options: .ignoreUnknownCharacters),
              let lrcStr = String(data: decoded, encoding: .utf8), !lrcStr.isEmpty else { return [] }
        return MusicLyricsParser.parse(lrc: lrcStr)
    }

    // MARK: - Direct Personal NAS Connectors (Navidrome / Emby / 飞牛音乐 fnOS, Zero Core)

    func fetchNASTracks(config: MusicNASConfig) async -> [MusicTrack] {
        switch config.kind {
        case .navidrome: return await fetchNavidromeTracks(config: config, query: nil)
        case .emby: return await fetchEmbyTracks(config: config, query: nil)
        case .fnos: return await fetchFnosTracks(config: config, query: nil)
        default: return []
        }
    }

    func searchNAS(config: MusicNASConfig, query: String) async -> [MusicTrack] {
        switch config.kind {
        case .navidrome: return await fetchNavidromeTracks(config: config, query: query)
        case .emby: return await fetchEmbyTracks(config: config, query: query)
        case .fnos: return await fetchFnosTracks(config: config, query: query)
        default: return []
        }
    }

    private func fetchNavidromeTracks(config: MusicNASConfig, query: String?) async -> [MusicTrack] {
        let base = config.serverURL.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
        guard !base.isEmpty, !config.username.isEmpty else { return [] }
        let salt = String(UUID().uuidString.prefix(8))
        let md5Input = Data((config.password + salt).utf8)
        let token = Insecure.MD5.hash(data: md5Input).map { String(format: "%02x", $0) }.joined()
        let authQ = "u=\(Self.urlEnc(config.username))&t=\(token)&s=\(salt)&v=1.16.1&c=iduanju&f=json"
        let endpoint = (query?.isEmpty == false)
            ? "\(base)/rest/search3.view?\(authQ)&query=\(Self.urlEnc(query!))&songCount=30&albumCount=0&artistCount=0"
            : "\(base)/rest/getRandomSongs.view?\(authQ)&size=40"
        guard let url = URL(string: endpoint),
              let (data, resp) = try? await session.data(from: url),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let sub = root["subsonic-response"] as? [String: Any] else { return [] }

        let rawSongs: [[String: Any]]
        if let sr = sub["searchResult3"] as? [String: Any], let arr = sr["song"] as? [[String: Any]] {
            rawSongs = arr
        } else if let rs = sub["randomSongs"] as? [String: Any], let arr = rs["song"] as? [[String: Any]] {
            rawSongs = arr
        } else {
            return []
        }

        return rawSongs.compactMap { s in
            guard let id = s["id"] as? String, let title = s["title"] as? String else { return nil }
            let artist = s["artist"] as? String ?? "未知歌手"
            let album = s["album"] as? String ?? ""
            let dur = Double(s["duration"] as? Int ?? 210)
            let suffix = (s["suffix"] as? String ?? "FLAC").uppercased()
            let br = s["bitRate"] as? Int ?? 0
            let coverId = s["coverArt"] as? String ?? id
            let coverURL = "\(base)/rest/getCoverArt.view?\(authQ)&id=\(Self.urlEnc(coverId))&size=500"
            let streamURL = "\(base)/rest/stream.view?\(authQ)&id=\(Self.urlEnc(id))&format=raw"
            return MusicTrack(
                id: "navidrome:\(id)",
                source: .navidrome,
                sourceId: id,
                title: title,
                artist: artist,
                album: album,
                coverURL: coverURL,
                durationSec: max(10, dur),
                qualityLabel: br > 0 ? "\(suffix) \(br)k" : "\(suffix) 无损",
                entitlement: .full,
                streamURL: streamURL,
                streamHeaders: nil,
                pluginId: nil,
                extraHash: nil
            )
        }
    }

    private func fetchEmbyTracks(config: MusicNASConfig, query: String?) async -> [MusicTrack] {
        let base = config.serverURL.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
        guard !base.isEmpty, let authURL = URL(string: "\(base)/Users/AuthenticateByName") else { return [] }
        var authReq = URLRequest(url: authURL)
        authReq.httpMethod = "POST"
        authReq.setValue("application/json", forHTTPHeaderField: "Content-Type")
        authReq.setValue("Emby Client=\"iDuanJu\", Device=\"iOS\", DeviceId=\"idj-ios\", Version=\"1.6\"", forHTTPHeaderField: "X-Emby-Authorization")
        authReq.httpBody = try? JSONSerialization.data(withJSONObject: ["Username": config.username, "Pw": config.password])
        guard let (aData, aResp) = try? await session.data(for: authReq),
              (aResp as? HTTPURLResponse)?.statusCode == 200,
              let aRoot = (try? JSONSerialization.jsonObject(with: aData)) as? [String: Any],
              let token = aRoot["AccessToken"] as? String,
              let uid = (aRoot["User"] as? [String: Any])?["Id"] as? String else { return [] }

        let qPart = (query?.isEmpty == false) ? "&SearchTerm=\(Self.urlEnc(query!))" : ""
        guard let listURL = URL(string: "\(base)/Users/\(uid)/Items?IncludeItemTypes=Audio&Recursive=true&Limit=40&Fields=MediaSources\(qPart)") else { return [] }
        var listReq = URLRequest(url: listURL)
        listReq.setValue(token, forHTTPHeaderField: "X-Emby-Token")
        guard let (data, resp) = try? await session.data(for: listReq),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let items = root["Items"] as? [[String: Any]] else { return [] }

        return items.compactMap { it in
            guard let id = it["Id"] as? String, let name = it["Name"] as? String else { return nil }
            let artists = (it["Artists"] as? [String])?.joined(separator: " / ") ?? "未知歌手"
            let album = it["Album"] as? String ?? ""
            let ticks = it["RunTimeTicks"] as? Int64 ?? 2_100_000_000
            let dur = Double(ticks) / 10_000_000.0
            let cover = "\(base)/Items/\(id)/Images/Primary?maxWidth=500&api_key=\(token)"
            let stream = "\(base)/Audio/\(id)/stream?static=true&api_key=\(token)"
            return MusicTrack(
                id: "emby:\(id)",
                source: .emby,
                sourceId: id,
                title: name,
                artist: artists,
                album: album,
                coverURL: cover,
                durationSec: max(10, dur),
                qualityLabel: "NAS 原流",
                entitlement: .full,
                streamURL: stream,
                streamHeaders: ["X-Emby-Token": token],
                pluginId: nil,
                extraHash: nil
            )
        }
    }

    private func fetchFnosTracks(config: MusicNASConfig, query: String?) async -> [MusicTrack] {
        let base = config.serverURL.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
        guard !base.isEmpty, let loginURL = URL(string: "\(base)/music/api/v1/user/password-login") else { return [] }
        let pwHash = SHA256.hash(data: Data(config.password.utf8)).map { String(format: "%02x", $0) }.joined()
        var loginReq = URLRequest(url: loginURL)
        loginReq.httpMethod = "POST"
        loginReq.setValue("application/json", forHTTPHeaderField: "Content-Type")
        loginReq.setValue("v1", forHTTPHeaderField: "X-Music-API")
        loginReq.httpBody = try? JSONSerialization.data(withJSONObject: [
            "username": config.username,
            "password": pwHash,
            "deviceId": "iduanju0000000000000000000000001"
        ])
        guard let (lData, lResp) = try? await session.data(for: loginReq),
              (lResp as? HTTPURLResponse)?.statusCode == 200,
              let lRoot = (try? JSONSerialization.jsonObject(with: lData)) as? [String: Any],
              let token = (lRoot["data"] as? [String: Any])?["userToken"] as? String else { return [] }

        let endpoint = (query?.isEmpty == false)
            ? "\(base)/music/api/v1/search/track?q=\(Self.urlEnc(query!))&page=1&pageSize=30"
            : "\(base)/music/api/v1/track/list?page=1&pageSize=40"
        guard let url = URL(string: endpoint) else { return [] }
        var req = URLRequest(url: url)
        req.setValue("music-token=\(token)", forHTTPHeaderField: "Cookie")
        req.setValue("v1", forHTTPHeaderField: "X-Music-API")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any],
              let list = (d["list"] as? [[String: Any]]) ?? (d["tracks"] as? [[String: Any]]) else { return [] }

        return list.compactMap { it in
            guard let guid = it["guid"] as? String, let title = it["title"] as? String else { return nil }
            let artist = (it["artists"] as? [[String: Any]])?.compactMap { $0["name"] as? String }.joined(separator: " / ") ?? (it["artist"] as? String ?? "未知歌手")
            let album = it["albumTitle"] as? String ?? ""
            let dur = Double(it["duration"] as? Int ?? 210)
            let coverId = it["coverId"] as? String ?? ""
            let cover = coverId.isEmpty ? "" : "\(base)/music/api/v1/static/cover?coverId=\(Self.urlEnc(coverId))&size=500"
            let stream = "\(base)/music/api/v1/track/stream?guid=\(Self.urlEnc(guid))"
            return MusicTrack(
                id: "fnos:\(guid)",
                source: .fnos,
                sourceId: guid,
                title: title,
                artist: artist,
                album: album,
                coverURL: cover,
                durationSec: max(10, dur),
                qualityLabel: "飞牛无损",
                entitlement: .full,
                streamURL: stream,
                streamHeaders: ["Cookie": "music-token=\(token)", "X-Music-API": "v1"],
                pluginId: nil,
                extraHash: nil
            )
        }
    }

    // MARK: - Helpers

    static func bestMatch(in candidates: [MusicTrack], target: MusicTrack) -> MusicTrack? {
        let tTitle = normalize(target.title)
        let tArtist = normalize(target.artist.components(separatedBy: "/").first ?? target.artist)
        var best: (score: Int, track: MusicTrack)?
        for c in candidates {
            let ct = normalize(c.title)
            let ca = normalize(c.artist)
            var score = 0
            if ct == tTitle { score += 50 }
            else if ct.contains(tTitle) || tTitle.contains(ct) { score += 25 }
            if !tArtist.isEmpty && (ca.contains(tArtist) || tArtist.contains(ca)) { score += 35 }
            if abs(c.durationSec - target.durationSec) <= 3.0 { score += 15 }
            if score >= 50 && score > (best?.score ?? -1) {
                best = (score, c)
            }
        }
        return best?.track
    }

    private static func normalize(_ s: String) -> String {
        s.lowercased()
            .replacingOccurrences(of: #"\(.*?\)|（.*?）|\[.*?\]"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: "", options: .regularExpression)
    }

    private static func httpsURL(_ s: String) -> String {
        s.hasPrefix("http://") ? "https://" + s.dropFirst(7) : s
    }

    private static func urlEnc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? s
    }

    private static func urlValueEnc(_ s: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}
