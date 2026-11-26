'use strict';

const BASE = 'https://hongguoduanju.com';
const WEB_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';
const PURE_UA = 'com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)';
const TAB_UA = 'com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)';
const CLOCK_URL = 'https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/';
const PRELOAD_HOSTS = ['reading.snssdk.com', 'api5-normal-sinfonlinea.fqnovel.com', 'api5-normal-sinfonlineb.fqnovel.com'];
const PRELOAD_PATH = '/novel/player/multi_video_model/preload/v1';
const TAB_HOSTS = ['api5-normal-sinfonlineb.fqnovel.com', 'api5-normal-hl.fqnovel.com'];
const TAB_PATH = '/reading/bookapi/video_tab/multi_video_model/v1/';

const APP_QUERY = [
  ['aid', '8662'], ['app_name', 'novelread'], ['version_code', '74532'], ['version_name', '7.4.5.32'],
  ['update_version_code', '74532'], ['manifest_version_code', '74532'], ['os', 'android'], ['ssmix', 'a'],
  ['channel', 'update_64'], ['device_platform', 'android'], ['device_type', '25053RT47C'], ['device_brand', 'Redmi'],
  ['os_version', '16'], ['os_api', '36'], ['ac', 'wifi'], ['language', 'zh'], ['host_abi', 'arm64-v8a'],
];
const DEVICE_ID = '1624456208323876';
const IID = '1624456208327972';
const CDID = '91d56f7e-c027-4ce9-9a3c-229d59a13c3b';
const BASE_PAIRS = APP_QUERY.concat([['iid', IID], ['cdid', CDID], ['device_id', DEVICE_ID]]);

const CATEGORIES = [
  { id: 'rank:hot-drama', name: '热播榜', isRank: true },
  { id: 'rank:new-drama', name: '新剧榜', isRank: true },
  { id: 'type:real-drama:romance', name: '都市爱情' },
  { id: 'type:real-drama:comeback', name: '逆袭' },
  { id: 'type:real-drama:suspense', name: '悬疑' },
  { id: 'type:real-drama:costume', name: '古装' },
  { id: 'type:real-drama:period', name: '年代' },
  { id: 'type:real-drama:cute-kids', name: '萌宝' },
  { id: 'type:real-drama:comedy', name: '喜剧' },
  { id: 'type:real-drama:growth', name: '成长' },
  { id: 'type:comic-drama:romance', name: '漫剧·爱情' },
  { id: 'type:ai-drama:romance', name: 'AI短剧·爱情' },
];

const PROFILE_B64 = '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';

const EMPTY = new Uint8Array(0);
const M64 = 0xffffffffffffffffn;

function u32(x) { return x >>> 0; }
function le32(b, o) { return (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0; }
function be32(b, o) { return ((b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]) >>> 0; }
function leBytes(v) { v = v >>> 0; const o = new Uint8Array(4); o[0] = v & 255; o[1] = (v >>> 8) & 255; o[2] = (v >>> 16) & 255; o[3] = (v >>> 24) & 255; return o; }
function beBytes(v) { v = v >>> 0; const o = new Uint8Array(4); o[0] = (v >>> 24) & 255; o[1] = (v >>> 16) & 255; o[2] = (v >>> 8) & 255; o[3] = v & 255; return o; }
function concat() {
  let n = 0;
  for (let i = 0; i < arguments.length; i++) n += arguments[i].length;
  const out = new Uint8Array(n);
  let at = 0;
  for (let i = 0; i < arguments.length; i++) { out.set(arguments[i], at); at += arguments[i].length; }
  return out;
}
function reverseBytes(b) {
  const o = new Uint8Array(b.length);
  for (let i = 0; i < b.length; i++) o[i] = b[b.length - 1 - i];
  return o;
}
function hexToBytes(h) {
  const n = h.length >> 1, o = new Uint8Array(n);
  for (let i = 0; i < n; i++) o[i] = parseInt(h.slice(i * 2, i * 2 + 2), 16);
  return o;
}
function bytesToHex(b) {
  let s = '';
  for (let i = 0; i < b.length; i++) s += (b[i] < 16 ? '0' : '') + b[i].toString(16);
  return s;
}
function asciiBytes(s) {
  const o = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) o[i] = s.charCodeAt(i) & 255;
  return o;
}
function asciiString(b) {
  let s = '';
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
  return s;
}
function md5Hex(b) { return host.crypto.md5(bytesToHex(b), { in: 'hex', out: 'hex' }); }
function md5Bytes(b) { return hexToBytes(md5Hex(b)); }
function b64(b) { return host.codec.convert(bytesToHex(b), 'hex', 'base64'); }
function rol32(v, n) { n &= 31; v >>>= 0; return n === 0 ? v : ((v << n) | (v >>> (32 - n))) >>> 0; }
function ror32(v, n) { n &= 31; v >>>= 0; return n === 0 ? v : ((v >>> n) | (v << (32 - n))) >>> 0; }
function ror64(v, n) { n = BigInt(n & 63); v &= M64; return n === 0n ? v : ((v >> n) | (v << (64n - n))) & M64; }
function rdLE64(b, o) { return BigInt(le32(b, o)) | (BigInt(le32(b, o + 4)) << 32n); }
function wrLE64(v) { v &= M64; return concat(leBytes(Number(v & 0xffffffffn)), leBytes(Number((v >> 32n) & 0xffffffffn))); }
function getIV(iv, data) {
  let v = iv >>> 0;
  for (let i = 0; i < data.length; i++) {
    const b = data[i];
    if ((i & 1) === 0) v = ((v >>> 4) ^ v ^ (v << 6) ^ b) >>> 0;
    else v = ~((v >>> 7) ^ v ^ (b | (v << 12))) >>> 0;
  }
  return v;
}
function sumMD5(data) {
  let check = 0x20220420 >>> 0;
  for (let i = 0; i < 12; i++) {
    let temp;
    if ((i & 1) === 0) { temp = ((check >>> 3) ^ check) >>> 0; check = (data[i] ^ (check << 7)) >>> 0; }
    else { temp = ((check >>> 5) ^ check) >>> 0; check = ~(data[i] | (check << 11)) >>> 0; }
    check = (check ^ temp) >>> 0;
  }
  return ((check | 4) ^ 0x01000000) >>> 0;
}

const SM3_IV = [0x7380166F, 0x4914B2B9, 0x172442D7, 0xDA8A0600, 0xA96F30BC, 0x163138AA, 0xE38DEE4D, 0xB0FB0E4E];
function sm3(data) {
  const v = SM3_IV.slice();
  const len = data.length;
  const total = ((len + 1 + 8 + 63) >> 6) << 6;
  const padded = new Uint8Array(total);
  padded.set(data, 0);
  padded[len] = 0x80;
  const bits = BigInt(len) * 8n;
  padded.set(beBytes(Number((bits >> 32n) & 0xffffffffn)), total - 8);
  padded.set(beBytes(Number(bits & 0xffffffffn)), total - 4);
  const w = new Array(68);
  const w1 = new Array(64);
  for (let off = 0; off < total; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = be32(padded, off + i * 4);
    for (let j = 16; j < 68; j++) w[j] = (p1((w[j - 16] ^ w[j - 9] ^ rol32(w[j - 3], 15)) >>> 0) ^ rol32(w[j - 13], 7) ^ w[j - 6]) >>> 0;
    for (let j = 0; j < 64; j++) w1[j] = (w[j] ^ w[j + 4]) >>> 0;
    let a = v[0], b = v[1], c = v[2], d = v[3], e = v[4], f = v[5], g = v[6], h = v[7];
    for (let j = 0; j < 64; j++) {
      const tj = j < 16 ? 0x79CC4519 : 0x7A879D8A;
      const ss1 = rol32((rol32(a, 12) + e + rol32(tj, j)) >>> 0, 7);
      const ss2 = (ss1 ^ rol32(a, 12)) >>> 0;
      const ff = j < 16 ? (a ^ b ^ c) >>> 0 : ((a & b) | (a & c) | (b & c)) >>> 0;
      const gg = j < 16 ? (e ^ f ^ g) >>> 0 : ((e & f) | (~e & g)) >>> 0;
      const tt1 = (ff + d + ss2 + w1[j]) >>> 0;
      const tt2 = (gg + h + ss1 + w[j]) >>> 0;
      d = c; c = rol32(b, 9); b = a; a = tt1;
      h = g; g = rol32(f, 19); f = e; e = p0(tt2);
    }
    v[0] = (v[0] ^ a) >>> 0; v[1] = (v[1] ^ b) >>> 0; v[2] = (v[2] ^ c) >>> 0; v[3] = (v[3] ^ d) >>> 0;
    v[4] = (v[4] ^ e) >>> 0; v[5] = (v[5] ^ f) >>> 0; v[6] = (v[6] ^ g) >>> 0; v[7] = (v[7] ^ h) >>> 0;
  }
  return concat(beBytes(v[0]), beBytes(v[1]), beBytes(v[2]), beBytes(v[3]),
    beBytes(v[4]), beBytes(v[5]), beBytes(v[6]), beBytes(v[7]));
}
function p0(x) { return (x ^ rol32(x, 9) ^ rol32(x, 17)) >>> 0; }
function p1(x) { return (x ^ rol32(x, 15) ^ rol32(x, 23)) >>> 0; }

