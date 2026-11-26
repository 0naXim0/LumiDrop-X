// ── PASS 24 acceptance harness — the unified selection treatment ───────────
// Executes the REAL index.html script under a DOM shim, then drives the
// REAL renderers + REAL selection functions through the Pass-24 test plan:
// thumbnail preservation (no rebuild, no thumbUrl re-request during
// selection), identity-keyed selection (vl slot keys / data-k / data-tp),
// counter sync, select-all/deselect-all, sorting identity, rapid toggling,
// the virtualized node-reuse discipline, and source-level pins that the
// on-thumbnail check badges are GONE and the ring rules exist.
const fs = require('fs');
const file = process.argv[2];
const lines = fs.readFileSync(file, 'utf8').split('\n');
const starts = [], ends = [];
lines.forEach((l, i) => { if (l.includes('<script')) starts.push(i); if (l.includes('</script')) ends.push(i); });
const js = lines.slice(starts[1] + 1, ends[ends.length - 1]).join('\n');

// ── DOM shim — a NESTING div parser (the vl grid patch walks el.children
//    expecting exactly the cell divs), attrs carried per element ──────────
class Elem {
  constructor(tag) { this.tagName = (tag || 'div').toUpperCase(); this.nodeType = 1; this.children = []; this.parentNode = null;
    this._attrs = {}; this.style = {}; this.className = ''; this._html = ''; this._listeners = {};
    this.scrollTop = 0; this.scrollHeight = 8000; this.clientWidth = 1024; this.clientHeight = 600;
    this.value = ''; this.currentTime = 0; this.duration = 100; this.paused = true;
    this.muted = false; this._volume = 1; this.src = ''; }
  get firstChild() { return this.children[0] || null; }
  appendChild(n) { if (n.parentNode) n.parentNode.removeChild(n); n.parentNode = this; this.children.push(n); return n; }
  insertBefore(n, ref) { if (n.parentNode) n.parentNode.removeChild(n);
    const i = ref ? this.children.indexOf(ref) : -1;
    n.parentNode = this; if (i < 0) this.children.push(n); else this.children.splice(i, 0, n); return n; }
  removeChild(n) { const i = this.children.indexOf(n); if (i >= 0) this.children.splice(i, 1); n.parentNode = null; return n; }
  _parse(v) { this._html = String(v);
    // nesting parser over div open/close tags; non-div tags are skipped
    // (their content is never needed as elements by the vl/selection code)
    const stack = [this];
    const re = /<(\/?)div\s*([^>]*)>|<\/div>/g; let m;
    while ((m = re.exec(this._html)) !== null) {
      if (m[1] === '/') { if (stack.length > 1) stack.pop(); continue; }
      if (m[2] === undefined && m[0] === '</div>') { if (stack.length > 1) stack.pop(); continue; }
      const d = new Elem('div');
      const are = /([a-zA-Z-]+)="([^"]*)"/g; let a;
      while ((a = are.exec(m[2] || '')) !== null) {
        if (a[1] === 'class') d.className = a[2]; else d.setAttribute(a[1], a[2]);
      }
      if (d._attrs.id) document._byId[d._attrs.id] = d;
      stack[stack.length - 1].appendChild(d);
      stack.push(d);
    } }
  set innerHTML(v) { this.children.forEach(c => c.parentNode = null); this.children = []; this._parse(v); }
  get innerHTML() { return this._html; }
  insertAdjacentHTML(pos, html) { this._parse((this._html || '') + html); }   // 'beforeend' is all the app uses
  setAttribute(k, v) { this._attrs[k] = String(v); if (k === 'value') this.value = String(v); }
  getAttribute(k) { return k in this._attrs ? this._attrs[k] : null; }
  removeAttribute(k) { delete this._attrs[k]; if (k === 'src') this.src = ''; }
  addEventListener(t, f) { (this._listeners[t] = this._listeners[t] || []).push(f); }
  querySelectorAll() { return []; } querySelector() { return null; }
  removeEventListener() {} click() {} focus() {} blur() {}
  load() {} pause() { this.paused = true; } play() { this.paused = false; return undefined; }
  get classList() { const self = this; return {
    add(c) { if (!(' ' + self.className + ' ').includes(' ' + c + ' ')) self.className = (self.className + ' ' + c).trim(); },
    remove(c) { self.className = (' ' + self.className + ' ').replace(' ' + c + ' ', ' ').trim(); },
    contains(c) { return (' ' + self.className + ' ').includes(' ' + c + ' '); } }; }
  get offsetTop() { const i = Array.prototype.indexOf.call(this.parentNode ? this.parentNode.children : [], this); return i < 0 ? 0 : i * 150; }
  get offsetHeight() { return 150; } get offsetWidth() { return 100; }
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

