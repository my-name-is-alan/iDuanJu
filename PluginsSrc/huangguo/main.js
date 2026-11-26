// 黄果短剧 — three sub-sources ported from the native client:
//   ai     huangguoai.com mirrors (HTML cards + /api JSON + ranks JSON-LD + playback POST)
//   video  huangguo.video (HTML cards, data-hls; Cloudflare handled by the host)
//   legacy cloudfront app API (AES-CBC params + encrypted responses + guest login)
const WEB_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1';

const AI_MIRRORS = [
  'https://00b4b.adihsppe.cc', 'https://bsl8m.adihsppe.cc', 'https://hgai12.com',
  'https://brma.adihsppe.cc', 'https://mzofzp.adihsppe.cc', 'https://rpb7bz.adihsppe.cc',
  'https://v2tg.adihsppe.cc', 'https://huangguoai.com', 'https://huangguoai.io',
  'https://huangguoai.me', 'https://huangguoai.ai', 'https://thu.agdkczeyx.cc',
  'https://ttvoij.ediayikma.cc',
];
const AI_CHANNELS = [
  { slug: 'recommend', name: '精选推荐', kind: 'html' },
  { slug: 'newest', name: '最近上新', kind: 'html' },
  { slug: 'hot', name: '热播榜', kind: 'rank' },
  { slug: 'rank-recommend', name: '推荐榜', kind: 'rank' },
  { slug: 'potential', name: '潜力榜', kind: 'rank' },
  { slug: 'ai-duanju', name: 'AI短剧', kind: 'api' },
  { slug: 'ai-manju', name: 'AI漫剧', kind: 'api' },
  { slug: 'ai-mogai', name: 'AI魔改', kind: 'api' },
];
const VIDEO_BASE = 'https://huangguo.video';
const VIDEO_CHANNELS = [
  { slug: '0', name: '视频全部' }, { slug: '1', name: 'MV/音乐剧' },
  { slug: '2', name: '短片' }, { slug: '3', name: '连续剧' }, { slug: '4', name: '片段' },
];
const LEGACY_FE = 'https://d2pypzndaqisk.cloudfront.net';
const LEGACY_APIS = ['https://dr6skssi3nxbk.cloudfront.net', 'https://d18ka9rqpfd3lo.cloudfront.net', 'https://d37n0wjehmw08f.cloudfront.net'];
const LEGACY_CDN = 'https://sjljsla.lkkwip.cn';
const LEGACY_IMG = 'https://zzzznnn.lkkwip.cn';
const LEGACY_XUA = 'BuildID=com.abc.Butterfly;SysType=ios;DevID=00000000000000000000000000000000;Ver=1.0.0;DevType=iPhone;DeviceBrand=APPLE;DeviceModel=iPhone;SystemName=iOS;SystemVersion=18.7;Terminal=1;IsH5=1;Sid=00000000000000000000000000000000';
const NON_CONSENT = ['强奸', '強姦', '强暴', '強暴', '迷奸', '迷姦', '轮奸', '輪姦', '下药', '下藥', '迷药', '迷藥', '催眠', '胁迫', '脅迫', '强迫', '強迫', '被迫',
  '肉偿', '肉償', '凌辱', '侵犯', '猥亵', '猥褻', '非自愿', '囚禁', '绑架', '綁架', '偷拍', '盗摄', '醉奸', '睡奸', '性奴', '调教', '調教', 'rape', 'forced', 'drugged', 'hypno'];

let AI = { base: '', at: 0 };
let LG = { s: null, loaded: false };

// ---------- text utils ----------
function utf8Bytes(s) {
  const out = [];
  for (let i = 0; i < s.length; i++) {
    let c = s.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
      const lo = s.charCodeAt(i + 1);
      if (lo >= 0xdc00 && lo <= 0xdfff) { c = 0x10000 + ((c - 0xd800) << 10) + (lo - 0xdc00); i++; }
    }
    if (c < 0x80) out.push(c);
    else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
    else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
  }
  return out;
}

function urlEncode(s) {
  const bytes = utf8Bytes(String(s));
  let out = '';
  for (let i = 0; i < bytes.length; i++) {
    const b = bytes[i];
    const keep = (b >= 65 && b <= 90) || (b >= 97 && b <= 122) || (b >= 48 && b <= 57) ||
      b === 45 || b === 95 || b === 46 || b === 126;
    if (keep) out += String.fromCharCode(b);
    else if (b === 32) out += '+';
    else out += '%' + (b < 16 ? '0' : '') + b.toString(16).toUpperCase();
  }
  return out;
}

function int(v) { const n = parseInt(v, 10); return isFinite(n) ? n : 0; }
function str(v) { return v == null ? '' : String(v); }

// ---------- HTML helpers (mirror the native regex scraper) ----------
const RE_NON_CONTENT = /<!--[\s\S]*?-->|<script\b[^>]*>[\s\S]*?<\/script\s*>|<style\b[^>]*>[\s\S]*?<\/style\s*>/gi;

function groups(p, s) {
  const m = new RegExp(p, 'i').exec(s);
  return m ? m : null;
}
function all(p, s) {
  const re = new RegExp(p, 'gi');
  const out = [];
  let m;
  while ((m = re.exec(s)) !== null) {
    out.push(m);
    if (m[0] === '') re.lastIndex++;
  }
  return out;
}
function htmlReplace(p, s, withText) { return s.replace(new RegExp(p, 'gi'), withText); }