function varint(value) {
  let v = BigInt(value);
  if (v < 0n) v &= M64;
  const out = [];
  while (v > 0x7fn) { out.push(Number(v & 0x7fn) | 0x80); v >>= 7n; }
  out.push(Number(v));
  return Uint8Array.from(out);
}
function pKey(tag, wire) { return varint((BigInt(tag) << 3n) | BigInt(wire)); }
function pBytes(tag, b) { return b.length === 0 ? EMPTY : concat(pKey(tag, 2), varint(b.length), b); }
function pStr(tag, s) { return pBytes(tag, asciiBytes(String(s))); }
function pSInt(tag, value) {
  let v = BigInt(value);
  const zz = ((v << 1n) ^ (v >> 63n)) & M64;
  return concat(pKey(tag, 0), varint(zz));
}

function xmxor(data, key) {
  const n = data.length;
  const e = new Uint8Array(n);
  for (let i = 0; i < n; i++) {
    const at = (i * 4) & 28;
    const d0 = key[at], d1 = key[at + 1];
    const sw = ((data[i] << 4) | (data[i] >> 4)) & 0xff;
    let d2 = (sw + d0) & 0xff;
    d2 = ((~d2 & 0xff) ^ d1) & 0xff;
    d2 = ((d2 << 3) | (d2 >> 5)) & 0xff;
    d2 = (d2 + d1) & 0xff;
    d2 = (d2 ^ d0) & 0xff;
    e[n - 1 - i] = (~d2) & 0xff;
  }
  const last = e[n - 1] ^ e[n - 2];
  const first = e[0];
  e[0] = ((~last & 0xff) + first) & 0xff;
  e[1] = ((e[0] ^ e[n - 1] ^ 254) + e[1]) & 0xff;
  const shifted = ((e[1] << 3) | (e[1] >> 5)) & 0xff;
  const fix2 = (((last - first) & 0xff) ^ (shifted ^ 2)) & 0xff;
  e[2] = (e[2] + fix2) & 0xff;
  for (let i = 0; i < n - 4; i++) {
    const sh = ((e[i + 2] << 3) | (e[i + 2] >> 5)) & 0xff;
    const temp = (sh ^ e[i + 1] ^ ((i + 3) & 0xff)) & 0xff;
    e[i + 3] = ((~temp & 0xff) + e[i + 3]) & 0xff;
  }
  e[n - 1] = (e[n - 1] ^ e[n - 2]) & 0xff;
  let sum = 0;
  for (let i = 1; i < n; i++) sum = (sum + e[i]) & 0xffffffff;
  e[0] = ((e[0] ^ e[1]) + (sum & 0xff)) & 0xff;
  return e;
}

function keyHash(signKey, random) {
  const hash = sm3(concat(signKey, leBytes(random), signKey));
  const d1 = (random >>> 16) & 255;
  let d2 = (d1 << 11) | (random >>> 24);
  d2 = (d2 ^ (d1 >>> 5)) >>> 0;
  d2 = (d2 ^ d1) >>> 0;
  d2 = ~d2 >>> 0;
  return [hash, leBytes(d2)];
}

const CON = [[2, 0, 3, 1], [1, 3, 2, 0], [3, 2, 1, 0], [2, 3, 1, 0]];
const ORDER = [
  [0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11],
  [0, 9, 14, 11, 4, 13, 2, 3, 8, 1, 6, 15, 12, 5, 10, 7],
  [0, 9, 14, 3, 4, 13, 2, 15, 8, 1, 6, 11, 12, 5, 10, 7],
  [0, 9, 14, 15, 4, 13, 2, 3, 8, 1, 6, 11, 12, 5, 10, 7],
];
function transform(plain, key, iv, branch) {
  const hg = profile();
  const box = hg.boxes[branch];
  const ini = le32(hg.initial[branch], 0);
  const expanded = new Uint8Array(48);
  for (let i = 0; i < 16; i++) expanded[i] = key[i] ^ hg.initial[branch][i % 4];
  let rounds = 8;
  for (let i = 4; i < 12; i++) {
    const at = 4 * (i - 1);
    let a = expanded[at], b = expanded[at + 1], c = expanded[at + 2], d = expanded[at + 3];
    if ((i & 3) === 0) {
      const old = a;
      a = ((ini >>> (rounds & 24)) ^ box[b]) & 0xff;
      b = box[c]; c = box[d]; d = box[old];
    }
    rounds += 2;
    const vals = [a, b, c, d];
    for (let j = 0; j < 4; j++) expanded[at + 4 + j] = vals[j] ^ expanded[at - 12 + j];
  }
  const pad = 16 - (plain.length % 16);
  const source = concat(plain, new Uint8Array(pad).fill(pad));
  let previous = new Uint8Array(iv);
  const out = [];
  const co = CON[branch];
  for (let at = 0; at < source.length; at += 16) {
    let state = new Uint8Array(16);
    for (let i = 0; i < 16; i++) {
      const keyIndex = 4 * Math.floor(i / 4) + co[i % 4];
      state[i] = source[at + i] ^ previous[i] ^ expanded[keyIndex];
    }
    for (let round = 1; round <= 2; round++) {
      let old = new Uint8Array(state);
      for (let i = 0; i < 16; i++) {
        const index = co[Math.floor(i / 4)] * 4 + (i % 4);
        state[i] = box[old[index]];
      }
      old = new Uint8Array(state);
      for (let i = 0; i < 16; i++) state[i] = old[ORDER[branch][i]];
      if (round === 1) {
        old = new Uint8Array(state);
        for (let i = 0; i < 16; i++) {
          const index = 4 * Math.floor(i / 4) + co[i % 4];
          state[i] = old[index];
        }
        for (let j = 0; j < 4; j++) {
          const total = state[j] ^ state[4 + j] ^ state[8 + j] ^ state[12 + j];
          const first = state[j];
          for (let i = 0; i < 4; i++) {
            const next = i < 3 ? state[(i + 1) * 4 + j] : first;
            const x = state[i * 4 + j] ^ next;
            const xt = ((x << 1) ^ ((x & 128) !== 0 ? 0x1b : 0)) & 0xff;
            state[i * 4 + j] = (state[i * 4 + j] ^ total ^ xt) & 0xff;
          }
        }
      }
      for (let i = 0; i < 16; i++) state[i] = (state[i] ^ expanded[16 * round + 4 * Math.floor(i / 4) + co[i % 4]]) & 0xff;
    }
    previous = new Uint8Array(16);
    for (let i = 0; i < 16; i++) previous[i] = state[i] ^ expanded[16 + i];
    out.push(previous);
  }
  return concat.apply(null, out);
}

function expandWords(data) {
  const di = [];
  for (let off = 0; off + 4 <= data.length; off += 4) di.push(be32(data, off));
  const di0 = di[0];
  for (let i = 0; i < 112; i++) {
    const di1 = di[i + 1], di14 = di[i + 14];
    const r1 = (rol32(di1, 14) ^ rol32(di1, 25) ^ (di1 >>> 3)) >>> 0;
    const r2 = (rol32(di14, 13) ^ rol32(di14, 15) ^ (di14 >>> 10)) >>> 0;
    di.push(((i === 0 ? di0 : di[i]) + di[i + 9] + r1 + r2) >>> 0);
  }
  return di;
}

function mixRounds(st, r, di, tt, ivV1) {
  let s = st.slice();
  const x = function (k) { return r[k] | 0; };
  for (let i = 0; i < (r[0] >>> 0); i++) {
    const base = (ivV1 + i) >>> 0;
    const w = di[base & 127];
    const n1 = (((s[x(3)] ^ s[x(4)]) & s[x(1)]) ^ s[x(3)]) >>> 0;
    const n2 = (rol32(s[x(1)], 26) ^ rol32(s[x(1)], 21) ^ rol32(s[x(1)], 7)) >>> 0;
    const n4 = (w + n1 + n2 + tt[base & 63] + s[x(5)]) >>> 0;
    const n5 = (rol32(s[x(2)], 30) ^ rol32(s[x(2)], 19) ^ rol32(s[x(2)], 10)) >>> 0;
    const n6 = ((s[x(2)] & s[x(6)]) | ((s[x(2)] | s[x(6)]) & s[x(7)])) >>> 0;
    const n7 = (n5 + n6) >>> 0;
    const old = s[x(9)];
    s = [s[s.length - 1]].concat(s.slice(0, s.length - 1));
    s[x(10)] = (n7 + n4) >>> 0;
    s[x(8)] = (old + n4) >>> 0;
  }
  return s;
}

