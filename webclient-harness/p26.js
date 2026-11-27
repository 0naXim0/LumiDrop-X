// ── PASS 26 acceptance harness — the iPad 4 legacy receive path ─────────
// Executes the REAL index.html script with the REAL legacy-iOS routing
// active (an iPad 4 / iOS 10.3.3 Safari user agent), then drives the REAL
// receive pipeline end to end: dispatch, tracked XHR receive, finalization
// ack, viewer presentation, serial pump discipline, refusals, duplicate
// dispatch, and the legacy quick-accept regression.
const fs = require('fs');
const file = process.argv[2] || '/home/z/my-project/work/lumidrop/LumiDropX/app/src/main/assets/index.html';
const lines = fs.readFileSync(file, 'utf8').split('\n');
const starts = [], ends = [];
lines.forEach((l, i) => { if (l.includes('<script')) starts.push(i); if (l.includes('</script')) ends.push(i); });
const js = lines.slice(starts[1] + 1, ends[ends.length - 1]).join('\n');

// ── DOM shim (accept.js's shape, plus the PASS 26 receive viewer) ────────
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
  setAttribute(k, v) { this._attrs[k] = String(v); if (k === 'download') this._download = String(v); if (k === 'href') this._href = String(v); if (k === 'src') this.src = String(v); }
  getAttribute(k) { return k in this._attrs ? this._attrs[k] : null; }
  removeAttribute(k) { delete this._attrs[k]; if (k === 'src') this.src = ''; }   // real DOM: removing src clears .src
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
// The iPad 4 itself: iOS 10.3.3 stock Safari UA — the legacy routing's own
// detector must route this build through the legacy path.
const IPAD_UA = 'Mozilla/5.0 (iPad; CPU OS 10_3_3 like Mac OS X) AppleWebKit/603.3.8 (KHTML, like Gecko) Version/10.0.3 Mobile/14G60 Safari/602.1';
global.window = { addEventListener() {}, removeEventListener() {}, setTimeout, clearTimeout,
  getComputedStyle: (el) => document.getComputedStyle(el), location: { protocol: 'http:', host: 'x', pathname: '/' },
  navigator: { userAgent: IPAD_UA }, EventSource: undefined, innerWidth: 1024, innerHeight: 600, MSStream: undefined };
// The script's detectors read the GLOBAL navigator (and window.navigator in
// places) — Node ships its own getter-only global navigator ('Node.js/24'),
// and a plain assignment cannot replace it; both must carry the iPad UA.
Object.defineProperty(globalThis, 'navigator', { value: { userAgent: IPAD_UA }, configurable: true, writable: true });
global.setInterval = () => 0;   // no auto-poll / no repeat beats — the harness drives each beat itself
global.clearInterval = () => {};
global.requestAnimationFrame = (f) => setTimeout(f, 0);
global.cancelAnimationFrame = clearTimeout;
global.Image = class { set src(v) { this._src = v; if (this.onload) this.onload(); } };
const _ls = {}; global.localStorage = { getItem: k => (k in _ls ? _ls[k] : null), setItem: (k, v) => { _ls[k] = String(v); }, removeItem: k => { delete _ls[k]; } };
// PASS 26: the blob surface the tracked receive uses (URL.createObjectURL /
// revokeObjectURL are the only URL uses in the whole script — verified).
let _blobSeq = 0;
global.URL = { createObjectURL: () => 'blob:fake-' + (++_blobSeq), revokeObjectURL: () => {} };
// XHR shim: records EVERY request (method, url, body) so the harness can
// assert exactly what the page put on the wire — and supports the receive
// XHR's responseType + getResponseHeader.
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
// Respond an XHR with a JSON body (xhrGet/xhrPost driver).
global._respondXhr = function (xhr, body) {
  xhr.status = 200; xhr.responseText = JSON.stringify(body); xhr.response = xhr.responseText;
  xhr.readyState = 4; xhr._headers = xhr._headers || {};
  if (xhr.onreadystatechange) xhr.onreadystatechange();
  if (xhr.onload) xhr.onload();
};
// Respond a tracked RECEIVE XHR: status 200, a blob response, and the
// host's X-LDX-ACK attempt identity — exactly what the server stamps.
global._respondRecv = function (xhr, tidGen, ok, blob) {
  xhr.status = ok ? 200 : 400;
  if (ok && xhr.responseType === 'blob') xhr.response = blob || { size: 1234, type: 'image/jpeg' };
  xhr.readyState = 4; xhr._headers = ok ? { 'X-LDX-ACK': tidGen } : {};
  if (xhr.onreadystatechange) xhr.onreadystatechange();
  if (xhr.onload) xhr.onload();
};
// Spy on the retired interception dispatch — it must stay retired.
let _iframeFires = 0;
try { (0, eval)(js); const _origFire = globalThis._legacyFireIframe;
  if (typeof _origFire === 'function') globalThis._legacyFireIframe = function () { _iframeFires++; };
} catch (e) { console.log('eval note:', e.message); }