// ── helpers ───────────────────────────────────────────────────────────────
const W = globalThis;
const content = document.getElementById('content');
// the real page's #content lives inside <body>; attaching it makes
// document.querySelectorAll(...) — used by selectAllVisible — see the tree
if (content.parentNode !== document.body) document.body.appendChild(content);
const src = fs.readFileSync(file, 'utf8');
let pass = 0, fail = 0;
function check(name, ok, extra) { if (ok) { pass++; console.log('PASS', name); } else { fail++; console.log('FAIL', name, extra || ''); } }
function findByClass(root, cls) { const out = [];
  (function walk(n) { if (!n) return; if ((' ' + (n.className || '') + ' ').includes(' ' + cls + ' ')) out.push(n);
    (n.children || []).forEach(walk); })(root); return out; }
function selPillHidden() { return document.getElementById('selDlBtn').classList.contains('hid'); }
function counterTxt() { return String(document.getElementById('selDlCount').textContent); }
function fileCounterTxt() { return String(document.getElementById('filesSelDlCount').textContent); }
// identity of a rendered item:
//  1. keyed-set nodes carry data-k (the keyFn output)
//  2. track rows carry data-tp (their path)
//  3. vl slots carry _vlKey — non-grid unit keys ARE the item key; grid unit
//     keys are g{row}|p1|p2|... mapped by the cell's index in its row
//  4. fallback: the item's own thumbnail URL inside the node's markup
function keyOfEl(el) {
  if (!el) return null;
  if (el._attrs && el._attrs['data-k']) return el._attrs['data-k'];
  if (el._attrs && el._attrs['data-tp']) return el._attrs['data-tp'];
  let n = el;
  while (n && n._vlKey === undefined) n = n.parentNode;
  if (n && n._vlKey != null) {
    const k = String(n._vlKey);
    if (k.charAt(0) !== 'g') return k;
    const parts = k.split('|');
    if (parts.length > 1) {
      const cellCls = (' ' + el.className + ' ').includes(' img-cell ') ? 'img-cell'
        : (' ' + el.className + ' ').includes(' grid-cell ') ? 'grid-cell' : null;
      if (cellCls) {
        const sibs = findByClass(n, cellCls);
        const idx = sibs.indexOf(el);
        if (idx >= 0 && parts[idx + 1]) return parts[idx + 1];
      }
    }
  }
  const m = /path=([^&"']+)/.exec(el._html || '');
  return m ? decodeURIComponent(m[1]) : null;
}
// a ✓ glyph may exist ONLY in the dedicated check column of a row markup —
// never inside the thumb/icon container markup
function glyphInDedicatedColumn(html, columnCls, thumbCls) {
  if (!html) return false;
  const colIdx = html.indexOf('class="' + columnCls + '"');
  const glyphIdx = html.indexOf('10003');
  const thumbSeg = (html.match(new RegExp('class="' + thumbCls + '"[\\s\\S]*?<\\/div>')) || [''])[0];
  return colIdx >= 0 && glyphIdx > colIdx && thumbSeg.indexOf('10003') < 0;
}

// spy on the REAL thumbnail URL builder — selection must NEVER re-request
const _tu = W.thumbUrl; let tuCalls = 0;
W.thumbUrl = function () { tuCalls++; return _tu.apply(this, arguments); };

// fixtures
function pics(n, b) { const a = []; for (let i = 1; i <= n; i++)
  a.push({ path: '/storage/emulated/0/DCIM/' + b + '/photo-' + i + '.jpg', name: 'photo-' + i + '.jpg', bucket: b, size: 1024 * i, modified: 1700000000000 + i }); return a; }
function vidList(n) { const a = []; for (let i = 1; i <= n; i++)
  a.push({ path: '/storage/emulated/0/Movies/clip-' + i + '.mp4', name: 'clip-' + i + '.mp4', duration: 60 * i, size: 2048 * i, modified: 1700000000000 }); return a; }
function tracks(n) { const a = []; for (let i = 1; i <= n; i++)
  a.push({ path: '/storage/emulated/0/Music/track-' + i + '.mp3', name: 'track-' + i + '.mp3', title: 'Track ' + i, artist: 'Artist ' + i, duration: 180 + i, modified: 1700000000000 }); return a; }
function docList(n) { const a = []; for (let i = 1; i <= n; i++)
  a.push({ path: '/storage/emulated/0/Docs/doc-' + i + '.pdf', name: 'doc-' + i + '.pdf', ext: 'pdf', size: 512 * i, modified: 1700000000000 }); return a; }
function appList(n) { const a = []; for (let i = 1; i <= n; i++)
  a.push({ pkg: 'com.t' + i + '.app', name: 'App ' + i, version: '1.' + i, size: 4096 * i, modified: 1700000000000 }); return a; }
function fileItems(n) { const a = [];
  a.push({ path: '/storage/emulated/0/Pics', name: 'Pics', isDir: true, size: 0, modified: 1700000000000 });
  for (let i = 1; i <= n; i++)
    a.push({ path: '/storage/emulated/0/Pics/pic-' + i + '.jpg', name: 'pic-' + i + '.jpg', isDir: false, ext: 'jpg', size: 900 * i, modified: 1700000000000 });
  a.push({ path: '/storage/emulated/0/report.pdf', name: 'report.pdf', isDir: false, ext: 'pdf', size: 4096, modified: 1700000000000 });
  a.push({ path: '/storage/emulated/0/song.mp3', name: 'song.mp3', isDir: false, ext: 'mp3', size: 4096, modified: 1700000000000 });
  return a; }

// ═══ GROUP 1 — source-level pins: the badge system is gone, the ring exists ═══
check('S1 old on-thumbnail check badge CSS (\\2713 circle) removed entirely',
  src.indexOf("\\2713") < 0);
check('S2 the old .sel-mode .img-cell::before CSS RULE is gone (dark circle on every cell)',
  !src.includes('.sel-mode .img-cell::before{') && !src.includes('.img-cell.sel::before{'));
check('S3 ring rule on .img-cell.sel::after (2px accent + 7px radius, order-independent)',
  /\.img-cell\.sel::after\{(?=[^}]*border:2px solid var\(--acc\))(?=[^}]*border-radius:7px)/.test(src));
check('S4 ring rule on .grid-cell.sel::after (2px accent + 7px radius, order-independent)',
  /\.grid-cell\.sel::after\{(?=[^}]*border:2px solid var\(--acc\))(?=[^}]*border-radius:7px)/.test(src));
