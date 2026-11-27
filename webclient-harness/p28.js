// ── PASS 28 acceptance harness — browser-native download CANCELLATION ─────
// Executes the REAL index.html script with the REAL legacy-iOS routing active
// (an iPad 4 / iOS 10.3.3 Safari user agent), then drives the REAL direct-
// download pipeline's CANCELLATION behaviors: the watcher's 'cancelled'
// verdict advancing the serial pump (the row the server's verdict regime
// resolves as a deliberate browser cancel), the next queued item proceeding
// intact, a fresh dispatch immediately after a cancelled one, and the Pass-27
// direct-download architecture itself (hidden-iframe dispatch, zero
// page-memory receives) still intact.
const fs = require('fs');
const file = process.argv[2] || '/home/z/my-project/work/lumidrop/LumiDropX/app/src/main/assets/index.html';
const lines = fs.readFileSync(file, 'utf8').split('\n');
const starts = [], ends = [];
lines.forEach((l, i) => { if (l.includes('<script')) starts.push(i); if (l.includes('</script')) ends.push(i); });
const js = lines.slice(starts[1] + 1, ends[ends.length - 1]).join('\n');

// ── DOM shim (same shape as the p27 harness) ──────────────────────────────
class Elem {
  constructor(tag) { this.tagName = (tag || 'div').toUpperCase(); this.children = []; this.parentNode = null;
    this._attrs = {}; this.style = {}; this.className = ''; this._html = ''; this._listeners = {};
    this.scrollTop = 0; this.scrollHeight = 8000; this.clientWidth = 1024; this.clientHeight = 600;
    this.checked = false; this.textContent = ''; this.disabled = false; this.value = ''; this.src = ''; }
  get firstChild() { return this.children[0] || null; }
  appendChild(n) { if (n.parentNode) n.parentNode.removeChild(n); n.parentNode = this; this.children.push(n); return n; }
  insertBefore(n, ref) { if (n.parentNode) n.parentNode.removeChild(n);
    const i = ref ? this.children.indexOf(ref) : -1;
    n.parentNode = this; if (i < 0) this.children.push(n); else this.children.splice(i, 0, n); return n; }
  removeChild(n) { const i = this.children.indexOf(n); if (i >= 0) this.children.splice(i, 1); n.parentNode = null; return n; }
  set innerHTML(v) { this.children.forEach(c => c.parentNode = null); this.children = []; this._html = String(v); }
  get innerHTML() { return this._html; }
  setAttribute(k, v) { this._attrs[k] = String(v); if (k === 'src') this.src = String(v); if (k === 'href') this._href = String(v); if (k === 'download') this._download = String(v); }
  getAttribute(k) { return k in this._attrs ? this._attrs[k] : null; }
  removeAttribute(k) { delete this._attrs[k]; if (k === 'src') this.src = ''; }
  addEventListener(t, f) { (this._listeners[t] = this._listeners[t] || []).push(f); }
  click() { global.__clicks = global.__clicks || []; global.__clicks.push(this._href || this._download || this._attrs['onclick'] || this.tagName); }
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
  querySelectorAll(sel) { return []; },
  querySelector(sel) { return null; },
  addEventListener() {}, removeEventListener() {},
  documentElement: new Elem('html'), body: new Elem('body'),
  getComputedStyle(el) { return { paddingLeft: '0px', paddingRight: '0px', paddingTop: '0px', paddingBottom: '0px' }; }
};
const IPAD_UA = 'Mozilla/5.0 (iPad; CPU OS 10_3_3 like Mac OS X) AppleWebKit/603.3.8 (KHTML, like Gecko) Version/10.0.3 Mobile/14G60 Safari/602.1';
global.window = { addEventListener() {}, removeEventListener() {}, setTimeout, clearTimeout,
  getComputedStyle: (el) => document.getComputedStyle(el), location: { protocol: 'http:', host: 'x', pathname: '/' },
  navigator: { userAgent: IPAD_UA }, EventSource: undefined, innerWidth: 1024, innerHeight: 600, MSStream: undefined };