function hashF13V5(qhash, digest, ts) {
  const hg = profile();
  const tsBytes = leBytes(ts);
  const iv = getIV(getIV(getIV(0x20230928, qhash), digest), tsBytes);
  const ivV1 = 0x9d4a97c2 >>> 0;
  const count = (ivV1 + ts) >>> 0;
  const tt = hg.factorTable.map(function (v) { return ror32(v, (count + 1) & 31); });
  const shift = (count + 2) & 7;
  const pad = Array.from(hg.padding).map(function (v) { return ((v | (v << 8)) >>> shift) & 255; });
  const data = concat(qhash, digest, tsBytes, Uint8Array.from(pad), Uint8Array.from([0, 0, 0, 0, 0, 0, 1, 0xa0]));
  const di = expandWords(data);
  const ini = hg.factorIV.map(function (v) { return ror32(v, count & 31); });
  const chosen = [96, 1, 3, 4, 0, 6, 5, 2, 0, 7, 7];
  const d = mixRounds(ini, chosen, di, tt, ivV1);
  const ret = concat.apply(null, d.slice(0, 8).map(function (v, i) { return beBytes((v + ini[i]) >>> 0); }));
  const folded = new Uint8Array(16);
  for (let i = 0; i < 16; i++) folded[i] = ret[i] ^ ret[i + 16];
  const check = (sumMD5(folded) | 0x20) >>> 0;
  return concat(folded, leBytes(check));
}

function helios(t, license, aid, random) {
  const key = asciiBytes(md5Hex(concat(leBytes(random), asciiBytes(aid))));
  const keys = new Array(35);
  keys[0] = rdLE64(key, 0);
  let a = keys[0], b = rdLE64(key, 8);
  const queue = [rdLE64(key, 16), rdLE64(key, 24)];
  for (let i = 0; i < 34; i++) {
    let x = (ror64(b, 8) + a) & M64;
    x ^= BigInt(i);
    queue.push(x);
    x = (x ^ ror64(a, 61)) & M64;
    keys[i + 1] = x; a = x; b = queue.shift();
  }
  const raw = asciiBytes(String(t) + '-' + license + '-' + aid);
  const n = 16 - (raw.length % 16);
  const plain = concat(raw, new Uint8Array(n).fill(n));
  const out = [leBytes(random)];
  for (let at = 0; at < plain.length; at += 16) {
    let l = rdLE64(plain, at), r = rdLE64(plain, at + 8);
    for (let i = 0; i < 34; i++) {
      r = keys[i] ^ ((l + ror64(r, 8)) & M64);
      l = (r ^ ror64(l, 61)) & M64;
    }
    out.push(wrLE64(l), wrLE64(r));
  }
  return b64(concat.apply(null, out));
}

let PROCESS_NONCE = '';
let PROCESS_STARTED = 0;

function medusaV5(qhash, digest, ts, values, msgRand, nonce, packedRand, pNonce, pStarted, field21) {
  const hg = profile();
  const f13 = hashF13V5(qhash, digest, ts);
  const flags = new Uint8Array(8);
  const version = values['version_name'] || '';
  const did = values['device_id'] || '';
  const osVer = values['os_version'] || '';
  const devType = values['device_type'] || '';
  const channel = values['channel'] || '';
  const n = function (tag, v) { return pSInt(tag, v); };
  const s = function (tag, v) { return pStr(tag, v); };
  let deviceInfo = concat(n(1, 1), n(2, 2), s(3, '8662'), s(4, did));
  deviceInfo = concat(deviceInfo, s(5, pNonce), s(6, '!noperm!'), s(12, 'Asia/Shanghai,8'), s(13, 'zh_CN'));
  deviceInfo = concat(deviceInfo, s(22, osVer), n(25, pStarted), s(30, devType), s(31, 'HONOR'), s(32, devType), s(34, 'HONOR'));
  const env = concat(s(6, 'v04.10.03.04-bugfix'), s(8, channel), pBytes(12, deviceInfo), s(14, version));
  let msg = concat(pBytes(1, hg.header), n(2, 5), n(3, msgRand), s(4, '8662'));
  msg = concat(msg, s(5, did), s(6, '646637794'), s(7, version), s(8, 'v04.10.03-ml-android'));
  msg = concat(msg, n(9, 0x040a0300), pBytes(10, flags), n(12, ts), pBytes(13, f13));
  msg = concat(msg, pBytes(14, qhash.subarray(0, 6)));
  msg = concat(msg, pBytes(15, concat(n(1, 1), n(2, 1), n(3, 1), n(5, 1))));
  msg = concat(msg, n(17, ts), s(20, 'none'));
  if (field21 !== null && field21 !== undefined) msg = concat(msg, n(21, field21));
  msg = concat(msg, pBytes(23, env));
  msg = concat(msg, s(24, '{"cmr":16777216,"cmr2":16777216,"un_h":11,"vpn":0,"kd":0,"dyn":"","do":1,"tk":true}'));
  const pair = keyHash(hg.material, nonce);
  let tr = reverseBytes(concat(flags, xmxor(msg, pair[0])));
  const seed = pair[1];
  for (let i = 0; i < tr.length; i++) tr[i] ^= seed[(~i) & 3];
  const check = (0x18000001 | ((qhash[0] & 63) << 14) | ((f13[0] & 63) << 8)) >>> 0;
  let packed = concat(Uint8Array.from([0xca]), leBytes(packedRand), leBytes(check), tr,
    Uint8Array.from([(nonce >>> 16) & 255, (nonce >>> 24) & 255]));
  if (packed.length < 248) throw host.error('E_INTERNAL', 'medusa 长度异常');
  const lane = [6, 5, 3, 4, 2, 7, 1, 0];
  const bits = [1, 7, 3, 6, 2, 0, 4, 5];
  const plain = new Uint8Array(31);
  for (let i = 0; i < 248; i++) {
    plain[Math.floor(i / 8)] |= ((packed[i] >>> lane[i % 8]) & 1) << bits[i % 8];
  }
  const channelBytes = transform(plain, md5Bytes(hg.material.subarray(0, 16)), md5Bytes(hg.material.subarray(16, 32)), ts & 3);
  const bodyOut = new Uint8Array(packed);
  for (let i = 0; i < 248; i++) {
    const clearMask = ~(1 << lane[i % 8]) & 0xff;
    const bit = ((channelBytes[Math.floor(i / 8)] >>> bits[i % 8]) & 1) << lane[i % 8];
    bodyOut[i] = (bodyOut[i] & clearMask) | bit;
  }
  const hdr = concat(leBytes(5), hg.header);
  const mask = leBytes(ts);
  for (let i = 0; i < hdr.length; i++) hdr[i] ^= mask[i & 3];
  return b64(concat(hdr, Uint8Array.from([nonce & 255, (nonce >>> 8) & 255, 0, 1, channelBytes[31]]), bodyOut));
}

function encV5(str) {
  let out = '';
  for (let i = 0; i < str.length; i++) {
    const c = str.charCodeAt(i);
    const keep = (c >= 65 && c <= 90) || (c >= 97 && c <= 122) || (c >= 48 && c <= 57) ||
      c === 45 || c === 95 || c === 46 || c === 126 || c === 42;
    out += keep ? str[i] : '%' + (c < 16 ? '0' : '') + c.toString(16).toUpperCase();
  }
  return out;
}
function encodeV5Query(pairs) {
  const out = [];
  for (let i = 0; i < pairs.length; i++) out.push(encV5(pairs[i][0]) + '=' + encV5(pairs[i][1]));
  return out.join('&');
}

function signV5(pairs, nowMillis, body, withBody, entropy) {
  const hg = profile();
  const now = Math.round(nowMillis);
  const ent = entropy;
  const digest = md5Bytes(body);
  const ts = Math.floor(now / 1000) >>> 0;
  const tsBytes = leBytes(ts);
  const base = [];
  for (let i = 0; i < pairs.length; i++) if (pairs[i][0] !== '_rticket') base.push(pairs[i]);
  const baseQuery = encodeV5Query(base);
  for (let offset = 0; offset < 1024; offset++) {
    const ticket = now + offset;
    const query = baseQuery + '&_rticket=' + ticket;
    const qhash = sm3(asciiBytes(query));
    const low = getIV(getIV(getIV(0x20230928, qhash), digest), tsBytes) & 15;
    if (low !== 12) continue;
    const values = {};
    for (let i = 0; i < base.length; i++) values[base[i][0]] = base[i][1];
    values['_rticket'] = String(ticket);
    const headers = {
      'x-khronos': String(ts),
      'x-helios': helios(BigInt(ts), '646637794', '8662', le32(ent, 4)),
      'x-medusa': medusaV5(qhash, digest, ts, values, le32(ent, 0), le32(ent, 8), le32(ent, 12),
        PROCESS_NONCE, PROCESS_STARTED, 312),
    };
    if (withBody) {
      headers['Content-Type'] = 'application/json; charset=utf-8';
      headers['x-ss-stub'] = bytesToHex(digest).toUpperCase();
    }
    return { query: query, headers: headers };
  }
  throw host.error('E_INTERNAL', '签名档位搜索失败');
}

