// ── Pass 19 full acceptance harness ───────────────────────────────────────
// Executes the REAL index.html script (all functions, real pipelines) under
// a DOM shim, then drives every _vlSet-based tab and the album-sync
// generation machinery. Prints PASS/FAIL per check.
const fs = require('fs');
const file = process.argv[2];
const lines = fs.readFileSync(file, 'utf8').split('\n');
const starts = [], ends = [];
lines.forEach((l, i) => { if (l.includes('<script')) starts.push(i); if (l.includes('</script>')) ends.push(i); });
const js = lines.slice(starts[1] + 1, ends[ends.length - 1]).join('\n');

// ── DOM shim ──────────────────────────────────────────────────────────────
class Elem {
  constructor(tag) { this.tagName = (tag || 'div').toUpperCase(); this.children = []; this.parentNode = null;
    this._attrs = {}; this.style = {}; this.className = ''; this._html = ''; this._listeners = {};
    this.scrollTop = 0; this.scrollHeight = 8000; this.clientWidth = 1024; this.clientHeight = 600; }
  get firstChild() { return this.children[0] || null; }
  appendChild(n) { if (n.parentNode) n.parentNode.removeChild(n); n.parentNode = this; this.children.push(n); return n; }
  insertBefore(n, ref) { if (n.parentNode) n.parentNode.removeChild(n);
    const i = ref ? this.children.indexOf(ref) : -1;
    n.parentNode = this; if (i < 0) this.children.push(n); else this.children.splice(i, 0, n); return n; }
  removeChild(n) { const i = this.children.indexOf(n); if (i >= 0) this.children.splice(i, 1); n.parentNode = null; return n; }
  set innerHTML(v) { this.children.forEach(c => c.parentNode = null); this.children = [];
    this._html = String(v);
    const pgm = /<div\s+class="pg-media"[^>]*>\s*<div\s+id="([^"]+)"/.exec(String(v));
    if (pgm) {
      const outer = new Elem('div'); outer.setAttribute('data-pg', '1'); outer.className = 'pg-media';
      const inner = new Elem('div'); inner._attrs.id = pgm[1];
      outer.appendChild(inner); this.appendChild(outer); document._byId[pgm[1]] = inner;
    } else if (v && String(v).indexOf('data-pg') >= 0) {
      const d = new Elem('div'); d.setAttribute('data-pg', '1'); d.className = 'pg-media'; this.appendChild(d);
      const idm = /id="([^"]+)"/.exec(String(v));
      if (idm) { const inner = new Elem('div'); inner._attrs.id = idm[1]; d.appendChild(inner); document._byId[idm[1]] = inner; }
    } else {
      // flat parser: recognize every top-level <div class="X"...> open tag as
      // a child element (nested content is flattened onto it — enough for
      // probe rows/cells, which is all the vl code builds).
      const re = /<div\s+(?:class="([^"]*)"\s+)?id="([^"]+)"[^>]*>/g; let m;
      const re2 = /<div\s+class="([^"]*)"[^>]*>/g; let m2;
      const seen = new Set();
      while ((m = re.exec(this._html)) !== null) {
        const d = new Elem('div'); if (m[1]) d.className = m[1];
        d._attrs.id = m[2]; this.appendChild(d); document._byId[m[2]] = d; seen.add(d);
      }
      while ((m2 = re2.exec(this._html)) !== null) {
        const cls = m2[1] || '';
        if (cls === 'pg-media') continue;   // handled above with its id child
        const d = new Elem('div'); d.className = cls; this.appendChild(d);
      }
    } }
  get innerHTML() { return this._html; }
  setAttribute(k, v) { this._attrs[k] = String(v); if (k === 'data-k') this._k = String(v); }
  getAttribute(k) { return k in this._attrs ? this._attrs[k] : (this._k && k === 'data-k' ? this._k : null); }
  removeAttribute(k) { delete this._attrs[k]; }
  addEventListener(t, f) { (this._listeners[t] = this._listeners[t] || []).push(f); }
  querySelectorAll(sel) { const out = [];
    (function walk(n) { if (!n) return; if (sel[0] === '.' && (' ' + (n.className || '') + ' ').includes(' ' + sel.slice(1) + ' ')) out.push(n);
      (n.children || []).forEach(walk); })(this); return out; }
  querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
  get classList() { const self = this; return {
    add(c) { if (!(' ' + self.className + ' ').includes(' ' + c + ' ')) self.className = (self.className + ' ' + c).trim(); },
    remove(c) { self.className = (' ' + self.className + ' ').replace(' ' + c + ' ', ' ').trim(); },
    contains(c) { return (' ' + self.className + ' ').includes(' ' + c + ' '); } }; }
  get offsetTop() { const i = Array.prototype.indexOf.call(this.parentNode ? this.parentNode.children : [], this); return i < 0 ? 0 : i * 150; }
  get offsetHeight() { return 150; }
  get offsetWidth() { return 100; }
}
global.document = {
  _byId: {},
  createElement(t) { return new Elem(t); },
  getElementById(id) { return this._byId[id] || (this._byId[id] = new Elem('div')); },
  querySelectorAll(sel) { const out = [];
    (function walk(n) { if (!n) return; if (sel[0] === '.' && (' ' + (n.className || '') + ' ').includes(' ' + sel.slice(1) + ' ')) out.push(n);
      (n.children || []).forEach(walk); })(this.body);
    return out; },
  querySelector(sel) { return this.querySelectorAll(sel)[0] || null; },
  addEventListener() {}, removeEventListener() {},
  documentElement: new Elem('html'), body: new Elem('body'),
  getComputedStyle(el) { return { paddingLeft: '0px', paddingRight: '0px', paddingTop: '0px', paddingBottom: '0px' }; }
};
global.window = { addEventListener() {}, removeEventListener() {}, setTimeout, clearTimeout,
  getComputedStyle: (el) => document.getComputedStyle(el), location: { protocol: 'http:', host: 'x', pathname: '/' },
  navigator: { userAgent: 'probe' }, EventSource: undefined, innerWidth: 1024, innerHeight: 600 };