function unescapeHtml(s) {
  let t = String(s);
  const map = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&#39;': "'", '&#x27;': "'", '&apos;': "'", '&nbsp;': ' ', '&#x2F;': '/', '&#47;': '/' };
  for (const k in map) t = t.split(k).join(map[k]);
  t = t.replace(/&#(\d+);/g, (m, n) => { try { return String.fromCodePoint(parseInt(n, 10)); } catch (e) { return m; } });
  return t;
}

function cleanText(s) {
  let t = htmlReplace(RE_NON_CONTENT.source, String(s), ' ');
  t = t.replace(/<[^>]+>/g, ' ');
  t = unescapeHtml(t);
  return t.replace(/[\s\u00a0\u3000]+/g, ' ').trim();
}

function attr(block, names) {
  for (let i = 0; i < names.length; i++) {
    const m = groups('\\b' + names[i].replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*=\\s*["\']([^"\']+)["\']', block);
    if (m) return unescapeHtml(m[1]).trim();
  }
  return '';
}

function classText(raw, cls) {
  const c = cls.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = groups('<[^>]+class=["\'][^"\']*\\b' + c + '\\b[^"\']*["\'][^>]*>([\\s\\S]*?)</[a-z0-9]+>', raw);
  return m ? cleanText(m[1]) : '';
}

function pageTitle(h) {
  let m = groups('<h1[^>]*>([\\s\\S]*?)</h1>', h);
  if (m) { const t = cleanText(m[1]); if (t) return t; }
  m = groups('<meta\\b[^>]*property=["\']og:title["\'][^>]*content=["\']([^"\']+)["\'][^>]*>', h);
  if (m) return unescapeHtml(m[1]).trim();
  m = groups('<title[^>]*>([\\s\\S]*?)</title>', h);
  if (m) return cleanText(m[1]);
  return '';
}
function metaDescription(h) {
  const m = groups('<meta\\b[^>]*(?:name|property)=["\'](?:description|og:description)["\'][^>]*content=["\']([^"\']+)["\'][^>]*>', h);
  return m ? unescapeHtml(m[1]) : '';
}
function ogImage(h) {
  const m = groups('<meta\\b[^>]*property=["\']og:image["\'][^>]*content=["\']([^"\']+)["\'][^>]*>', h);
  return m ? m[1].trim() : '';
}
function episodeIndex(s, fallback) {
  const m = groups('(?:第\\s*0*(\\d+)\\s*(?:集|话|期)|(?:更新至|共|全)\\s*0*(\\d+)\\s*(?:集|话|期)|(?:episode|ep)\\s*#?\\s*0*(\\d+))', s);
  if (m) for (let i = 1; i < m.length; i++) if (m[i]) { const n = int(m[i]); if (n > 0) return n; }
  return fallback;
}
function cleanID(s) {
  let t = unescapeHtml(String(s)).replace(/^[ \/\t\r\n"']+|[ \/\t\r\n"']+$/g, '');
  const i = t.search(/[?#]/);
  if (i >= 0) t = t.slice(0, i);
  return t;
}
function detailID(s) {
  const p = pathParts(s);
  for (let i = 0; i < p.length - 1; i++) if (p[i] === 'detail') return cleanID(p[i + 1]);
  return '';
}
function titleNeedsRepair(t, id) {
  const x = String(t || '').trim();
  return !x || x === id || x === '短剧' || x === '搜索' ||
    /(?:\b(?:class|type|title|aria-label)\s*=|(?:data|ata)-search-input|hg-search(?:__|-))/.test(x);
}
function resolveURL(base, rel) {
  const r = unescapeHtml(String(rel || '')).trim().replace(/\\\//g, '/');
  if (!r) return '';
  try { return host.url.resolve(base, r); } catch (e) { return ''; }
}
function pathParts(u) {
  const m = String(u).match(/^[a-z]+:\/\/[^\/]+(\/[^?#]*)?/i);
  const p = m ? (m[1] || '/') : String(u).split(/[?#]/)[0];
  return p.split('/').filter(function (x) { return x.length > 0; });
}
function mediaURL(page, raw) {
  const t = unescapeHtml(String(raw || '')).replace(/^[ \t\r\n"']+|[ \t\r\n"']+$/g, '');
  if (!t) return '';
  const r = resolveURL(page, t);
  const l = r.toLowerCase();
  return (l.indexOf('.m3u8') >= 0 || l.indexOf('.mp4') >= 0) ? r : '';
}

// ---------- HTTP ----------
async function rq(url, opt) {
  opt = opt || {};
  const headers = Object.assign({ 'User-Agent': WEB_UA, 'Accept': opt.accept || 'application/json, text/javascript, */*; q=0.01', 'Accept-Language': 'zh-CN,zh;q=0.9' }, opt.headers || {});
  if (opt.referer) headers['Referer'] = opt.referer;
  const o = { url: url, headers: headers, timeout: opt.timeout || 8000, redirect: 'follow' };
  if (opt.method) o.method = opt.method;
  if (opt.body !== undefined) { o.body = opt.body; if (!o.method) o.method = 'POST'; }
  if (opt.responseType) o.responseType = opt.responseType;
  const r = await host.http.request(o);
  return { status: r.status, url: str(r.url) || url, body: r.body == null ? '' : String(r.body), raw: r.body };
}
async function rqJson(url, opt) {
  const r = await rq(url, opt);
  if (r.status !== 200) throw host.error('E_HTTP', '黄果接口 HTTP ' + r.status);
  try { return JSON.parse(r.body); } catch (e) { throw host.error('E_PARSE', '黄果接口返回非 JSON'); }
}
function hostOf(u) { const m = String(u).match(/^https?:\/\/([^\/]+)/i); return m ? m[1] : ''; }

// ---------- items ----------
function remarksOf(total, finished) {
  if (!total) return finished ? '已完结' : '';
  return finished ? '全' + total + '集' : '更新至' + total + '集';
}
function makeItem(id, title, cover, remarks, tags) {
  const it = { id: id, title: title, cover: cover || '', remarks: remarks || '', orientation: 'portrait' };
  if (tags && tags.length) it.tags = tags;
  return it;
}
function blocked(title) {
  const t = String(title || '');
  for (let i = 0; i < NON_CONSENT.length; i++) if (t.toLowerCase().indexOf(NON_CONSENT[i].toLowerCase()) >= 0) return true;
  return false;
}

// ================= AI site =================
function aiStoredBase() { try { return str(host.storage.get('ai.base')); } catch (e) { return ''; } }
function aiStoredMirrors() { try { const v = host.storage.get('ai.mirrors'); return Array.isArray(v) ? v : []; } catch (e) { return []; } }
function aiSaveBase(base) { try { host.storage.set('ai.base', base); } catch (e) {} }

async function aiProbe(base) {
  const r1 = await rq(base + '/', { accept: 'text/html', timeout: 3500 });
  if (r1.status !== 200) return '';
  const body = r1.body;
  if (body.indexOf('hg-drama-card') < 0 && body.indexOf('videoInitialData') < 0 && body.indexOf('/ai-duanju/') < 0) return '';
  const origin = (r1.url.match(/^(https?:\/\/[^\/]+)/) || [])[1] || base;
  const r2 = await rq(origin + '/api/videos/category/ai-duanju?sort=hot&page=1&size=1', { referer: origin + '/', timeout: 3500 });
  if (r2.status !== 200) return '';
  try { const j = JSON.parse(r2.body); if (!j || !j.data || !Array.isArray(j.data.items)) return ''; } catch (e) { return ''; }
  const final = (r2.url.match(/^(https?:\/\/[^\/]+)/) || [])[1] || origin;
  return final;
}

async function aiLearnMirrors(base) {
  let at = 0;
  try { at = int(host.storage.get('ai.mirrorsAt')); } catch (e) {}
  if (at && Date.now() - at < 12 * 3600 * 1000) return;
  try {
    const r = await rq(base + '/go-home/', { accept: 'text/html', referer: base + '/', timeout: 4000 });
    if (r.status !== 200) return;
    const found = [];
    const re = /https:\/\/((?:[a-z0-9-]+\.)?(?:adihsppe\.cc|huangguoai\.(?:com|io|me|ai)|hgai\d*\.com))(?=[\/"'\s<])/gi;
    let m;
    while ((m = re.exec(r.body)) !== null) {
      const o = 'https://' + m[1];
      if (found.indexOf(o) < 0) found.push(o);
    }
    if (found.length) host.storage.set('ai.mirrors', found.slice(0, 24));
    host.storage.set('ai.mirrorsAt', Date.now());
  } catch (e) {}
}

async function ensureAIBase() {
  if (AI.base && Date.now() - AI.at < 10 * 60 * 1000) return AI.base;
  const cands = [];
  function add(x) { if (x && cands.indexOf(x) < 0) cands.push(x); }
  add(aiStoredBase());
  aiStoredMirrors().forEach(add);
  AI_MIRRORS.forEach(add);
  const started = Date.now();
  for (let i = 0; i < cands.length && i < 8; i++) {
    if (i > 0 && Date.now() - started > 9000) break;
    const base = await aiProbe(cands[i]);
    if (base) {
      AI.base = base; AI.at = Date.now(); aiSaveBase(base);
      aiLearnMirrors(base);
      host.log && host.log('[黄果] AI 站镜像 ' + base);
      return base;
    }
  }
  throw host.error('E_NETWORK', '黄果 AI 站镜像均不可用');
}

function aiJSONCards(root, base) {
  const out = [];
  const seen = {};
  function walk(v) {
    if (Array.isArray(v)) { for (let i = 0; i < v.length; i++) walk(v[i]); return; }
    if (!v || typeof v !== 'object') return;
    if (v.id != null) {
      let id = cleanID(str(v.id) || str(v.videoId) || str(v.slug));
      const d = detailID(id); if (d) id = d;
      const title = str(v.title) || str(v.name) || str(v.videoTitle);
      if (id && !titleNeedsRepair(title, id) && !seen[id]) {
        seen[id] = 1;
        const cover = str(v.cover) || str(v.coverUrl) || str(v.cover_url) || str(v.coverImage) || str(v.image) || str(v.thumbnail) || str(v.poster);
        const eps = int(v.episode_count || v.episodeCount || v.total_episodes || v.totalEpisodes || (typeof v.episodes === 'number' ? v.episodes : 0));
        const fin = v.is_finished === true || int(v.is_finished) === 1;
        out.push(makeItem(id, title, resolveURL(base + '/', cover), remarksOf(eps, fin), []));
      }
      return;
    }
    for (const k in v) walk(v[k]);
  }
  walk(root);
  return out;
}

function aiCardsHTML(html, pageURL) {
  const markup = htmlReplace(RE_NON_CONTENT.source, html, '');
  const out = [];
  const seen = {};
  function add(it) { if (it && !seen[it.id]) { seen[it.id] = 1; out.push(it); } }
  const starts = all('<div\\b[^>]*class=["\'][^"\']*\\bhg-drama-card\\b[^"\']*["\'][^>]*>', markup);
  for (let i = 0; i < starts.length; i++) {
    const st = starts[i].index;
    const end = i + 1 < starts.length ? starts[i + 1].index : markup.length;
    const block = markup.slice(st, end);
    let id = cleanID(attr(block, ['data-track-id']));
    if (!id) { const m = groups('href=["\']([^"\']*?/(?:video|detail)/([^"\'\/?#]+)[^"\']*)["\']', block); if (m) id = cleanID(m[2]); }
    if (!id) continue;
    let title = attr(block, ['data-track-title']);
    if (titleNeedsRepair(title, id)) title = classText(block, 'hg-drama-card__title');
    if (titleNeedsRepair(title, id)) title = attr(block, ['alt', 'title']);
    if (titleNeedsRepair(title, id)) continue;
    let cover = resolveURL(pageURL, attr(block, ['data-src', 'data-original', 'src']));
    if (cover.indexOf('cover-placeholder') >= 0) cover = '';
    const epText = classText(block, 'hg-drama-card__episode');
    const eps = int(attr(block, ['data-ep-base'])) || episodeIndex(epText, 0);
    const tags = [];
    const tms = all('<a\\b[^>]*class=["\'][^"\']*\\bhg-tag\\b[^"\']*["\'][^>]*>([\\s\\S]*?)</a>', block);
    for (let k = 0; k < tms.length; k++) { const t = cleanText(tms[k][1]); if (t) tags.push(t); }
    add(makeItem(id, title, cover, epText || remarksOf(eps, false), tags));
  }
  if (!out.length) {
    const links = all('<a\\b[^>]*href=["\']([^"\']*?/(?:video|detail)/([^"\'\/?#]+)[^"\']*)["\'][^>]*>([\\s\\S]*?)</a>', markup);
    for (let i = 0; i < links.length; i++) {
      const id = cleanID(links[i][2]);
      let t = attr(links[i][0], ['title', 'alt', 'aria-label']);
      if (titleNeedsRepair(t, id)) t = cleanText(links[i][3]);
      if (titleNeedsRepair(t, id)) continue;
      add(makeItem(id, t, resolveURL(pageURL, attr(links[i][0], ['data-src', 'data-original', 'src'])), '', []));
    }
  }
  return out;
}

function aiRank(html, slug, base) {
  const covers = {};
  const hrefs = all('href=["\']([^"\']*/video/(\\d+)[^"\']*)["\']', html);
  for (let i = 0; i < hrefs.length; i++) {
    const id = hrefs[i][2];
    if (covers[id]) continue;
    const windowText = html.slice(hrefs[i].index, hrefs[i].index + 1200);
    const c = groups('(https://pic\\.(?:drleao|qnlycc)\\.cn[^"\\s<>]+)', windowText);
    if (c) covers[id] = c[1];
  }
  const scripts = all('<script[^>]*ld\\+json[^>]*>([\\s\\S]*?)</script>', html);
  for (let i = 0; i < scripts.length; i++) {
    let o;
    try { o = JSON.parse(unescapeHtml(scripts[i][1])); } catch (e) { continue; }
    const graph = o && Array.isArray(o['@graph']) ? o['@graph'] : [];
    for (let g = 0; g < graph.length; g++) {
      const node = graph[g];
      if (!node || node['@type'] !== 'ItemList' || str(node['@id']).indexOf('/ranks/' + slug + '/#itemlist') < 0) continue;
      const els = Array.isArray(node.itemListElement) ? node.itemListElement : [];
      const out = [];
      for (let e = 0; e < els.length; e++) {
        const el = els[e] || {};
        const id = cleanID(str(el.url).replace(/^\/*/, '').indexOf('/video/') >= 0 ? pathParts(str(el.url)).slice(-1)[0] : '');
        const name = str(el.name);
        if (!id || !name) continue;
        out.push(makeItem(id, name, covers[id] || '', '', []));
        out[out.length - 1].rank = int(el.position) || 0;
      }
      return out;
    }
  }
  return [];
}

async function aiList(slug, page) {
  const ch = AI_CHANNELS.filter(function (c) { return c.slug === slug; })[0];
  const base = await ensureAIBase();
  if (ch && ch.kind === 'rank') {
    if (page > 1) return { items: [], hasMore: false };
    const r = await rq(base + '/ranks/' + slug.replace('rank-', '') + '/', { accept: 'text/html', referer: base + '/', timeout: 10000 });
    if (r.status !== 200) throw host.error('E_HTTP', '黄果榜单 HTTP ' + r.status);
    return { items: aiRank(r.body, slug.replace('rank-', ''), base), hasMore: false };
  }
  if (ch && ch.kind === 'api') {
    const u = base + '/api/videos/category/' + slug + '?sort=hot&page=' + page + '&size=24';
    try {
      const j = await rqJson(u, { referer: base + '/' + slug + '/', timeout: 10000 });
      const items = aiJSONCards(j, base);
      if (items.length) return { items: items, hasMore: items.length >= 24 };
    } catch (e) {
      if (page > 1) throw e;
    }
    if (page > 1) return { items: [], hasMore: false };
    const r = await rq(base + '/' + slug + '/', { accept: 'text/html', referer: base + '/', timeout: 10000 });
    if (r.status !== 200) throw host.error('E_HTTP', '黄果频道 HTTP ' + r.status);
    return { items: aiCardsHTML(r.body, base + '/' + slug + '/'), hasMore: false };
  }
  const u = base + '/' + slug + '/' + page + '/';
  const r = await rq(u, { accept: 'text/html', referer: base + '/', timeout: 10000 });
  if (r.status !== 200) throw host.error('E_HTTP', '黄果频道 HTTP ' + r.status);
  const items = aiCardsHTML(r.body, u);
  return { items: items, hasMore: items.length > 0 };
}

async function aiDetail(id) {
  const base = await ensureAIBase();
  let title = '', cover = '', desc = '', tags = [], eps = [];
  if (/^\d{1,20}$/.test(id)) {
    try {
      const j = await rqJson(base + '/api/videos/' + id, { referer: base + '/video/' + id + '/', timeout: 10000 });
      const d = j && j.data ? j.data : {};
      title = str(d.title);
      cover = resolveURL(base + '/', str(d.cover));
      desc = str(d.description);
      const raw = Array.isArray(d.episodes) ? d.episodes : [];
      for (let i = 0; i < raw.length; i++) {
        const e = raw[i] || {};
        const n = int(e.ep_num);
        if (n > 0) eps.push({ id: String(n), name: '第' + n + '集', duration: int(e.duration) });
      }
    } catch (e) {}
  }
  if (!eps.length || !title) {
    const page = base + '/video/' + id + '/';
    const r = await rq(page, { accept: 'text/html', referer: base + '/', timeout: 10000 });
    if (r.status !== 200) throw host.error('E_HTTP', '黄果详情 HTTP ' + r.status);
    const h = r.body;
    if (!title) title = pageTitle(h) || '';
    if (!cover) {
      for (const key of ['coverSrc', 'posterSrc', 'cover_src', 'poster', 'cover']) {
        const m = groups('"' + key + '"\\s*:\\s*"([^"]+)"', h);
        if (m) { const c = resolveURL(page, unescapeHtml(m[1].replace(/\\\//g, '/')).trim()); if (c && c.indexOf('eisees.com') < 0) { cover = c; break; } }
      }
    }
    if (!cover) { const c = groups('(https://pic\\.(?:drleao|qnlycc)\\.cn/[^"\'\\s<>]+)', h); if (c) cover = c[1]; }
    if (!cover) { const og = resolveURL(page, ogImage(h)); if (og && og.indexOf('eisees.com') < 0) cover = og; }
    if (!desc) desc = metaDescription(h);
    if (!eps.length) eps = aiEpisodesHTML(h, page, id);
  }
  if (!eps.length) throw host.error('E_NOT_FOUND', '黄果详情没有可播放分集');
  return {
    title: title || ('黄果 ' + id), cover: cover, desc: desc, tags: tags, actors: [],
    lines: [{ name: '黄果短剧', episodes: eps }],
  };
}

function aiEpisodesHTML(html, pageURL, sourceID) {
  const markup = htmlReplace(RE_NON_CONTENT.source, html, '');
  const links = all('<a\\b[^>]*href=["\']([^"\']*/video/[^"\']+)["\'][^>]*>([\\s\\S]*?)</a>', markup);
  function linkOf(href) {
    const full = resolveURL(pageURL, href);
    const parts = pathParts(full);
    if (parts.length < 2 || parts.length > 3 || parts[0] !== 'video' || parts[1] !== sourceID) return null;
    return { full: full, key: parts.length === 3 ? cleanID(parts[2]) : '1' };
  }
  function isControl(tag) {
    if (attr(tag, ['data-ep-id'])) return true;
    const cls = attr(tag, ['class']);
    return (' ' + cls + ' ').indexOf(' hg-web-play__ep ') >= 0;
  }
  let marked = false;
  for (let i = 0; i < links.length; i++) { if (linkOf(links[i][1]) && isControl(links[i][0])) { marked = true; break; } }
  const seen = {};
  const used = {};
  const eps = [];
  for (let i = 0; i < links.length; i++) {
    const l = linkOf(links[i][1]);
    if (!l || (marked && !isControl(links[i][0])) || seen[l.key] || seen[l.full]) continue;
    seen[l.key] = 1; seen[l.full] = 1;
    let title = attr(links[i][0], ['title', 'aria-label']) || cleanText(links[i][2]);
    let idx = int(attr(links[i][0], ['data-ep-id']));
    if (idx <= 0) { const m = groups('/ep-(\\d+)(?:/|$)', l.full); if (m) idx = int(m[1]); }
    if (idx <= 0 && l.key === '1') idx = 1;
    if (idx <= 0) idx = episodeIndex(title, eps.length + 1);
    while (used[idx]) idx++;
    used[idx] = 1;
    const generic = !title || /^\d+$/.test(title) || ['立即播放', '播放', '选集', '正片', '在线观看'].indexOf(title) >= 0;
    eps.push({ id: String(idx), name: generic ? '第' + idx + '集' : title, duration: 0 });
  }
  eps.sort(function (a, b) { return int(a.id) - int(b.id); });
  return eps;
}

async function aiPlay(id, ep) {
  if (!/^\d{1,20}$/.test(id) || ep <= 0) throw host.error('E_NOT_FOUND', '黄果分集参数无效');
  const base = await ensureAIBase();
  const page = base + '/video/' + id + '/';
  const headers = {};
  const referer = base + '/video/' + id + '/ep-' + ep + '/';
  const body = {};
  try {
    const env = await rqJson(base + '/api/videos/' + id + '/episodes/' + ep + '/playback', {
      method: 'POST', body: body, accept: 'application/json, text/javascript, */*; q=0.01', referer: page, timeout: 10000,
      headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest', 'Origin': base },
    });
    if (!(int(env.status) === 1 || int(env.code) === 200)) throw host.error('E_UPSTREAM', str(env.msg) || '黄果取流失败');
    const data = env.data || {};
    const streams = data.streams || {};
    const main = str(streams.main);
    const hevc = str(streams.hevc);
    const cdn = data.cdn || {};
    const ck = str(cdn.authKey), cv = str(cdn.authValue), app = str(cdn.app);
    const cdnOn = cdn.m3u8Enabled === true || cdn.otherEnabled === true;
    if (cdnOn && ck.toLowerCase() === 'x-cdn-auth' && cv && app && cv.length <= 2048 && app.length <= 64 && !/[\u0000-\u0020\u007f]/.test(cv + app)) {
      headers['x-cdn-auth'] = cv; headers['x-cdn-app'] = app;
    }
    const out = [];
    if (main.indexOf('https://') === 0) out.push({ url: main, format: main.toLowerCase().indexOf('.m3u8') >= 0 ? 'hls' : 'mp4', name: '高清', headers: headers });
    if (hevc.indexOf('https://') === 0) out.push({ url: hevc, format: hevc.toLowerCase().indexOf('.m3u8') >= 0 ? 'hls' : 'mp4', name: 'HEVC', headers: headers });
    if (out.length) return { streams: out };
  } catch (e) {}
  const r = await rq(page, { accept: 'text/html', referer: base + '/', timeout: 10000 });
  if (r.status !== 200) throw host.error('E_UPSTREAM', '黄果取流失败');
  const media = aiVideoURL(r.body, page);
  if (!media) throw host.error('E_UNSUPPORTED', '黄果未返回播放地址');
  return { streams: [{ url: media, format: media.toLowerCase().indexOf('.m3u8') >= 0 ? 'hls' : 'mp4', name: '高清', headers: { 'Referer': page, 'Origin': base } }] };
}

function aiVideoURL(h, pageURL) {
  const m = groups('<script\\b[^>]*id=["\']videoInitialData["\'][^>]*>([\\s\\S]*?)</script>', h);
  if (m) {
    let o = null;
    try { o = JSON.parse(unescapeHtml(m[1])); } catch (e) {}
    if (o) {
      let u = mediaURL(pageURL, str(o.videoSrc) || str(o.videoUrl) || str(o.playUrl));
      if (u) return u;
      const eps = o.epPlaySrcs || {};
      let pref = str(o.ep) || str(o.episode);
      const e = groups('/ep-(\\d+)(?:/|$)', pageURL);
      if (e) pref = e[1];
      if (pref && eps[pref]) { u = mediaURL(pageURL, str(eps[pref])); if (u) return u; }
      const keys = Object.keys(eps);
      for (let i = 0; i < keys.length; i++) { u = mediaURL(pageURL, str(eps[keys[i]])); if (u) return u; }
    }
  }
  const d = groups('data-play-src=["\']([^"\']+)["\']', h);
  if (d) { const u = mediaURL(pageURL, d[1]); if (u) return u; }
  const vs = all('["\']?(?:videoSrc|videoUrl|playUrl)["\']?\\s*[:=]\\s*["\']([^"\']+\\.(?:m3u8|mp4)(?:[^"\']*)?)["\']', h);
  for (let i = 0; i < vs.length; i++) { const u = mediaURL(pageURL, vs[i][1]); if (u) return u; }
  return '';
}

// ================= 视频站 =================
function videoCardBlocks(html) {
  const arts = all('<article\\b[^>]*class=["\'][^"\']*\\bvideo-card\\b[^"\']*["\'][^>]*>[\\s\\S]*?</article>', html);
  if (arts.length) return arts.map(function (m) { return m[0]; });
  return all('<a\\b[^>]*href=["\']([^"\']*/(?:series|video)/[^"\'/?#]+[^"\']*)["\'][^>]*>[\\s\\S]*?</a>', html).map(function (m) { return m[0]; });
}
function videoRef(href) {
  const parts = pathParts(unescapeHtml(href));
  for (let i = 0; i < parts.length - 1; i++) {
    if (parts[i] === 'series' || parts[i] === 'video') {
      const c = cleanID(parts[i + 1]);
      return c ? { kind: parts[i], sid: parts[i] + '/' + c } : null;
    }
  }
  return null;
}
function videoCards(html, pageURL) {
  const blocks = videoCardBlocks(html);
  const out = [];
  const seen = {};
  for (let i = 0; i < blocks.length; i++) {
    const b = blocks[i];
    const l = groups('<a\\b[^>]*href=["\']([^"\']*/(?:series|video)/[^"\'/?#]+[^"\']*)["\']', b);
    if (!l) continue;
    const ref = videoRef(l[1]);
    if (!ref || seen[ref.sid]) continue;
    seen[ref.sid] = 1;
    let title = attr(b, ['title', 'alt', 'aria-label']);
    if (!title) title = cleanText(b);
    if (!title) continue;
    out.push(makeItem('v:' + ref.sid, title, resolveURL(pageURL, attr(b, ['data-src', 'data-original', 'src'])), classText(b, 'text-gold-dim'), []));
  }
  return out;
}
function videoEpisodes(html, pageURL) {
  const blocks = videoCardBlocks(html);
  const out = [];
  const seen = {};
  for (let i = 0; i < blocks.length; i++) {
    const b = blocks[i];
    const l = groups('<a\\b[^>]*href=["\']([^"\']*/(?:series|video)/[^"\'/?#]+[^"\']*)["\']', b);
    if (!l) continue;
    const ref = videoRef(l[1]);
    if (!ref || ref.kind !== 'video') continue;
    const key = ref.sid.slice('video/'.length);
    if (seen[key]) continue;
    seen[key] = 1;
    const page = resolveURL(pageURL, l[1]);
    const media = dataHLS(b, pageURL);
    let title = attr(b, ['title']) || attr(b, ['alt']) || cleanText(b);
    const ep = episodeIndex(title, i + 1);
    out.push({ id: 'v1|' + key + '|' + (media || page), name: title || ('第' + ep + '集'), duration: 0, _ep: ep });
  }
  out.sort(function (a, b) { return a._ep - b._ep; });
  return out.map(function (e) { return { id: e.id, name: e.name, duration: e.duration }; });
}
function dataHLS(block, pageURL) {
  const m = groups('data-hls=["\']([^"\']+)["\']', block);
  return m ? mediaURL(pageURL, m[1]) : '';
}

async function videoList(slug, page) {
  let q = [];
  if (slug !== '0') q.push('category=' + slug);
  if (page > 1) q.push('page=' + page);
  const u = VIDEO_BASE + '/videos' + (q.length ? '?' + q.join('&') : '');
  const r = await rq(u, { accept: 'text/html', referer: VIDEO_BASE + '/', timeout: 12000 });
  if (r.status !== 200) throw host.error('E_HTTP', '黄果视频站 HTTP ' + r.status);
  const items = videoCards(r.body, u);
  return { items: items, hasMore: items.length > 0 };
}

async function videoSearch(q) {
  const u = VIDEO_BASE + '/search?q=' + urlEncode(q);
  const r = await rq(u, { accept: 'text/html', referer: VIDEO_BASE + '/', timeout: 7000 });
  if (r.status !== 200) throw host.error('E_HTTP', '黄果视频站 HTTP ' + r.status);
  let body = r.body;
  const a = body.indexOf('id="search-content"');
  if (a >= 0) body = body.slice(a);
  const b = body.indexOf('</section>');
  if (b >= 0) body = body.slice(0, b);
  return videoCards(body, u);
}

async function videoDetail(sidRaw) {
  const sid = sidRaw.indexOf('series/') === 0 || sidRaw.indexOf('video/') === 0 ? sidRaw : 'series/' + sidRaw;
  const page = VIDEO_BASE + '/' + sid;
  const r = await rq(page, { accept: 'text/html', referer: VIDEO_BASE + '/', timeout: 12000 });
  if (r.status !== 200) throw host.error('E_HTTP', '黄果视频站 HTTP ' + r.status);
  const h = r.body;
  let eps = sid.indexOf('video/') === 0 ? [] : videoEpisodes(h, page);
  if (!eps.length) {
    const m = dataHLS(h, page);
    if (m) eps = [{ id: 'v1||' + m, name: '第1集', duration: 0 }];
  }
  if (!eps.length) throw host.error('E_NOT_FOUND', '黄果视频站没有可播放分集');
  const cover = resolveURL(page, ogImage(h)) || '';
  return {
    title: pageTitle(h) || sid, cover: cover, desc: metaDescription(h), tags: [], actors: [],
    lines: [{ name: '视频站', episodes: eps }],
  };
}

async function videoPlay(episodeId) {
  const p = episodeId.split('|');
  let target = p.length >= 3 ? p.slice(2).join('|') : '';
  if (!target) throw host.error('E_NOT_FOUND', '黄果视频站分集无效');
  if (target.toLowerCase().indexOf('.m3u8') < 0 && target.toLowerCase().indexOf('.mp4') < 0) {
    const r = await rq(target, { accept: 'text/html', referer: VIDEO_BASE + '/', timeout: 12000 });
    if (r.status !== 200) throw host.error('E_HTTP', '黄果视频站 HTTP ' + r.status);
    target = dataHLS(r.body, target) || '';
  }
  if (!target) throw host.error('E_UNSUPPORTED', '黄果视频站未返回播放地址');
  const headers = { 'Referer': VIDEO_BASE + '/', 'Origin': VIDEO_BASE };
  if (target.toLowerCase().indexOf('.mp4') >= 0) return { streams: [{ url: target, format: 'mp4', name: '高清', headers: headers }] };
  return { streams: [await resolveHLS(target, headers)] };
}

async function resolveHLS(url, headers) {
  try {
    const plURL = url;
    const text = (await rq(url, { accept: '*/*', referer: headers['Referer'], timeout: 6000 })).body;
    if (/^#EXT-X-STREAM-INF/m.test(text)) {
      const lines = text.split(/\r?\n/);
      let best = '', bestScore = -1, pending = null;
      for (let i = 0; i < lines.length; i++) {
        const t = lines[i].trim();
        if (!t) continue;
        if (t.indexOf('#EXT-X-STREAM-INF:') === 0) {
          const h = groups('RESOLUTION\\s*=\\s*(\\d+)x(\\d+)', t);
          const bw = groups('(?:AVERAGE-)?BANDWIDTH\\s*=\\s*(\\d+)', t);
          pending = (h ? int(h[2]) * 100000 : 0) + (bw ? int(bw[1]) : 0);
          continue;
        }
        if (t.charAt(0) === '#') continue;
        if (pending != null && pending > bestScore) { bestScore = pending; best = resolveURL(plURL, t); }
        pending = null;
      }
      if (best) return { url: best, key: '', format: 'hls', name: '高清', headers: headers };
    }
  } catch (e) {}
  return { url: url, key: '', format: 'hls', name: '高清', headers: headers };
}

// ================= App 源 (legacy cloudfront) =================
function lgLoad() {
  if (LG.loaded) return LG.s;
  LG.loaded = true;
  try {
    const v = host.storage.get('lg.session');
    LG.s = v && typeof v === 'object' ? v : null;
    if (LG.s && (!LG.s.img || LG.s.img === LEGACY_CDN)) LG.s.img = LEGACY_IMG;
  } catch (e) { LG.s = null; }
  return LG.s;
}
function lgSave() { try { if (LG.s) host.storage.set('lg.session', LG.s); } catch (e) {} }
function lgXUA(dev) { return LEGACY_XUA.replace('DevID=00000000000000000000000000000000', 'DevID=' + dev); }

async function lgFrontend() {
  const page = await rq(LEGACY_FE + '/', { accept: 'text/html', referer: LEGACY_FE + '/', timeout: 6000 });
  if (page.status !== 200) throw host.error('E_NETWORK', '黄果官网不可用');
  const scripts = all('<script\\b[^>]*\\bsrc=["\']([^"\']+)["\']', page.body);
  for (let i = 0; i < scripts.length; i++) {
    const s = scripts[i][1];
    if (s.indexOf('/main-') >= 0 && s.slice(-3) === '.js') {
      const u = resolveURL(LEGACY_FE + '/', s);
      if (hostOf(u) !== hostOf(LEGACY_FE)) continue;
      const js = await rq(u, { accept: '*/*', referer: LEGACY_FE + '/', timeout: 8000 });
      if (js.status === 200) return js.body;
    }
  }
  throw host.error('E_NETWORK', '黄果官网未提供可识别的接口脚本');
}

function lgKeyFromScript(js, name) {
  const m = groups('["\']' + name + '["\']\\s*[:,]\\s*["\']([^"\'\\\\\\r\\n]+)["\']', js);
  return m ? m[1] : '';
}

async function lgApiBase() {
  const s = lgLoad();
  if (s && s.api) return s.api;
  for (let i = 0; i < LEGACY_APIS.length; i++) {
    try {
      const r = await rqJson(LEGACY_APIS[i] + '/api/app/ping/check', { referer: LEGACY_FE + '/', timeout: 4000 });
      if (int(r.code) === 200) { if (s) { s.api = LEGACY_APIS[i]; lgSave(); } return LEGACY_APIS[i]; }
    } catch (e) {}
  }
  const js = await lgFrontend();
  const hosts = [];
  const re = /https:\/\/[a-z0-9]+\.cloudfront\.net/gi;
  let m;
  while ((m = re.exec(js)) !== null) if (m[0] !== LEGACY_FE && hosts.indexOf(m[0]) < 0) hosts.push(m[0]);
  for (let i = 0; i < hosts.length && i < 6; i++) {
    try {
      const r = await rqJson(hosts[i] + '/api/app/ping/check', { referer: LEGACY_FE + '/', timeout: 4000 });
      if (int(r.code) === 200) { if (s) { s.api = hosts[i]; lgSave(); } return hosts[i]; }
    } catch (e) {}
  }
  throw host.error('E_NETWORK', '黄果旧 API 候选域名均不可用');
}

function lgAES(op, data, keyText, ivText, input, output) {
  return host.crypto.aes({
    op: op, mode: 'cbc', padding: 'pkcs7',
    key: keyText, keyEncoding: 'utf8', iv: ivText, ivEncoding: 'utf8',
    data: data, in: input, out: output,
  });
}

async function lgLogin() {
  const s = lgLoad() || { ik: '', pk: '', piv: '', token: '', dev: '', api: '', img: LEGACY_IMG };
  if (!s.pk || utf8Bytes(s.pk).length !== 16 || !s.piv || utf8Bytes(s.piv).length !== 16) {
    const js = await lgFrontend();
    s.ik = lgKeyFromScript(js, 'interfaceKey');
    s.pk = lgKeyFromScript(js, 'parameterKey');
    s.piv = lgKeyFromScript(js, 'parameterIv');
  }
  if (!s.ik || utf8Bytes(s.pk).length !== 16 || utf8Bytes(s.piv).length !== 16) throw host.error('E_UNSUPPORTED', '黄果官网协议已变化，无法识别接口参数');
  if (!s.dev) s.dev = host.crypto.randomHex(8).toUpperCase() + String(Date.now());
  LG.s = s;
  lgSave();
  const enc = lgAES('encrypt', JSON.stringify({ devID: s.dev, isAppStore: false, sysType: 'ios' }), s.pk, s.piv, 'utf8', 'base64');
  const base = await lgApiBase();
  host.log && host.log('[黄果] App 源 API ' + base);
  const r = await rq(base + '/api/app/mine/login/h5', {
    method: 'POST', body: { data: enc }, referer: LEGACY_FE + '/', timeout: 6000,
    headers: { 'Content-Type': 'application/json', 'temp': 'test', 'X-User-Agent': lgXUA(s.dev), 'Origin': LEGACY_FE },
  });
  const env = JSON.parse(r.body);
  if (int(env.code) === 4007 || str(env.msg).indexOf('frequent') >= 0) throw host.error('E_RATE_LIMIT', '黄果登录过于频繁，请稍后重试');
  const data = lgUnpack(env, s);
  const tok = str(data && data.token);
  if (!tok || tok.indexOf(' ') >= 0) throw host.error('E_UPSTREAM', '黄果访客登录未返回有效令牌');
  s.token = tok;
  s.at = Date.now();
  LG.s = s;
  lgSave();
  return s;
}

function lgMask(msg, s) {
  const t = s && s.token;
  return t && t.length >= 8 ? String(msg).split(t).join('[已隐藏]') : String(msg);
}

function lgUnpack(env, s) {
  const code = str(env.code);
  if (code && code !== '200' && code !== '0') {
    throw host.error('E_UPSTREAM', code + ' ' + lgMask(env.msg, s));
  }
  let data = env.data;
  const hash = env.hash;
  const hashOn = hash === true || (typeof hash === 'string' && ['', 'false', '0', 'null'].indexOf(hash.toLowerCase()) < 0) ||
    (hash != null && typeof hash !== 'boolean' && typeof hash !== 'string');
  if (hashOn && typeof data === 'string') {
    const rawHex = host.codec.hexEncode(data.trim(), { from: 'base64' });
    if (rawHex.length < 24) throw host.error('E_PARSE', '黄果加密响应过短');
    const rHex = host.codec.hexEncode(str(s.ik), { from: 'utf8' }) + rawHex.slice(0, 24);
    const l2 = rHex.length >> 1;
    const sh = host.crypto.sha256(rHex, { in: 'hex', out: 'hex' }).slice(16, 48);
    const d = host.crypto.sha256(sh + rHex.slice(0, l2), { in: 'hex', out: 'hex' });
    const f = host.crypto.sha256(rHex.slice(l2) + sh, { in: 'hex', out: 'hex' });
    const key = d.slice(0, 16) + f.slice(16, 48) + d.slice(48);
    const iv = f.slice(0, 8) + d.slice(24, 40) + f.slice(56);
    const plain = host.crypto.aes({
      op: 'decrypt', mode: 'cbc', padding: 'pkcs7',
      key: key, keyEncoding: 'hex', iv: iv, ivEncoding: 'hex',
      data: rawHex.slice(24), in: 'hex', out: 'utf8',
    });
    try { data = JSON.parse(plain); } catch (e) { throw host.error('E_PARSE', '黄果响应解密失败'); }
  }
  if (data == null) throw host.error('E_UPSTREAM', '黄果旧 API 未返回数据');
  return data;
}

async function lgSession() {
  const s = lgLoad();
  if (s && s.token) return s;
  return await lgLogin();
}

async function lgRequest(path, params) {
  const s = await lgSession();
  const base = await lgApiBase();
  let full = path;
  let body;
  const headers = {
    'User-Agent': WEB_UA, 'Accept': 'application/json', 'temp': 'test',
    'X-User-Agent': lgXUA(s.dev), 'Origin': LEGACY_FE, 'Referer': LEGACY_FE + '/',
  };
  if (s.token) headers['Authorization'] = s.token;
  if (params) {
    const enc = lgAES('encrypt', JSON.stringify(sortedJSON(params)), s.pk, s.piv, 'utf8', 'base64');
    full += (full.indexOf('?') >= 0 ? '&' : '?') + 'data=' + urlEncode(enc);
  }
  const r = await rq(base + full, { headers: headers, referer: LEGACY_FE + '/', timeout: 8000 });
  if (r.status < 200 || r.status >= 300) { if (r.status >= 500 && s) { s.api = ''; lgSave(); } throw host.error('E_HTTP', '黄果旧 API HTTP ' + r.status); }
  let env;
  try { env = JSON.parse(r.body); } catch (e) { throw host.error('E_PARSE', '黄果旧 API 返回非 JSON'); }
  const code = str(env.code);
  if (code && code !== '200' && code !== '0') {
    if (code === '5005' || code.indexOf('5005') === 0) {
      if (s) { s.token = ''; s.at = 0; lgSave(); }
      const s2 = await lgLogin();
      const headers2 = Object.assign({}, headers, { 'Authorization': s2.token });
      const r2 = await rq(base + full, { headers: headers2, referer: LEGACY_FE + '/', timeout: 8000 });
      let env2;
      try { env2 = JSON.parse(r2.body); } catch (e) { throw host.error('E_PARSE', '黄果旧 API 返回非 JSON'); }
      return lgUnpack(env2, s2);
    }
    throw host.error('E_UPSTREAM', code + ' ' + lgMask(env.msg, s));
  }
  return lgUnpack(env, s);
}

function sortedJSON(o) {
  const keys = Object.keys(o).sort();
  const parts = [];
  for (let i = 0; i < keys.length; i++) parts.push(JSON.stringify(keys[i]) + ':' + JSON.stringify(o[keys[i]]));
  return '{' + parts.join(',') + '}';
}

function lgRows(v) { return Array.isArray(v) ? v : (v && Array.isArray(v.list) ? v.list : []); }

async function lgTabs() {
  const s = lgLoad();
  if (s && Array.isArray(s.tabs) && s.tabsAt && Date.now() - s.tabsAt < 30 * 60 * 1000) return s.tabs;
  const data = await lgRequest('/api/app/playlet-tab/all', null);
  const rows = lgRows(data);
  const tabs = [];
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i] || {};
    const id = cleanID(str(r.id));
    if (id) tabs.push({ id: id, name: str(r.name) || id });
  }
  const cur = lgLoad();
  if (cur) { cur.tabs = tabs; cur.tabsAt = Date.now(); lgSave(); }
  return tabs;
}

async function lgImageCdn() {
  const s = lgLoad();
  if (s && s.img && s.imgAt && Date.now() - s.imgAt < 6 * 3600 * 1000) return s.img;
  try {
    const data = await lgRequest('/api/app/ping/domain/h5', null);
    const rows = data && Array.isArray(data.sourceList) ? data.sourceList : [];
    for (let i = 0; i < rows.length; i++) {
      const row = rows[i] || {};
      if (str(row.type).toUpperCase() !== 'IMAGE') continue;
      const doms = Array.isArray(row.domain) ? row.domain : [];
      let pick = '';
      for (let k = 0; k < doms.length; k++) if (int(doms[k] && doms[k].status) === 0) { pick = str(doms[k].url); break; }
      if (!pick && doms.length) pick = str(doms[0].url);
      if (pick) {
        const cur = lgLoad(); if (cur) { cur.img = pick.replace(/\/+$/, ''); cur.imgAt = Date.now(); lgSave(); }
        return pick.replace(/\/+$/, '');
      }
    }
  } catch (e) {}
  return (s && s.img) || LEGACY_IMG;
}

function absCover(raw, base) {
  const t = str(raw).trim();
  if (!t) return '';
  if (t.indexOf('http://') === 0) return 'https://' + t.slice(7);
  if (t.indexOf('https://') === 0) return t;
  if (t.indexOf('//') === 0) return 'https:' + t;
  return base.replace(/\/+$/, '') + '/' + t.replace(/^\/+/, '');
}

async function lgList(tabId, page, skipImg) {
  const data = await lgRequest('/api/app/playlet/home/tab/' + tabId, { pageNumber: String(page), pageSize: '20', tabSortType: '1' });
  const rows = lgRows(data);
  const s = lgLoad();
  const img = skipImg ? ((s && s.img) || LEGACY_IMG) : await lgImageCdn();
  const items = [];
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i] || {};
    const id = cleanID(str(r.id) || str(r.dramaId) || str(r.playletId));
    if (!id) continue;
    const title = str(r.title) || str(r.name) || str(r.dramaName) || id;
    items.push(makeItem('l:' + id, title, absCover(str(r.cover) || str(r.coverUrl) || str(r.image) || str(r.imageUrl) || str(r.img) || str(r.poster), img), str(r.remark) || str(r.statusText), []));
  }
  return { items: items, hasMore: rows.length >= 20 };
}

async function lgDetail(id) {
  const raw = (await lgRequest('/api/app/playlet/detail/' + id, null)) || {};
  let chapters = Array.isArray(raw.chapters) ? raw.chapters : [];
  if (!chapters.length) {
    try { chapters = lgRows(await lgRequest('/api/app/playlet-chapter/list/' + id, null)); } catch (e) {}
  }
  const eps = [];
  const seen = {};
  for (let i = 0; i < chapters.length; i++) {
    const c = chapters[i] || {};
    const media = str(c.videoUrl) || str(c.video_url) || str(c.url) || str(c.playUrl);
    if (!media) continue;
    const key = cleanID(str(c.id)) || cleanID(media);
    if (!key || seen[key]) continue;
    seen[key] = 1;
    const title = str(c.title) || str(c.name);
    let idx = episodeIndex(title, 0) || int(c.currentEpisode) || (i + 1);
    const label = title && !/^\d+$/.test(title) ? title : '第' + idx + '集';
    eps.push({ id: 'l1|' + key + '|' + media, name: label, duration: int(c.mediaTime), _ep: idx });
  }
  eps.sort(function (a, b) { return a._ep - b._ep; });
  if (!eps.length) throw host.error('E_NOT_FOUND', 'App 源没有可播放分集');
  const img = await lgImageCdn();
  return {
    title: str(raw.title) || str(raw.name) || ('黄果 ' + id),
    cover: absCover(str(raw.cover) || str(raw.coverUrl), img),
    desc: str(raw.desc) || str(raw.intro) || str(raw.description),
    tags: [], actors: [],
    lines: [{ name: 'App 源', episodes: eps.map(function (e) { return { id: e.id, name: e.name, duration: e.duration }; }) }],
  };
}

async function lgPlay(episodeId) {
  const p = episodeId.split('|');
  const media = p.length >= 3 ? p.slice(2).join('|') : '';
  if (!media) throw host.error('E_NOT_FOUND', 'App 源分集无效');
  const s = await lgSession();
  const base = await lgApiBase();
  const path = media.charAt(0) === '/' ? media.slice(1) : media;
  const url = base + '/api/app/vid/h5/m3u8/' + path + '?token=' + urlEncode(s.token) + '&c=' + urlEncode(LEGACY_CDN);
  return { streams: [{ url: url, format: 'hls', name: '高清', key: '', headers: { 'Referer': LEGACY_FE + '/', 'Origin': LEGACY_FE, 'X-User-Agent': lgXUA(s.dev) } }] };
}

// ================= routing =================
function splitSid(raw) {
  const id = str(raw);
  if (id.indexOf('v:') === 0) return { sub: 'video', ref: id.slice(2) };
  if (id.indexOf('l:') === 0) return { sub: 'legacy', ref: id.slice(2) };
  if (id.indexOf('ai:') === 0) return { sub: 'ai', ref: id.slice(3) };
  return { sub: 'ai', ref: id };
}

async function categories() {
  const cats = [];
  for (let i = 0; i < AI_CHANNELS.length; i++) cats.push({ id: 'ai|' + AI_CHANNELS[i].slug, name: AI_CHANNELS[i].name });
  for (let i = 0; i < VIDEO_CHANNELS.length; i++) cats.push({ id: 'video|' + VIDEO_CHANNELS[i].slug, name: VIDEO_CHANNELS[i].name });
  let tabs = [];
  const s = lgLoad();
  if (s && Array.isArray(s.tabs) && s.tabs.length) tabs = s.tabs;
  if (tabs.length) {
    for (let i = 0; i < tabs.length; i++) cats.push({ id: 'legacy|' + tabs[i].id, name: tabs[i].name });
  } else {
    cats.push({ id: 'legacy|', name: 'App 源' });
  }
  return cats;
}

async function category(arg) {
  const id = str(arg && arg.id);
  const page = Math.max(1, int(arg && arg.page) || 1);
  const parts = id.split('|');
  if (parts[0] === 'ai' && parts[1]) return await aiList(parts[1], page);
  if (parts[0] === 'video' && parts[1] != null) return await videoList(parts[1], page);
  if (parts[0] === 'legacy') {
    let tab = parts[1] || '';
    if (!tab) {
      const tabs = await lgTabs();
      tab = tabs.length ? tabs[0].id : '';
    }
    if (!tab) throw host.error('E_NOT_FOUND', 'App 源没有栏目');
    return await lgList(tab, page);
  }
  if (id.indexOf('cat:') === 0) {
    const old = id.split(':');
    return await aiList(old[1], page);
  }
  throw host.error('E_NOT_FOUND', '分类不存在');
}

async function home() {
  const cats = await categories();
  let items = [];
  try {
    const base = await ensureAIBase();
    const r = await rq(base + '/recommend/1/', { accept: 'text/html', referer: base + '/', timeout: 8000 });
    if (r.status === 200) items = aiCardsHTML(r.body, base + '/recommend/1/').slice(0, 30);
  } catch (e) {
    host.log && host.log('[黄果] 首页推荐失败：' + (e && e.message ? e.message : e));
  }
  return { categories: cats, items: items };
}

async function search(arg) {
  const q = str(arg && arg.keyword).trim();
  const page = Math.max(1, int(arg && arg.page) || 1);
  if (!q) return { items: [], page: page, hasMore: false };
  const groupsOut = [];
  let firstErr = null;
  const base = await ensureAIBase().catch(function (e) { if (!firstErr) firstErr = e; return ''; });
  if (base) {
    try {
      const j = await rqJson(base + '/api/search?q=' + urlEncode(q) + '&page=' + page + '&size=24', { referer: base + '/', timeout: 5000 });
      groupsOut.push(aiJSONCards(j, base));
    } catch (e) { if (!firstErr) firstErr = e; }
  }
  if (page === 1) {
    try { groupsOut.push(await videoSearch(q)); } catch (e) { if (!firstErr) firstErr = e; }
    try {
      const s = lgLoad();
      if (s && Array.isArray(s.tabs) && s.tabs.length && s.token) {
        const needle = q.toLowerCase();
        const scans = s.tabs.slice(0, 2);
        for (let i = 0; i < scans.length; i++) {
          try {
            const pg = await lgList(scans[i].id, 1, true);
            groupsOut.push(pg.items.filter(function (it) { return it.title.toLowerCase().indexOf(needle) >= 0; }));
          } catch (e) {}
        }
      }
    } catch (e) {}
  }
  if (!groupsOut.length && firstErr) throw firstErr;
  const merged = [];
  const seen = {};
  for (let i = 0; i < groupsOut.length; i++) {
    const g = groupsOut[i];
    for (let k = 0; k < g.length; k++) {
      const it = g[k];
      if (!it || seen[it.id] || blocked(it.title)) continue;
      seen[it.id] = 1;
      merged.push(it);
    }
  }
  return { items: merged, page: page, hasMore: page === 1 && merged.length >= 24 };
}

async function detail(arg) {
  const sid = splitSid(arg && arg.id);
  if (sid.sub === 'video') return await videoDetail(sid.ref);
  if (sid.sub === 'legacy') return await lgDetail(sid.ref);
  return await aiDetail(sid.ref);
}

async function play(arg) {
  const sid = splitSid(arg && arg.id);
  const eid = str(arg && arg.episodeId);
  if (sid.sub === 'video') return await videoPlay(eid);
  if (sid.sub === 'legacy') return await lgPlay(eid);
  return await aiPlay(sid.ref, int(eid));
}

export default {
  home: home,
  category: category,
  search: search,
  detail: detail,
  play: play,
};