function profile() {
  if (!PROFILE_CACHE) {
    const b = hexToBytes(host.codec.convert(PROFILE_B64, 'base64', 'hex'));
    const boxes = [], initial = [], factorIV = [], factorTable = [];
    for (let i = 0; i < 4; i++) boxes.push(b.subarray(48 + i * 256, 48 + (i + 1) * 256));
    for (let i = 0; i < 4; i++) initial.push(b.subarray(1072 + i * 4, 1076 + i * 4));
    for (let i = 0; i < 8; i++) factorIV.push(le32(b, 3136 + i * 4));
    for (let i = 0; i < 64; i++) factorTable.push(le32(b, 3168 + i * 4));
    PROFILE_CACHE = {
      material: b.subarray(0, 32), header: b.subarray(32, 48), boxes: boxes, initial: initial,
      factorIV: factorIV, factorTable: factorTable, padding: b.subarray(3424, 3428),
    };
  }
  return PROFILE_CACHE;
}
let PROFILE_CACHE = null;

function ensureProcess() {
  if (!PROCESS_NONCE) {
    PROCESS_NONCE = host.crypto.randomHex(18);
    PROCESS_STARTED = Date.now();
  }
}

let clockAt = 0, clockOffset = 0;
function parseHTTPDate(s) {
  const m = /^[A-Za-z]{3}, (\d{2}) ([A-Za-z]{3}) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT$/.exec(String(s || '').trim());
  if (!m) return NaN;
  const mon = { Jan: 0, Feb: 1, Mar: 2, Apr: 3, May: 4, Jun: 5, Jul: 6, Aug: 7, Sep: 8, Oct: 9, Nov: 10, Dec: 11 }[m[2]];
  if (mon === undefined) return NaN;
  return Date.UTC(Number(m[3]), mon, Number(m[1]), Number(m[4]), Number(m[5]), Number(m[6]));
}
async function signingOffset() {
  const now = Date.now();
  if (clockAt && now - clockAt < 60000) return clockOffset;
  const t0 = Date.now();
  let offset = 0;
  try {
    const r = await host.http.request({
      url: CLOCK_URL, method: 'HEAD',
      headers: { 'Cache-Control': 'no-cache', 'User-Agent': PURE_UA }, timeout: 5000,
    });
    const t1 = Date.now();
    const age = r.headers ? r.headers['age'] : null;
    const server = parseHTTPDate(r.headers ? r.headers['date'] : '');
    if (!isNaN(server) && (!age || age === '0') && t1 - t0 <= 10000) {
      offset = server + 500 - (t0 + t1) / 2;
    }
  } catch (e) {
    host.log('时钟校准失败：' + (e && e.message ? e.message : e));
  }
  clockAt = now;
  clockOffset = offset;
  return offset;
}

function webGet(url) {
  return host.http.request({
    url: url,
    headers: {
      'User-Agent': WEB_UA,
      'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      'Accept-Language': 'zh-CN,zh;q=0.9',
    },
    timeout: 20000,
  }).then(function (r) {
    if (r.status !== 200) throw host.error(r.status === 404 ? 'E_NOT_FOUND' : 'E_HTTP', '网页返回 HTTP ' + r.status);
    return String(r.body == null ? '' : r.body);
  });
}

function routerData(html) {
  const at = html.indexOf('_ROUTER_DATA');
  if (at < 0) return null;
  const start = html.indexOf('{', at);
  if (start < 0) return null;
  let depth = 0, inStr = false, esc = false;
  for (let i = start; i < html.length; i++) {
    const c = html.charCodeAt(i);
    if (inStr) {
      if (esc) esc = false;
      else if (c === 92) esc = true;
      else if (c === 34) inStr = false;
    } else if (c === 34) inStr = true;
    else if (c === 123) depth++;
    else if (c === 125) {
      depth--;
      if (depth === 0) {
        try { return JSON.parse(html.slice(start, i + 1)); } catch (e) { return null; }
      }
    }
  }
  return null;
}

function rankItems(list) {
  const out = [];
  for (let i = 0; i < (list || []).length; i++) {
    const x = list[i] || {};
    const id = String(x.seriesId || x.id || '');
    const title = x.title || '';
    if (!id || !title) continue;
    const remarks = x.heatText || x.scoreText || (x.episodeCount ? '全' + x.episodeCount + '集' : '');
    out.push({
      id: id, title: title, cover: x.cover || '', remarks: remarks,
      tags: Array.isArray(x.tags) ? x.tags : [], badges: Array.isArray(x.statusTags) ? x.statusTags : [],
    });
  }
  return out;
}

function categoryItems(list) {
  const out = [];
  for (let i = 0; i < (list || []).length; i++) {
    const x = list[i] || {};
    const id = String(x.series_id || '');
    const title = x.series_name || '';
    if (!id || !title) continue;
    const actors = [];
    for (let j = 0; j < (x.celebrities || []).length; j++) {
      const c = x.celebrities[j];
      if (c && c.nickname) actors.push(c.nickname);
    }
    out.push({
      id: id, title: title, cover: x.series_cover || '',
      remarks: x.episode_right_text || (x.episode_cnt ? '全' + x.episode_cnt + '集' : ''),
      tags: Array.isArray(x.tags) ? x.tags : [], actors: actors,
    });
  }
  return out;
}

async function home() {
  try {
    const rd = routerData(await webGet(BASE + '/rank/hot-drama'));
    const page = rd && rd.loaderData ? rd.loaderData['rank_hot-drama/page'] : null;
    const content = page && page.content ? page.content : page;
    const items = rankItems(content ? content.rankList : null);
    if (items.length) return { categories: CATEGORIES, items: items };
  } catch (e) {
    host.log('热播榜首页失败：' + (e && e.message ? e.message : e));
  }
  const rd = routerData(await webGet(BASE + '/category/real-drama/romance?page=1'));
  const v = rd && rd.loaderData ? rd.loaderData['category_$'] : null;
  if (!v) throw host.error('E_PARSE', '红果网页结构已变化');
  return { categories: CATEGORIES, items: categoryItems(v.recommendList) };
}

async function category(arg) {
  const id = String(arg && arg.id ? arg.id : '');
  const p = Math.max(1, parseInt(arg && arg.page, 10) || 1);
  if (id.indexOf('rank:') === 0) {
    const name = id.slice(5);
    if (!/^(hot|new)-drama$/.test(name)) throw host.error('E_NOT_FOUND', '榜单项不存在');
    const url = BASE + '/rank/' + name + (p > 1 ? '?page=' + p : '');
    const rd = routerData(await webGet(url));
    const page = rd && rd.loaderData ? rd.loaderData['rank_' + name + '/page'] : null;
    const content = page && page.content ? page.content : page;
    if (!content) throw host.error('E_PARSE', '榜单结构已变化');
    const pg = content.pagination || (page && page.pagination) || {};
    const total = parseInt(pg.totalPages, 10) || 0;
    return { items: rankItems(content.rankList), page: p, hasMore: total > 0 && p < total };
  }
  const parts = id.split(':');
  if (parts.length !== 3 || parts[0] !== 'type') throw host.error('E_NOT_FOUND', '分类不存在');
  if (!/^[a-z-]+$/.test(parts[1]) || !/^[a-z-]+$/.test(parts[2])) throw host.error('E_NOT_FOUND', '分类不存在');
  const rd = routerData(await webGet(BASE + '/category/' + parts[1] + '/' + parts[2] + '?page=' + p));
  const v = rd && rd.loaderData ? rd.loaderData['category_$'] : null;
  if (!v) throw host.error('E_PARSE', '分类结构已变化');
  const pg = v.pagination || {};
  const total = parseInt(pg.totalPages, 10) || 0;
  return { items: categoryItems(v.recommendList), page: p, hasMore: total > 0 && p < total };
}

async function detail(arg) {
  const sid = String(arg && arg.id ? arg.id : '').trim();
  if (!/^\d{1,30}$/.test(sid)) throw host.error('E_NOT_FOUND', '剧集编号无效');
  const rd = routerData(await webGet(BASE + '/player/' + sid));
  const ld = rd && rd.loaderData ? rd.loaderData : null;
  let page = ld ? ld['player_(series_id)/page'] : null;
  if (!page && ld) {
    const keys = Object.keys(ld);
    for (let i = 0; i < keys.length; i++) {
      if (keys[i].indexOf('player_') === 0 && ld[keys[i]] && ld[keys[i]].seriesDetail) { page = ld[keys[i]]; break; }
    }
  }
  const sd = page && page.seriesDetail ? page.seriesDetail : null;
  if (!sd) throw host.error('E_PARSE', '详情结构已变化');
  const vids = Array.isArray(sd.vid_list) ? sd.vid_list : [];
  if (!vids.length) throw host.error('E_PARSE', '该剧没有可播放分集');
  const episodes = [];
  for (let i = 0; i < vids.length; i++) {
    const vid = String(vids[i] || '');
    if (vid) episodes.push({ id: vid, name: '第' + (i + 1) + '集' });
  }
  const actors = [];
  for (let i = 0; i < (sd.celebrities || []).length; i++) {
    const c = sd.celebrities[i];
    if (c && c.nickname) actors.push(c.nickname);
  }
  return {
    title: sd.series_name || '',
    cover: sd.series_cover || '',
    desc: sd.series_intro || '',
    tags: Array.isArray(sd.tags) ? sd.tags : [],
    actors: actors,
    lines: [{ name: '红果短剧', episodes: episodes }],
  };
}