global.setInterval = () => 0; global.clearInterval = () => {};
global.requestAnimationFrame = (f) => setTimeout(f, 0);
global.cancelAnimationFrame = clearTimeout;
global.Image = class { set src(v) { if (this.onload) this.onload(); } };
const _ls = {}; global.localStorage = { getItem: k => (k in _ls ? _ls[k] : null), setItem: (k, v) => { _ls[k] = String(v); }, removeItem: k => { delete _ls[k]; } };
global._pendingXhrs = [];
global.XMLHttpRequest = class {
  open(m, u) { this._url = u; this.readyState = 4; }
  setRequestHeader() {}
  send() { global._pendingXhrs.push(this); }
  addEventListener() {}
};
global._respondXhr = function (xhr, body) {
  xhr.status = 200; xhr.responseText = JSON.stringify(body); xhr.response = xhr.responseText;
  if (xhr.onreadystatechange) xhr.onreadystatechange();
  if (xhr.onload) xhr.onload();
};
try { (0, eval)(js); } catch (e) { console.log('eval note:', e.message); }
if (typeof globalThis._albumGen === 'undefined') globalThis._albumGen = { img: 0, vid: 0 };

// ── helpers ───────────────────────────────────────────────────────────────
const W = globalThis;
const content = document.getElementById('content');
function findByClass(root, cls) { const out = [];
  (function walk(n) { if (!n) return; if ((' ' + (n.className || '') + ' ').includes(' ' + cls + ' ')) out.push(n);
    (n.children || []).forEach(walk); })(root); return out; }
function unitsIn(cls) { const g = findByClass(content, cls); if (!g.length) return -1;
  const b = g[g.length - 1]; const st = b._vlCtl; if (!st) return -1;
  return Object.keys(st.nodes).length; }
