import XCTest
@testable import STV

@MainActor final class NovelCacheTests: XCTestCase {
    func testNeighborPrefetchPlanIsBoundedAndKeepsPreviousChapter() {
        XCTAssertEqual(NovelWebLoader.prefetchIndices(around: 5, total: 12, count: 3), [6, 4, 7, 8, 3, 2])
        XCTAssertEqual(NovelWebLoader.prefetchIndices(around: 0, total: 4, count: 20), [1, 2, 3])
        XCTAssertEqual(NovelWebLoader.prefetchIndices(around: 3, total: 4, count: 20), [2, 1, 0])
        XCTAssertTrue(NovelWebLoader.prefetchIndices(around: 0, total: 4, count: 0).isEmpty)
    }

    func testCancelledLoadDoesNotWriteCacheOrPoisonRetry() async throws {
        let source = LegadoSource(bookSourceUrl: "https://cancel.test/" + UUID().uuidString, bookSourceName: "cancel", searchUrl: "/s", ruleContent: ["content": "id.content@text"])
        LegadoSourceStore.shared.add([source])
        let item = LegadoSearchItem(name: "取消测试", author: "", kind: "", intro: "", cover: "", latest: "", bookURL: source.id + "/book", sourceURL: source.id, sourceName: "cancel")
        let book = NovelStore.shared.addWeb(item, info: nil, toc: [LegadoChapter(title: "第一章", url: source.id + "/1")])
        let gate = DispatchSemaphore(value: 0)
        defer { gate.signal(); LegadoHTTP.transport = nil; NovelStore.shared.remove(book); LegadoSourceStore.shared.remove([source.id]) }
        LegadoHTTP.transport = { request in
            _ = gate.wait(timeout: .now() + 3)
            return (Data("<div id='content'>迟到正文</div>".utf8), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let loader = NovelWebLoader.shared
        let request = Task { try await loader.load(book, 0) }
        for _ in 0..<100 where !loader.isLoading(book, 0) { try await Task.sleep(for: .milliseconds(5)) }
        request.cancel()
        try await Task.sleep(for: .milliseconds(30))
        gate.signal()
        do { try await request.value; XCTFail("cancelled load succeeded") } catch is CancellationError {} catch { XCTFail("unexpected: \(error)") }
        for _ in 0..<100 where loader.isLoading(book, 0) { try await Task.sleep(for: .milliseconds(5)) }
        XCTAssertFalse(NovelStore.shared.isCached(book, 0))
        XCTAssertNil(loader.error(book, 0))
        LegadoHTTP.transport = { request in (Data("<div id='content'>重试正文</div>".utf8), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!) }
        try await loader.load(book, 0)
        XCTAssertTrue(NovelStore.shared.chapterText(book, 0).contains("重试正文"))
    }

    func testPrefetchCancellationPreservesForegroundWaiter() async throws {
        let source = LegadoSource(bookSourceUrl: "https://prefetch.test/" + UUID().uuidString, bookSourceName: "prefetch", searchUrl: "/s", ruleContent: ["content": "id.content@text"])
        LegadoSourceStore.shared.add([source])
        let item = LegadoSearchItem(name: "预取所有权", author: "", kind: "", intro: "", cover: "", latest: "", bookURL: source.id + "/book", sourceURL: source.id, sourceName: "prefetch")
        let book = NovelStore.shared.addWeb(item, info: nil, toc: (0..<3).map { LegadoChapter(title: "章\($0)", url: source.id + "/\($0)") })
        try NovelStore.shared.cache(book, 0, "当前正文")
        let gate = DispatchSemaphore(value: 0)
        let loader = NovelWebLoader.shared
        defer { gate.signal(); loader.cancelPrefetch(book); LegadoHTTP.transport = nil; NovelStore.shared.remove(book); LegadoSourceStore.shared.remove([source.id]) }
        LegadoHTTP.transport = { request in
            _ = gate.wait(timeout: .now() + 3)
            return (Data("<div id='content'>共享正文</div>".utf8), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        loader.prefetch(book, around: 0)
        for _ in 0..<100 where !loader.isLoading(book, 1) { try await Task.sleep(for: .milliseconds(5)) }
        XCTAssertTrue(loader.isLoading(book, 1))
        let foreground = Task { try await loader.load(book, 1) }
        try await Task.sleep(for: .milliseconds(30))
        loader.cancelPrefetch(book)
        gate.signal()
        try await foreground.value
        XCTAssertTrue(NovelStore.shared.chapterText(book, 1).contains("共享正文"))
        XCTAssertNil(loader.error(book, 1))
    }

    func testDownloadAndResumeReadsCachedChaptersWithoutNetwork() async throws {
        let store = NovelStore.shared
        let source = LegadoSource(bookSourceUrl: "https://download.test/" + UUID().uuidString, bookSourceName: "下载测试", searchUrl: "/s", ruleContent: ["content": "id.content@text"])
        LegadoSourceStore.shared.add([source])
        let item = LegadoSearchItem(name: "下载验收", author: "", kind: "", intro: "", cover: "", latest: "", bookURL: source.id + "/book", sourceURL: source.id, sourceName: source.bookSourceName)
        let toc = (0..<3).map { LegadoChapter(title: "第\($0 + 1)章", url: source.id + "/\($0)") }
        let book = store.addWeb(item, info: nil, toc: toc)
        defer { LegadoHTTP.transport = nil; store.remove(book); LegadoSourceStore.shared.remove([source.id]) }
        LegadoHTTP.transport = { request in
            (Data("<div id='content'>离线测试正文</div>".utf8), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let loader = NovelWebLoader.shared
        loader.download(book, from: 0, count: nil)
        for _ in 0..<100 where loader.downloadStates[book.id]?.active == true { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertFalse(loader.downloadStates[book.id]?.active ?? true)
        XCTAssertEqual(store.cachedCount(book), 3)
        LegadoHTTP.transport = { _ in throw URLError(.notConnectedToInternet) }
        try await loader.load(book, 1)
        XCTAssertTrue(store.chapterText(book, 1).contains("离线测试正文"))
        loader.download(book, from: 0, count: nil)
        for _ in 0..<100 where loader.downloadStates[book.id]?.active == true { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertEqual(loader.downloadStates[book.id]?.failures, 0)
        XCTAssertEqual(loader.downloadStates[book.id]?.completed, 3)
    }

    func testDiskCacheSurvivesTOCReorderAndKeepsSourcesSeparate() throws {
        let store = NovelStore.shared
        let item = LegadoSearchItem(name: "缓存测试", author: "作者", kind: "", intro: "", cover: "", latest: "", bookURL: "https://test.invalid/" + UUID().uuidString, sourceURL: "https://source.invalid", sourceName: "test")
        let toc = [LegadoChapter(title: "第一章", url: "https://test.invalid/one"), LegadoChapter(title: "第二章", url: "https://test.invalid/two")]
        let book = store.addWeb(item, info: nil, toc: toc)
        defer { store.remove(book) }
        try store.cache(book, 1, "第二章\n离线正文")
        XCTAssertEqual(store.chapterText(book, 1), "第二章\n离线正文")
        XCTAssertEqual(store.cachedCount(book), 1)
        store.updateToc(book, Array(toc.reversed()))
        let updated = try XCTUnwrap(store.book(book.id))
        XCTAssertEqual(store.chapterText(updated, 0), "第二章\n离线正文")
        XCTAssertFalse(store.isCached(updated, 1))
        var other = item; other.sourceURL = "https://another.invalid"
        let alternate = store.addWeb(other, info: nil, toc: Array(toc.reversed()))
        defer { store.remove(alternate) }
        XCTAssertFalse(store.isCached(alternate, 1))
        store.record(book, chapter: 1, offset: 40)
        store.transferProgress(from: book, to: alternate)
        XCTAssertEqual(store.progress[alternate.id]?.chapter, 0, "source switch follows the chapter title, not the old row number")
        XCTAssertEqual(store.progress[alternate.id]?.offset, 0)
        XCTAssertThrowsError(try store.cache(book, 0, ""))
    }

    func testLegacyCacheIsOnlyMigratedOnce() throws {
        let store = NovelStore.shared
        let item = LegadoSearchItem(name: "旧缓存", author: "", kind: "", intro: "", cover: "", latest: "", bookURL: "https://legacy.test/" + UUID().uuidString, sourceURL: "https://source.test", sourceName: "test")
        let toc = [LegadoChapter(title: "一", url: "https://legacy.test/1"), LegadoChapter(title: "二", url: "https://legacy.test/2")]
        let book = store.addWeb(item, info: nil, toc: toc)
        defer { store.remove(book) }
        let old = NovelStore.dir(book.id).appendingPathComponent("ch/0.txt")
        try FileManager.default.createDirectory(at: old.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("第一章旧缓存".utf8).write(to: old)
        XCTAssertEqual(store.chapterText(book, 0), "第一章旧缓存")
        store.updateToc(book, Array(toc.reversed()))
        let updated = try XCTUnwrap(store.book(book.id))
        XCTAssertFalse(store.isCached(updated, 0), "the old index file must not migrate again onto another chapter")
        XCTAssertEqual(store.chapterText(updated, 1), "第一章旧缓存")
    }

    func testDownloadStopsOnSourceFailureAndCanResume() async throws {
        let source = LegadoSource(bookSourceUrl: "https://retry.test/" + UUID().uuidString, bookSourceName: "test", searchUrl: "/s", ruleContent: ["content": "id.content@text"])
        LegadoSourceStore.shared.add([source])
        let item = LegadoSearchItem(name: "恢复测试", author: "", kind: "", intro: "", cover: "", latest: "", bookURL: source.id + "/book", sourceURL: source.id, sourceName: "test")
        let book = NovelStore.shared.addWeb(item, info: nil, toc: (0..<3).map { LegadoChapter(title: "第\($0)章", url: source.id + "/\($0)") })
        defer { LegadoHTTP.transport = nil; NovelStore.shared.remove(book); LegadoSourceStore.shared.remove([source.id]) }
        LegadoHTTP.transport = { request in
            if request.url!.path.hasSuffix("/1") { throw URLError(.notConnectedToInternet) }
            return (Data("<div id='content'>正文</div>".utf8), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let loader = NovelWebLoader.shared
        loader.download(book, from: 0, count: nil)
        for _ in 0..<100 where loader.downloadStates[book.id]?.active == true { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertEqual(NovelStore.shared.cachedCount(book), 1)
        XCTAssertEqual(loader.downloadStates[book.id]?.failures, 1)
        LegadoHTTP.transport = { request in
            XCTAssertFalse(request.url!.path.hasSuffix("/0"), "completed chapters must be reused")
            return (Data("<div id='content'>恢复后的正文</div>".utf8), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        loader.download(book, from: 0, count: nil)
        for _ in 0..<100 where loader.downloadStates[book.id]?.active == true { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertEqual(NovelStore.shared.cachedCount(book), 3)
        XCTAssertEqual(loader.downloadStates[book.id]?.failures, 0)
    }
    func testCachedTOCRestoresChapterIndexAndNextChapterScriptBindings()async throws{
        let source=LegadoSource(bookSourceUrl:"https://index.test/"+UUID().uuidString,bookSourceName:"index",searchUrl:"/s",ruleContent:["content":"@js:chapter.getIndex()+':'+chapter.getTitle()+':'+nextChapterUrl"])
        LegadoSourceStore.shared.add([source])
        let item=LegadoSearchItem(name:"index fixture",author:"",kind:"",intro:"",cover:"",latest:"",bookURL:source.id+"/book",sourceURL:source.id,sourceName:"index")
        let toc=(0..<3).map{LegadoChapter(title:"chapter\($0)",url:source.id+"/\($0)")}
        let store=NovelStore.shared;let book=store.addWeb(item,info:nil,toc:toc)
        defer{LegadoHTTP.transport=nil;store.remove(book);LegadoSourceStore.shared.remove([source.id])}
        LegadoHTTP.transport={req in (Data("<body>fixture</body>".utf8),HTTPURLResponse(url:req.url!,statusCode:200,httpVersion:nil,headerFields:nil)!)}
        try await NovelWebLoader.shared.load(book,1)
        XCTAssertEqual(store.chapterText(book,1),"chapter1\n1:chapter1:"+toc[2].url)
    }

    func testLargeWebBookChapterAccessAndScrollLookupAvoidsBulkMigrationAndNetworkStorm() throws {
        let store = NovelStore.shared
        let item = LegadoSearchItem(name: "长篇目录测试", author: "测试", kind: "", intro: "", cover: "", latest: "", bookURL: "https://large.test/" + UUID().uuidString, sourceURL: "https://source.large.test", sourceName: "large")
        let toc = (0..<3000).map { LegadoChapter(title: "第\($0 + 1)章 测试", url: "https://large.test/ch/\($0)") }
        let book = store.addWeb(item, info: nil, toc: toc)
        defer { store.remove(book) }

        let start = Date()
        XCTAssertFalse(store.isCached(book, 2499))
        XCTAssertEqual(store.chapterText(book, 2499), "")
        let elapsed = Date().timeIntervalSince(start)
        XCTAssertLessThan(elapsed, 0.25, "first lookup on a 3000-chapter web book must not scan all chapters")

        let paginator = NovelPaginator(book: book)
        paginator.configure(NovelPageStyle(
            size: CGSize(width: 340, height: 640), fontSize: 18, lineSpacing: 0.5, indent: true, fontID: "system",
            text: .black, secondary: .gray, background: .white, highlight: .green
        ))
        // Scroll mode lookup with triggerLoad=false must never fire network requests or paginate.
        let scrollAttr = paginator.scrollChapter(2499, triggerLoad: false)
        XCTAssertTrue(scrollAttr.string.contains("第2500章 测试"))
        XCTAssertFalse(NovelWebLoader.shared.isLoading(book, 2499))
        XCTAssertFalse(paginator.hasCachedImages(2499))
    }

    func testNovelPagerControllerTocJumpStaysResponsiveAcrossAllTurnStyles() throws {
        let store = NovelStore.shared
        var fullText = ""
        var chapters: [NovelChapter] = []
        for i in 0..<200 {
            let start = (fullText as NSString).length
            let body = "第\(i + 1)章 跳转验证\n" + String(repeating: "这是第\(i + 1)章的正文段落内容，用于验证目录跳转后页面正常渲染且翻页不卡死。\n", count: 18)
            fullText += body
            let end = (fullText as NSString).length
            chapters.append(NovelChapter(title: "第\(i + 1)章 跳转验证", start: start, end: end))
        }
        let parsed = NovelParsed(title: "跳转回归书", author: "QA", text: fullText, chapters: chapters, cover: nil)
        let book = try store.add(parsed, format: "txt")
        defer { store.remove(book) }

        let paginator = NovelPaginator(book: book)
        paginator.configure(NovelPageStyle(
            size: CGSize(width: 350, height: 660), fontSize: 18, lineSpacing: 0.5, indent: true, fontID: "system",
            text: .black, secondary: .gray, background: .white, highlight: .green
        ))
        let vc = NovelPagerController(paginator: paginator, start: NovelPageRef(chapter: 0, index: 0))
        vc.insets = UIEdgeInsets(top: 50, left: 20, bottom: 34, right: 20)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = vc
        window.makeKeyAndVisible()
        vc.view.layoutIfNeeded()

        for style in [NovelTurnStyle.slide, .curl, .cover, .none] {
            vc.setStyle(style)
            vc.show(NovelPageRef(chapter: 150, index: 0))
            vc.view.layoutIfNeeded()
            XCTAssertEqual(vc.current, NovelPageRef(chapter: 150, index: 0), "style \(style) failed to jump to ch 150")
            XCTAssertFalse(vc.view.subviews.isEmpty, "style \(style) rendered empty subviews after jump")
            XCTAssertEqual(vc.view.subviews.first?.frame, vc.view.bounds, "style \(style) frame mismatch after jump")
            vc.turn(+1)
        }
    }

    func testPaginateGuardsEdgeSizesWithoutHanging() {
        let attr = NovelLayout.attributed("第一章\n正文内容测试", title: "第一章", size: 18, lineSpacing: 0.5, serif: false, color: .black)
        XCTAssertEqual(NovelLayout.paginate(attr, size: CGSize(width: 10, height: 10)).count, 1)
        XCTAssertEqual(NovelLayout.paginate(NSAttributedString(), size: CGSize(width: 320, height: 600)).count, 1)
        XCTAssertGreaterThanOrEqual(NovelLayout.paginate(attr, size: CGSize(width: 320, height: 600)).count, 1)
    }

}