function modelOf(entry) {
  if (!entry) return null;
  const v = entry.video_model;
  if (typeof v === 'string') {
    try { return JSON.parse(v); } catch (e) { return null; }
  }
  return v && typeof v === 'object' ? v : null;
}

function intOf(v, d) {
  const n = parseInt(v, 10);
  return isNaN(n) ? d : n;
}

function materialize(model) {
  const listRaw = model.video_list;
  const entries = [];
  if (Array.isArray(listRaw)) {
    for (let i = 0; i < listRaw.length; i++) entries.push(listRaw[i]);
  } else if (listRaw && typeof listRaw === 'object') {
    const ks = Object.keys(listRaw);
    for (let i = 0; i < ks.length; i++) entries.push(listRaw[ks[i]]);
  }
  const choices = [];
  let vc2 = 0;
  for (let i = 0; i < entries.length; i++) {
    const v = entries[i] || {};
    const meta = v.video_meta || {};
    const enc = v.encrypt_info || {};
    const codec = String(meta.codec_type || v.codec_type || '').toLowerCase();
    if (codec === 'bytevc2' || String(v.gear_des_key || '').toLowerCase().indexOf('bytevc2') >= 0) { vc2++; continue; }
    const urls = [];
    if (typeof v.main_url === 'string') urls.push(v.main_url);
    if (typeof v.backup_url === 'string') urls.push(v.backup_url);
    if (Array.isArray(v.backup_url_list)) {
      for (let j = 0; j < v.backup_url_list.length; j++) {
        if (typeof v.backup_url_list[j] === 'string') urls.push(v.backup_url_list[j]);
      }
    }
    let primary = '';
    for (let j = 0; j < urls.length; j++) {
      if (urls[j].indexOf('https://') === 0) { primary = urls[j]; break; }
    }
    if (!primary) continue;
    let key = '';
    const spade = String(enc.spade_a || v.spade_a || '');
    if (spade || enc.encrypt === true || enc.encryption_method === 'cenc-aes-ctr') {
      key = contentKey(spade);
      if (!key) continue;
    }
    const w = intOf(meta.vwidth, 0) || intOf(v.vwidth, 0);
    const h = intOf(meta.vheight, 0) || intOf(v.vheight, 0);
    let height = parseInt(String(meta.definition || v.definition || '').toLowerCase().replace('p', ''), 10);
    if (!height || isNaN(height)) height = w > 0 && h > 0 ? Math.min(w, h) : 0;
    const definition = meta.definition || v.definition || (height ? height + 'p' : '');
    const bitrate = intOf(meta.bitrate, 0) || intOf(v.bitrate, 0);
    choices.push({
      url: primary, key: key, name: String(definition || '视频'), height: height,
      codec: codec, h264: codec === 'h264' || codec === 'avc1', bitrate: bitrate,
    });
  }
  choices.sort(function (a, b) {
    if (a.height !== b.height) return b.height - a.height;
    if (a.h264 !== b.h264) return a.h264 ? -1 : 1;
    return b.bitrate - a.bitrate;
  });
  const seen = {};
  const streams = [];
  for (let i = 0; i < choices.length && streams.length < 12; i++) {
    const c = choices[i];
    const tag = c.name + '|' + c.codec;
    if (seen[tag]) continue;
    seen[tag] = 1;
    streams.push({ url: c.url, format: 'mp4', key: c.key, name: c.name });
  }
  if (!streams.length) {
    if (vc2 > 0 && vc2 === entries.length) throw host.error('E_UNSUPPORTED', '该分集只有 VVC（bytevc2）编码，当前播放器不支持');
    throw host.error('E_INTERNAL', '没有可用的媒体或解密密钥');
  }
  const expiresAt = intOf(model.url_expire, 0);
  const out = { streams: streams };
  if (expiresAt > 0) out.expiresAt = expiresAt;
  return out;
}

function tabPairs(vid) {
  return BASE_PAIRS.concat([
    ['video_id', vid], ['video_id_type', '0'], ['device_level', '1'],
    ['need_all_video_definition', '1'], ['need_mp4_align', '0'], ['use_os_player', '0'],
    ['video_platform', '1024'], ['source', '0'], ['from_video_id', ''],
  ]);
}

async function tryTab(vid, offset, errors) {
  ensureProcess();
  for (let i = 0; i < TAB_HOSTS.length; i++) {
    const h = TAB_HOSTS[i];
    const now = Math.round(Date.now() + offset);
    let signed;
    try {
      signed = signV5(tabPairs(vid), now, EMPTY, false, hexToBytes(host.crypto.randomHex(16)));
    } catch (e) {
      errors.push('签名失败：' + (e && e.message ? e.message : e));
      return null;
    }
    const headers = { 'User-Agent': TAB_UA, 'Accept': 'application/json; charset=utf-8', 'Accept-Language': 'zh-CN,zh;q=0.9' };
    for (const k in signed.headers) headers[k] = signed.headers[k];
    let r;
    try {
      r = await host.http.request({ url: 'https://' + h + TAB_PATH + '?' + signed.query, method: 'GET', headers: headers, timeout: 15000 });
    } catch (e) {
      errors.push(h + ' 请求失败');
      continue;
    }
    if (r.status !== 200) { errors.push(h + ' HTTP ' + r.status); continue; }
    const text = String(r.body == null ? '' : r.body);
    if (!text || text.length <= 2) { errors.push(h + ' 空响应'); continue; }
    let j;
    try { j = JSON.parse(text); } catch (e) { errors.push(h + ' 返回非 JSON'); continue; }
    const code = j.code != null ? String(j.code) : (j.BaseResp && j.BaseResp.StatusCode != null ? String(j.BaseResp.StatusCode) : '0');
    if (code !== '0') { errors.push('video_tab code=' + code + ' ' + (j.message || '')); continue; }
    const data = j.data || {};
    const bucket = data.video_model_data || data['1004'] || data;
    const entry = bucket ? bucket[vid] : null;
    const model = modelOf(entry);
    if (!model) { errors.push('video_tab 缺少该分集模型'); continue; }
    return materialize(model);
  }
  return null;
}