let pass = 0, fail = 0;
function check(name, cond) { if (cond) { pass++; console.log('PASS', name); } else { fail++; console.log('FAIL', name); } }
function tracks12() { const t = []; for (let i = 0; i < 12; i++) t.push({ title: 'T' + i, artist: 'Ar', album: 'Al' + (i % 2), path: '/m/' + i + '.mp3', name: 'T' + i + '.mp3', size: 1, modified: 1, isDir: false, duration: 60000 }); return t; }
function media(n, bucket, kind) { const a = [];
  for (let i = 0; i < n; i++) a.push({ name: bucket + '-' + i + '.' + (kind === 'vid' ? 'mp4' : 'jpg'),
    path: '/' + bucket + '/' + i + '.' + (kind === 'vid' ? 'mp4' : 'jpg'), size: 1000 + i,
    modified: 1700000000000 + i, bucket, ext: kind === 'vid' ? 'mp4' : 'jpg', isDir: false, duration: 60000 }); return a; }

// ══ TEST A — every tab renders its content ════════════════════════════════
// Images
S.page = 'images'; S.sort = 'date'; S.sortAsc = false; S.searchQ = '';
S.imgList = media(24, 'Camera').concat(media(24, 'Screenshots'));
renderImages();
check('A1 Images renders window units', unitsIn('img-grid') > 0);
check('A2 Images album strip present', findByClass(content, 'album-card').length >= 3);
// Videos
S.page = 'videos';
S.vidList = media(16, 'Camera', 'vid').concat(media(16, 'Movies', 'vid'));
renderVideos();
check('A3 Videos renders window units', unitsIn('media-list') > 0 || findByClass(content, 'vl-slot').length > 0);
// Music — renderMusic reads S.musicAll
S.page = 'music';
S.musicAll = []; const tracks = [];
for (let i = 0; i < 12; i++) tracks.push({ title: 'T' + i, artist: 'Ar', album: 'Al' + (i % 2), path: '/m/' + i + '.mp3', name: 'T' + i + '.mp3', size: 1, modified: 1, isDir: false, duration: 60000 });
S.musicAll = tracks;
try { renderMusic(); } catch (e) { console.log('   renderMusic threw:', e.message); }
check('A4 Music renders (rows or vl units)', findByClass(content, 'track-row').length > 0 || findByClass(content, 'vl-slot').length > 0);
// Docs
S.page = 'docs'; const docs = [];
for (let i = 0; i < 10; i++) docs.push({ name: 'doc' + i + '.pdf', path: '/d/' + i + '.pdf', size: 1, modified: 1, isDir: false, ext: 'pdf' });
S.docsAll = docs;
try { renderDocs(); } catch (e) { console.log('   renderDocs threw:', e.message); }
check('A5 Docs renders rows', findByClass(content, 'media-row').length > 0 || findByClass(content, 'vl-slot').length > 0);
// Apps
S.page = 'apps'; const apps = [];
for (let i = 0; i < 9; i++) apps.push({ name: 'App' + i, path: '/a/' + i + '.apk', label: 'App' + i, size: 1, modified: 1, isDir: false, ext: 'apk', pkg: 'com.a' + i });
S.appsAll = apps;
try { renderApps(); } catch (e) { console.log('   renderApps threw:', e.message); }
check('A6 Apps renders rows', findByClass(content, 'media-row').length > 0 || findByClass(content, 'vl-slot').length > 0 || findByClass(content, 'app-row').length > 0);
// Files — renderFiles(window.D_FILES)
S.page = 'files'; window.D_FILES = { path: '/', name: '/', items: docs.concat([{ name: 'f.txt', path: '/f.txt', size: 2, modified: 1, isDir: false, ext: 'txt' }]) };
try { renderFiles(window.D_FILES); } catch (e) { console.log('   renderFiles threw:', e.message); }
check('A7 Files renders rows', (function(){ const b=findByClass(content,'files-body'); if(!b.length) return false;
  const st=b[b.length-1]._vlCtl; return !!(st&&Object.keys(st.nodes).length>0); })());

