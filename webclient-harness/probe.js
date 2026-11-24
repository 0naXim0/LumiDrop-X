// ── Pass 19 empirical probe ──────────────────────────────────────────────
// Goal: prove or disprove, by executing the REAL shipped code paths, that
// FP18's _nodesTok eviction removes freshly placed cells (the reported
// "containers render, content missing" regression).
//
// Strategy: load the main <script> of a given index.html build with a DOM
// shim that records what _vlSync actually leaves in the box, then drive
// renderImages() through the real pipeline (real _vlPipeline, real
// renderImages, real _vlSet → _vl → st.set → _vlSync).
const fs = require('fs');
const file = process.argv[2];
const html = fs.readFileSync(file, 'utf8');
const lines = html.split('\n');
const starts = []; const ends = [];
lines.forEach((l, i) => { if (l.includes('<script')) starts.push(i); if (l.includes('</script>')) ends.push(i); });
// main script = the big one (starts[1] .. ends[1]); there are exactly 2
const js = lines.slice(starts[1] + 1, ends[ends.length - 1]).join('\n');

// ── DOM shim ──────────────────────────────────────────────────────────────
class Elem {
  constructor(tag) { this.tagName = (tag||'div').toUpperCase(); this.children = []; this.parentNode = null;
    this._attrs = {}; this.style = {}; this.className = ''; this.innerHTML = ''; this._listeners = {};
    this.scrollTop = 0; this.scrollHeight = 0; this.clientWidth = 400; this.clientHeight = 600; }
  get firstChild() { return this.children[0] || null; }
  appendChild(n) { if (n.parentNode) n.parentNode.removeChild(n); n.parentNode = this; this.children.push(n); return n; }
  set innerHTML(v) { this.children.forEach(c => c.parentNode = null); this.children = [];
    this._html = v; if (v && v.indexOf('data-pg') >= 0) { const d = new Elem('div'); d.setAttribute('data-pg', '1'); d.className = 'pg-media'; this.appendChild(d); } }
  get innerHTML() { return this._html || ''; }
  insertBefore(n, ref) { if (n.parentNode) n.parentNode.removeChild(n);
    const i = ref ? this.children.indexOf(ref) : -1;
    n.parentNode = this; if (i < 0) this.children.push(n); else this.children.splice(i, 0, n); return n; }
  removeChild(n) { const i = this.children.indexOf(n); if (i >= 0) this.children.splice(i, 1); n.parentNode = null; return n; }
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
  // offsetTop probe: slot transform emulation — units sit at i*H; vl uses
  // offsetTop deltas of probe children to measure stride; give each child a
  // deterministic offsetTop by sibling index * 150.
  get offsetTop() { const i = Array.prototype.indexOf.call(this.parentNode ? this.parentNode.children : [], this); return i < 0 ? 0 : i * 150; }
  get offsetHeight() { return 150; }
  get offsetWidth() { return 100; }
}
function collectAll(root, sel) { const out = [];
  (function walk(n) { if (!n) return; (n.children || []).forEach(walk);
    if (sel[0] === '.' && (' ' + (n.className || '') + ' ').includes(' ' + sel.slice(1) + ' ')) out.push(n);
    else if (sel[0] === '#' && n._attrs && n._attrs.id === sel.slice(1)) out.push(n); })(root);
  return out; }
global.document = {
  _byId: {},
  querySelectorAll(sel) { const out = [];
    (function walk(n) { if (!n) return; if (sel[0] === '.' && (' ' + (n.className || '') + ' ').includes(' ' + sel.slice(1) + ' ')) out.push(n);
      (n.children || []).forEach(walk); })(this.body);
    out.push(...collectAll(this.documentElement, sel)); return out; },
  querySelector(sel) { return this.querySelectorAll(sel)[0] || null; },
  createElement(t) { return new Elem(t); },
  getElementById(id) { return this._byId[id] || (this._byId[id] = new Elem('div')); },
  addEventListener() {}, removeEventListener() {},
  documentElement: new Elem('html'), body: new Elem('body'),
  getComputedStyle(el) { return { paddingLeft: '0px', paddingRight: '0px', paddingTop: '0px', paddingBottom: '0px' }; }
};
global.window = { addEventListener() {}, removeEventListener() {}, setTimeout, clearTimeout,
  getComputedStyle: (el) => document.getComputedStyle(el), location: { protocol: 'http:', host: 'x', pathname: '/' },
  navigator: { userAgent: 'probe' }, EventSource: undefined };