Object.defineProperty(globalThis, 'navigator', { value: { userAgent: IPAD_UA }, configurable: true, writable: true });
const INTERVALS = [];
global.setInterval = (fn, ms) => { INTERVALS.push(fn); return INTERVALS.length; };
global.clearInterval = () => {};
global.requestAnimationFrame = (f) => setTimeout(f, 0);
global.cancelAnimationFrame = clearTimeout;
global.Image = class { set src(v) { this._src = v; if (this.onload) this.onload(); } };
const _ls = {}; global.localStorage = { getItem: k => (k in _ls ? _ls[k] : null), setItem: (k, v) => { _ls[k] = String(v); }, removeItem: k => { delete _ls[k]; } };
let objectUrlCalls = 0;
global.URL = { createObjectURL: () => { objectUrlCalls++; return 'blob:SHOULD-NOT-HAPPEN-' + objectUrlCalls; }, revokeObjectURL: () => {} };
const REQUESTS = [];
global._pendingXhrs = [];
global.XMLHttpRequest = class {
  constructor() { this.readyState = 0; this.status = 0; this.responseText = ''; this.response = null; this._headers = {}; }
  open(m, u) { this._method = m; this._url = u; this.readyState = 1; }
  setRequestHeader(k, v) { (this._reqHeaders = this._reqHeaders || {})[k] = v; }
  getResponseHeader(k) { return (this._headers && this._headers[k]) !== undefined ? this._headers[k] : null; }
  send(body) { this._body = body; REQUESTS.push({ method: this._method, url: this._url, body: body || '' });
    global._pendingXhrs.push(this); }
  addEventListener() {}
  abort() { this.status = 0; if (this.onabort) this.onabort(); }
};
global._respondXhr = function (xhr, body) {
  xhr.status = 200; xhr.responseText = JSON.stringify(body); xhr.response = xhr.responseText;
  xhr.readyState = 4; xhr._headers = xhr._headers || {};
  if (xhr.onreadystatechange) xhr.onreadystatechange();
};
let iframeFires = 0;
try {
  (0, eval)(js);
  const origFire = globalThis._legacyFireIframe;
  if (typeof origFire === 'function')
    globalThis._legacyFireIframe = function (u) { iframeFires++; return origFire.call(this, u); };
} catch (e) { console.log('eval note:', e.message); }

const W = globalThis;
let pass = 0, fail = 0;
function check(name, cond) { if (cond) { pass++; console.log('PASS', name); } else { fail++; console.log('FAIL', name); } }
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
function reqsOf(part) { return REQUESTS.filter(r => r.url.indexOf(part) >= 0); }
function beatOf(name) { return global._pendingXhrs.filter(x => x._url && x._url.indexOf('/api/client/handoff-sync?watch=' + encodeURIComponent(name)) >= 0); }
function fireBeatTick() { for (const fn of INTERVALS.slice()) fn(); }
const answered = new Set();
function respondBeat(name, st, tid) {
  const xs = beatOf(name).filter(x => !answered.has(x));
  if (!xs.length) return false;
  const x = xs[xs.length - 1];
  answered.add(x);
  global._respondXhr(x, { beat: { i: tid || 'tid-x', s: st }, rows: [] });
  return true;
}

(async function main() {
  // ── C1: the direct-download architecture is intact ──
  check('C1 iPad 10.3.3 UA routes legacy', W._legacyIOS === true);
  W.dlQSFile('/api/qs/dl?n=Cancel%20Me.jpg', 'Cancel Me.jpg', false);
  await sleep(80);
  const frame = document.getElementById('legacyDlFrame');
  check('C2a the direct iframe dispatch (octet=1&handoff=1) still fires — the browser download mechanism owns the bytes',
    !!frame && frame.src === '/api/qs/dl?n=Cancel%20Me.jpg&octet=1&handoff=1' && iframeFires === 1);
  check('C2b zero page-memory receives (no blob, no file-byte XHR)', objectUrlCalls === 0 && reqsOf('/api/qs/dl').length === 0);

  // ── C3: the server's verdict resolves CANCELLED — the watcher advances
  // the serial pump to the next queued item, exactly like every verdict ──
  W._dispatchQSItem('/api/qs/dl?n=Next%20Up.zip', 'Next Up.zip', false, true);
  await sleep(30);
  check('C3a the queued item waits behind the current one (serial discipline)', iframeFires === 1);
  await sleep(380);   // the first beat (350 ms)
  fireBeatTick();
  check('C3b the cancelled verdict is observed', respondBeat('Cancel Me.jpg', 'cancelled', 'tid-1'));
  await sleep(40);
  check('C3c the CANCELLED verdict advances the pump — the next queued item dispatches, the queue never treats the cancelled item as delivered',
    iframeFires === 2 && frame.src === '/api/qs/dl?n=Next%20Up.zip&octet=1&handoff=1');
  check('C3d no page-memory receive happened for either item', objectUrlCalls === 0);

  // ── C4: a fresh dispatch immediately after a cancelled one works ──
  await sleep(400);   // the second item's own first beat (350 ms after its dispatch)
  check('C4a the second item completes normally', respondBeat('Next Up.zip', 'completed', 'tid-2'));
  await sleep(40);
  W.dlQSFile('/api/qs/dl?n=Fresh%20After%20Cancel.jpg', 'Fresh After Cancel.jpg', false);
  await sleep(60);
  check('C4b a new transfer dispatches cleanly right after a cancelled one (no stale state, no wedge)',
    iframeFires === 3 && frame.src === '/api/qs/dl?n=Fresh%20After%20Cancel.jpg&octet=1&handoff=1');

  // ── C5: the cancelled verdict renders as the terminal cancelled row ──
  const r = W._xferDlRow('t9', { tid: 't9', name: 'Cancel Me.jpg', state: 'cancelled', ho: false, received: 10, total: 100, speed: 0, qPos: 0 });
  check('C5 a cancelled row renders its honest terminal label (never Waiting, never Downloading)',
    r.label === 'Canceled' && r.st === 'cancelled');

  console.log('════', 'PASS:' + pass, 'FAIL:' + fail, '════');
  process.exit(fail ? 1 : 0);
})().catch(e => { console.log('HARNESS ERROR:', e && e.stack || e); process.exit(2); });