function crc32(buf) {
  let crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    let c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function gzipStore(buf) {
  const parts = [Uint8Array.from([0x1f, 0x8b, 0x08, 0, 0, 0, 0, 0, 0, 0xff])];
  let off = 0;
  if (buf.length === 0) parts.push(Uint8Array.from([0x01, 0, 0, 0xff, 0xff]));
  while (off < buf.length) {
    const n = Math.min(65535, buf.length - off);
    const final = off + n >= buf.length ? 1 : 0;
    parts.push(Uint8Array.from([final, n & 255, (n >>> 8) & 255, (~n) & 255, ((~n) >>> 8) & 255]));
    parts.push(buf.subarray(off, off + n));
    off += n;
  }
  const crc = crc32(buf);
  const size = buf.length >>> 0;
  parts.push(Uint8Array.from([crc & 255, (crc >>> 8) & 255, (crc >>> 16) & 255, (crc >>> 24) & 255,
    size & 255, (size >>> 8) & 255, (size >>> 16) & 255, (size >>> 24) & 255]));
  return concat.apply(null, parts);
}

async function tryPreload(vid, offset, errors) {
  ensureProcess();
  const body = JSON.stringify({
    biz_param: {
      detail_page_version: 0, device_level: 2, disable_digg_stat: false, disable_video_relate_book: false,
      need_all_video_definition: true, need_mp4_align: false, use_os_player: false, use_server_dns: false, video_platform: 1024,
    },
    dr_scene: 'preload',
    mixed_video_id_map: { '1004': [vid] },
  });
  const gz = gzipStore(asciiBytes(body));
  for (let i = 0; i < PRELOAD_HOSTS.length; i++) {
    const h = PRELOAD_HOSTS[i];
    const now = Math.round(Date.now() + offset);
    let signed;
    try {
      signed = signV5(BASE_PAIRS, now, gz, true, hexToBytes(host.crypto.randomHex(16)));
    } catch (e) {
      errors.push('签名失败：' + (e && e.message ? e.message : e));
      return null;
    }
    const headers = { 'User-Agent': 'okhttp/4.12.0', 'Accept': 'application/json', 'Content-Encoding': 'gzip' };
    for (const k in signed.headers) headers[k] = signed.headers[k];
    let r;
    try {
      r = await host.http.request({
        url: 'https://' + h + PRELOAD_PATH + '?' + signed.query, method: 'POST', headers: headers,
        body: host.codec.convert(bytesToHex(gz), 'hex', 'base64'), bodyType: 'base64', timeout: 15000,
      });
    } catch (e) {
      errors.push(h + ' 请求失败');
      continue;
    }
    if (r.status === 429 || r.status >= 500) { errors.push(h + ' HTTP ' + r.status); continue; }
    if (r.status !== 200) { errors.push(h + ' HTTP ' + r.status); continue; }
    const text = String(r.body == null ? '' : r.body);
    if (!text || text.length <= 2) { errors.push(h + ' 空响应'); continue; }
    let j;
    try { j = JSON.parse(text); } catch (e) { errors.push(h + ' 返回非 JSON'); continue; }
    const code = j.code != null ? String(j.code) : (j.BaseResp && j.BaseResp.StatusCode != null ? String(j.BaseResp.StatusCode) : '0');
    if (code !== '0') { errors.push('preload code=' + code + ' ' + (j.message || '')); continue; }
    const data = j.data || {};
    const entry = data[vid] || (data['1004'] ? data['1004'][vid] : null);
    const model = modelOf(entry);
    if (!model) { errors.push('preload 缺少该分集模型'); continue; }
    return materialize(model);
  }
  return null;
}

async function webFallback(vid) {
  const r = await host.http.request({
    url: BASE + '/api/video/protected/fallback-play-info',
    method: 'POST',
    headers: {
      'User-Agent': WEB_UA, 'Content-Type': 'application/json; charset=utf-8', 'Accept': 'application/json',
      'Origin': BASE, 'Referer': BASE + '/',
    },
    body: { item_id: vid, force_refresh: false },
    bodyType: 'json',
    responseType: 'json',
    timeout: 10000,
  });
  if (r.status !== 200) throw host.error('E_HTTP', '网页备用播放接口 HTTP ' + r.status);
  const j = r.body;
  if (!j || typeof j !== 'object') throw host.error('E_PARSE', '网页备用播放接口返回异常');
  const d = j.data && typeof j.data === 'object' && Object.keys(j.data).length ? j.data : j;
  const url = d.play_url || d.main_url || d.url || '';
  if (typeof url !== 'string' || url.indexOf('https://') !== 0) throw host.error('E_PARSE', '网页备用播放地址无效');
  const out = { streams: [{ url: url, format: 'mp4', name: d.definition || '360p' }] };
  const exp = intOf(d.expire_time, 0);
  if (exp > 0) out.expiresAt = exp;
  return out;
}

async function play(arg) {
  const vid = String(arg && arg.episodeId ? arg.episodeId : '').trim();
  if (!/^\d{1,30}$/.test(vid)) throw host.error('E_PARSE', '分集编号无效');
  ensureProcess();
  const errors = [];
  let offset = 0;
  try { offset = await signingOffset(); } catch (e) { host.log('跳过时钟校准'); }
  let r = await tryTab(vid, offset, errors);
  if (!r) r = await tryPreload(vid, offset, errors);
  if (!r) {
    try { r = await webFallback(vid); } catch (e) {
      errors.push('网页备用：' + (e && e.message ? e.message : e));
    }
  }
  if (!r) throw host.error('E_INTERNAL', '取流失败：' + errors.slice(-3).join(' / '));
  return r;
}

function popcountN(x) {
  let c = 0;
  while (x) { c += x & 1; x >>>= 1; }
  return c;
}

function transformNonmagic(a, mode) {
  let w11 = 0x55, w12 = 0xfa;
  const out = new Uint8Array(a.length);
  for (let i = 0; i < a.length; i++) {
    const c = a[i];
    let x;
    if (i % 2 === 0) { x = w12; w12 = c; } else { x = w11; w11 = c; }
    const delta = mode !== 0 ? popcountN(i) + 0x15 : -0x15 - popcountN(i);
    out[i] = ((x ^ c) + delta) & 0xff;
  }
  return out;
}

function swapPairs(b) {
  const o = new Uint8Array(b);
  for (let i = 0; i + 1 < o.length; i += 2) { const t = o[i]; o[i] = o[i + 1]; o[i + 1] = t; }
  return o;
}

function transformMagic(a, mode) {
  const s = swapPairs(a);
  let w12 = 0x55, w13 = 0xfa;
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    let x, y;
    if (i % 2 === 0) { x = w13; y = w12; w13 = c; } else { x = w12; y = w13; w12 = c; }
    const delta = mode !== 0 ? popcountN(i) + 0x15 : -0x15 - popcountN(i);
    out[i] = ((x ^ c) - y + delta) & 0xff;
  }
  return swapPairs(out);
}

function hexval(ch) {
  if (ch >= 0x30 && ch <= 0x39) return ch - 0x30;
  if (ch >= 0x61 && ch <= 0x66) return ch - 0x57;
  return -1;
}

function unwrapSpade(spade, mode) {
  mode = mode || 0;
  const ln = spade.length;
  if (ln < 4) return null;
  const v = spade[0] ^ spade[1] ^ spade[2];
  const hdr = v - 0x30;
  if (hdr < 1) return null;
  const w22 = ln - v + 0x2f;
  if (w22 < 1 || 1 + w22 > ln) return null;
  const a = spade.subarray(1, 1 + w22);
  const si = ln - hdr - 2;
  if (si < 0 || si + 1 >= ln || ln - hdr < 0) return null;
  const seed = spade[si] ^ spade[si + 1];
  const buf = new Uint8Array(hdr);
  for (let i = 0; i < hdr; i++) buf[i] = seed ^ spade[ln - hdr + i];
  const magic = buf.length >= 6 && (asciiString(buf.subarray(0, 6)) === 'app_v2' || asciiString(buf.subarray(0, 6)) === 'web_v2');
  const a2 = magic ? transformMagic(a, mode) : transformNonmagic(a, mode);
  if (!a2.length) return null;
  const hv = hexval(a2[0]);
  if (hv < 0) return null;
  const remaining = w22 - hv;
  if (remaining < 2 || remaining > a2.length) return null;
  return asciiString(a2.subarray(1, remaining));
}

function contentKey(envelope) {
  const t = String(envelope || '').trim();
  if (!t) return null;
  const cands = [];
  let s = t.replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4 !== 0) s += '=';
  try { cands.push(hexToBytes(host.codec.convert(s, 'base64', 'hex'))); } catch (e) {}
  if (/^[0-9a-fA-F]+$/.test(t) && t.length % 2 === 0) cands.push(hexToBytes(t));
  for (let i = 0; i < cands.length; i++) {
    const k = unwrapSpade(cands[i], 0);
    if (k && /^[0-9a-fA-F]{32}$/.test(k)) return k.toLowerCase();
  }
  return null;
}

// ── 官方弹幕：/novel/commentapi/comment/list/{vid}/v1/（X-Gorgon nonce 变体 / X-Ladon / X-Argus）──

const DM_HOST = 'https://api5-normal-sinfonlineb.fqnovel.com';
const DM_UA = 'com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)';
const DM_PAGE = 90;
const DM_PAGES = 6;
const DM_ITEMS = 1200;
const DM_WINDOW = 3600000;
const DM_MAX_WINDOW = 86400000;
const DM_ARGUS_KEY = hexToBytes('ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c');

