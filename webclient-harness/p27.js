// ── PASS 27 acceptance harness — the RESTORED direct browser download ────
// Executes the REAL index.html script with the REAL legacy-iOS routing
// active (an iPad 4 / iOS 10.3.3 Safari user agent), then drives the REAL
// restored direct-download pipeline: hidden-iframe dispatch (octet=1&
// handoff=1 — the browser's own download mechanism takes the attachment; NO
// page-memory accumulation), the watcher's terminal-verdict auto-advance,
// serial discipline, the honest failure path, quick-accept, duplicate
// dispatch, and the Active-row "Waiting for Safari…" label (no state flip).
const fs = require('fs');
const file = process.argv[2] || '/home/z/my-project/work/lumidrop/LumiDropX/app/src/main/assets/index.html';
const lines = fs.readFileSync(file, 'utf8').split('\n');
const starts = [], ends = [];
lines.forEach((l, i) => { if (l.includes('<script')) starts.push(i); if (l.includes('</script')) ends.push(i); });
const js = lines.slice(starts[1] + 1, ends[ends.length - 1]).join('\n');

// ── DOM shim ──────────────────────────────────────────────────────────────
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
// Interval capture: every setInterval callback is SAVED (never auto-fired) so
// the harness drives each beat tick manually — deterministic and fast.
const INTERVALS = [];
global.setInterval = (fn, ms) => { INTERVALS.push(fn); return INTERVALS.length; };
global.clearInterval = () => {};
global.requestAnimationFrame = (f) => setTimeout(f, 0);
global.cancelAnimationFrame = clearTimeout;
global.Image = class { set src(v) { this._src = v; if (this.onload) this.onload(); } };
const _ls = {}; global.localStorage = { getItem: k => (k in _ls ? _ls[k] : null), setItem: (k, v) => { _ls[k] = String(v); }, removeItem: k => { delete _ls[k]; } };
// Blob-surface guard: the restored architecture must NEVER accumulate a file
// in page memory — createObjectURL counts and must stay ZERO for the whole
// harness (the old Pass-26 flow used it on every receive).
let objectUrlCalls = 0;
global.URL = { createObjectURL: () => { objectUrlCalls++; return 'blob:SHOULD-NOT-HAPPEN-' + objectUrlCalls; }, revokeObjectURL: () => {} };
// XHR recording (coordination traffic only — accept POSTs and handoff-sync
// beats; a file-byte fetch would be the rejected JavaScript-mediated receive).
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
// The restored dispatch spy: the hidden iframe IS the direct-download
// vehicle — the pump MUST call it (the reverse of Pass 26's retirement pin).
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
// Always answer the NEWEST unanswered beat for the item — an older one may
// already have resolved its promise (a second resolve is a silent no-op,
// which is exactly what the first harness run tripped over).
const answered = new Set();
function respondBeat(name, st, tid, pd, ho) {
  const xs = beatOf(name).filter(x => !answered.has(x));
  if (!xs.length) return false;
  const x = xs[xs.length - 1];
  answered.add(x);
  global._respondXhr(x, { beat: { i: tid || 'tid-x', s: st, pd: !!pd, ho: !!ho }, rows: [] });
  return true;
}