check('S5 ring rule on file-item thumbs (.file-thumb-wrap/.file-icon-box)',
  /\.file-item\.sel \.file-thumb-wrap::after/.test(src) && /\.file-item\.sel \.file-icon-box::after/.test(src));
check('S6 ring rule on media-row thumbs (.media-thumb/.file-icon-box)',
  /\.media-row\.sel \.media-thumb::after/.test(src) && /\.media-row\.sel \.file-icon-box::after/.test(src));
check('S7 track-row.sel joins the unified container tint',
  /\.track-row\.sel\{background:rgba\(145,114,246,\.1\)\}/.test(src));
check('S8 dual-render idiom intact (actions hidden, check column shown in sel-mode)',
  /\.sel-mode \.row-actions\{display:none/.test(src) && /\.sel-mode \.row-check\{/.test(src) && /\.sel-mode \.file-chk\{/.test(src));
check('S9 every selection &#10003; glyph lives in a dedicated check column (file-chk/row-check) — never in a thumb container; any other &#10003; use is only the "✓ Copied" toast',
  (function () {
    let idx = 0, ok = true, selSites = 0;
    while ((idx = src.indexOf('&#10003;', idx + 1)) >= 0) {
      const before = src.slice(Math.max(0, idx - 220), idx);
      const after = src.slice(idx, idx + 20);
      if (/class="(file-chk|row-check)"/.test(before)) { selSites++; continue; }
      if (/^&#10003; Copied/.test(after)) continue;
      ok = false;
    }
    return ok && selSites === 6;   // file-chk ×2, row-check ×4
  })());
check('S10 .file-icon-box is position:relative (its ::after ring anchor)',
  /\.file-icon-box\{[^}]*position:relative/.test(src));

// ═══ GROUP 2 — Images grid: thumbnail preservation + identity + counters ═══
W.S.page = 'images'; W.S.imgList = pics(24, 'Camera');
W.renderImages();
let cells = findByClass(content, 'img-cell');
check('A1 images grid renders cells', cells.length > 0);
const firstCell = cells[0];
const firstKey = keyOfEl(firstCell);
const firstUnitHtml = (firstCell.parentNode && firstCell.parentNode._html) || '';
check('A1b grid cell identity resolves to its item path (slot-key mapping)',
  typeof firstKey === 'string' && firstKey.indexOf('/photo-') > 0);
W.toggleImgSelMode();                                  // enter selection mode (real path)
W.imgClick(firstKey, firstCell);                       // select via the REAL click path
check('A2 selected cell carries .sel', firstCell.classList.contains('sel'));
check('A3 S.sel keyed by the item\'s stable path identity', !!W.S.sel[firstKey] && Object.keys(W.S.sel).length === 1);
check('A4 counter reads 1', counterTxt() === '1');
check('A5 cell node identity preserved (no rebuild on selection)',
  findByClass(content, 'img-cell')[0] === firstCell);
check('A6 cell markup unchanged by selection (thumbnail markup not swapped)',
  ((firstCell.parentNode && firstCell.parentNode._html) || '') === firstUnitHtml);
check('A7 no check glyph ever enters the rendered grid markup',
  !findByClass(content, 'img-cell').some(e => {
    let n = e; while (n && !(n._html && n._html.length)) n = n.parentNode;
    return (n && (n._html || '').indexOf('10003') >= 0) || (e._html || '').indexOf('10003') >= 0;
  }) && src.indexOf("html:'<div class=\"img-cell-inner\">") > 0);
W.imgClick(firstKey, firstCell);                      // deselect via the REAL click path
check('A8 deselect removes .sel and the key', !firstCell.classList.contains('sel') && !W.S.sel[firstKey]);
check('A9 deselect leaves zero selection traces (state + pill hidden)',
  Object.keys(W.S.sel).length === 0 && selPillHidden());

// select-all / deselect-all (real path)
W.selectAllVisible();
const rendered = findByClass(content, 'img-cell');
const selCount = rendered.filter(e => e.classList.contains('sel')).length;
check('A10 select all marks every RENDERED cell', selCount > 0 && rendered.every(e => e.classList.contains('sel')));
check('A11 select-all counter equals the SELECTED COUNT (virtualization renders a window; select-all covers the whole list)',
  counterTxt() === String(Object.keys(W.S.sel).length) && Object.keys(W.S.sel).length >= selCount);
check('A11b select-all keys match the rendered items\' identities',
  rendered.every(e => !!W.S.sel[keyOfEl(e)]));
W.selectAllVisible();
check('A12 deselect all clears every cell, S.sel, and hides the pill',
  findByClass(content, 'img-cell').every(e => !e.classList.contains('sel')) &&
  Object.keys(W.S.sel).length === 0 && selPillHidden());

// sorting keeps selection identity (Test H)
const sortKey = keyOfEl(findByClass(content, 'img-cell')[3]);
W.imgClick(sortKey, findByClass(content, 'img-cell')[3]);
W.S.sortAsc = true; W.renderImages();                  // pipeline re-sort + full re-render
const selAfter = findByClass(content, 'img-cell').filter(e => e.classList.contains('sel'));
check('A13 selection follows the ITEM across a re-sort (still 1 selected, same key)',
  Object.keys(W.S.sel).length === 1 && !!W.S.sel[sortKey] &&
  selAfter.length === 1 && keyOfEl(selAfter[0]) === sortKey);
W.S.sortAsc = false;
W.clearSelSmooth();

// ═══ GROUP 3 — thumbnail preservation: selection never re-requests thumbs ═══
// settle the pipeline on the CURRENT sort state first (A13 flipped it — a
// re-render HERE would legitimately rebuild; from now on it is a cache hit)
W.renderImages();
const tuBefore = tuCalls;
W.toggleImgSelMode();
const bCells = findByClass(content, 'img-cell');
for (let i = 0; i < bCells.length; i++) W.imgClick(keyOfEl(bCells[i]), bCells[i]);
W.selectAllVisible(); W.selectAllVisible();
W.toggleImgSelMode();                                  // exits + clearSel (real path)
check('B1 zero thumbUrl calls across enter/select/deselect-all/exit (no thumbnail reloads)',
  tuCalls === tuBefore);
check('B2 exiting selection mode clears every visual + state trace',
  Object.keys(W.S.sel).length === 0 && findByClass(content, 'img-cell').every(e => !e.classList.contains('sel')) &&
  !content.classList.contains('sel-mode'));

// same-list re-render → nodes REUSED, the .sel patch applied (recycled views
// never show another item's selection)
W.toggleImgSelMode();
const c0 = findByClass(content, 'img-cell')[0];
W.imgClick(keyOfEl(c0), c0);
W.renderImages();                                     // SAME list/filter — keyed reuse path
const again = findByClass(content, 'img-cell');
check('B3 same-list re-render reuses the SAME cell nodes (keyed discipline)',
  again.length > 0 && again[0] === c0);
check('B4 the selected node keeps .sel after the re-render (no stale/lost styling)',
  c0.classList.contains('sel') && Object.keys(W.S.sel).length === 1);
W.clearSelSmooth();

// ═══ GROUP 4 — Videos / Music / Docs / Apps rows (real renderers, real clicks) ═══
function rowSuite(name, page, setup, toggleFn, clickFn, rowCls, thumbCls) {
  W.S.selMode = false; W.S.sel = {}; W.S._si = {}; W.S.page = page; setup();
  toggleFn();                                          // enter selection mode (real path)
  const rows = findByClass(content, rowCls);
  if (!rows.length) { check(name + ': rows render', false); return; }
  const el0 = rows[0], k0 = keyOfEl(el0);
  check(name + ': row identity resolves', typeof k0 === 'string' && k0.length > 0);
  clickFn(0, el0, k0);
  check(name + ': select via real click adds .sel + identity key', el0.classList.contains('sel') && !!W.S.sel[k0]);
  check(name + ': counter syncs', counterTxt() === '1');
  clickFn(0, el0, k0);
  check(name + ': deselect removes .sel + key and hides the pill',
    !el0.classList.contains('sel') && !W.S.sel[k0] && selPillHidden());
  check(name + ': the ✓ glyph stays in the dedicated column, the thumb markup stays glyph-free',
    glyphInDedicatedColumn(el0._html || el0.innerHTML || '', 'row-check', thumbCls));
  W.clearSelSmooth();                                  // exit + clear
}

rowSuite('C1 videos', 'videos', function () { W.S.vidList = vidList(8); W.renderVideos(); },
  () => W.toggleVidSelMode(),
  (i, el, k) => W.vidRowClick(k, el, 'clip'),
  'media-row', 'media-thumb');

W.S.page = 'music'; W.S.musicAll = tracks(8); W.renderMusic();
let tRows = findByClass(content, 'track-row');
W.toggleMusicSelMode();
const tk1 = keyOfEl(tRows[1]), tk2 = keyOfEl(tRows[2]);
W.musicRowClick(tk1, tRows[1], 'Track 1');
W.musicRowClick(tk2, tRows[2], 'Track 2');
check('C2 music: two tracks selected via real clicks',
  Object.keys(W.S.sel).length === 2 && tRows[1].classList.contains('sel') && tRows[2].classList.contains('sel'));
W.musicRowClick(tk1, tRows[1], 'Track 1');
check('C3 music: deselecting ONE removes exactly that track',
  Object.keys(W.S.sel).length === 1 && !tRows[1].classList.contains('sel') && tRows[2].classList.contains('sel'));
W.S._playingPath = tk2; W.renderMusic();               // playing + selected on the same row
tRows = findByClass(content, 'track-row');
const t2 = tRows.filter(e => keyOfEl(e) === tk2)[0];
check('C4 music: playing ♪ + selection coexist on one row (playback indicator intact)',
  !!t2 && t2.classList.contains('playing') && t2.classList.contains('sel') && t2.getAttribute('data-tp') === tk2);
W.clearSelSmooth(); W.S._playingPath = null;

rowSuite('C5 docs', 'docs', function () { W.S.docsAll = docList(8); W.renderDocs(); },
  () => W.toggleDocsSelMode(),
  (i, el, k) => W.docsRowClick(k, el, 'doc'),
  'media-row', 'file-icon-box');

rowSuite('C6 apps', 'apps', function () { W.S.appsAll = appList(8); W.renderApps(); },
  () => W.toggleAppsSelMode(),
  (i, el, k) => W.appsRowClick(k, el, 'app'),
  'media-row', 'file-icon-box');
check('C7 apps: selection stays keyed by PACKAGE identity (apk:pkg convention preserved)',
  src.indexOf("'apk:'") > 0 && /keyFn:function\(it\)\{return 'apk:'\+it\.pkg;?\}/.test(src));

// ═══ GROUP 5 — Files tab: grid mode AND list mode (real renderers, real clicks) ═══
W.S.page = 'files'; W.S.selMode = false; W.S.sel = {}; W.S._si = {};
const D = { path: '/storage/emulated/0', items: fileItems(10), isRoot: false };
W.S.viewGrid = true; W.renderFiles(D);
let gCells = findByClass(content, 'grid-cell');
check('D1 files grid renders cells', gCells.length > 0);
W.toggleFileSelMode();
const gImgCell = gCells.filter(e => {
  let n = e; while (n && !(n._html && n._html.length)) n = n.parentNode;
  const h = (n && n._html) || '';
  return h.indexOf('file-thumb-wrap') >= 0 || /path=/.test(h);   // grid thumbs are bare <img src="/api/thumb?path=…">
})[0];
const gKey = keyOfEl(gImgCell);
check('D1b the picked grid cell is an image item (identity resolves)',
  typeof gKey === 'string' && gKey.indexOf('pic-') > 0);
W.gridItemClick(gKey, false, 'pic', gImgCell);
check('D2 files grid: select via real click (identity key, .sel class)',
  !!W.S.sel[gKey] && gImgCell.classList.contains('sel'));
check('D3 files grid: no check glyph anywhere in the rendered grid markup (the badge system is gone)',
  !findByClass(content, 'grid-cell').some(e => {
    let n = e; while (n && !(n._html && n._html.length)) n = n.parentNode;
    return n && (n._html || '').indexOf('10003') >= 0;
  }));
W.clearSelSmooth();
W.S.viewGrid = false; W.renderFiles(D);
let fRows = findByClass(content, 'file-item');
check('D4 files list renders rows', fRows.length > 0);
W.toggleFileSelMode();
const fImgRow = fRows.filter(e => (e._html || '').indexOf('file-thumb-wrap') >= 0)[0];
const fKey = keyOfEl(fImgRow);
W.fileItemClick(null, fKey, false, 'pic', fImgRow);
check('D5 files list: select via real click (key + .sel + counter)',
  !!W.S.sel[fKey] && fImgRow.classList.contains('sel') && fileCounterTxt() === '1');
check('D6 files list: the ✓ glyph lives in the dedicated file-chk column, the thumbnail markup stays glyph-free',
  glyphInDedicatedColumn(fImgRow._html || '', 'file-chk', 'file-thumb-wrap'));
W.fileItemClick(null, fKey, false, 'pic', fImgRow);
check('D7 files list: deselect clears row + key',
  !fImgRow.classList.contains('sel') && !W.S.sel[fKey]);
W.clearSelSmooth();

// ═══ GROUP 6 — rapid interaction (Test E) ═══
W.S.page = 'images'; W.S.imgList = pics(40, 'Rapid'); W.renderImages();
W.toggleImgSelMode();
const rapid = findByClass(content, 'img-cell');
let rapidOk = true;
for (let i = 0; i < rapid.length; i++) {
  W.imgClick(keyOfEl(rapid[i]), rapid[i]);
  if (!rapid[i].classList.contains('sel')) rapidOk = false;
}
const nSel = Object.keys(W.S.sel).length;
const nCls = rapid.filter(e => e.classList.contains('sel')).length;
check('E1 rapid select all-by-one: counter == DOM .sel count == S.sel size',
  rapidOk && nSel === nCls && counterTxt() === String(nCls));
const evenCount = Math.ceil(rapid.length / 2);
for (let i = 0; i < rapid.length; i += 2) W.imgClick(keyOfEl(rapid[i]), rapid[i]);
check('E2 rapid interleaved deselect: exactly the even cells cleared, odd kept, all three views agree',
  rapid.filter(e => e.classList.contains('sel')).length === nCls - evenCount &&
  Object.keys(W.S.sel).length === rapid.filter(e => e.classList.contains('sel')).length &&
  counterTxt() === String(Object.keys(W.S.sel).length));
W.clearSelSmooth();
check('E3 clearSel leaves zero stale visual state anywhere',
  Object.keys(W.S.sel).length === 0 &&
  findByClass(content, 'img-cell').every(e => !e.classList.contains('sel')) &&
  !content.classList.contains('sel-mode'));

console.log('════', 'PASS:' + pass, 'FAIL:' + fail, '════');
process.exit(fail ? 1 : 0);