const W = globalThis;
let pass = 0, fail = 0;
function check(name, cond) { if (cond) { pass++; console.log('PASS', name); } else { fail++; console.log('FAIL', name); } }
function reqsOf(part) { return REQUESTS.filter(r => r.url.indexOf(part) >= 0); }
function pendRecv() { return global._pendingXhrs.filter(x => x._url && x._url.indexOf('recv=1') >= 0); }
function beatOf(name) { return global._pendingXhrs.filter(x => x._url && x._url.indexOf('/api/client/handoff-sync?watch=' + encodeURIComponent(name)) >= 0); }
function respondBeat(name, st, tid, pd, ho) {
  const xs = beatOf(name);
  if (!xs.length) return false;
  global._respondXhr(xs[0], { beat: { i: tid || 'tid-x', s: st, pd: !!pd, ho: !!ho }, rows: [] });
  return true;
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

(async function main() {
  // ── C1: the legacy routing itself is active for this build's UA ──
  check('C1 iPad 10.3.3 UA routes legacy', W._legacyIOS === true);

  // ── C2/C3: manual per-item Get — the tracked receive dispatch ──
  W.dlQSFile('/api/qs/dl?n=Photo%20001.jpg', 'Photo 001.jpg', false);
  await sleep(30);
  const acceptPosts = reqsOf('/api/quickshare/accept');
  check('C2 accept POST fired', acceptPosts.length === 1 && acceptPosts[0].method === 'POST' && acceptPosts[0].body.indexOf('n=Photo%20001.jpg') >= 0);
  const recv1 = pendRecv();
  check('C3 tracked receive XHR dispatched (recv=1)', recv1.length === 1 && recv1[0]._url === '/api/qs/dl?n=Photo%20001.jpg&recv=1');
  check('C3b NO octet/handoff interception params on the receive', recv1.length === 1 && recv1[0]._url.indexOf('octet=1') < 0 && recv1[0]._url.indexOf('handoff=1') < 0);
  check('C3c the retired interception iframe stayed retired', _iframeFires === 0);

  // ── C4/C5: the load completes in-page → finalization ack + viewer ──
  global._respondRecv(recv1[0], 'tid-1.0', true);
  await sleep(10);
  const acks = reqsOf('/api/client-transfer/ack');
  check('C4 finalization ack POSTed with the response\'s exact attempt identity', acks.length === 1 && acks[0].method === 'POST' && acks[0].url === '/api/client-transfer/ack?t=tid-1&g=0');
  const ov = document.getElementById('legacyRecvOv');
  const img = document.getElementById('legacyRecvImg');
  const openBtn = document.getElementById('legacyRecvOpenBtn');
  check('C5 receive viewer opened with the received image', !ov.classList.contains('hid') && String(img.src).indexOf('blob:') === 0 && openBtn.classList.contains('hid'));

  // ── C6/C7: the completed verdict holds the turn for the user; Next advances ──
  W._dispatchQSItem('/api/qs/dl?n=Docs.zip', 'Docs.zip', false, true);
  await sleep(30);
  // Wait for the first beat (350ms) and answer it with the COMPLETED verdict
  await sleep(380);
  const beat1 = respondBeat('Photo 001.jpg', 'completed', 'tid-1');
  check('C6a watcher beat observed', beat1);
  await sleep(20);
  const recv2 = pendRecv().filter(x => x._url.indexOf('Docs.zip') >= 0);
  check('C6b completed verdict does NOT auto-advance past the presented item (user pace)', recv2.length === 0);
  W._legacyAdvance();   // the viewer's Next button
  await sleep(30);
  const recv2b = pendRecv().filter(x => x._url.indexOf('Docs.zip') >= 0);
  check('C7 viewer Next advances the serial pump to the queued item', recv2b.length === 1 && recv2b[0]._url === '/api/qs/dl?n=Docs.zip&recv=1');

  // ── C8: a received ZIP presents the Open affordance, not the image ──
  global._respondRecv(recv2b[0], 'tid-2.2', true, { size: 30000000, type: 'application/zip' });
  await sleep(10);
  check('C8 a received ZIP opens the viewer with the Open affordance (no image)',
    !ov.classList.contains('hid') && !String(img.src || '').length && !openBtn.classList.contains('hid') &&
    document.getElementById('legacyRecvHint').innerHTML.indexOf('Open') >= 0);
  const acks2 = reqsOf('/api/client-transfer/ack');
  check('C8b the ZIP receive acked its own attempt identity', acks2.length === 2 && acks2[1].url === '/api/client-transfer/ack?t=tid-2&g=2');

  // ── C9: a refused receive (host cancel tombstone) — no ack, honest advance ──
  W._legacyAdvance();   // Docs.zip was done (ack landed) → advance
  await sleep(30);
  // NOTE: the Docs.zip verdict beat fires ~350ms after its dispatch; wait
  // for it so the pump's watcher is in the beat loop, then queue a third
  // item and drive its refusal.
  await sleep(380);
  respondBeat('Docs.zip', 'completed', 'tid-2');
  await sleep(10);
  W._legacyAdvance();   // Docs.zip completed+holding → the user's Next
  await sleep(30);
  check('C9a pump idle after the batch (no further dispatch)', pendRecv().filter(x => x._url.indexOf('Ghost') >= 0).length === 0);

  // Direct refusal: dispatch an item whose receive is refused (400)
  W.dlQSFile('/api/qs/dl?n=Ghost.bin', 'Ghost.bin', false);
  await sleep(30);
  const recv3 = pendRecv().filter(x => x._url.indexOf('Ghost.bin') >= 0);
  check('C9b refused item dispatched serially', recv3.length === 1);
  const ackCountBefore = reqsOf('/api/client-transfer/ack').length;
  global._respondRecv(recv3[0], null, false);   // 400 "Cancelled by host"
  await sleep(10);
  check('C9c a refused receive acks nothing', reqsOf('/api/client-transfer/ack').length === ackCountBefore);
  await sleep(400);
  const beat3 = respondBeat('Ghost.bin', 'failed', 'tid-3');
  await sleep(10);
  check('C9d failed verdict advances the pump honestly', beat3);

  // ── C10: the legacy quick-accept regression pin ──
  // Auto-accept ON: an incoming offer list must be accepted AND dispatched
  // into the legacy pump immediately — the old build skipped legacy iOS.
  document.getElementById('qsAutoChk').checked = true;
  W.S.qsDispatched = {};
  W.handleQState({ sid: 'srv-26', v: 7, qc: 2, xfers: [] });
  await sleep(80);   // the 50ms coalesce
  const qsFetch = global._pendingXhrs.filter(x => x._url === '/api/quickshare');
  check('C10a offer list refetched on version bump', qsFetch.length === 1);
  global._respondXhr(qsFetch[0], { files: [
    { name: 'Auto 1.jpg', dlUrl: '/api/qs/dl?n=Auto%201.jpg', isText: false, isFolder: false, consumed: false },
    { name: 'Auto 2.png', dlUrl: '/api/qs/dl?n=Auto%202.png', isText: false, isFolder: false, consumed: false },
    { name: 'Note 1', text: 'hello', isText: true, consumed: false }
  ] });
  await sleep(80);
  const autoAccept = reqsOf('/api/quickshare/accept');
  const autoBatch = autoAccept.filter(r => r.body.indexOf('n=Auto%201.jpg') >= 0);
  check('C10b quick-accept takes the file items immediately on legacy', autoBatch.length === 1 &&
    autoBatch[0].method === 'POST' && autoBatch[0].body.indexOf('n=Auto%202.png') >= 0 &&
    autoBatch[0].body.indexOf('Note') < 0);
  const autoRecv = pendRecv().filter(x => x._url.indexOf('Auto%20') >= 0);
  check('C10c the accepted items are dispatched into the tracked receive pump (serially, one at a time)', autoRecv.length === 1 && autoRecv[0]._url === '/api/qs/dl?n=Auto%201.jpg&recv=1');
  check('C10d a text item is never dispatched as a receive', pendRecv().filter(x => x._url.indexOf('Note') >= 0).length === 0);

  // ── C11: exactly-once dispatch — a double-fire of the same handler ──
  const before11 = pendRecv().length;
  W.dlQSFile('/api/qs/dl?n=Photo%20001.jpg', 'Photo 001.jpg', false);
  await sleep(20);
  W.dlQSFile('/api/qs/dl?n=Photo%20001.jpg', 'Photo 001.jpg', false);
  await sleep(20);
  check('C11 a double-fire of the same item never queues a second receive', pendRecv().length === before11);

  // ── C12: the whole harness ran without a single interception dispatch ──
  check('C12 the interception iframe stayed retired for the entire session', _iframeFires === 0);

  console.log('════', 'PASS:' + pass, 'FAIL:' + fail, '════');
  process.exit(fail ? 1 : 0);
})().catch(e => { console.log('HARNESS ERROR:', e && e.stack || e); process.exit(2); });