function dmRol64(v, n) {
  v &= M64; n = ((n % 64) + 64) % 64;
  return n === 0 ? v : ((v << BigInt(n)) | (v >> BigInt(64 - n))) & M64;
}
function dmRor64(v, n) { return dmRol64(v, -n); }
function dmRol8(x, n) { const s = n & 7; x &= 255; return s === 0 ? x : ((x << s) | (x >> (8 - s))) & 255; }
function dmRev8(x) {
  let v = x & 255;
  v = ((v & 0xF0) >> 4) | ((v & 0x0F) << 4);
  v = ((v & 0xCC) >> 2) | ((v & 0x33) << 2);
  v = ((v & 0xAA) >> 1) | ((v & 0x55) << 1);
  return v & 255;
}
function dmPad(d) {
  const p = 16 - (d.length % 16);
  const o = new Uint8Array(d.length + p);
  o.set(d); o.fill(p, d.length);
  return o;
}
function dmGoEnc(s) {
  const b = asciiBytes(String(s)); let o = '';
  for (let i = 0; i < b.length; i++) {
    const c = b[i];
    if ((c >= 65 && c <= 90) || (c >= 97 && c <= 122) || (c >= 48 && c <= 57) || c === 45 || c === 95 || c === 46 || c === 126) o += String.fromCharCode(c);
    else if (c === 32) o += '+';
    else o += '%' + (c < 16 ? '0' : '') + c.toString(16).toUpperCase();
  }
  return o;
}
function dmBasePairs(did, iid) {
  return [
    ['ac', 'wifi'], ['aid', '8662'], ['app_name', 'novelread'], ['channel', 'update_64'], ['device_brand', 'Redmi'],
    ['device_id', did], ['device_platform', 'android'], ['device_type', '25053RT47C'], ['dpi', '520'], ['iid', iid],
    ['language', 'zh'], ['manifest_version_code', '73532'], ['os', 'android'], ['os_api', '36'], ['os_version', '16'],
    ['resolution', '1280*2772'], ['ssmix', 'a'], ['update_version_code', '73532'], ['version_code', '73532'], ['version_name', '7.3.5.32'],
  ];
}
function dmQuery(stampMs, did, iid) {
  const p = dmBasePairs(did, iid);
  p.push(['_rticket', String(stampMs)]);
  p.sort(function (a, b) { return a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0; });
  const out = [];
  for (let i = 0; i < p.length; i++) out.push(dmGoEnc(p[i][0]) + '=' + dmGoEnc(p[i][1]));
  return out.join('&');
}
function dmNewDeviceId() {
  const rnd = BigInt('0x' + host.crypto.randomHex(8));
  return String(1000000000000000000n + (rnd % 8000000000000000000n));
}
function dmIdentity() {
  let did = '', iid = '';
  try {
    const a = host.storage.get('danmaku.did'), b = host.storage.get('danmaku.iid');
    did = a == null ? '' : String(a);
    iid = b == null ? '' : String(b);
  } catch (e) {}
  if (!/^\d{19}$/.test(did) || !/^\d{19}$/.test(iid)) {
    did = dmNewDeviceId(); iid = dmNewDeviceId();
    try { host.storage.set('danmaku.did', did); host.storage.set('danmaku.iid', iid); } catch (e) {}
  }
  return [did, iid];
}
function dmXGorgon(rawQuery, stamp, nonce) {
  const head2 = (((nonce[0] % 7) + 1) << 5) & 0xff;
  const head3 = 0xE0 | (nonce[1] & 0x0F);
  const seed = new Uint8Array(20);
  const digest = md5Bytes(asciiBytes(rawQuery));
  for (let i = 0; i < 4; i++) seed[i] = digest[i];
  seed[12] = 0; seed[13] = 1; seed[14] = 7; seed[15] = 4;
  const tb = beBytes(stamp >>> 0);
  for (let i = 0; i < 4; i++) seed[16 + i] = tb[i];
  const key = [0x4A, 0x00, 0x16, head3, 0x47, 0x6C, 0x00, head2];
  const box = new Uint8Array(256);
  for (let i = 0; i < 256; i++) box[i] = i;
  let j = 0;
  // key schedule 是赋值不是交换，故意保留
  for (let i = 0; i < 256; i++) { j = (j + box[i] + key[i & 7]) & 0xFF; box[i] = box[j]; }
  j = 0;
  for (let off = 0; off < 20; off++) {
    const i = (off + 1) & 0xFF;
    j = (j + box[i]) & 0xFF;
    box[i] = box[j];
    seed[off] ^= box[(box[i] + box[j]) & 0xFF];
  }
  for (let i = 0; i < 20; i++) seed[i] = dmRev8(dmRol8(seed[i], 4) ^ seed[(i + 1) % 20]) ^ 0xEB;
  return bytesToHex(concat(Uint8Array.from([0x84, 0x04, head2, head3, 0x00, 0x00]), seed));
}
function dmLadon(stamp, prefix) {
  const digest = asciiBytes(bytesToHex(md5Bytes(concat(prefix, asciiBytes('8662')))));
  let left = rdLE64(digest, 0), right = rdLE64(digest, 8);
  const queue = [rdLE64(digest, 16), rdLE64(digest, 24)];
  const keys = [];
  for (let i = 0; i < 34; i++) {
    keys.push(left);
    const mixed = ((dmRor64(right, 8) + left) & M64) ^ BigInt(i);
    queue.push(mixed);
    left = (mixed ^ dmRol64(left, 3)) & M64;
    right = queue.shift();
  }
  const data = dmPad(asciiBytes(String(stamp) + '-1611921764-3019'));
  for (let off = 0; off < data.length; off += 16) {
    let bl = rdLE64(data, off), br = rdLE64(data, off + 8);
    for (let k = 0; k < keys.length; k++) {
      br = keys[k] ^ ((bl + dmRor64(br, 8)) & M64);
      bl = br ^ dmRol64(bl, 3);
    }
    data.set(wrLE64(bl), off); data.set(wrLE64(br), off + 8);
  }
  return host.codec.convert(bytesToHex(concat(prefix, data)), 'hex', 'base64');
}
function dmAES(key, iv, data) {
  return hexToBytes(host.crypto.aes({
    op: 'encrypt', mode: 'cbc', padding: 'none',
    key: bytesToHex(key), keyEncoding: 'hex',
    iv: bytesToHex(iv), ivEncoding: 'hex',
    data: bytesToHex(data), in: 'hex', out: 'hex',
  }));
}
function dmArgus(rawQuery, stamp, nonce) {
  const key = DM_ARGUS_KEY;
  const parts = [];
  function vi(field, value) { parts.push(varint(field * 8)); parts.push(varint(value)); }
  function by(field, v) { parts.push(varint(field * 8 + 2)); parts.push(varint(v.length)); parts.push(v); }
  const n3 = (nonce[6] | (nonce[7] << 8) | (nonce[8] << 16) | (nonce[9] << 24)) >>> 0;
  vi(1, 0x20200929 * 2);
  vi(2, 2);
  vi(3, n3);
  by(4, asciiBytes('3019'));
  by(6, asciiBytes('1611921764'));
  by(7, asciiBytes('6.8.1.32'));
  by(8, asciiBytes('v04.07.01-ml-android'));
  vi(9, 135135744);
  by(10, new Uint8Array(8));
  vi(12, (BigInt(stamp) * 2n) & M64);
  const bodyHash = sm3(new Uint8Array(16));
  const queryHash = sm3(asciiBytes(rawQuery));
  by(13, bodyHash.subarray(0, 6));
  by(14, queryHash.subarray(0, 6));
  const nested = [0x08, (2 + (nonce[10] % 49) * 2) & 0xff];
  const fs = [2, 3, 4];
  for (let i = 0; i < 3; i++) { nested.push(varint(fs[i] * 8)); nested.push(varint(1388734)); }
  by(15, Uint8Array.from(nested));
  by(20, asciiBytes('none'));
  vi(21, 738);

  const data = dmPad(concat.apply(null, parts));
  const derived = sm3(concat(key, Uint8Array.from([0x3C, 0xCC, 0x56, 0x7B]), key));
  const rounds = [];
  for (let i = 0; i < 4; i++) rounds.push(rdLE64(derived, i * 8));
  const c = 0x3DC94C3A046D678Bn;
  for (let i = 4; i < 72; i++) {
    let mixed = dmRor64(rounds[i - 1], 3) ^ rounds[i - 3];
    mixed = (mixed ^ dmRor64(mixed, 1)) & M64;
    rounds.push((((~rounds[i - 4]) & M64) ^ mixed ^ ((c >> BigInt((i - 4) % 62)) & 1n) ^ 3n) & M64);
  }
  for (let off = 0; off < data.length; off += 16) {
    let l = rdLE64(data, off), r = rdLE64(data, off + 8);
    for (let i = 0; i < rounds.length; i++) {
      const k = rounds[i];
      const next = (l ^ ((dmRol64(r, 1) & dmRol64(r, 8)) ^ dmRol64(r, 2)) ^ k) & M64;
      l = r; r = next;
    }
    data.set(wrLE64(l), off); data.set(wrLE64(r), off + 8);
  }
  const xor = [0xD0, 0x4F, 0xFD, 0xFF, 0xD0, 0x4F, 0xFD, 0xFF];
  for (let i = 0; i < data.length; i++) data[i] ^= xor[i % 8];
  const rev = reverseBytes(concat(Uint8Array.from(xor), data));
  const container = dmPad(concat(Uint8Array.from([0xA6, 0xE7, 0x83, 0xEE, 0x70, 0x01, 0x10, 0x09, 0x18]), rev, Uint8Array.from([0x56, 0x7B])));
  const enc = dmAES(md5Bytes(key.subarray(0, 16)), md5Bytes(key.subarray(16)), container);
  return host.codec.convert(bytesToHex(concat(Uint8Array.from([0x3C, 0xCC]), enc)), 'hex', 'base64');
}
function dmHeaders(rawQuery, stamp, nonce) {
  const out = {
    'X-Gorgon': dmXGorgon(rawQuery, stamp, nonce),
    'X-Khronos': String(stamp),
    'X-SS-Req-Ticket': String(stamp * 1000),
    'X-Ladon': dmLadon(stamp, nonce.subarray(2, 6)),
  };
  try { out['X-Argus'] = dmArgus(rawQuery, stamp, nonce); } catch (e) { host.log('X-Argus 生成失败：' + (e && e.message ? e.message : e)); }
  return out;
}

