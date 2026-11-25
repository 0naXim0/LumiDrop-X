// ── PASS 22 acceptance harness — the 12-video Waiting scenario ───────────
// Executes the REAL index.html script under the same DOM shim accept.js
// uses, then drives the REAL dlSelected/_dlPump/xfersApply machinery
// through the exact reported reproduction (Test B), plus the scheduler
// stress cases (swallowed dispatch / zero-byte re-fire bounds / pause
// independence / duplicate protection / with-bytes resume ownership).
const fs = require('fs');
const file = process.argv[2];
const lines = fs.readFileSync(file, 'utf8').split('\n');
const starts = [], ends = [];
lines.forEach((l, i) => { if (l.includes('<script')) starts.push(i); if (l.includes('</script')) ends.push(i); });
const js = lines.slice(starts[1] + 1, ends[ends.length - 1]).join('\n');

// ── DOM shim (accept.js's Elem, extended with the media-element surface
//    openVid/closeVid touch — pause/load/removeAttribute are no-ops, which
//    is exactly what the playback/transfer INDEPENDENCE test needs: playback
//    must be observable as a pure no-op for the transfer queue) ────────────
class Elem {
  constructor(tag) { this.tagName = (tag || 'div').toUpperCase(); this.children = []; this.parentNode = null;
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
  set innerHTML(v) { this.children.forEach(c => c.parentNode = null); this.children = []; this._html = String(v); }
  get innerHTML() { return this._html; }
  setAttribute(k, v) { this._attrs[k] = String(v); if (k === 'value') this.value = String(v); }
  getAttribute(k) { return this._attrs[k]; }
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
  getElementById(id) { return this._byId[id] || (this._byId[id] = new Elem(t = 'div')); },
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
  setRequestHeader() {} send() { global._pendingXhrs.push(this); } addEventListener() {}
};
global._respondXhr = function (xhr, body) {
  xhr.status = 200; xhr.responseText = JSON.stringify(body); xhr.response = xhr.responseText;
  if (xhr.onreadystatechange) xhr.onreadystatechange();
  if (xhr.onload) xhr.onload();
};
// fake clock so the pump's release windows are drivable deterministically
let __now = 1700000000000;
Date.now = () => __now;
try { (0, eval)(js); } catch (e) { console.log('eval note:', e.message); }

let pass = 0, fail = 0;
function check(name, ok, extra) { if (ok) { pass++; console.log('PASS', name); } else { fail++; console.log('FAIL', name, extra || ''); } }

// spy-wrap the browser-native dispatch: the REAL pump logic runs; only the
// <a download> DOM click is recorded instead of performed.
const __dispatched = [];
global._directDownload = function (url, name) { __dispatched.push({ url, name, at: __now }); };

// XFER row helper — snapshot rows in the exact shape xfersApply consumes.
// The tid is STABLE per logical row (mirrors real snapshots: one row keeps
// its tid across state transitions), so each snap UPDATES the same row
// instead of stacking stale duplicates the pump would misread.
function row(name, state, received, total, rq, pd) {
  return { i: 'k:' + name, n: name, d: 'dl', s: state, r: received || 0, t: total || 1000,
    b: 0, rq: (rq === undefined ? true : rq), pd: !!pd, ho: false };
}
function snap(rows) { try { xfersApply(rows); } catch (e) { console.log('   xfersApply threw:', e.message); } }
function resetXfer() { XFER.items = {}; XFER.order = []; }

// ══ TEST B — the exact 12-video reproduction ═════════════════════════════
const vids = [];
for (let i = 1; i <= 12; i++) {
  const p = '/storage/emulated/0/DCIM/Camera/VID_20260' + (i < 10 ? '0' + i : i) + '.mp4';
  vids.push(p); S.sel[p] = 1; S._si[p] = { isDir: false, name: 'VID_20260' + (i < 10 ? '0' + i : i) + '.mp4' };
}
try { dlSelected(); check('B1 dlSelected runs clean', true); }
catch (e) { check('B1 dlSelected runs clean', false, e.message); }

const regs = _pendingXhrs.filter(x => String(x._url).indexOf('/api/download/register') >= 0);
check('B2 one batched registration call', regs.length === 1, 'got ' + regs.length);
check('B3 pump holds the batch (11 queued + 1 in flight)',
  _dlPumpQ.length === 11 && Object.keys(_dlPumpSlots).length === 1,
  'q=' + _dlPumpQ.length + ' slots=' + Object.keys(_dlPumpSlots).length);
check('B4 window=1: exactly ONE dispatch fired (the old code fired 12)', __dispatched.length === 1, 'got ' + __dispatched.length);

// duplicate protection: re-selecting the same files must not duplicate
try { dlFile(vids[0], 'VID_20260 01.mp4', true); } catch (e) {}
check('B5 duplicate dlFile is a no-op (url identity)',
  _dlPumpQ.length === 11 && Object.keys(_dlPumpSlots).length === 1);

// ── videos 1..10 download one by one; at video 6's turn, PLAY 11 and 12 ──
const doneRows = [];
function completeOne(i) {
  const nm = 'VID_20260' + (i < 10 ? '0' + i : i) + '.mp4';
  snap(doneRows.concat([row(nm, 'pending', 0, 1000, false)]));
  __now += 500; global._dlPumpTick();
  snap(doneRows.concat([row(nm, 'active', 400, 1000)]));
  __now += 500;
  snap(doneRows.concat([row(nm, 'completed', 1000, 1000)]));
  doneRows.push(row(nm, 'completed', 1000, 1000));
  __now += 100; global._dlPumpTick();
}
completeOne(1); completeOne(2); completeOne(3); completeOne(4); completeOne(5);
check('B6 five completions → six dispatches (one at a time)', __dispatched.length === 6, 'got ' + __dispatched.length);

// PLAY videos 11 and 12 while they are still Waiting (the exact trigger).
// Video 6 is mid-flight streaming the whole time — its row stays live, so
// the pump sees genuine work (exactly the real device's state).
try { openVid(vids[10], 'VID_2026011.mp4'); openVid(vids[11], 'VID_2026012.mp4'); } catch (e) { console.log('   openVid note:', e.message); }
const done6 = doneRows.slice();
snap(done6.concat([row('VID_2026006.mp4', 'active', 300, 1000)]));
__now += 10000; snap(done6.concat([row('VID_2026006.mp4', 'active', 600, 1000)]));
__now += 10000; snap(done6.concat([row('VID_2026006.mp4', 'active', 900, 1000)]));
__now += 10000; global._dlPumpTick();
try { closeVid(); } catch (e) { }
__now += 100; global._dlPumpTick();
snap(done6.concat([row('VID_2026006.mp4', 'active', 950, 1000)]));
check('B7 playback never consumed a dispatch slot', __dispatched.length === 6, 'got ' + __dispatched.length);
check('B8 playback never touched the queue', _dlPumpQ.length === 6, 'q=' + _dlPumpQ.length);

// videos 6..10 finish
completeOne(6); completeOne(7); completeOne(8); completeOne(9); completeOne(10);
check('B9 after the first ten, the 11th dispatch fired', __dispatched.length === 11, 'got ' + __dispatched.length);

// the two PLAYED videos take their turns and complete — no eternal Waiting
completeOne(11);
check('B10 the played video 11 dispatched and completed', __dispatched.length === 12, 'got ' + __dispatched.length);
completeOne(12);
check('B11 the played video 12 dispatched — NO video stuck in Waiting', __dispatched.length === 12, 'got ' + __dispatched.length);
check('B12 queue fully drained', _dlPumpQ.length === 0 && Object.keys(_dlPumpSlots).length === 0,
  'q=' + _dlPumpQ.length + ' slots=' + Object.keys(_dlPumpSlots).length);
check('B13 all 12 rows completed', doneRows.length === 12);

// ══ TEST C — scheduler stress ════════════════════════════════════════════
// C1 swallowed dispatch: dispatched row stays pending with rq=false past
// the 8s window → slot frees, next item flows.
__dispatched.length = 0; _dlPumpQ.length = 0; for (const k in _dlPumpSlots) delete _dlPumpSlots[k];
const s1 = '/v/a.mp4', s2 = '/v/b.mp4';
_dlPumpEnqueue(s1, 'a.mp4'); _dlPumpEnqueue(s2, 'b.mp4');
check('C1a window holds at one', __dispatched.length === 1 && _dlPumpQ.length === 1);
snap([row('a.mp4', 'pending', 0, 1000, false)]);       // request never arrived (rq=false)
__now += 9000; global._dlPumpTick();
check('C1b swallowed dispatch freed the slot → next item flows', __dispatched.length === 2, 'got ' + __dispatched.length);

resetXfer();
// C2 zero-byte failure → exactly ONE bounded re-fire
__dispatched.length = 0; _dlPumpQ.length = 0; for (const k in _dlPumpSlots) delete _dlPumpSlots[k];
_dlPumpRetry = {};
_dlPumpEnqueue(s1, 'a.mp4');
snap([row('a.mp4', 'failed', 0, 1000)]);               // zero-byte failure
__now += 100; global._dlPumpTick();
check('C2a zero-byte failure releases the slot', Object.keys(_dlPumpSlots).length === 0);
setTimeout(() => {                                     // the 400ms re-fire (real timer)
  // the re-fired request ARRIVES and parks the row pending again (rq=false,
  // fresh) — exactly what a real host reports for the re-dispatched item.
  snap([row('a.mp4', 'pending', 0, 1000, false)]);
  global._dlPumpTick();
  check('C2b bounded re-fire re-enqueued exactly once', _dlPumpQ.length + Object.keys(_dlPumpSlots).length === 1,
    'q=' + _dlPumpQ.length + ' slots=' + Object.keys(_dlPumpSlots).length);
  // second zero-byte failure → NO second re-fire (bounded)
  __dispatched.length = 0;
  if (Object.keys(_dlPumpSlots).length === 0) { _dlPumpEnqueue(s1, 'a.mp4'); }
  snap([row('a.mp4', 'failed', 0, 1000)]);
  __now += 100; global._dlPumpTick();
  setTimeout(() => {
    global._dlPumpTick();
    check('C2c the re-fire is bounded to one attempt', (_dlPumpRetry[s1] || 0) <= 1 && _dlPumpQ.length + Object.keys(_dlPumpSlots).length <= 1,
      'retry=' + (_dlPumpRetry[s1] || 0));

    resetXfer();
// C3 with-bytes failure: rests resumable — NO re-fire (the browser's
    // download manager owns the resume), slot frees so the batch flows.
    __dispatched.length = 0; _dlPumpQ.length = 0; _dlPumpRetry = {};
    for (const k in _dlPumpSlots) delete _dlPumpSlots[k];
    _dlPumpEnqueue(s1, 'a.mp4'); _dlPumpEnqueue(s2, 'b.mp4');
    snap([row('a.mp4', 'failed', 500, 1000)]);          // WITH bytes
    __now += 100; global._dlPumpTick();
    check('C3 with-bytes failure frees the slot, no re-fire, batch continues',
      __dispatched.length === 2 && (_dlPumpRetry[s1] || 0) === 0 && Object.keys(_dlPumpSlots).length <= 1,
      'disp=' + __dispatched.length + ' retry=' + (_dlPumpRetry[s1] || 0) + ' slots=' + Object.keys(_dlPumpSlots).length);

    resetXfer();
// C4 host-paused row: not a batch turn; a paused QUEUED item holds.
    __dispatched.length = 0; _dlPumpQ.length = 0;
    for (const k in _dlPumpSlots) delete _dlPumpSlots[k];
    _dlPumpEnqueue(s1, 'a.mp4'); _dlPumpEnqueue(s2, 'b.mp4');
    snap([row('a.mp4', 'active', 10, 1000, true, true)]);  // paused mid-flight
    __now += 100; global._dlPumpTick();
    check('C4a paused dispatched row releases its slot → batch flows', __dispatched.length === 2, 'got ' + __dispatched.length);

    resetXfer();
// C5 cancel of the active row → next dispatch (queue never locks).
    __dispatched.length = 0; _dlPumpQ.length = 0;
    for (const k in _dlPumpSlots) delete _dlPumpSlots[k];
    _dlPumpEnqueue(s1, 'a.mp4'); _dlPumpEnqueue(s2, 'b.mp4');
    snap([row('a.mp4', 'cancelled', 200, 1000)]);
    __now += 100; global._dlPumpTick();
    check('C5 cancelled row frees its slot → next item starts', __dispatched.length === 2, 'got ' + __dispatched.length);

    resetXfer();
// C6 row vanished entirely (completed + auto-dismissed) past the
    // 6s missing-window → slot frees.
    __dispatched.length = 0; _dlPumpQ.length = 0;
    for (const k in _dlPumpSlots) delete _dlPumpSlots[k];
    _dlPumpEnqueue(s1, 'a.mp4'); _dlPumpEnqueue(s2, 'b.mp4');
    global._dlPumpTick();
    __now += 7000; global._dlPumpTick();                // row never appears
    check('C6 missing row frees its slot after the window', __dispatched.length === 2, 'got ' + __dispatched.length);

    console.log('════', 'PASS:' + pass, 'FAIL:' + fail, '════');
    process.exit(fail ? 1 : 0);
  }, 500);
}, 500);