global.setInterval = () => 0;   // keep the event loop free — timers at top level must not hold the process
global.clearInterval = () => {};
global.requestAnimationFrame = (f) => setTimeout(f, 0);
global.cancelAnimationFrame = clearTimeout;
global.Image = class { set src(v) { this._src = v; if (this.onload) this.onload(); } };
// localStorage shim (settings persistence reads)
const _ls = {}; global.localStorage = { getItem: k => (k in _ls ? _ls[k] : null), setItem: (k, v) => { _ls[k] = String(v); }, removeItem: k => { delete _ls[k]; } };
// XHR shim: album list responses
global._pendingXhrs = [];
global.XMLHttpRequest = class {
  open(m, u) { this._url = u; this.readyState = 4; }
  setRequestHeader() {}
  send() { global._pendingXhrs.push(this); }
  addEventListener() {}
};
// fetch/xhrGet is XmlHttpRequest-based inside index.html; expose a driver
global._respondXhr = function (xhr, body) {
  xhr.status = 200; xhr.responseText = JSON.stringify(body); xhr.response = xhr.responseText;
  xhr.responseText !== undefined && xhr.onreadystatechange && xhr.onreadystatechange();
  xhr.onload && xhr.onload();
};
// location/XHR enough; eval the script with a function wrapper, but the
// script uses top-level function declarations — use indirect eval so its
// globals land on globalThis where our driver can reach them.
(0, eval)(js);

// ── Drive: the REAL renderImages pipeline ────────────────────────────────
const W = globalThis;
const content = document.getElementById('content');
// 24 images across 3 albums — enough to exercise windowing with identical
// first-window numbers across albums (the FP18 crash shape).
function items(n, bucket) {
  const a = [];
  for (let i = 0; i < n; i++) a.push({ name: bucket + '-' + i + '.jpg', path: '/' + bucket + '/' + i + '.jpg',
    size: 1000 + i, modified: 1700000000000 + i, bucket, ext: 'jpg', isDir: false });
  return a;
}
const all = items(8, 'Camera').concat(items(8, 'Screenshots'), items(8, 'WhatsApp'));
S.imgList = all;
S.page = 'images';
S.sort = 'date'; S.sortAsc = false; S.searchQ = '';
// Render album 'all' first (the initial state)
renderImages();
// querySelector doesn't exist in shim — find the grid via _pgWrap's child
function findByClass(root, cls) { const out = [];
  (function walk(n) { if (!n) return; if ((' ' + (n.className || '') + ' ').includes(' ' + cls + ' ')) out.push(n);
    (n.children || []).forEach(walk); })(root); return out; }
let grids = findByClass(content, 'img-grid');
const box = grids[grids.length - 1];
function liveUnits(b) { let n = 0; (function walk(x) { if (!x) return;
    if ((' ' + (x.className || '') + ' ').includes(' img-cell ')) n++;
    (x.children || []).forEach(walk); })(b); return n; }
console.log('[A] after initial renderImages (album=all): live .img-cell units =', liveUnits(box));
// Pick Camera — the exact FP18 regression path: same window numbers, new token
_albumPick('img', 'Camera');
let g2 = findByClass(content, 'img-grid'); const box2 = g2[g2.length - 1];
console.log('[B] after _albumPick(Camera): live .img-cell units =', liveUnits(box2));
// Pick Screenshots
_albumPick('img', 'Screenshots');
let g3 = findByClass(content, 'img-grid'); const box3 = g3[g3.length - 1];
console.log('[C] after _albumPick(Screenshots): live .img-cell units =', liveUnits(box3));
// Rapid 20 alternating picks
for (let i = 0; i < 20; i++) _albumPick('img', i % 2 ? 'Camera' : 'Screenshots');
let g4 = findByClass(content, 'img-grid'); const box4 = g4[g4.length - 1];
console.log('[D] after 20 rapid picks: live .img-cell units =', liveUnits(box4));
// Verify the CONTENT is the last picked album (Screenshots = even picks)
const names = findByClass(box4, 'img-cell-inner').length;
console.log('[E] inner nodes present =', names);
const st = box4._vlCtl;
const okInit = liveUnits(findByClass(content, 'img-grid')[0] || box) > 0;
console.log('[F] controller state: first=', st.first, 'last=', st.last, 'items=', st.items.length,
  'nodes keys=', Object.keys(st.nodes).length, 'slots=', st.slots.length,
  'listTok=', st._listTok, 'tokApplied=', st._tokApplied, 'nodesTok=', st._nodesTok);
process.exit(0);