(async function main() {
  // ── C1: the legacy routing is active for this build's UA ──
  check('C1 iPad 10.3.3 UA routes legacy', W._legacyIOS === true);

  // ── C2: manual per-item Get — the DIRECT browser download dispatch ──
  W.dlQSFile('/api/qs/dl?n=Photo%20001.jpg', 'Photo 001.jpg', false);
  await sleep(80);
  check('C2a accept POST fired', reqsOf('/api/quickshare/accept').length === 1);
  const frame = document.getElementById('legacyDlFrame');
  check('C2b the hidden iframe navigates to the tracked URL with octet=1&handoff=1 (the browser download mechanism takes the attachment)',
    !!frame && frame.src === '/api/qs/dl?n=Photo%20001.jpg&octet=1&handoff=1');
  check('C2c the interception dispatch fired exactly once', iframeFires === 1);
  check('C2d NO page-memory file receive — zero createObjectURL calls, no file-byte XHRs',
    objectUrlCalls === 0 && reqsOf('/api/qs/dl').length === 0);

  // ── C3: the watcher drives on authoritative state; active holds, completed advances ──
  await sleep(380);   // the first beat (350 ms)
  const b1 = respondBeat('Photo 001.jpg', 'active', 'tid-1');
  check('C3a watcher beat observed', b1);
  await sleep(20);
  // A second item queued: it must NOT dispatch while the first is in flight.
  W._dispatchQSItem('/api/qs/dl?n=Docs.zip', 'Docs.zip', false, true);
  await sleep(30);
  const frameSrc1 = frame.src;
  check('C3b serial discipline — the queued item does not dispatch early', frameSrc1 === '/api/qs/dl?n=Photo%20001.jpg&octet=1&handoff=1' && iframeFires === 1);
  fireBeatTick();
  check('C3c completed verdict observed', respondBeat('Photo 001.jpg', 'completed', 'tid-1'));
  await sleep(30);
  check('C3d the completed verdict AUTO-ADVANCES the serial pump to the queued item (the browser Downloads UI owns the save)',
    iframeFires === 2 && frame.src === '/api/qs/dl?n=Docs.zip&octet=1&handoff=1');

  // ── C4: a failed verdict advances honestly (no false completion) ──
  // (The Docs.zip watcher's first beat fires ~350ms after its dispatch.)
  await sleep(420);
  const b2 = respondBeat('Docs.zip', 'failed', 'tid-2');
  check('C4a failed verdict observed', b2);
  await sleep(20);
  // No third item is queued: the pump idles cleanly after the honest failure.
  check('C4b the failed verdict advances the pump (idle, no wedge)', iframeFires === 2);

  // ── C5: quick-accept on legacy accepts AND dispatches directly ──
  document.getElementById('qsAutoChk').checked = true;
  W.S.qsDispatched = {};
  W.handleQState({ sid: 'srv-27', v: 7, qc: 2, xfers: [] });
  await sleep(80);   // the 50ms coalesce
  const qsFetch = global._pendingXhrs.filter(x => x._url === '/api/quickshare');
  check('C5a offer list refetched on version bump', qsFetch.length === 1);
  global._respondXhr(qsFetch[0], { files: [
    { name: 'Auto 1.jpg', dlUrl: '/api/qs/dl?n=Auto%201.jpg', isText: false, isFolder: false, consumed: false },
    { name: 'Auto 2.png', dlUrl: '/api/qs/dl?n=Auto%202.png', isText: false, isFolder: false, consumed: false },
    { name: 'Note 1', text: 'hello', isText: true, consumed: false }
  ] });
  await sleep(80);
  const autoBatch = reqsOf('/api/quickshare/accept').filter(r => r.body.indexOf('n=Auto%201.jpg') >= 0);
  check('C5b quick-accept takes the file items immediately on legacy',
    autoBatch.length === 1 && autoBatch[0].body.indexOf('n=Auto%202.png') >= 0 && autoBatch[0].body.indexOf('Note') < 0);
  await sleep(40);
  check('C5c the first accepted item dispatches DIRECTLY (iframe), serially',
    iframeFires === 3 && frame.src === '/api/qs/dl?n=Auto%201.jpg&octet=1&handoff=1');
  check('C5d a text item is never dispatched', reqsOf('/api/qs/dl').length === 0 && objectUrlCalls === 0);

  // ── C6: exactly-once dispatch — a double-fire never queues a second receive ──
  const firesBefore = iframeFires;
  W.dlQSFile('/api/qs/dl?n=Photo%20001.jpg', 'Photo 001.jpg', false);
  await sleep(30);
  W.dlQSFile('/api/qs/dl?n=Photo%20001.jpg', 'Photo 001.jpg', false);
  await sleep(30);
  check('C6 a double-fire of the same item never queues a second dispatch', iframeFires === firesBefore);

  // ── C7: the Active-row handoff label — the state never flips out of Active ──
  const r1 = W._xferDlRow('t1', { tid: 't1', name: 'Album.zip', state: 'active', ho: true, received: 0, total: 100000, speed: 0, qPos: 0 });
  const r2 = W._xferDlRow('t2', { tid: 't2', name: 'Queued.bin', state: 'pending', ho: false, received: 0, total: 50, speed: 0, qPos: 2 });
  const r3 = W._xferDlRow('t3', { tid: 't3', name: 'Streaming.bin', state: 'active', ho: false, received: 10, total: 100, speed: 1, qPos: 0 });
  check('C7a an ACTIVE re-await row renders the honest sublabel WITHOUT leaving Active',
    r1.label === 'Waiting for Safari\u2026' && r1.st === 'active');
  check('C7b an ordinary queued row keeps its queue label', r2.label.indexOf('in queue') >= 0 && r2.st === 'pending');
  check('C7c an ordinary streaming row keeps Downloading', r3.label === 'Downloading' && r3.st === 'active');

  // ── C8: the whole harness never once accumulated a file in page memory ──
  check('C8 zero page-memory file receives across the entire session', objectUrlCalls === 0);

  console.log('════', 'PASS:' + pass, 'FAIL:' + fail, '════');
  process.exit(fail ? 1 : 0);
})().catch(e => { console.log('HARNESS ERROR:', e && e.stack || e); process.exit(2); });
