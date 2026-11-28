(function (h) {
    const call = (n, a) => { const r = h(n, JSON.stringify(a === undefined ? null : a)); return r === undefined ? undefined : JSON.parse(r); };
    const str = (v) => v == null ? '' : String(v);
    const parseObj = (t) => { try { return JSON.parse(t) || new Object(); } catch (e) { return new Object(); } };
    const bytesOf = (v) => Array.isArray(v) ? v : call('bytes.enc', { s: str(v), cs: 'UTF-8' });

    const wrapResponse = (r, reqUrl) => {
        const hdrs = (r && r.headers) || {};
        const lowerMap = {};
        for (const k of Object.keys(hdrs)) lowerMap[k.toLowerCase()] = hdrs[k];
        const status = (r && (r.status || r.code)) || 200;
        const bodyText = r && r.body != null ? str(r.body) : '';
        const finalUrl = (r && r.url) ? str(r.url) : str(reqUrl);
        const cookieMap = () => {
            const out = {};
            const sc = lowerMap['set-cookie'] || '';
            for (const part of str(sc).split(/\n|,(?=[^ ;]+=)/)) {
                const kv = part.split(';')[0].trim();
                const eq = kv.indexOf('=');
                if (eq > 0) out[kv.slice(0, eq).trim()] = kv.slice(eq + 1).trim();
            }
            return __jmap(out);
        };
        const o = {
            __url: finalUrl,
            __body: bodyText,
            __code: status,
            body: () => bodyText,
            bodyAsBytes: () => (r && Array.isArray(r.bytes)) ? r.bytes : bytesOf(bodyText),
            code: () => status,
            statusCode: () => status,
            message: () => '',
            url: () => finalUrl,
            header: (k) => lowerMap[str(k).toLowerCase()] || null,
            headers: () => __jmap(Object.assign({}, hdrs)),
            cookies: cookieMap,
            cookie: (k) => cookieMap().get(str(k)),
            isSuccessful: () => status >= 200 && status < 400,
            toString: () => bodyText,
            raw: () => ({ request: () => ({ url: () => finalUrl }), code: () => status, header: (k) => lowerMap[str(k).toLowerCase()] || null, headers: () => hdrs }),
        };
        return o;
    };

    const pad2 = (n) => String(n).padStart(2, '0');
    const formatDate = (ms, fmt, tzOffsetHours) => {
        const d = new Date(Number(ms) + (tzOffsetHours != null ? Number(tzOffsetHours) * 3600000 : 0));
        const useUtc = tzOffsetHours != null;
        const Y = useUtc ? d.getUTCFullYear() : d.getFullYear();
        const M = (useUtc ? d.getUTCMonth() : d.getMonth()) + 1;
        const D = useUtc ? d.getUTCDate() : d.getDate();
        const H = useUtc ? d.getUTCHours() : d.getHours();
        const m = useUtc ? d.getUTCMinutes() : d.getMinutes();
        const s = useUtc ? d.getUTCSeconds() : d.getSeconds();
        const S = useUtc ? d.getUTCMilliseconds() : d.getMilliseconds();
        if (!fmt) return Y + '/' + pad2(M) + '/' + pad2(D) + ' ' + pad2(H) + ':' + pad2(m);
        return str(fmt)
            .replace(/yyyy/g, String(Y))
            .replace(/yy/g, String(Y).slice(-2))
            .replace(/MM/g, pad2(M))
            .replace(/M/g, String(M))
            .replace(/dd/g, pad2(D))
            .replace(/d/g, String(D))
            .replace(/HH/g, pad2(H))
            .replace(/H/g, String(H))
            .replace(/mm/g, pad2(m))
            .replace(/m/g, String(m))
            .replace(/ss/g, pad2(s))
            .replace(/s/g, String(s))
            .replace(/SSS/g, String(S).padStart(3, '0'));
    };

    const makeJsURL = (rawUrl, baseUrl) => {
        const resolved = baseUrl ? call('resolveUrl', { url: str(rawUrl), base: str(baseUrl) }) : str(rawUrl);
        const m = /^([a-zA-Z][a-zA-Z0-9+.-]*):\/\/([^\/?#]*)(.*)$/.exec(resolved || '');
        const protocol = m ? m[1] : '';
        const authority = m ? m[2] : '';
        const rest = m ? m[3] : str(resolved);
        const hashIdx = rest.indexOf('#');
        const noHash = hashIdx >= 0 ? rest.slice(0, hashIdx) : rest;
        const ref = hashIdx >= 0 ? rest.slice(hashIdx + 1) : '';
        const qIdx = noHash.indexOf('?');
        const path = qIdx >= 0 ? noHash.slice(0, qIdx) : (noHash || '/');
        const query = qIdx >= 0 ? noHash.slice(qIdx + 1) : '';
        const colonIdx = authority.lastIndexOf(':');
        const host = colonIdx > 0 && !authority.endsWith(']') ? authority.slice(0, colonIdx) : authority;
        const port = colonIdx > 0 && !authority.endsWith(']') ? Number(authority.slice(colonIdx + 1)) : -1;
        const params = {};
        if (query) {
            for (const pair of query.split('&')) {
                if (!pair) continue;
                const eq = pair.indexOf('=');
                const k = decodeURIComponent((eq >= 0 ? pair.slice(0, eq) : pair).replace(/\+/g, ' '));
                const v = eq >= 0 ? decodeURIComponent(pair.slice(eq + 1).replace(/\+/g, ' ')) : '';
                if (!params[k]) params[k] = [];
                params[k].push(v);
            }
        }
        return {
            protocol, host, hostname: host, port, path, file: query ? path + '?' + query : path,
            query, ref, hash: ref ? '#' + ref : '', href: resolved,
            searchParams: {
                get: (k) => (params[str(k)] && params[str(k)][0]) || null,
                getAll: (k) => params[str(k)] ? params[str(k)].slice() : [],
                has: (k) => Object.prototype.hasOwnProperty.call(params, str(k)),
            },
            toString: () => resolved,
        };
    };

    const wrapTTF = (id) => {
        if (id == null || id < 0) return null;
        return {
            __ttf: id,
            getGlyfIdByUnicode: (u) => call('ttf.glyfIdByUni', { id, u: Number(u) }),
            getUnicodeByGlyfId: (g) => call('ttf.uniByGlyfId', { id, g: Number(g) }),
            getGlyfById: (g) => call('ttf.glyfById', { id, g: Number(g) }),
            getGlyfByUnicode: (u) => call('ttf.glyfByUni', { id, u: Number(u) }),
            getUnicodeByGlyf: (g) => call('ttf.uniByGlyf', { id, g: str(g) }),
            getCodeByGlyf: (g) => call('ttf.codeByGlyf', { id, g: str(g) }),
        };
    };

    const sym = (t, key, iv) => {
        const k = bytesOf(key), v = iv == null ? null : bytesOf(iv), tt = str(t || 'AES/ECB/PKCS5Padding');
        const run = (mode, data) => call('cipher', { t: tt, mode, key: k, iv: v, b: data });
        const input = (d) => Array.isArray(d) ? d : (/^[0-9a-fA-F]+$/.test(str(d)) && str(d).length % 2 === 0 ? call('hex.dec', { s: str(d) }) : call('b64.dec', { s: str(d) }));
        return {
            decrypt: (d) => run(2, input(d)),
            decryptStr: (d, cs) => call('bytes.dec', { b: run(2, input(d)), cs: cs || 'UTF-8' }),
            encrypt: (d) => run(1, bytesOf(d)),
            encryptBase64: (d) => call('b64.enc', { b: run(1, bytesOf(d)) }),
            encryptHex: (d) => call('hex.enc', { b: run(1, bytesOf(d)) }),
        };
    };

    globalThis.__jmap = (o) => {
        const m = o || new Object();
        const def = (k, f) => Object.defineProperty(m, k, { value: f, enumerable: false, writable: true, configurable: true });
        def('get', (k) => (m[k] === undefined ? null : m[k]));
        def('put', (k, v) => { m[k] = v; return v; });
        def('containsKey', (k) => Object.prototype.hasOwnProperty.call(m, k));
        def('keySet', () => Object.keys(m));
        def('isEmpty', () => Object.keys(m).length === 0);
        return m;
    };

    globalThis.__strResponse = (r) => wrapResponse(r, r && (typeof r.url === 'function' ? r.url() : r.url));

    globalThis.java = {
        ajax: (u, timeout) => call('ajax', { url: str(Array.isArray(u) ? u[0] : u), timeout: timeout == null ? null : Number(timeout) }),
        ajaxAll: (urls, skipRateLimit) => {
            const list = Array.isArray(urls) ? urls.map(str) : [];
            const res = call('ajaxAll', { urls: list, skipRateLimit: !!skipRateLimit }) || [];
            return res.map((r, i) => wrapResponse(r, list[i]));
        },
        ajaxTestAll: (urls, timeout, skipRateLimit) => {
            const list = Array.isArray(urls) ? urls.map(str) : [];
            const res = call('ajaxAll', { urls: list, timeout: timeout == null ? null : Number(timeout), skipRateLimit: !!skipRateLimit }) || [];
            return res.map((r, i) => wrapResponse(r, list[i]));
        },
        connect: (u, h2, timeout) => wrapResponse(call('connect', { url: str(u), headers: h2 || null, timeout: timeout == null ? null : Number(timeout) }), u),
        head: (u, h2, timeout) => wrapResponse(call('connect', { url: str(u), method: 'HEAD', headers: h2 || null, timeout: timeout == null ? null : Number(timeout) }), u),
        get: (k, h2, timeout) => {
            if (h2 === undefined && timeout === undefined && !/^https?:/.test(str(k))) return call('getVar', { k: str(k) });
            return wrapResponse(call('connect', { url: str(k), method: 'GET', headers: h2 || null, timeout: timeout == null ? null : Number(timeout) }), k);
        },
        post: (u, b, h2, timeout) => wrapResponse(call('connect', { url: str(u), method: 'POST', body: str(b), headers: h2 || null, timeout: timeout == null ? null : Number(timeout) }), u),
        put: (k, v) => { call('putVar', { k: str(k), v: str(v) }); return v; },
        getString: (rule, content) => call('getString', { rule: str(rule), content: content === undefined ? null : str(content) }),
        getStringList: (rule, content) => call('getStringList', { rule: str(rule), content: content === undefined ? null : str(content) }),
        setContent: (c, b) => call('setContent', { content: str(c), base: b === undefined ? null : str(b) }),
        base64Decode: (s, cs) => call('b64d', { s: str(s), cs: typeof cs === 'string' ? cs : null }),
        base64DecodeToByteArray: (s) => call('b64.dec', { s: str(s) }),
        base64Encode: (s) => call('b64e', { s: str(s) }),
        md5Encode: (s) => call('md5', { s: str(s) }),
        md5Encode16: (s) => call('md5', { s: str(s) }).substring(8, 24),
        encodeURI: (s, cs) => call('encodeURI', { s: str(s), cs: cs || null }),
        timeFormat: (t) => formatDate(t, null, null),
        timeFormatUTC: (t, fmt, sh) => formatDate(t, fmt, sh == null ? 0 : Number(sh)),
        htmlFormat: (s) => call('htmlFormat', { s: str(s) }),
        log: (s) => { call('log', { s: str(s) }); return s; },
        logType: (x) => { call('log', { s: (typeof x) + ': ' + str(x) }); return x; },
        toast: (m) => { call('log', { s: '[toast] ' + str(m) }); },
        longToast: (m) => { call('log', { s: '[toast] ' + str(m) }); },
        getCookie: (u, k) => call('cookie', { url: str(u), k: k == null ? null : str(k) }),
        getCookieStr: (u) => java.getCookie(u),
        t2s: (s) => call('convert', { s: str(s), to: 'Hant-Hans' }),
        s2t: (s) => call('convert', { s: str(s), to: 'Hans-Hant' }),
        toNumChapter: (s) => call('toNumChapter', { s: str(s) }),
        toURL: (u, base) => makeJsURL(u, base),
        randomUUID: () => { const u = call('uuid', null); return { toString: () => u, valueOf: () => u }; },
        getWebViewUA: () => call('ua', {}),
        androidId: () => call('androidId', {}),
        webView: (html, url, js, cacheFirst) => call('webView', { html: html == null ? null : str(html), url: url == null ? null : str(url), js: js == null ? null : str(js), cacheFirst: !!cacheFirst }),
        webViewGetSource: (html, url, js, sourceRegex, cacheFirst) => call('webView', { html: html == null ? null : str(html), url: url == null ? null : str(url), js: js == null ? null : str(js), sourceRegex: sourceRegex == null ? null : str(sourceRegex), cacheFirst: !!cacheFirst }),
        webViewGetOverrideUrl: (html, url, js, overrideUrlRegex, cacheFirst) => call('webView', { html: html == null ? null : str(html), url: url == null ? null : str(url), js: js == null ? null : str(js), overrideUrlRegex: overrideUrlRegex == null ? null : str(overrideUrlRegex), cacheFirst: !!cacheFirst }),
        startBrowser: (url, title, html) => { call('browser', { url: str(url), title: title == null ? '' : str(title), html: html == null ? null : str(html), wait: false }); return ''; },
        startBrowserAwait: (url, title, refetch, html) => { const b = call('browser', { url: str(url), title: title == null ? '' : str(title), html: html == null ? null : str(html), refetch: refetch !== false, wait: true }); return wrapResponse({ url: str(url), body: b, status: 200 }, url); },
        showBrowser: (url, html, preloadJs, title) => { call('browser', { url: str(url), title: title == null ? '' : str(title), html: html == null ? null : str(html), wait: false }); return ''; },
        openVideoPlayer: (url, title) => { call('log', { s: '[video] ' + str(title) + ' ' + str(url) }); },
        getVerificationCode: () => { throw new Error('需要输入验证码（请在书源登录页通过内置浏览器完成验证）'); },
        openUrl: () => {},
        open: () => {},
        refreshExplore: () => { call('refresh', { kind: 'explore' }); return true; },
        refreshBookInfo: () => { call('refresh', { kind: 'info' }); return true; },
        refreshBookToc: () => { call('refresh', { kind: 'toc' }); return true; },
        refreshContent: () => { call('refresh', { kind: 'content' }); return true; },
        cacheContent: (chapter, text) => call('batch.cacheContent', { chapter: typeof chapter === 'string' ? chapter : (chapter && chapter.url ? str(chapter.url) : ''), index: chapter && typeof chapter.index === 'number' ? chapter.index : -1, content: str(text) }),
        singleFlight: (k, fn) => {
            if (typeof fn !== 'function') return null;
            call('lock.acquire', { k: str(k) });
            try { return fn(); } finally { call('lock.release', { k: str(k) }); }
        },
        lock: (k, fn) => {
            if (typeof fn !== 'function') return null;
            call('lock.acquire', { k: str(k) });
            try { return fn(); } finally { call('lock.release', { k: str(k) }); }
        },
        tick: (name) => call('tick', { name: str(name) }),
        getThemeMode: () => 'light',
        getThemeConfig: () => '{}',
        getThemeConfigMap: () => __jmap({}),
        getReadBookConfig: () => '{}',
        getReadBookConfigMap: () => __jmap({}),

        // Crypto / Sign / Digest / HMAC
        createSymmetricCrypto: sym,
        createAsymmetricCrypto: (alg) => {
            let pub = null, priv = null;
            const toBytes = (d) => Array.isArray(d) ? d : call('b64.dec', { s: str(d) });
            const o = {
                setPublicKey: (k) => { pub = toBytes(k); return o; },
                setPrivateKey: (k) => { priv = toBytes(k); return o; },
                decrypt: (d) => call('rsa.dec', { key: pub, priv, b: toBytes(d) }),
                decryptStr: (d) => call('bytes.dec', { b: call('rsa.dec', { key: pub, priv, b: toBytes(d) }), cs: 'UTF-8' }),
                encrypt: (d) => call('rsa.enc', { key: pub, b: bytesOf(d) }),
                encryptBase64: (d) => call('b64.enc', { b: o.encrypt(d) }),
            };
            return o;
        },
        createSign: (alg) => {
            let pub = null, priv = null;
            const toBytes = (d) => Array.isArray(d) ? d : call('b64.dec', { s: str(d) });
            const o = {
                setPublicKey: (k) => { pub = toBytes(k); return o; },
                setPrivateKey: (k) => { priv = toBytes(k); return o; },
                sign: (d) => call('sign.sign', { alg: str(alg || 'SHA256withRSA'), priv, b: bytesOf(d) }),
                signHex: (d) => call('hex.enc', { b: o.sign(d) }),
                signBase64: (d) => call('b64.enc', { b: o.sign(d) }),
                verify: (d, sig) => call('sign.verify', { alg: str(alg || 'SHA256withRSA'), pub, b: bytesOf(d), sig: toBytes(sig) }),
            };
            return o;
        },
        digestHex: (data, alg) => call('hex.enc', { b: call('digest', { alg: str(alg || 'SHA-256'), b: bytesOf(data) }) }),
        digestBase64Str: (data, alg) => call('b64.enc', { b: call('digest', { alg: str(alg || 'SHA-256'), b: bytesOf(data) }) }),
        HMacHex: (data, alg, key) => call('hex.enc', { b: call('hmac', { alg: str(alg || 'HmacSHA256'), key: bytesOf(key), b: bytesOf(data) }) }),
        HMacBase64: (data, alg, key) => call('b64.enc', { b: call('hmac', { alg: str(alg || 'HmacSHA256'), key: bytesOf(key), b: bytesOf(data) }) }),

        aesDecodeToByteArray: (d, k, t, iv) => sym(t || 'AES/ECB/PKCS5Padding', k, iv).decrypt(d),
        aesDecodeToString: (d, k, t, iv) => sym(t || 'AES/ECB/PKCS5Padding', k, iv).decryptStr(d),
        aesDecodeArgsBase64Str: (d, k, t, iv) => sym(t || 'AES/ECB/PKCS5Padding', call('b64.dec', { s: str(k) }), iv ? call('b64.dec', { s: str(iv) }) : null).decryptStr(d),
        aesBase64DecodeToByteArray: (d, k, t, iv) => sym(t || 'AES/ECB/PKCS5Padding', k, iv).decrypt(call('b64.dec', { s: str(d) })),
        aesBase64DecodeToString: (d, k, t, iv) => sym(t || 'AES/ECB/PKCS5Padding', k, iv).decryptStr(call('b64.dec', { s: str(d) })),
        aesEncodeToByteArray: (d, k, t, iv) => sym(t || 'AES/ECB/PKCS5Padding', k, iv).encrypt(d),
        aesEncodeToString: (d, k, t, iv) => sym(t || 'AES/ECB/PKCS5Padding', k, iv).encryptHex(d),
        aesEncodeToBase64ByteArray: (d, k, t, iv) => bytesOf(sym(t || 'AES/ECB/PKCS5Padding', k, iv).encryptBase64(d)),
        aesEncodeToBase64String: (d, k, t, iv) => sym(t || 'AES/ECB/PKCS5Padding', k, iv).encryptBase64(d),
        aesEncodeArgsBase64Str: (d, k, t, iv) => sym(t || 'AES/ECB/PKCS5Padding', call('b64.dec', { s: str(k) }), iv ? call('b64.dec', { s: str(iv) }) : null).encryptBase64(d),

        desDecodeToString: (d, k, t, iv) => sym(t || 'DES/ECB/PKCS5Padding', k, iv).decryptStr(d),
        desBase64DecodeToString: (d, k, t, iv) => sym(t || 'DES/ECB/PKCS5Padding', k, iv).decryptStr(call('b64.dec', { s: str(d) })),
        desEncodeToString: (d, k, t, iv) => sym(t || 'DES/ECB/PKCS5Padding', k, iv).encryptHex(d),
        desEncodeToBase64String: (d, k, t, iv) => sym(t || 'DES/ECB/PKCS5Padding', k, iv).encryptBase64(d),

        tripleDESDecodeStr: (d, k, t, iv) => sym(t || 'DESede/ECB/PKCS5Padding', k, iv).decryptStr(d),
        tripleDESDecodeArgsBase64Str: (d, k, t, iv) => sym(t || 'DESede/ECB/PKCS5Padding', call('b64.dec', { s: str(k) }), iv ? call('b64.dec', { s: str(iv) }) : null).decryptStr(d),
        tripleDESEncodeBase64Str: (d, k, t, iv) => sym(t || 'DESede/ECB/PKCS5Padding', k, iv).encryptBase64(d),
        tripleDESEncodeArgsBase64Str: (d, k, t, iv) => sym(t || 'DESede/ECB/PKCS5Padding', call('b64.dec', { s: str(k) }), iv ? call('b64.dec', { s: str(iv) }) : null).encryptBase64(d),

        hexDecodeToString: (s) => call('bytes.dec', { b: call('hex.dec', { s: str(s) }), cs: 'UTF-8' }),
        hexDecodeToByteArray: (s) => call('hex.dec', { s: str(s) }),
        hexEncodeToString: (s) => call('hex.enc', { b: bytesOf(s) }),
        strToBytes: (s, cs) => call('bytes.enc', { s: str(s), cs: cs || 'UTF-8' }),
        bytesToStr: (b, cs) => call('bytes.dec', { b, cs: cs || 'UTF-8' }),

        // Font de-obfuscation (QueryTTF)
        queryTTF: (data, useCache) => {
            if (Array.isArray(data)) return wrapTTF(call('ttf.load', { b: data, cache: useCache !== false }));
            return wrapTTF(call('ttf.load', { src: str(data), cache: useCache !== false }));
        },
        queryBase64TTF: (b64) => wrapTTF(call('ttf.load', { b64: str(b64), cache: true })),
        replaceFont: (text, errTTF, okTTF, filter) => call('ttf.replace', {
            text: str(text),
            err: errTTF && errTTF.__ttf != null ? errTTF.__ttf : -1,
            ok: okTTF && okTTF.__ttf != null ? okTTF.__ttf : -1,
            filter: !!filter,
        }),

        // Sandboxed Files / Script Cache / ZIP
        importScript: (p) => call('file.importScript', { path: str(p) }),
        cacheFile: (u, sec) => call('file.cacheFile', { url: str(u), sec: Number(sec || 0) }),
        downloadFile: (u) => call('file.downloadFile', { url: str(u) }),
        readFile: (p) => call('file.read', { path: str(p) }),
        readTxtFile: (p, cs) => call('file.readTxt', { path: str(p), cs: cs || 'UTF-8' }),
        deleteFile: (p) => call('file.delete', { path: str(p) }),
        getFile: (p) => ({
            getPath: () => str(p),
            getName: () => str(p).split('/').pop() || '',
            exists: () => call('file.exists', { path: str(p) }),
            readBytes: () => call('file.read', { path: str(p) }),
            readText: (cs) => call('file.readTxt', { path: str(p), cs: cs || 'UTF-8' }),
        }),
        unzipFile: (p) => call('file.unzip', { path: str(p) }),
        unArchiveFile: (p) => call('file.unzip', { path: str(p) }),
        getZipStringContent: (u, p, cs) => call('file.zipStr', { url: str(u), path: str(p), cs: cs || 'UTF-8' }),
        getZipByteArrayContent: (u, p) => call('file.zipBytes', { url: str(u), path: str(p) }),
        getTxtInFolder: (p) => call('file.txtInFolder', { path: str(p) }),
    };

    globalThis.cookie = {
        getCookie: (u) => java.getCookie(u),
        getKey: (u, k) => java.getCookie(u, k),
        setCookie: (u, c) => call('cookieSet', { url: str(u), c: str(c) }),
        removeCookie: (u) => call('cookieRemove', { url: str(u) }),
    };

    // ---- Rhino / Android compatibility ----
    function JString(a, b, c, d) {
        let s;
        if (a == null || typeof a !== 'object' || !Array.isArray(a)) s = str(a);
        else if (typeof b === 'number') s = call('bytes.dec', { b: a.slice(b, b + c), cs: d || 'UTF-8' });
        else s = call('bytes.dec', { b: a, cs: b || 'UTF-8' });
        return { toString: () => s, valueOf: () => s, getBytes: (cs) => call('bytes.enc', { s, cs: cs || 'UTF-8' }), length: () => s.length };
    }
    const Arrays = {
        copyOf: (a, n) => { const r = a.slice(0, n); while (r.length < n) r.push(0); return r; },
        copyOfRange: (a, from, to) => { const r = a.slice(from, to); while (r.length < to - from) r.push(0); return r; },
        toString: (a) => '[' + a.join(', ') + ']',
    };
    const Base64 = {
        getDecoder: () => ({ decode: (v) => call('b64.dec', { s: Array.isArray(v) ? call('bytes.dec', { b: v, cs: 'UTF-8' }) : str(v) }) }),
        getMimeDecoder: () => Base64.getDecoder(),
        getUrlDecoder: () => Base64.getDecoder(),
        getEncoder: () => ({ encodeToString: (b) => call('b64.enc', { b: bytesOf(b) }), encode: (b) => call('bytes.enc', { s: call('b64.enc', { b: bytesOf(b) }), cs: 'UTF-8' }) }),
    };
    function Inflater(nowrap) {
        let input = [], decoded = null, position = 0;
        return {
            setInput: (b, off, len) => { off = off || 0; input = b.slice(off, len == null ? b.length : off + len); decoded = null; position = 0; },
            inflate: (out, off, len) => { if (decoded === null) decoded = call('inflate', { b: input, nowrap: !!nowrap }); off = off || 0; const n = Math.min(decoded.length - position, len == null ? out.length - off : len); for (let i = 0; i < n; i++) out[off + i] = decoded[position + i]; position += n; return n; },
            end: () => { input = []; decoded = []; position = 0; }, finished: () => decoded !== null && position >= decoded.length,
        };
    }
    const MessageDigest = { getInstance: (alg) => { let acc = []; return {
        update: (b) => { acc = acc.concat(bytesOf(b)); },
        digest: (b) => { const all = b === undefined ? acc : acc.concat(bytesOf(b)); acc = []; return call('digest', { alg: str(alg), b: all }); },
    }; } };
    const Cipher = { getInstance: (t) => { let mode = 2, key = null, iv = null; return {
        init: (m, k, p) => { mode = m; key = k; iv = p || null; },
        doFinal: (b) => call('cipher', { t: str(t), mode, key: key.key, iv: iv ? iv.iv : null, b: bytesOf(b) }),
    }; }, ENCRYPT_MODE: 1, DECRYPT_MODE: 2 };
    function SecretKeySpec(k, alg) { return { key: bytesOf(k), alg: str(alg) }; }
    function IvParameterSpec(v) { return { iv: bytesOf(v) }; }
    function ByteArrayOutputStream() {
        let data = [];
        return { write: (b, off, len) => {
            const a = typeof b === 'number' ? [b] : bytesOf(b).slice(off || 0, len == null ? b.length : (off || 0) + len);
            if (data.length + a.length > 16777216) throw new Error('字节流过大');
            data = data.concat(a.map(x => (x << 24) >> 24));
        }, toByteArray: () => data.slice(), size: () => data.length, reset: () => { data = []; }, flush: () => {}, close: () => {} };
    }
    function GZIPOutputStream(out) {
        const buffer = new ByteArrayOutputStream(); let finished = false;
        const finish = () => { if (!finished) { out.write(call('gzip', { b: buffer.toByteArray(), encode: true })); finished = true; } };
        return { write: buffer.write, finish, close: finish, flush: () => {} };
    }
    function GZIPInputStream(input) {
        const b = input.bytes || bytesOf(input);
        return { bytes: b.length > 2 && (b[0] & 255) === 31 && (b[1] & 255) === 139 ? call('gzip', { b, encode: false }) : b };
    }
    function InputStreamReader(input, cs) { return { text: call('bytes.dec', { b: input.bytes || bytesOf(input), cs: cs || 'UTF-8' }) }; }
    function BufferedReader(input) {
        const lines = input.text.split(/\r\n|\n|\r/); let i = 0;
        if (lines[lines.length - 1] === '') lines.pop();
        return { readLine: () => i < lines.length ? lines[i++] : null, close: () => {} };
    }
    function JURL(url) { return { openConnection: () => {
        let method = 'GET', headers = {}, response = null; const output = new ByteArrayOutputStream();
        const send = () => response || (response = call('http.bytes', { url: str(url), method, headers, b: output.toByteArray() }));
        return { setRequestMethod: m => { method = str(m); }, setRequestProperty: (k, v) => { headers[str(k)] = str(v); },
            setDoOutput: () => {}, setConnectTimeout: () => {}, setReadTimeout: () => {},
            getOutputStream: () => output, getResponseCode: () => send().status,
            getHeaderField: k => { const h = send().headers; const n = Object.keys(h).find(x => x.toLowerCase() === str(k).toLowerCase()); return n ? h[n] : null; },
            getInputStream: () => ({ bytes: send().b }), getErrorStream: () => ({ bytes: send().b }), disconnect: () => {} };
    } }; }
    function SecureRandom() { return { generateSeed: n => call('random.bytes', { n: Number(n) }), nextBytes: out => { const b = call('random.bytes', { n: out.length }); for (let i = 0; i < b.length; i++) out[i] = b[i]; } }; }
    function JBigInteger(a, b) {
        const B = BigInt, zero = B(0), one = B(1), eight = B(8); let value;
        if (typeof a === 'number' && b && typeof b.generateSeed === 'function') {
            if (a < 0 || a > 8192) throw new Error('大整数长度无效');
            const bytes = b.generateSeed(Math.ceil(a / 8)); value = zero;
            for (const byte of bytes) value = (value << eight) + B(byte & 255);
            if (a) value &= (one << B(a)) - one;
        } else if (typeof a === 'number' && Array.isArray(b)) {
            value = zero; for (const byte of b) value = (value << eight) + B(byte & 255);
            if (a < 0) value = -value;
        } else if (Array.isArray(a)) {
            value = zero; for (const byte of a) value = (value << eight) + B(byte & 255);
            if (a.length && (a[0] & 128)) value -= one << B(a.length * 8);
        } else {
            const s = str(a); if (s.length > 8192) throw new Error('大整数过大');
            value = B(Number(b || 10) === 16 ? (s[0] === '-' ? '-0x' + s.slice(1) : '0x' + s) : s);
        }
        const other = x => B(x.toString());
        const wrap = v => new JBigInteger(v.toString(), 10);
        return { toString: radix => value.toString(Number(radix || 10)),
            subtract: x => wrap(value - other(x)), add: x => wrap(value + other(x)),
            mod: x => { const m = other(x); if (m <= zero) throw new Error('模数无效'); return wrap((value % m + m) % m); },
            modPow: (x, y) => { let e = other(x), m = other(y), r = one; if (e < zero || m <= zero) throw new Error('模幂参数无效');
                if (e.toString(2).length > 8192 || m.toString(2).length > 8192) throw new Error('模幂参数过大');
                let v = (value % m + m) % m; while (e > zero) { if (e & one) r = r * v % m; e >>= one; v = v * v % m; } return wrap(r % m); },
            toByteArray: () => { let n = 1; while (value < -(one << B(n * 8 - 1)) || value >= (one << B(n * 8 - 1))) n++;
                let v = value < zero ? (one << B(n * 8)) + value : value; const out = new Array(n);
                for (let i = n - 1; i >= 0; i--) { out[i] = Number(v & B(255)); if (out[i] >= 128) out[i] -= 256; v >>= eight; } return out; } };
    }
    JBigInteger.valueOf = n => new JBigInteger(str(n), 10);
    JBigInteger.ONE = JBigInteger.valueOf(1);

    globalThis.getHeaderMap = () => __jmap(call('headers', null));
    globalThis.getSource = () => globalThis.source;
    globalThis.JavaImporter = function () { return new Object(); };

    // ---- source / book / chapter / cache wrappers ----
    globalThis.__wrapSource = (src) => {
        src.getKey = () => src.bookSourceUrl;
        src.getTag = () => src.bookSourceName || src.bookSourceUrl;
        src.key = src.bookSourceUrl;
        src.getVariable = () => call('sourceGet', { k: '__sourceVariable' });
        src.setVariable = (v) => call('sourcePut', { k: '__sourceVariable', v: str(v) });
        src.putVariable = (v) => call('sourcePut', { k: '__sourceVariable', v: str(v) });
        src.get = (k) => call('sourceGet', { k: str(k) });
        src.put = (k, v) => call('sourcePut', { k: str(k), v: str(v) });
        src.getLoginInfo = () => call('loginInfo', null);
        src.getLoginInfoMap = () => __jmap(parseObj(call('loginInfo', null)));
        if (!Object.getOwnPropertyDescriptor(globalThis, 'infoMap')) {
            Object.defineProperty(globalThis, 'infoMap', { configurable: true, get: () => parseObj(call('loginInfo', null)) });
        }
        src.putLoginInfo = (v) => { call('putLoginInfo', { v: str(v) }); return true; };
        src.removeLoginInfo = () => call('putLoginInfo', { v: '' });
        src.getLoginHeader = () => call('loginHeader', null);
        src.getLoginHeaderMap = () => __jmap(parseObj(call('loginHeader', null)));
        src.putLoginHeader = (v) => call('putLoginHeader', { v: str(v) });
        src.removeLoginHeader = () => call('putLoginHeader', { v: '' });
        src.getLoginJs = () => src.loginUrl || '';
        src.putConcurrent = (v) => call('source.putConcurrent', { v: str(v) });
        src.refreshExplore = () => call('refresh', { kind: 'explore' });
        src.refreshJSLib = () => call('refresh', { kind: 'jsLib' });
        globalThis.sourceApi = src;
        return src;
    };

    globalThis.__wrapBook = (b) => {
        if (!b || typeof b !== 'object') return b;
        b.getVariable = (k) => call('getVar', { k: k ? str(k) : '__bookVariable' });
        b.setVariable = (k, v) => call('putVar', { k: v === undefined ? '__bookVariable' : str(k), v: str(v === undefined ? k : v) });
        b.putVariable = (k, v) => b.setVariable(k, v);
        b.getCustomVariable = () => call('getVar', { k: '__bookVariable' });
        b.putCustomVariable = (v) => call('putVar', { k: '__bookVariable', v: str(v) });
        return b;
    };

    globalThis.__wrapChapter = (c) => {
        if (!c || typeof c !== 'object') return c;
        c.getTitle = () => c.title || '';
        c.getUrl = () => c.url || '';
        c.getIndex = () => c.index || 0;
        c.getVariable = (k) => call('getVar', { k: 'ch.' + (c.index || 0) + '.' + (k ? str(k) : '__chapterVariable') });
        c.putVariable = (k, v) => call('putVar', { k: 'ch.' + (c.index || 0) + '.' + (v === undefined ? '__chapterVariable' : str(k)), v: str(v === undefined ? k : v) });
        return c;
    };

    globalThis.cache = {
        get: (k) => call('cacheGet', { k: str(k) }),
        put: (k, v, sec) => { call('cachePut', { k: str(k), v: v == null ? null : str(v), sec: Number(sec || 0) }); return v; },
        delete: (k) => call('cachePut', { k: str(k), v: null, sec: 0 }),
        getFromMemory: (k) => call('cacheGet', { k: 'mem.' + str(k) }),
        putMemory: (k, v) => call('cachePut', { k: 'mem.' + str(k), v: str(v), sec: 0 }),
        deleteMemory: (k) => call('cachePut', { k: 'mem.' + str(k), v: null, sec: 0 }),
        getFile: (k) => call('cacheGet', { k: 'file.' + str(k) }),
        putFile: (k, v, sec) => call('cachePut', { k: 'file.' + str(k), v: str(v), sec: Number(sec || 0) }),
    };

    // ---- Jsoup / Java collections ----
    const jsoupEl = (h) => ({
        __h: h,
        select: (q) => jsoupEls(call('jsoup.select', { h: [h], q: str(q) })),
        selectFirst: (q) => { const r = call('jsoup.select', { h: [h], q: str(q), first: true }); return r.length ? jsoupEl(r[0]) : null; },
        getElementsByClass: (c) => jsoupEls(call('jsoup.select', { h: [h], q: '.' + str(c) })),
        getElementsByTag: (t) => jsoupEls(call('jsoup.select', { h: [h], q: str(t) })),
        getElementById: (i) => { const r = call('jsoup.select', { h: [h], q: '#' + str(i), first: true }); return r.length ? jsoupEl(r[0]) : null; },
        children: () => jsoupEls(call('jsoup.children', { h: [h] })),
        text: () => call('jsoup.text', { h: [h] }),
        ownText: () => call('jsoup.ownText', { h: [h] }),
        textNodes: () => call('jsoup.textNodes', { h: [h] }).map((t) => ({ text: () => t, toString: () => t })),
        attr: (k) => call('jsoup.attr', { h: [h], k: str(k) }),
        absUrl: (k) => call('jsoup.attr', { h: [h], k: 'abs:' + str(k) }),
        hasClass: (c) => (' ' + call('jsoup.attr', { h: [h], k: 'class' }) + ' ').indexOf(' ' + str(c) + ' ') >= 0,
        html: () => call('jsoup.html', { h: [h] }),
        outerHtml: () => call('jsoup.outer', { h: [h] }),
        toString: () => call('jsoup.outer', { h: [h] }),
        toJSON: () => call('jsoup.outer', { h: [h] }),
    });
    const jsoupEls = (hs) => {
        const a = hs.map(jsoupEl);
        const def = (k, f) => Object.defineProperty(a, k, { value: f, enumerable: false });
        def('select', (q) => jsoupEls(call('jsoup.select', { h: hs, q: str(q) })));
        def('text', () => call('jsoup.text', { h: hs }));
        def('eachText', () => hs.map((x) => call('jsoup.text', { h: [x] })));
        def('attr', (k) => call('jsoup.attr', { h: hs, k: str(k) }));
        def('eachAttr', (k) => hs.map((x) => call('jsoup.attr', { h: [x], k: str(k) })));
        def('html', () => call('jsoup.html', { h: hs }));
        def('outerHtml', () => call('jsoup.outer', { h: hs }));
        def('first', () => (a.length ? a[0] : null));
        def('last', () => (a.length ? a[a.length - 1] : null));
        def('eq', (i) => jsoupEls(hs[i] === undefined ? [] : [hs[i]]));
        def('toString', () => call('jsoup.outer', { h: hs }));
        return a;
    };
    globalThis.__nodeList = (hs) => jsoupEls(hs);
    const jsoupParse = (html) => jsoupEl(call('jsoup.parse', { html: str(html) }));
    globalThis.org = { jsoup: { Jsoup: { parse: jsoupParse, parseBodyFragment: jsoupParse } } };
    Object.assign(globalThis.java, {
        getElement: (rule) => jsoupEls(call('jsoup.rule', { rule: str(rule) })),
        getElements: (rule) => jsoupEls(call('jsoup.rule', { rule: str(rule) })),
    });
    const listMethods = {
        toArray: function () { return this; },
        size: function () { return this.length; },
        get: function (i) { return this[i]; },
        isEmpty: function () { return this.length === 0; },
        add: function (x) { this.push(x); return true; },
        contains: function (x) { return this.indexOf(x) >= 0; },
    };
    for (const k of Object.keys(listMethods)) {
        if (!(k in Array.prototype)) Object.defineProperty(Array.prototype, k, { value: listMethods[k], enumerable: false, writable: true, configurable: true });
    }

    globalThis.Packages = {
        java: {
            lang: { String: JString, Thread: { sleep: () => {} } },
            nio: { charset: { Charset: { forName: (name) => str(name) }, StandardCharsets: { UTF_8: 'UTF-8' } } },
            util: {
                Arrays, Base64,
                ArrayList: function () { return []; },
                HashMap: function () { return __jmap({}); },
                UUID: { randomUUID: () => java.randomUUID() },
                zip: { Inflater, GZIPOutputStream, GZIPInputStream },
            },
            security: { MessageDigest, SecureRandom },
            io: { ByteArrayOutputStream, InputStreamReader, BufferedReader },
            net: { URL: JURL },
            math: { BigInteger: JBigInteger },
        },
        javax: { crypto: { Cipher, spec: { SecretKeySpec, IvParameterSpec } } },
        cn: { hutool: { crypto: { digest: { DigestUtil: { md5Hex: (s) => java.md5Encode(s), sha256Hex: (s) => java.digestHex(s, 'SHA-256') } } } } },
        io: { legado: { app: { help: { http: { StrResponse: function (url, body) { return __strResponse({ url: url, body: body }); } } } } } },
    };

    Object.assign(globalThis.java, Packages.java);
    globalThis.javax = Packages.javax;
    globalThis.__legado_call = call;
})(__host);
