import XCTest
@testable import STV

final class LegadoFullParityTests: XCTestCase {
    override func setUp() {
        super.setUp()
        LegadoHTTP.transport = nil
        LegadoBackstage.loader = nil
        LegadoRateLimit.resetForTest()
        LegadoReplaceStore.shared.replaceAllForTest([])
    }

    override func tearDown() {
        LegadoHTTP.transport = nil
        LegadoBackstage.loader = nil
        LegadoRateLimit.resetForTest()
        LegadoReplaceStore.shared.replaceAllForTest([])
        super.tearDown()
    }

    private func http(_ url: String, body: String, status: Int = 200, headers: [String: String] = ["Content-Type": "text/html; charset=utf-8"]) -> (Data, HTTPURLResponse) {
        let u = URL(string: url)!
        return (Data(body.utf8), HTTPURLResponse(url: u, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!)
    }

    /// Minimal valid TrueType (sfnt) binary with head/maxp/loca/glyf/cmap(format 4) for synthetic contour de-obfuscation tests.
    private func buildSyntheticTTF(entries: [(UInt16, [(Int16, Int16)])]) -> Data {
        func be16(_ v: UInt16) -> [UInt8] { [UInt8(v >> 8), UInt8(v & 0xFF)] }
        func be32(_ v: UInt32) -> [UInt8] { [UInt8((v >> 24) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)] }

        let sorted = entries.sorted { $0.0 < $1.0 }
        let numGlyphs = UInt16(sorted.count + 1)

        // glyf + loca (indexToLocFormat = 1, 32-bit offsets)
        var glyf = [UInt8]()
        var offsets: [UInt32] = [0]
        // glyph 0 (.notdef empty)
        offsets.append(0)
        for (_, pts) in sorted {
            var g = [UInt8]()
            g += be16(1) // numberOfContours = 1
            g += be16(0) + be16(0) + be16(100) + be16(100) // bbox
            g += be16(UInt16(pts.count - 1)) // endPtsOfContours[0]
            g += be16(0) // instructionLength = 0
            // flags: onCurve (0x01) for every point, 16-bit deltas
            for _ in pts { g.append(0x01) }
            var prevX: Int16 = 0
            for p in pts {
                let dx = p.0 &- prevX; prevX = p.0
                g += be16(UInt16(bitPattern: dx))
            }
            var prevY: Int16 = 0
            for p in pts {
                let dy = p.1 &- prevY; prevY = p.1
                g += be16(UInt16(bitPattern: dy))
            }
            while g.count % 4 != 0 { g.append(0) }
            glyf += g
            offsets.append(UInt32(glyf.count))
        }
        var loca = [UInt8]()
        for o in offsets { loca += be32(o) }

        // head (54 bytes, indexToLocFormat = 1 at offset 50)
        var head = [UInt8](repeating: 0, count: 54)
        head[50] = 0; head[51] = 1

        // maxp (6 bytes)
        var maxp = [UInt8]()
        maxp += be32(0x00010000) + be16(numGlyphs)

        // cmap (header + 1 subtable format 4, platform 3 encoding 1)
        let segCount = UInt16(sorted.count + 1)
        var fmt4 = [UInt8]()
        var subBody = [UInt8]()
        for (cp, _) in sorted { subBody += be16(cp) }
        subBody += be16(0xFFFF)
        subBody += be16(0) // reservedPad
        for (cp, _) in sorted { subBody += be16(cp) }
        subBody += be16(0xFFFF)
        for (idx, (cp, _)) in sorted.enumerated() {
            let gid = UInt16(idx + 1)
            let delta = gid &- cp
            subBody += be16(delta)
        }
        subBody += be16(1)
        for _ in 0..<Int(segCount) { subBody += be16(0) }
        let fmt4Len = UInt16(14 + subBody.count)
        fmt4 += be16(4) + be16(fmt4Len) + be16(0)
        fmt4 += be16(segCount * 2) + be16(2) + be16(0) + be16(0)
        fmt4 += subBody

        var cmap = [UInt8]()
        cmap += be16(0) + be16(1) // version 0, 1 table
        cmap += be16(3) + be16(1) + be32(12) // platform 3, encoding 1, offset 12
        cmap += fmt4

        let tables: [(String, [UInt8])] = [("cmap", cmap), ("glyf", glyf), ("head", head), ("loca", loca), ("maxp", maxp)]
        var out = [UInt8]()
        out += be32(0x00010000) + be16(UInt16(tables.count)) + be16(64) + be16(2) + be16(16)
        var curOffset = 12 + tables.count * 16
        var paddedTables: [[UInt8]] = []
        for (tag, bytes) in tables {
            out += Array(tag.utf8)
            out += be32(0) // checksum
            out += be32(UInt32(curOffset))
            out += be32(UInt32(bytes.count))
            var p = bytes
            while p.count % 4 != 0 { p.append(0) }
            paddedTables.append(p)
            curOffset += p.count
        }
        for p in paddedTables { out += p }
        return Data(out)
    }

    func testQueryTTFReplaceFont() throws {
        // Real font: '一'(0x4E00) has contour A, '二'(0x4E8C) has contour B.
        // Obfuscated font: PUA 0xE001 has contour A, PUA 0xE002 has contour B.
        let contourA: [(Int16, Int16)] = [(10, 10), (90, 10), (90, 20)]
        let contourB: [(Int16, Int16)] = [(10, 10), (50, 80), (90, 10)]
        let realTTF = buildSyntheticTTF(entries: [(0x4E00, contourA), (0x4E8C, contourB)])
        let obfTTF = buildSyntheticTTF(entries: [(0xE001, contourA), (0xE002, contourB)])

        let realB64 = realTTF.base64EncodedString()
        let obfB64 = obfTTF.base64EncodedString()
        let src = LegadoSource(bookSourceUrl: "https://ttf.test", bookSourceName: "TTF", searchUrl: "/s?k={{key}}")
        let ctx = LegadoContext(source: src)
        let js = """
        var baseF = java.queryTTF('\(realB64)');
        var errF = java.queryTTF('\(obfB64)');
        java.replaceFont('第\\uE001章 卷\\uE002', errF, baseF, false);
        """
        let decoded = try ctx.evalJS(js, result: nil, baseURL: src.bookSourceUrl) as? String
        XCTAssertEqual(decoded, "第一章 卷二")
    }

    func testLoginUIV2Flow() throws {
        var src = LegadoSource(bookSourceUrl: "https://loginv2.test", bookSourceName: "V2", searchUrl: "/s?k={{key}}")
        src.loginUi = #"{"version":2}"#
        src.loginUrl = """
        function loginUi(state) {
            if (state && state.step === 2) {
                return { rows: [
                    { key: 'code', name: '验证码', type: 'text' },
                    { key: 'submit', name: '确认登录', type: 'button', action: 'verify' }
                ]};
            }
            return { rows: [
                { key: 'phone', name: '手机号', type: 'text' },
                { key: 'next', name: '获取验证码', type: 'button', action: 'sendCode' }
            ]};
        }
        function loginAction(action, state, form) {
            if (action === 'sendCode') {
                if (!form.phone) return { error: { phone: '请输入手机号' } };
                return { state: { step: 2, phone: form.phone }, message: '验证码已发送' };
            }
            if (action === 'verify') {
                return { login: { phone: state.phone, token: 'tok_' + form.code }, close: true, message: '登录成功' };
            }
            return {};
        }
        """
        defer { LegadoSourceState.logout(src.bookSourceUrl) }

        XCTAssertTrue(LegadoLoginV2.isV2(src))
        let step1 = try LegadoLoginV2.render(src, stateJSON: "{}")
        XCTAssertEqual(step1.map(\.key), ["phone", "next"])

        let errRes = try LegadoLoginV2.performAction(src, action: "sendCode", stateJSON: "{}", form: ["phone": ""])
        XCTAssertEqual(errRes.errors["phone"], "请输入手机号")

        let nextRes = try LegadoLoginV2.performAction(src, action: "sendCode", stateJSON: "{}", form: ["phone": "13800138000"])
        XCTAssertEqual(nextRes.message, "验证码已发送")
        let nextState = try XCTUnwrap(nextRes.stateJSON)

        let step2 = try LegadoLoginV2.render(src, stateJSON: nextState)
        XCTAssertEqual(step2.map(\.key), ["code", "submit"])

        let doneRes = try LegadoLoginV2.performAction(src, action: "verify", stateJSON: nextState, form: ["code": "9988"])
        XCTAssertTrue(doneRes.close)
        XCTAssertEqual(LegadoSourceState.loginInfo(src.bookSourceUrl)["token"], "tok_9988")
    }

    func testCookiesCaptureAndNoRedirectOption() throws {
        let testURL = URL(string: "https://cookie-parity.test/login")!
        defer { LegadoCookies.remove(for: testURL) }

        LegadoHTTP.transport = { req in
            if req.url?.path == "/login" {
                return self.http(req.url!.absoluteString, body: "ok", headers: [
                    "Content-Type": "text/plain",
                    "Set-Cookie": "sid=abc123; Path=/; Max-Age=3600"
                ])
            }
            return self.http(req.url!.absoluteString, body: req.value(forHTTPHeaderField: "Cookie") ?? "none")
        }
        _ = try LegadoHTTP.fetch(URLRequest(url: testURL))
        let second = try LegadoHTTP.fetch(URLRequest(url: URL(string: "https://cookie-parity.test/api")!))
        XCTAssertTrue(second.text.contains("sid=abc123"))

        let src = LegadoSource(bookSourceUrl: "https://cookie-parity.test", bookSourceName: "Opt", searchUrl: "/s?k={{key}}")
        let ctx = LegadoContext(source: src)
        let req = try LegadoURL.request(#"https://cookie-parity.test/r,{"method":"HEAD","followRedirects":false,"retry":2,"timeout":8000}"#, ctx: ctx, base: src.bookSourceUrl)
        XCTAssertEqual(req.request.httpMethod, "HEAD")
        XCTAssertFalse(req.followRedirects)
        XCTAssertEqual(req.retry, 2)
    }

    func testFullPipelineWebViewPostAndBodyJs() throws {
        var captured: LegadoBackstageRequest?
        LegadoBackstage.loader = { req in
            captured = req
            return LegadoResponse(url: req.url, status: 200, headers: [:], body: Data(), text: #"<div class="hit"><a href="/book/9">动态渲染书</a></div>"#)
        }
        let src = LegadoSource(
            bookSourceUrl: "https://wv.test",
            bookSourceName: "WV",
            searchUrl: #"/search,{"method":"POST","webView":true,"bodyJs":"'q=' + key","overrideUrlRegex":"https://wv\\.test/final/.*"}"#,
            ruleSearch: ["bookList": "class.hit", "name": "tag.a@text", "bookUrl": "tag.a@href"]
        )
        let hits = try LegadoClient.search(src, key: "星海")
        XCTAssertEqual(hits.first?.name, "动态渲染书")
        XCTAssertEqual(captured?.method, "POST")
        XCTAssertEqual(captured?.body, "q=星海")
        XCTAssertEqual(captured?.overrideUrlRegex, #"https://wv\.test/final/.*"#)
    }

    func testTocAndContentHooks() throws {
        LegadoHTTP.transport = { req in
            switch req.url?.path {
            case "/toc":
                return self.http(req.url!.absoluteString, body: #"<ul><li data-vip="1" data-time="2026-01-01"><a href="/c/1">第一章 初遇</a></li></ul>"#)
            case "/c/1":
                return self.http(req.url!.absoluteString, body: #"<h1>真正标题</h1><div class="p">原始AD正文</div><div class="sub">作者的话：感谢支持</div>"#)
            default:
                return self.http(req.url!.absoluteString, body: "")
            }
        }
        let src = LegadoSource(
            bookSourceUrl: "https://hooks.test",
            bookSourceName: "Hooks",
            searchUrl: "/s?k={{key}}",
            ruleToc: [
                "preUpdateJs": "@js:source.put('preToc', 'ran'); result",
                "chapterList": "tag.li",
                "chapterName": "tag.a@text",
                "chapterUrl": "tag.a@href",
                "isVip": "@data-vip",
                "updateTime": "@data-time",
                "formatJs": "@js:'【精校】' + title"
            ],
            ruleContent: [
                "title": "tag.h1@text",
                "content": "class.p@text",
                "subContent": "class.sub@text",
                "replaceRegex": "@js:result.replace('AD', '')"
            ]
        )
        let toc = try LegadoClient.toc(src, tocURL: "https://hooks.test/toc")
        XCTAssertEqual(toc.count, 1)
        XCTAssertEqual(toc[0].title, "【精校】第一章 初遇")
        XCTAssertTrue(toc[0].isVip)
        XCTAssertEqual(toc[0].updateTime, "2026-01-01")
        XCTAssertEqual(LegadoVars.get("s|" + src.bookSourceUrl, "preToc"), "ran")

        let content = try LegadoClient.content(src, chapter: toc[0], nextChapterURL: nil)
        XCTAssertTrue(content.contains("真正标题"))
        XCTAssertTrue(content.contains("原始正文"))
        XCTAssertFalse(content.contains("AD"))
        XCTAssertTrue(content.contains("作者的话：感谢支持"))
    }

    func testJsEncodeUtilsAndHelpers() throws {
        let src = LegadoSource(bookSourceUrl: "https://util.test", bookSourceName: "Util", searchUrl: "/s?k={{key}}")
        let ctx = LegadoContext(source: src)
        let js = """
        JSON.stringify({
            hmacHex: java.HMacHex('hello', 'HmacSHA256', 'secret'),
            hmacB64: java.HMacBase64('hello', 'HmacSHA256', 'secret'),
            sha256: java.digestHex('abc', 'SHA-256'),
            gcm: (function() {
                var c = java.createSymmetricCrypto('AES/GCM/NoPadding', '1234567890abcdef', '1234567890ab');
                return c.decryptStr(c.encryptBase64('机密正文'));
            })(),
            urlHost: java.toURL('https://a.test:8443/x/y?k=1').host,
            utc: java.timeFormatUTC(0, 'yyyy-MM-dd', 8),
            numCh: java.toNumChapter('第十二章 风起'),
            simp: java.t2s('測試繁體'),
            t1: java.tick('k'),
            t2: java.tick('k')
        })
        """
        let raw = try XCTUnwrap(ctx.evalJS(js, result: nil, baseURL: src.bookSourceUrl) as? String)
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
        XCTAssertEqual(obj["hmacHex"] as? String, "88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b")
        XCTAssertEqual(obj["sha256"] as? String, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertFalse((obj["hmacB64"] as? String ?? "").isEmpty)
        XCTAssertEqual(obj["gcm"] as? String, "机密正文")
        XCTAssertEqual(obj["urlHost"] as? String, "a.test")
        XCTAssertEqual(obj["utc"] as? String, "1970-01-01")
        XCTAssertEqual(obj["numCh"] as? String, "第12章 风起")
        XCTAssertEqual(obj["t1"] as? Int, 1)
        XCTAssertEqual(obj["t2"] as? Int, 2)
    }

    func testSandboxedFilesAndZip() throws {
        // Build a minimal valid STORE ZIP containing "note.txt" -> "Hello ZIP!"
        func buildStoreZip(name: String, content: Data) -> Data {
            func le16(_ v: UInt16) -> [UInt8] { [UInt8(v & 0xFF), UInt8(v >> 8)] }
            func le32(_ v: UInt32) -> [UInt8] { [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 24) & 0xFF)] }
            let nameBytes = [UInt8](name.utf8)
            let dataBytes = [UInt8](content)
            var out = [UInt8]()
            // Local file header
            out += le32(0x04034B50) + le16(20) + le16(0) + le16(0) + le16(0) + le16(0) + le32(0)
            out += le32(UInt32(dataBytes.count)) + le32(UInt32(dataBytes.count))
            out += le16(UInt16(nameBytes.count)) + le16(0)
            out += nameBytes + dataBytes
            let cdOffset = UInt32(out.count)
            // Central directory header
            var cd = [UInt8]()
            cd += le32(0x02014B50) + le16(20) + le16(20) + le16(0) + le16(0) + le16(0) + le16(0) + le32(0)
            cd += le32(UInt32(dataBytes.count)) + le32(UInt32(dataBytes.count))
            cd += le16(UInt16(nameBytes.count)) + le16(0) + le16(0) + le16(0) + le16(0) + le32(0) + le32(0)
            cd += nameBytes
            out += cd
            // End of central directory
            out += le32(0x06054B50) + le16(0) + le16(0) + le16(1) + le16(1)
            out += le32(UInt32(cd.count)) + le32(cdOffset) + le16(0)
            return Data(out)
        }

        let zipData = buildStoreZip(name: "note.txt", content: Data("ZIP正文内容".utf8))
        LegadoHTTP.transport = { req in
            (zipData, HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!)
        }
        let src = LegadoSource(bookSourceUrl: "https://files.test", bookSourceName: "Files", searchUrl: "/s?k={{key}}")
        let ctx = LegadoContext(source: src)
        let js = """
        var zp = java.downloadFile('https://files.test/a.zip');
        var direct = java.getZipStringContent('https://files.test/a.zip', 'note.txt');
        var folder = java.unzipFile(zp);
        var fromFolder = java.getTxtInFolder(folder);
        java.deleteFile(zp);
        JSON.stringify({ direct: direct, fromFolder: fromFolder });
        """
        let raw = try XCTUnwrap(ctx.evalJS(js, result: nil, baseURL: src.bookSourceUrl) as? String)
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
        XCTAssertEqual(obj["direct"] as? String, "ZIP正文内容")
        XCTAssertEqual(obj["fromFolder"] as? String, "ZIP正文内容")
    }

    func testSingleFileJsSourceBatchReviewDebugAndCheck() throws {
        let jsSourceText = """
        var config = {
            bookSourceUrl: "https://js-source.test",
            bookSourceName: "单文件JS演示源",
            bookSourceGroup: "JS",
            bookSourceType: 0
        };
        function search(key, page) {
            return [{ name: "云上之城-" + key, author: "林深", bookUrl: "/book/42", intro: "简介" }];
        }
        function getBookInfo(book) {
            return { name: "云上之城", author: "林深", intro: "完整简介", tocUrl: "/toc/42" };
        }
        function getChapters(book) {
            return [
                { title: "第一章 启程", url: "/ch/1" },
                { title: "第二章 远航", url: "/ch/2" }
            ];
        }
        function getContent(chapter, book, nextUrl) {
            return "<p>正文-" + chapter.title + "</p>";
        }
        function getContentBatch(chapters, book) {
            for (var i = 0; i < chapters.length; i++) {
                java.cacheContent(chapters[i], "批量正文-" + chapters[i].title);
            }
        }
        function getReviewSummary(chapter, book) {
            return [{ paraIndex: 1, count: 2, paraData: "p1" }];
        }
        function getReviewDetail(chapter, book, paraIndex, paraData, page) {
            return { items: [{ id: "r1", name: "读者甲", badges: ["铁粉"], content: { text: "写得太好了", likeCount: 18 } }] };
        }
        function getReviewReplies(chapter, book, paraIndex, paraData, reviewId, page) {
            return { items: [{ id: "r1_1", name: "读者乙", content: { text: "深有同感" } }] };
        }
        """
        let parsed = try LegadoSourceStore.parse(Data(jsSourceText.utf8))
        let src = try XCTUnwrap(parsed.first)
        XCTAssertTrue(src.isJsSource)
        XCTAssertTrue(src.supported)
        XCTAssertTrue(src.supportsContentBatch)
        XCTAssertTrue(LegadoReview.isEnabled(src))

        let hits = try LegadoClient.search(src, key: "星辰")
        XCTAssertEqual(hits.first?.name, "云上之城-星辰")

        let info = try LegadoClient.info(src, bookURL: hits[0].bookURL)
        XCTAssertEqual(info.author, "林深")

        let toc = try LegadoClient.toc(src, tocURL: info.tocURL)
        XCTAssertEqual(toc.count, 2)

        let single = try LegadoClient.content(src, chapter: toc[0], nextChapterURL: toc[1].url)
        XCTAssertEqual(single, "正文-第一章 启程")

        let batch = try LegadoClient.contentBatch(src, chapters: toc)
        XCTAssertEqual(batch[toc[0].url], "批量正文-第一章 启程")
        XCTAssertEqual(batch[toc[1].url], "批量正文-第二章 远航")

        let sum = try LegadoReview.summary(src, chapter: toc[0])
        XCTAssertEqual(sum.counts[1], 2)
        let det = try LegadoReview.detail(src, chapter: toc[0], paraIndex: 1, paraData: "p1")
        XCTAssertEqual(det.items.first?.content, "写得太好了")
        XCTAssertEqual(det.items.first?.likeCount, 18)
        let rep = try LegadoReview.replies(src, chapter: toc[0], paraIndex: 1, paraData: "p1", reviewId: "r1")
        XCTAssertEqual(rep.items.first?.content, "深有同感")

        let dbg = LegadoTools.debug(src, query: "星辰")
        XCTAssertTrue(dbg.succeeded)
        XCTAssertTrue(dbg.events.contains { $0.stage == "done" })

        let chk = LegadoTools.checkOne(src, keyword: "星辰", checkContent: true)
        XCTAssertEqual(chk.status, .ok)
        XCTAssertEqual(LegadoTools.lastCheck(src.id)?.status, .ok)
    }

    func testReplaceRulesImageDecodeAndSegments() throws {
        let count = try LegadoReplaceStore.shared.importJSON(#"""
        [{"id":101,"name":"去广告","pattern":"\\[广告[^\\]]*\\]","replacement":"","scope":"测试书","scopeContent":true,"scopeTitle":true,"isEnabled":true,"isRegex":true}]
        """#)
        XCTAssertEqual(count, 1)
        XCTAssertEqual(LegadoReplaceStore.shared.applyContent("正文[广告123]结束", bookName: "测试书"), "正文结束")
        XCTAssertEqual(LegadoReplaceStore.shared.applyContent("正文[广告123]结束", bookName: "其它书"), "正文[广告123]结束")
        XCTAssertEqual(LegadoReplaceStore.shared.applyTitle("第一章[广告]", bookName: "测试书"), "第一章")
        XCTAssertTrue(LegadoReplaceStore.shared.exportJSON().contains("去广告"))

        var imgSrc = LegadoSource(bookSourceUrl: "https://manga.test", bookSourceName: "Manga", searchUrl: "/s?k={{key}}")
        imgSrc.bookSourceType = 2
        imgSrc.coverDecodeJs = "@js:result.map(function(b){ return b ^ 0x55; })"
        let orig = Data([0x89, 0x50, 0x4E, 0x47])
        let enc = Data(orig.map { $0 ^ 0x55 })
        XCTAssertEqual(LegadoJsSource.decodeImageBytes(imgSrc, imageURL: "https://manga.test/1.png", data: enc, isCover: true), orig)

        let segs = splitChapterSegments("第一段\n<img src=\"https://manga.test/p1.jpg\"/>\n第二段")
        XCTAssertEqual(segs.count, 3)
        if case .image(_, let u) = segs[1] { XCTAssertEqual(u, "https://manga.test/p1.jpg") }
        else { XCTFail("Expected middle image segment") }
    }

    func testConcurrentRateJsonPathOrAndBookUrlPattern() throws {
        let t0 = Date()
        try LegadoRateLimit.acquire("https://rate.test", declaredRate: "1/120")
        try LegadoRateLimit.acquire("https://rate.test", declaredRate: "1/120")
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince(t0), 0.10)

        let json: Any = ["items": [["id": 1, "tag": "a"], ["id": 2, "tag": "b"], ["id": 3, "tag": "c"]]]
        let filtered = LegadoJSONPath.read(json, "$..items[?(@.id==1 || @.tag=='c')].id")
        XCTAssertEqual(filtered.compactMap { $0 as? Int }, [1, 3])
    }
}