async function dmRequest(vid, bookId, start, dur) {
  const stampMs = Date.now();
  const stamp = Math.floor(stampMs / 1000);
  const nonce = hexToBytes(host.crypto.randomHex(11));
  const ids = dmIdentity();
  const q = dmQuery(stampMs, ids[0], ids[1]);
  const headers = {
    'User-Agent': DM_UA, 'Accept': 'application/json', 'X-XS-From-Web': '0', 'Sdk-Version': '2',
    'Content-Type': 'application/json; charset=utf-8', 'Comment-Source': '601', 'Server-Channel': '1000', 'X-SS-STUB': '',
  };
  const sig = dmHeaders(q, stamp, nonce);
  for (const k in sig) headers[k] = sig[k];
  const r = await host.http.request({
    url: DM_HOST + '/novel/commentapi/comment/list/' + vid + '/v1/?' + q,
    method: 'POST', headers: headers, timeout: 4000, bodyType: 'json',
    body: {
      comment_source: 601, server_channel: 1000, group_id: vid, group_type: 30, comment_type: 20,
      sort: 1, count: DM_PAGE, cursor: '', aid: 8662, compliance_status: 0,
      business_param: { book_id: bookId, start_offset_time: start, playlet_item_duration: dur, need_danmaku_guide_type: [1, 3, 4, 2] },
    },
  });
  if (r.status !== 200) throw host.error('E_HTTP', '弹幕接口 HTTP ' + r.status);
  const text = String(r.body == null ? '' : r.body);
  if (!text) throw host.error('E_NETWORK', '弹幕接口空响应');
  let j;
  try { j = JSON.parse(text); } catch (e) { throw host.error('E_PARSE', '弹幕接口返回非 JSON'); }
  const code = j.code != null ? String(j.code) : (j.Code != null ? String(j.Code) : '');
  if (code !== '0' || !j.data || !Array.isArray(j.data.data_list)) {
    throw host.error('E_INTERNAL', '弹幕接口 code=' + code + ' ' + (j.message || ''));
  }
  return j;
}
function dmInt(v) {
  const s = String(v == null ? '' : v).trim();
  if (!/^-?\d+$/.test(s)) return null;
  const n = parseInt(s, 10);
  return isNaN(n) ? null : n;
}
function dmPageInfo(data) {
  let next = null, hasMore = null;
  const extra = data.extra || {};
  const raw = extra.next_query_danmaku_list_time;
  if (raw != null && String(raw).trim() !== '') { const n = dmInt(raw); if (n != null) next = n; }
  const info = data.common_list_info || {};
  if (info.has_more != null) {
    if (typeof info.has_more === 'boolean') hasMore = info.has_more;
    else { const s = String(info.has_more).toLowerCase(); if (s) hasMore = s === '1' || s === 'true' || s === 'yes'; }
  }
  let cursor = info.cursor;
  if (typeof cursor === 'string' && cursor.trim().charAt(0) === '{') {
    try { cursor = JSON.parse(cursor); } catch (e) { cursor = null; }
  }
  if (cursor && typeof cursor === 'object') {
    const ks = ['end_offset_time', 'next_offset_time', 'offset'];
    for (let i = 0; i < ks.length; i++) {
      const v = cursor[ks[i]];
      if (v == null) continue;
      const n = dmInt(v);
      if (n == null || (n === 0 && String(v) !== '0')) continue;
      if (next == null || n > next) next = n;
      break;
    }
  }
  if (hasMore === false) next = null;
  if (next != null && next <= 0) next = null;
  return { next: next, hasMore: hasMore };
}
function dmColor(v) {
  if (typeof v === 'number' && isFinite(v)) {
    const i = Math.trunc(v);
    if (i < 0) return '#FFFFFF';
    return '#' + ('000000' + (i & 0xFFFFFF).toString(16).toUpperCase()).slice(-6);
  }
  let s = v == null ? '' : String(v).trim();
  if (!s) return '#FFFFFF';
  if (s.charAt(0) === '#') {
    s = s.slice(1);
    if (s.length === 3) s = s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
    return /^[0-9a-fA-F]{6}$/.test(s) ? '#' + s.toUpperCase() : '#FFFFFF';
  }
  if (/^0x[0-9a-fA-F]+$/.test(s)) return '#' + ('000000' + (parseInt(s.slice(2), 16) & 0xFFFFFF).toString(16).toUpperCase()).slice(-6);
  if (/^\d+$/.test(s)) return '#' + ('000000' + (parseInt(s, 10) & 0xFFFFFF).toString(16).toUpperCase()).slice(-6);
  return '#FFFFFF';
}
function dmMode(holders) {
  const ks = ['mode', 'danmaku_type', 'position', 'fix_type', 'danmaku_position'];
  for (let h = 0; h < holders.length; h++) {
    const o = holders[h];
    if (!o || typeof o !== 'object') continue;
    for (let i = 0; i < ks.length; i++) {
      if (o[ks[i]] == null) continue;
      const t = String(o[ks[i]]).toLowerCase();
      if (t === 'top' || t === '2' || t === 'fix_top' || t === '顶部') return 'top';
      if (t === 'bottom' || t === '3' || t === 'fix_bottom' || t === '底部') return 'bottom';
    }
  }
  return 'scroll';
}
function dmClean(s) {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    out += (c < 0x20 || c === 0x7F || c === 0x2028 || c === 0x2029) ? ' ' : s.charAt(i);
  }
  return out.trim();
}
function dmItems(data, vid) {
  const out = [];
  const rows = data.data_list || [];
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i] || {};
    const comment = row.comment || {};
    if (!comment || typeof comment !== 'object') continue;
    const common = comment.common || {};
    const gid = common.group_id == null ? '' : String(common.group_id);
    if (gid && gid !== vid) continue;
    if (String(common.status == null ? '' : common.status) !== '1') continue;
    const content = common.content || {};
    const text = content.text == null ? '' : String(content.text);
    const expand = comment.expand || {};
    const raw = expand.offset_time != null ? expand.offset_time : comment.offset_time;
    if (raw == null || String(raw) === '') continue;
    const ms = Number(raw);
    if (!isFinite(ms) || ms < 0 || ms / 1000 > 604800) continue;
    let color = '#FFFFFF', found = false;
    const holders = [content, common, expand, comment];
    const keys = ['color', 'font_color', 'text_color', 'col'];
    for (let h = 0; h < holders.length && !found; h++) {
      const o = holders[h];
      if (!o || typeof o !== 'object') continue;
      for (let k = 0; k < keys.length; k++) {
        if (o[keys[k]] == null) continue;
        color = dmColor(o[keys[k]]); found = true; break;
      }
    }
    const stat = comment.stat || {};
    let likes = dmInt(stat.digg_count);
    if (likes == null || likes < 0) likes = 0;
    out.push({ time: ms / 1000, text: text, color: color, mode: dmMode([row, comment]), likes: likes });
  }
  return out;
}

async function danmaku(arg) {
  const ep = String(arg && arg.episodeKey ? arg.episodeKey : '').trim();
  const sid = String(arg && arg.seriesId ? arg.seriesId : '').trim();
  if (!/^\d{1,30}$/.test(ep) || !/^\d{1,30}$/.test(sid)) throw host.error('E_UNSUPPORTED', '该剧集没有弹幕');
  const items = [];
  const seen = {};
  let start = 0, dur = DM_WINDOW, empty = 0, truncated = false;
  for (let page = 0; page < DM_PAGES; page++) {
    let payload;
    try { payload = await dmRequest(ep, sid, start, dur); }
    catch (e) {
      if (page === 0) throw e;
      host.log('弹幕第 ' + (page + 1) + ' 页失败：' + (e && e.message ? e.message : e));
      truncated = true;
      break;
    }
    const data = payload.data || {};
    const rows = dmItems(data, ep);
    if (!rows.length) empty++; else empty = 0;
    for (let i = 0; i < rows.length; i++) {
      const it = rows[i];
      it.text = dmClean(it.text);
      if (!it.text || it.text.length > 200) continue;
      const key = it.time.toFixed(3) + '|' + it.text;
      if (seen[key]) continue;
      seen[key] = 1;
      items.push(it);
      if (items.length >= DM_ITEMS) break;
    }
    if (items.length >= DM_ITEMS) { truncated = true; break; }
    const p = dmPageInfo(data);
    if (p.next == null) { if (p.hasMore === true) truncated = true; break; }
    if (p.next <= start || p.next > 604800000 || empty >= 2 || page === DM_PAGES - 1) { truncated = true; break; }
    start = p.next;
    dur = Math.min(Math.max(dur, start + DM_WINDOW), DM_MAX_WINDOW);
  }
  items.sort(function (a, b) { return a.time - b.time; });
  return { items: items, truncated: truncated };
}

export default {
  home: home,
  category: category,
  detail: detail,
  play: play,
  danmaku: danmaku,
};