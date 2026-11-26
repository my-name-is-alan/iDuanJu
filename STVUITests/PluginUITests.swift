import XCTest

/// 1.5 插件管理 — real taps, network-free. Uses only the neutral demo plugin and the fixture 采集站;
/// the adult switch stays off, so adult plugins never appear on screen (no adult screenshots).
final class PluginUITests: XCTestCase {
    var app: XCUIApplication!

    func el(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }
    func launch(_ env: [String: String]) {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["STV_HERO_STILL"] = "1"
        app.launchEnvironment["STV_PLUGINS_RESET"] = "1"
        for (k, v) in env { app.launchEnvironment[k] = v }
        app.launch()
    }
    func tap(_ e: XCUIElement, _ msg: String = "", file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(e.waitForExistence(timeout: 15), "missing \(e) \(msg)", file: file, line: line)
        e.tap()
    }
    func shot(_ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a)
        if let dir = ProcessInfo.processInfo.environment["STV_SHOT_DIR"] {
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name + ".png"))
        }
    }
    func text(_ s: String) -> XCUIElement { app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", s)).firstMatch }

    func testOptInReviewedM3UNativePlayback() throws {
        guard let path = ProcessInfo.processInfo.environment["STV_COMPAT_BUNDLE"] else { throw XCTSkip("set STV_COMPAT_BUNDLE") }
        launch(["STV_PLUGIN_FILE":path])
        let tab = el("home.channel.公开测试动画 M3U")
        for _ in 0..<8 {
            if tab.exists && tab.isHittable { break }
            el("home.channels").swipeLeft()
        }
        tap(tab)
        tap(text("Big Buck Bunny"))
        XCTAssertTrue(el("detail.play").waitForExistence(timeout: 20))
        shot("ios-m3u-animation-detail")
        tap(el("detail.play"))
        // A successful resolver alone is NOT playback. Wait for the native transport clock.
        let clock = el("player.time")
        XCTAssertTrue(clock.waitForExistence(timeout: 35))
        var advanced = false
        for _ in 0..<30 {
            if clock.exists && !["00:00", "0:00", ""].contains(clock.label) { advanced = true; break }
            Thread.sleep(forTimeInterval: 1)
        }
        shot("ios-m3u-animation-playing")
        XCTAssertTrue(advanced, "native playback clock did not advance")
    }

    func testPluginManagerFromProfile() {
        launch(["STV_ROUTE": "profile", "STV_PLUGIN_DEMO": "1", "STV_CMS_FIXTURE": "1"])
        let entry = app.staticTexts["插件"].firstMatch
        XCTAssertTrue(entry.waitForExistence(timeout: 15))
        shot("01-profile-plugins-entry")
        entry.tap()
        XCTAssertTrue(el("plugins.root").waitForExistence(timeout: 10))
        XCTAssertTrue(el("plugins.row.com.example.demo.comics").waitForExistence(timeout: 5), "demo plugin listed")
        XCTAssertTrue(text("示例采集站").exists, "fixture 采集站 listed")
        XCTAssertFalse(text("18comic").exists, "adult plugins stay hidden while the adult switch is off")
        XCTAssertFalse(text("Manga18fx").exists)
        shot("02-plugins-list")
        app.swipeUp()
        shot("03-plugins-list-bottom")
        app.swipeDown()

        tap(app.staticTexts["示例漫画（演示）"].firstMatch)
        XCTAssertTrue(text("demo-comics.example.com").waitForExistence(timeout: 5), "allowed domains shown")
        let toggle = el("plugin.enabled")
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        XCTAssertEqual(toggle.value as? String, "1")
        shot("04-plugin-detail")
        toggle.switches.firstMatch.exists ? toggle.switches.firstMatch.tap() : toggle.tap()
        XCTAssertTrue(NSPredicate(format: "value == '0'").evaluate(with: toggle) || waitValue(toggle, "0"))
        toggle.switches.firstMatch.exists ? toggle.switches.firstMatch.tap() : toggle.tap()
        XCTAssertTrue(waitValue(toggle, "1"))
        app.swipeUp()
        tap(el("plugin.logs"))
        XCTAssertTrue(text("跳转被拦截").waitForExistence(timeout: 5))
        shot("05-plugin-logs")
    }

    func waitValue(_ e: XCUIElement, _ v: String) -> Bool {
        XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", v), object: e)], timeout: 5) == .completed
    }

    func testUpdateConsentShowsPermissionDiff() {
        launch(["STV_ROUTE": "plugins", "STV_PLUGIN_CONSENT_DEMO": "1"])
        XCTAssertTrue(el("plugins.consent").waitForExistence(timeout: 15))
        XCTAssertTrue(text("新增网络域名 cdn.demo-comics.example.net").waitForExistence(timeout: 5))
        XCTAssertTrue(text("新增：允许过 Cloudflare 验证").exists)
        shot("06-update-consent-diff")
        app.swipeUp()
        shot("07-update-consent-capabilities")
        tap(el("plugins.consent.install"))
        XCTAssertTrue(text("已安装 示例漫画（演示） 1.1.0").waitForExistence(timeout: 10))
    }

    /// Real network: set STV_SUB_URL to an idj1 subscription list to run; skipped otherwise.
    /// Adds the subscription, taps 全部安装, approves one batch consent sheet and checks every entry landed.
    func testSubscriptionInstallAll() throws {
        guard let sub = ProcessInfo.processInfo.environment["STV_SUB_URL"], sub.hasPrefix("https://") else {
            throw XCTSkip("set STV_SUB_URL to a subscription list")
        }
        launch(["STV_ROUTE": "plugins", "STV_PLUGINS_RESET": "1", "STV_SOURCES": "jable"])
        tap(el("plugins.sub.add"))
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText(sub)
        tap(app.buttons["添加"])
        let bulk = el("plugins.sub.installAll")
        XCTAssertTrue(bulk.waitForExistence(timeout: 60), "订阅加载后出现全部安装")
        for _ in 0..<6 where !bulk.isHittable { app.swipeUp() }
        XCTAssertTrue(bulk.isHittable)
        bulk.tap()
        shot("11-batch-prepare")
        if !el("plugins.batch.consent.install").waitForExistence(timeout: 180) { shot("11-batch-prepare-stuck") }
        let install = el("plugins.batch.consent.install")
        XCTAssertTrue(install.exists, "批量同意页出现")
        shot("12-batch-consent")
        install.tap()
        XCTAssertTrue(text("已安装 16 个插件").waitForExistence(timeout: 30))
        shot("13-batch-installed")
        XCTAssertTrue(text("已安装（16）").waitForExistence(timeout: 10), "16 个插件一次装好")
    }

    func testPasteRejectsInvalidPlugin() {
        launch(["STV_ROUTE": "plugins"])
        tap(el("plugins.add.paste"))
        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        editor.tap()
        editor.typeText("{\"files\":{}}")
        tap(app.buttons["下一步"])
        XCTAssertTrue(el("plugins.message").waitForExistence(timeout: 10))
        XCTAssertTrue((el("plugins.message").label).contains("idjplugin") || (el("plugins.message").label).contains("manifest"), el("plugins.message").label)
        shot("08-paste-rejected")
    }

    func testCMSSourceOnHome() {
        launch(["STV_CMS_FIXTURE": "1"])
        let tab = el("home.channel.示例采集站")
        if !tab.waitForExistence(timeout: 15) {
            // Source strip may need scrolling.
            el("home.channels").swipeLeft()
        }
        tap(tab, "fixture 采集站 appears as a 发现 source")
        XCTAssertTrue(text("山海之间").waitForExistence(timeout: 15), "fixture items from ?ac=detail")
        shot("09-cms-home")
        tap(text("山海之间"))
        XCTAssertTrue(text("第2集").waitForExistence(timeout: 15) || text("共2集").waitForExistence(timeout: 5))
        shot("10-cms-detail")
    }
}
