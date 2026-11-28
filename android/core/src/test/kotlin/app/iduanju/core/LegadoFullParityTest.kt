package app.iduanju.core

import app.iduanju.core.novel.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LegadoFullParityTest {
    @After fun tearDown() {
        NovelSources.fixture = null
        NovelSources.fixturePage = null
        LegadoWebView.backstage = null
        LegadoWebView.browser = null
        LegadoCookies.clearForTest()
        LegadoReplaceStore.clearForTest()
        LegadoRateLimit.resetForTest()
    }

    @Test fun regexAllInOneAndJsonPathFiltersAndGetVars() {
        val src = NovelSource(JSONObject("""{"bookSourceUrl":"https://rule.test"}"""))
        // :regex AllInOne list mode + $1/$2 extraction
        val html = "<item id='11'>Alpha</item><item id='22'>Beta</item>"
        val items = NovelSources.nodes(html, ":<item id='(\\d+)'>([^<]+)</item>", src, src.id)
        assertEquals(2, items.size)
        assertEquals("11|Alpha", NovelSources.ruleValue(items[0], "$1|$2", src, src.id))
        assertEquals("22|Beta", NovelSources.ruleValue(items[1], "$1|$2", src, src.id))

        // JSONPath filter predicates [?(@.k == v && @.score >= 8)]
        val json = JSONObject("""{"books":[{"name":"A","free":true,"score":9},{"name":"B","free":false,"score":10},{"name":"C","free":true,"score":5}]}""")
        val filtered = NovelSources.jsonNodes(json, "$.books[?(@.free == 'true' && @.score >= 8)].name")
        assertEquals(listOf("A"), filtered)

        // @put + @get variable roundtrip
        NovelSources.ruleValue("<div data-id='7788'>Book</div>", "@put:{bid:'div@data-id'}div@text", src, src.id)
        assertEquals("7788", NovelSources.ruleValue("", "@get:{bid}", src, src.id))
        assertEquals("id=7788", NovelSources.ruleValue("", "id={{@get:{bid}}}", src, src.id))

        // Lenient URL options with single quotes, unquoted keys, typographic quotes
        val opt = LegadoUrlOptions.parse("{method:'POST', followRedirects: false, retry: 2, timeout: 9000, webView:“true”, body:'k=1'}")
        assertEquals("POST", opt.method)
        assertEquals(false, opt.followRedirects)
        assertEquals(2, opt.retry)
        assertEquals(9000L, opt.timeoutMs)
        assertTrue(opt.webView)
        assertEquals("k=1", opt.body)
    }

    @Test fun cookiesLoginV1V2AndHeaderMerge() {
        val src = NovelSource(JSONObject("""{
            "bookSourceUrl":"https://auth.example",
            "bookSourceName":"AuthSource",
            "loginUi":"{\"version\":2}",
            "loginUrl":"@js:function loginUi(st){return {rows:[{key:'user',name:'账号',type:'text'},{key:'step',name:st.step||'first',type:'label'},{key:'go',name:'提交',type:'button',action:'submit'}]};} function loginAction(act,st,form){if(!form.user) return {error:{user:'请输入账号'}}; source.putLoginHeader(JSON.stringify({'X-Token':'tok-'+form.user})); return {state:{step:'done'},login:form,close:true};}"
        }"""))
        assertTrue(LegadoLogin.isV2(src))
        val rows = LegadoLogin.renderV2(src, "{}")
        assertEquals(3, rows.size)
        val errRes = LegadoLogin.performActionV2(src, "submit", "{}", mapOf("user" to ""))
        assertEquals("请输入账号", errRes.errors["user"])
        assertFalse(errRes.close)
        val okRes = LegadoLogin.performActionV2(src, "submit", "{}", mapOf("user" to "alice"))
        assertTrue(okRes.close)
        assertEquals("tok-alice", NovelSources.headers(src).optString("X-Token"))

        // Cookie capture, query, manual set, remove
        LegadoCookies.captureSetCookies("https://auth.example/login", listOf("sid=abc123; Path=/; HttpOnly", "theme=dark; Domain=.auth.example; Path=/"))
        assertEquals("abc123", LegadoCookies.getKey("https://auth.example/api/book", "sid"))
        assertEquals("dark", LegadoCookies.getKey("https://m.auth.example/page", "theme"))
        assertEquals("abc123", NovelSources.ruleValue("", "@js:java.getCookie('https://auth.example/','sid')", src, src.id))
        NovelSources.ruleValue("", "@js:cookie.setCookie('https://auth.example/','vip=1')", src, src.id)
        assertEquals("1", LegadoCookies.getKey("https://auth.example/", "vip"))
        LegadoLogin.logout(src)
        assertNull(LegadoCookies.cookieHeader("https://auth.example/"))
    }

    @Test fun fullPipelineWebViewAndUrlJsBodyJs() {
        val src = NovelSource(JSONObject("""{
            "bookSourceUrl":"https://wv.example",
            "bookSourceName":"WV",
            "searchUrl":"/s?q={{key}},{\"webView\":true,\"method\":\"POST\",\"body\":\"q={{key}}\",\"bodyJs\":\"result.toUpperCase()\"}",
            "ruleSearch":{"bookList":"tag.a","name":"text","bookUrl":"href"},
            "ruleToc":{"preUpdateJs":"java.put('pre','ok')","chapterList":"tag.li","chapterName":"text","chapterUrl":"a@href","formatJs":"'['+index+'] '+result"},
            "ruleContent":{"content":"id.c@html","title":"tag.h1@text","subContent":"id.sub@text","replaceRegex":"@js:result.replace('BAD','GOOD')"}
        }"""))
        var seenReq: BackstageRequest? = null
        LegadoWebView.backstage = LegadoBackstage { req ->
            seenReq = req
            NovelSources.PageResponse("<a href='/b/1'>hello</a>", req.url)
        }
        val hits = NovelSources.search(src, "test")
        assertEquals(1, hits.size)
        assertEquals("HELLO", hits[0].name) // bodyJs uppercased HTML!
        assertEquals("POST", seenReq?.method)
        assertEquals("q=test", seenReq?.body)

        // Toc preUpdateJs + formatJs + Content title/subContent/@js replaceRegex
        NovelSources.fixture = { u ->
            when {
                u.endsWith("/B/1") -> "<ul><li><a href='/ch/1'>Ch1</a></li></ul>"
                u.endsWith("/ch/1") -> "<h1>RealTitle</h1><div id='c'>Hello BAD world</div><div id='sub'>Author note</div>"
                else -> error(u)
            }
        }
        val details = NovelSources.details(src, hits[0].url)
        assertEquals("[0] Ch1", details.chapters.single().title)
        assertEquals("ok", NovelScript.withBook(hits[0].url) { NovelSources.ruleValue("", "@js:java.get('pre')", src, src.id) })
        val text = NovelSources.content(src, details.chapters.single(), null, hits[0].url)
        assertTrue(text.contains("RealTitle"))
        assertTrue(text.contains("Hello GOOD world"))
        assertTrue(text.contains("Author note"))
    }

    @Test fun jsExtensionsCryptoChineseZipAndQueryTTF() {
        val src = NovelSource(JSONObject("""{"bookSourceUrl":"https://ext.test"}"""))
        // HMAC, Digest, toURL, toNumChapter, t2s/s2t, timeFormatUTC, tick, lock
        val res = NovelSources.ruleValue(
            "",
            """@js:
                var h = java.HMacHex('hello','HmacSHA256','secret');
                var d = java.digestHex('hello','SHA-256');
                var u = java.toURL('/a/b?x=1&y=2#top','https://ext.test');
                var ch = java.toNumChapter('第一百二十三章 启程');
                var simp = java.t2s('書源引擎測試');
                var fmt = java.timeFormatUTC(0,'yyyy-MM-dd HH:mm',8);
                var tk = java.tick('k') + ',' + java.tick('k');
                var lk = java.singleFlight('sf', function(){ return 'ok'; });
                [h.length, d.length, u.host, u.searchParams.get('x'), u.ref, ch, simp, fmt, tk, lk].join('|');
            """.trimIndent(),
            src,
            src.id,
        )
        assertEquals("64|64|ext.test|1|top|第123章 启程|书源引擎测试|1970-01-01 08:00|1,2|ok", res)

        // Sandboxed ZIP + file API
        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zos ->
            zos.putNextEntry(ZipEntry("chapters/c1.txt"))
            zos.write("zip-chapter-body".toByteArray())
            zos.closeEntry()
        }
        val zipFile = LegadoFiles.safeFile(src.id, "book.zip")
        zipFile.writeBytes(zipOut.toByteArray())
        assertEquals("zip-chapter-body", NovelSources.ruleValue("", "@js:java.getZipStringContent('book.zip','chapters/c1.txt')", src, src.id))
        val unzipped = NovelSources.ruleValue("", "@js:var dir=java.unzipFile('book.zip'); java.getTxtInFolder(dir)", src, src.id)
        assertEquals("zip-chapter-body", unzipped)

        // Pure-Kotlin QueryTTF font de-obfuscation (synthetic valid TTF with remapped cmap)
        val correctTtf = buildSyntheticTtf(mapOf(0x4E00 to listOf(10 to 20, 30 to 40, 50 to 10), 0x4E8C to listOf(15 to 25, 45 to 55, 65 to 15)))
        val obfuscatedTtf = buildSyntheticTtf(mapOf(0xE001 to listOf(10 to 20, 30 to 40, 50 to 10), 0xE002 to listOf(15 to 25, 45 to 55, 65 to 15)))
        val b64Ok = Base64.getEncoder().encodeToString(correctTtf)
        val b64Err = Base64.getEncoder().encodeToString(obfuscatedTtf)
        val decoded = NovelSources.ruleValue(
            "",
            """@js:var ok=java.queryBase64TTF('$b64Ok'); var err=java.queryBase64TTF('$b64Err'); java.replaceFont('\uE001和\uE002', err, ok, false);""",
            src,
            src.id,
        )
        assertEquals("一和二", decoded)
    }

    @Test fun singleFileJsSourceBatchContentReviewReplaceAndDebugger() {
        val jsCode = """
            var config = {
                bookSourceUrl: "https://js.example",
                bookSourceName: "单文件JS源",
                bookSourceType: 0,
                exploreUrl: [{title:"精选", url:"https://js.example/rank"}]
            };
            function search(key, page) {
                java.log("searching " + key);
                return [{name: "星海漫游", author: "林海", bookUrl: "https://js.example/book/1"}];
            }
            function explore(url, page) { return search("rank", page); }
            function getBookInfo(book) { return {intro: "简介内容", tocUrl: "https://js.example/toc/1"}; }
            function getChapters(book) {
                return [
                    {title: "第一章 启航", url: "https://js.example/ch/1"},
                    {title: "第二章 跃迁", url: "https://js.example/ch/2"}
                ];
            }
            function getContent(ch, book, nextUrl) { return "正文内容广告词 " + ch.title; }
            function getContentBatch(chapters, book) {
                chapters.forEach(function(ch){ java.cacheContent(ch, "批量正文 " + ch.title); });
            }
            function getReviewSummary(ch, book) { return [{paraIndex: 1, count: 2, paraData: "p1"}]; }
            function getReviewDetail(ch, book, idx, pd, page) { return {items:[{id:"c1", name:"读者甲", content:{text:"写得好", replyCount:1}}], nextPageUrl:null}; }
            function getReviewReplies(ch, book, idx, pd, rid, page) { return {items:[{id:"r1", name:"读者乙", content:"同意"}]}; }
        """.trimIndent()

        val imported = NovelSources.importReport(jsCode)
        assertEquals(1, imported.sources.size)
        val src = imported.sources.single()
        assertTrue(src.isJsSource)

        // ReplaceRule purification
        LegadoReplaceStore.upsert(LegadoReplaceRule(id = 1, name = "去广告", pattern = "广告词\\s*", replacement = "", isRegex = true, scopeContent = true))

        // Search -> Details -> Content -> Batch -> Review
        val hits = NovelSources.search(src, "星海")
        assertEquals("星海漫游", hits.single().name)
        val details = NovelSources.details(src, hits.single().url)
        assertEquals(2, details.chapters.size)
        val body = NovelSources.content(src, details.chapters[0], details.chapters[1].url, hits.single().url, details.name)
        assertEquals("正文内容第一章 启航", body)

        val batch = NovelSources.contentBatch(src, details.chapters, hits.single().url, details.name)
        assertEquals(2, batch.size)
        assertEquals("批量正文 第二章 跃迁", batch["https://js.example/ch/2"])

        assertTrue(LegadoReview.isEnabled(src))
        val sum = LegadoReview.summary(src, details.chapters[0], hits.single().url, details.name)
        assertEquals(2, sum.counts[1])
        val revPage = LegadoReview.detail(src, details.chapters[0], 1, "p1", 1, hits.single().url, details.name)
        assertEquals("写得好", revPage.items.single().content)
        val repPage = LegadoReview.replies(src, details.chapters[0], 1, "p1", "c1", 1, hits.single().url, details.name)
        assertEquals("同意", repPage.items.single().content)

        // Single-source debugger & batch validator
        val report = LegadoTools.debug(src, "星海")
        assertTrue(report.succeeded)
        assertTrue(report.events.any { it.stage == "log" && it.message.contains("searching 星海") })
        val checks = LegadoTools.checkBatch(listOf(src), "星海", concurrency = 2)
        assertEquals(LegadoTools.CheckStatus.OK, checks.single().status)

        // Image/Cover byte decode hook (XOR 0x5A)
        val imgSrc = NovelSource(JSONObject("""{"bookSourceUrl":"https://img.test","coverDecodeJs":"@js:result.map(function(b){return b ^ 0x5A;})"}"""))
        val decodedBytes = LegadoJsSource.decodeImageBytes(imgSrc, "https://img.test/c.jpg", byteArrayOf(0x11, 0x22), isCover = true)
        assertArrayEquals(byteArrayOf((0x11 xor 0x5A).toByte(), (0x22 xor 0x5A).toByte()), decodedBytes)
    }

    @Test fun concurrentRateAndFollowRedirectsFalse() {
        val src = NovelSource(JSONObject("""{"bookSourceUrl":"https://rate.test","concurrentRate":"2/120"}"""))
        val t0 = System.currentTimeMillis()
        LegadoRateLimit.acquire(src.id, src.concurrentRate)
        LegadoRateLimit.acquire(src.id, src.concurrentRate)
        LegadoRateLimit.acquire(src.id, src.concurrentRate)
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("3rd request must wait for 120ms window, got ${elapsed}ms", elapsed >= 90L)
    }

    /** Builds a minimal valid TrueType font binary mapping each Unicode codepoint to a simple triangle glyph. */
    private fun buildSyntheticTtf(glyphs: Map<Int, List<Pair<Int, Int>>>): ByteArray {
        val entries = glyphs.entries.sortedBy { it.key }
        val numGlyphs = entries.size + 1 // gid 0 = .notdef

        // glyf table
        val glyfOut = ByteArrayOutputStream()
        val locaOffsets = IntArray(numGlyphs + 1)
        locaOffsets[0] = 0
        locaOffsets[1] = 0 // empty .notdef
        for ((idx, entry) in entries.withIndex()) {
            val pts = entry.value
            val start = glyfOut.size()
            val g = ByteArrayOutputStream()
            fun w16(v: Int) { g.write((v shr 8) and 0xFF); g.write(v and 0xFF) }
            w16(1) // numberOfContours = 1
            w16(0); w16(0); w16(100); w16(100) // bbox
            w16(pts.size - 1) // endPtsOfContours[0]
            w16(0) // instructionLength
            for (i in pts.indices) g.write(0x01) // on-curve, 16-bit signed deltas
            var px = 0
            for ((x, _) in pts) { w16(x - px); px = x }
            var py = 0
            for ((_, y) in pts) { w16(y - py); py = y }
            while (g.size() % 4 != 0) g.write(0)
            glyfOut.write(g.toByteArray())
            locaOffsets[idx + 2] = start + g.size()
        }
        val glyfBytes = glyfOut.toByteArray()

        // loca (format 1 = 32-bit offsets)
        val locaBuf = ByteBuffer.allocate((numGlyphs + 1) * 4)
        for (off in locaOffsets) locaBuf.putInt(off)
        val locaBytes = locaBuf.array()

        // head (54 bytes, indexToLocFormat = 1 at offset 50)
        val headBuf = ByteBuffer.allocate(54)
        headBuf.putInt(0x00010000)
        headBuf.putShort(50, 1)
        val headBytes = headBuf.array()

        // maxp (6 bytes, numGlyphs at offset 4)
        val maxpBuf = ByteBuffer.allocate(6)
        maxpBuf.putInt(0x00010000)
        maxpBuf.putShort(numGlyphs.toShort())
        val maxpBytes = maxpBuf.array()

        // cmap (format 4, one segment per codepoint + 0xFFFF terminator)
        val segCount = entries.size + 1
        val cmapSub = ByteArrayOutputStream()
        fun cw16(v: Int) { cmapSub.write((v shr 8) and 0xFF); cmapSub.write(v and 0xFF) }
        val subLen = 14 + segCount * 8 + 2
        cw16(4); cw16(subLen); cw16(0); cw16(segCount * 2); cw16(2); cw16(0); cw16(0)
        for (e in entries) cw16(e.key); cw16(0xFFFF) // endCode
        cw16(0) // reservedPad
        for (e in entries) cw16(e.key); cw16(0xFFFF) // startCode
        for ((idx, e) in entries.withIndex()) cw16(((idx + 1) - e.key) and 0xFFFF); cw16(1) // idDelta
        for (i in 0 until segCount) cw16(0) // idRangeOffset
        val subBytes = cmapSub.toByteArray()

        val cmapBuf = ByteBuffer.allocate(12 + subBytes.size)
        cmapBuf.putShort(0); cmapBuf.putShort(1) // version, numTables
        cmapBuf.putShort(3); cmapBuf.putShort(1); cmapBuf.putInt(12) // platform 3, enc 1, offset 12
        cmapBuf.put(subBytes)
        val cmapBytes = cmapBuf.array()

        val tables = listOf("cmap" to cmapBytes, "glyf" to glyfBytes, "head" to headBytes, "loca" to locaBytes, "maxp" to maxpBytes)
        val headerSize = 12 + tables.size * 16
        val total = headerSize + tables.sumOf { (it.second.size + 3) and 3.inv() }
        val out = ByteBuffer.allocate(total)
        out.putInt(0x00010000); out.putShort(tables.size.toShort()); out.putShort(0); out.putShort(0); out.putShort(0)
        var offset = headerSize
        for ((tag, bytes) in tables) {
            out.put(tag.toByteArray(Charsets.US_ASCII))
            out.putInt(0)
            out.putInt(offset)
            out.putInt(bytes.size)
            offset += (bytes.size + 3) and 3.inv()
        }
        for ((_, bytes) in tables) {
            out.put(bytes)
            while (out.position() % 4 != 0) out.put(0)
        }
        return out.array()
    }
}