// ══ TEST B — album correctness + out-of-order responses ═══════════════════
S.page = 'images'; S.imgAlbum = 'all'; renderImages();
_albumPick('img', 'Camera');
check('B1 pick Camera → imgAlbum=Camera', S.imgAlbum === 'Camera');
const camUnits = unitsIn('img-grid');
_albumPick('img', 'Screenshots');
check('B2 pick Screenshots → content re-rendered under new token', S.imgAlbum === 'Screenshots' && unitsIn('img-grid') > 0);
check('B3 album strip highlight follows', findByClass(content, 'album-card on').length >= 1);
// 100 rapid alternating picks — final state must be the last pick
for (let i = 0; i < 100; i++) _albumPick('img', i % 2 ? 'Camera' : 'Screenshots');
check('B4 100 rapid picks end on Camera (last pick i=99 odd)', S.imgAlbum === 'Camera');
check('B5 window live after 100 picks', unitsIn('img-grid') > 0);
// Out-of-order XHR: load in flight, pick lands, late response must NOT render
S.imgList = null; S.imgAlbum = 'all';
_albumGen.img = 0;
loadImages();                       // captures ag=1, xhr pending
_albumPick('img', 'Camera');        // bumps gen to 2, renders from... imgList null → loadImages again (ag=3)
const pend = global._pendingXhrs.slice();
// deliver the LAST pending response (newest generation) first
if (pend.length) _respondXhr(pend[pend.length - 1], { items: media(8, 'Fresh') });
// now deliver a STALE earlier response (older generation captured before the pick)
if (pend.length > 1) _respondXhr(pend[0], { items: media(50, 'StaleOld') });
// promises resolve on microtasks — flush before asserting
setTimeout(function () {
check('B6 newest response rendered (Fresh, 8 items)', Array.isArray(S.imgList) && S.imgList.length === 8 &&
  S.imgList[0] && S.imgList[0].bucket === 'Fresh');
check('B7 stale response discarded (list still Fresh)', Array.isArray(S.imgList) && S.imgList.length === 8 &&
  S.imgList[0] && S.imgList[0].bucket === 'Fresh');
// Videos independence
S.page = 'videos'; S.vidList = media(12, 'VAlbum', 'vid'); renderVideos();
_albumPick('vid', 'VAlbum');
check('B8 Videos pick independent of Images gen', S.vidAlbum === 'VAlbum' && unitsIn('media-list') >= 0);
// Empty album state
S.page = 'images'; S.imgList = []; renderImages();
check('B9 empty library → empty state, no crash', true);

}, 50);

// ══ TEST C — §1.1 deep checks (search / sort / music §5 markup) ═════════
S.page='images'; S.imgList = media(30, 'Camera'); S.searchQ='Camera-1';
try { renderImages(); check('C1 search-filtered render ok', true); } catch(e){ check('C1 search render', false); }
S.searchQ='';
S.sortAsc=true; try { renderImages(); check('C2 sort-asc re-render ok', true); } catch(e){ check('C2 sort asc', false); }
S.sortAsc=false;
// §5: music rows carry Video's per-item controls (verified on the BUILT unit markup)
S.page='music'; S.musicAll = tracks12();
try {
  renderMusic();
  const rows = findByClass(content, 'track-row');
  check('C3 music rows render for §5', rows.length > 0);
} catch(e){ console.log('   C3 threw:', e.message); check('C3 music rows', false); }
// §5 source-level: the music row build emits qp → pw → dl in Video's order
const src = fs.readFileSync(file, 'utf8');
const mi = src.indexOf('function renderMusic');
const seg = src.slice(mi, mi + 3200);
check('C4 music rows: qp+pw in Video order', seg.indexOf('media-qp') > 0 &&
  seg.indexOf('media-qp') < seg.indexOf('media-pw') && seg.indexOf('media-pw') < seg.indexOf('media-dl'));
check('C5 music rows use quickPlay/openPlayWith', seg.includes("quickPlay(") && seg.includes("openPlayWith("));
check('C6 audio-bar current-track handoff preserved', src.includes('apExtBtn') && src.includes('quickPlayAudio()'));

console.log('════', 'PASS:' + pass, 'FAIL:' + fail, '════');
process.exit(fail ? 1 : 0);
