// End-to-end probe: behaves like a device against a running server.
//   node test/e2e.mjs <outDir> <url> [<url> ...]
// Env: SURFER_WS (default wss://localhost:8686/ws),
//      VIEWPORT=WxHxDPR (default 800x1280x1.5), SWIPE=1 to measure a fast swipe,
//      IME_TAP=x,y to test typing: after the URLs, tap the field at (x, y) and
//      replay a keyboard's edit sequence (expects the text "Hello world!"),
//      SYNC=1 to test state sync: after the URLs, forget the session, restore it
//      from the synced state and load SYNC_CHECK_URL (saved as sync-check.png).
// Updates are composited like the app does; for each URL the picture is saved
// once the screen is quiet (all areas refined to lossless).

import { randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import sharp from 'sharp';
import WebSocket from 'ws';

const [outDir, ...urls] = process.argv.slice(2);
if (!outDir || urls.length === 0) {
  console.error('usage: node test/e2e.mjs <outDir> <url> [<url> ...]');
  process.exit(2);
}
mkdirSync(outDir, { recursive: true });

const [vw, vh, dpr] = (process.env.VIEWPORT ?? '800x1280x1.5').split('x').map(Number);
const FLAG_KEYFRAME = 1;
const FLAG_REFINE = 4;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// The device's saved state, kept up to date from sync messages (like the app does).
const saved = { cookies: new Map(), local: {}, tabs: [], active: 0, settings: {} };
const cookieKey = (c) => `${c.name}\u0001${c.domain}\u0001${c.path}\u0001${c.partitionKey ? JSON.stringify(c.partitionKey) : ''}`;
let syncMessages = 0;

function applySync(msg) {
  if (msg.t === 'cookies') {
    syncMessages++;
    for (const c of msg.set) saved.cookies.set(cookieKey(c), c);
    for (const c of msg.removed) saved.cookies.delete(cookieKey(c));
  } else if (msg.t === 'storage' && msg.area === 'local') {
    syncMessages++;
    const items = (saved.local[msg.key] ??= {});
    if (msg.op === 'set') items[msg.name] = msg.value;
    else if (msg.op === 'remove') delete items[msg.name];
    else saved.local[msg.key] = {};
  } else if (msg.t === 'tabs') {
    saved.tabs = msg.tabs.map((t) => ({ url: t.url }));
    saved.active = Math.max(0, msg.tabs.findIndex((t) => t.id === msg.activeId));
  } else if (msg.t === 'settings') {
    saved.settings = { layout: msg.layout, searchEngine: msg.searchEngine };
  }
}

/** One device connection with helpers to wait for messages and frames. */
function connect(deviceId) {
  const ws = new WebSocket(process.env.SURFER_WS ?? 'wss://localhost:8686/ws', { rejectUnauthorized: false });
  const c = { ws, activeId: 0, tabs: new Map(), frames: [], frameWaiters: [], messageWaiters: [], painting: Promise.resolve(), picture: null };
  c.paint = async (frame) => {
    const { width, height } = frame;
    if (!c.picture || c.picture.width !== width || c.picture.height !== height) c.picture = { width, height, data: Buffer.alloc(width * height * 3, 255) };
    for (const r of frame.rects) {
      const px = await sharp(r.data).removeAlpha().raw().toBuffer();
      for (let y = 0; y < r.h; y++) px.copy(c.picture.data, ((r.y + y) * width + r.x) * 3, y * r.w * 3, (y + 1) * r.w * 3);
    }
  };
  /**
   * Resolves once no update has arrived for `ms` (or after `maxMs`: a blinking
   * caret never stops) and all are painted.
   */
  c.quiet = async (ms = 800, maxMs = 5000) => {
    const until = performance.now() + maxMs;
    for (;;) {
      const n = c.frames.length;
      await sleep(ms);
      if (c.frames.length === n || performance.now() > until) return c.painting;
    }
  };
  c.save = (file) => sharp(c.picture.data, { raw: { width: c.picture.width, height: c.picture.height, channels: 3 } }).png().toFile(join(outDir, file));
  c.send = (msg) => ws.send(JSON.stringify(msg));
  c.waitMessage = (predicate, timeoutMs = 30000) =>
    new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timeout waiting for message')), timeoutMs);
      c.messageWaiters.push({ predicate, resolve: (m) => (clearTimeout(timer), resolve(m)) });
    });
  c.waitFrame = (predicate, timeoutMs = 30000) =>
    new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timeout waiting for frame')), timeoutMs);
      c.frameWaiters.push({ predicate, resolve: (f) => (clearTimeout(timer), resolve(f)) });
    });
  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      const rects = [];
      let o = 60;
      for (let n = data.readUInt16LE(2); n > 0; n--) {
        const length = data.readUInt32LE(o + 12);
        rects.push({ x: data.readUInt16LE(o), y: data.readUInt16LE(o + 2), w: data.readUInt16LE(o + 4), h: data.readUInt16LE(o + 6), format: data.readUInt8(o + 8), data: data.subarray(o + 16, o + 16 + length) });
        o += 16 + length;
      }
      const frame = {
        flags: data.readUInt8(1),
        frameId: data.readUInt32LE(4),
        width: data.readUInt16LE(12),
        height: data.readUInt16LE(14),
        scrollY: data.readFloatLE(24),
        rects,
        bytes: data.length,
        at: performance.now(),
      };
      c.frames.push(frame);
      // Composite in arrival order, then acknowledge (like the app).
      c.painting = c.painting.then(() => c.paint(frame)).then(() => c.send({ t: 'frameAck', frameId: frame.frameId }));
      for (const w of [...c.frameWaiters]) {
        if (w.predicate(frame)) c.frameWaiters.splice(c.frameWaiters.indexOf(w), 1), w.resolve(frame);
      }
      return;
    }
    const msg = JSON.parse(data.toString());
    applySync(msg);
    if (msg.t === 'tabs') {
      c.activeId = msg.activeId;
      for (const t of msg.tabs) c.tabs.set(t.id, t);
    } else if (msg.t === 'tabUpdate') {
      c.tabs.set(msg.id, msg);
    } else if (msg.t === 'error' || msg.t === 'notice') {
      console.log('server:', msg);
    }
    for (const w of [...c.messageWaiters]) {
      if (w.predicate(msg)) c.messageWaiters.splice(c.messageWaiters.indexOf(w), 1), w.resolve(msg);
    }
  });
  c.opened = new Promise((resolve, reject) => {
    ws.on('open', resolve);
    ws.on('error', reject);
  });
  c.handshake = async () => {
    await c.opened;
    c.send({ t: 'hello', proto: 1, app: 'e2e', appVersion: '0', deviceId, device: { model: 'e2e' }, viewport: { w: vw, h: vh, dpr } });
    const first = await c.waitMessage((m) => m.t === 'needRestore' || m.t === 'ready');
    if (first.t === 'needRestore') {
      c.send({ t: 'restoreBegin', cookies: [...saved.cookies.values()], tabs: saved.tabs, active: saved.active, settings: saved.settings });
      for (const [key, items] of Object.entries(saved.local)) c.send({ t: 'restoreLocal', key, items });
      c.send({ t: 'restoreEnd' });
      await c.waitMessage((m) => m.t === 'ready');
    }
    const tabsMsg = await c.waitMessage((m) => m.t === 'tabs', 60000);
    c.activeId = tabsMsg.activeId;
    return first.t;
  };
  return c;
}

async function load(c, url, name) {
  const started = performance.now();
  const firstFrames = c.frames.length;
  c.send({ t: 'navigate', tabId: c.activeId, input: url });
  await c.waitMessage((m) => m.t === 'tabUpdate' && m.id === c.activeId && m.loading === true, 15000).catch(() => {});
  await c.waitMessage((m) => m.t === 'tabUpdate' && m.id === c.activeId && m.loading === false, 45000);
  await c.quiet();
  const file = `${name}.png`;
  await c.save(file);
  const updates = c.frames.slice(firstFrames);
  const refines = updates.filter((f) => f.flags & FLAG_REFINE);
  const kb = (list) => (list.reduce((n, f) => n + f.bytes, 0) / 1024).toFixed(0);
  const tab = c.tabs.get(c.activeId);
  console.log(
    `${url}
  title: ${tab?.title}
  loaded and quiet in ${Math.round(performance.now() - started)} ms; ${updates.length} updates ${kb(updates)} KB ` +
      `(of which ${refines.length} sharpening ${kb(refines)} KB) → ${file}`,
  );
}

async function swipe(c) {
  const x = vw / 2;
  const startY = vh * 0.8;
  c.send({ t: 'touch', phase: 'start', time: Date.now(), points: [{ id: 0, x, y: startY }] });
  for (let i = 1; i <= 10; i++) {
    await sleep(10);
    c.send({ t: 'touch', phase: 'move', time: Date.now(), points: [{ id: 0, x, y: startY - i * 60 }] });
  }
  c.send({ t: 'touch', phase: 'end', time: Date.now(), points: [] });
  const before = c.frames.length;
  await sleep(2500);
  const moving = c.frames.slice(before).filter((f) => !(f.flags & FLAG_REFINE));
  const finalY = moving.at(-1)?.scrollY ?? 0;
  const span = (moving.at(-1)?.at - moving[0]?.at) / 1000;
  console.log(`  swipe: ${moving.length} updates in ${span.toFixed(2)} s (${(moving.length / span).toFixed(0)}/s), avg ${(moving.reduce((n, f) => n + f.bytes, 0) / moving.length / 1024).toFixed(0)} KB`);
  console.log(`  swipe: finger moved 600 px, page scrolled ${Math.round(finalY)} px → ${finalY > 700 ? 'FLING' : 'no fling'}`);
}

try {
  const deviceId = randomBytes(32).toString('base64url');
  const c = connect(deviceId);
  console.log(`handshake: ${await c.handshake()}`);
  for (const [i, url] of urls.entries()) {
    await load(c, url, String(i + 1).padStart(2, '0'));
    if (process.env.SWIPE === '1') await swipe(c);
  }

  if (process.env.SUGGEST) {
    c.send({ t: 'suggest', id: 1, q: process.env.SUGGEST });
    const res = await c.waitMessage((m) => m.t === 'suggestions' && m.id === 1, 5000);
    console.log(`suggest: "${res.q}" → ${res.items.map((i) => (i.kind === 'url' ? `<${i.text}>` : i.text)).join(' | ') || '(none)'}`);
  }

  if (process.env.IME_TAP) {
    const [x, y] = process.env.IME_TAP.split(',').map(Number);
    const t = Date.now();
    c.send({ t: 'touch', phase: 'start', time: t, points: [{ id: 0, x, y }] });
    c.send({ t: 'touch', phase: 'end', time: t + 50, points: [] });
    const focus = await c.waitMessage((m) => m.t === 'focus', 5000);
    console.log(`ime: focus ${focus.inputType} field ${focus.fieldId}, text "${focus.text}"`);
    // What an Android keyboard sends while typing "Hello world!" with a correction.
    const ops = [
      { t: 'commit', text: 'Hello ', cursor: 1 },
      { t: 'compose', text: 'wor', selStart: 3, selEnd: 3 },
      { t: 'compose', text: 'world', selStart: 5, selEnd: 5 },
      { t: 'finishCompose' },
      { t: 'deleteSurrounding', before: 1, after: 0 },
      { t: 'commit', text: 'd!', cursor: 1 },
    ];
    ops.forEach((op, i) => c.send({ ...op, seq: i + 1 }));
    const state = await c.waitMessage((m) => m.t === 'editState' && m.seq === ops.length, 5000);
    console.log(`ime: text "${state.text}" caret ${state.selStart}-${state.selEnd} → ${state.text === 'Hello world!' ? 'OK' : 'WRONG'}`);
    await c.quiet();
    await c.save('ime.png');
    if (process.env.KEYBOARD_H) {
      // The keyboard opens: the viewport shrinks and the field must end up inside it.
      const h = Number(process.env.KEYBOARD_H);
      c.send({ t: 'viewport', w: vw, h, dpr });
      await c.quiet(1200, 6000);
      await c.save('keyboard.png');
      const r = focus.rect;
      console.log(`keyboard: viewport ${vw}x${h}, field was at y=${Math.round(r.y)}-${Math.round(r.y + r.h)}; see keyboard.png`);
    }
  }

  if (process.env.SYNC === '1') {
    await sleep(1500); // one cookie poll
    const localKeys = Object.keys(saved.local).filter((k) => Object.keys(saved.local[k]).length > 0);
    console.log(`sync: ${syncMessages} messages, ${saved.cookies.size} cookies, localStorage of ${localKeys.length} origins (${localKeys.join(', ')})`);
    c.send({ t: 'forget' });
    await c.waitMessage((m) => m.t === 'forgotten');
    c.ws.close();

    const again = connect(deviceId);
    console.log(`reconnect after forget: ${await again.handshake()} (restored ${saved.tabs.length} tabs)`);
    await load(again, process.env.SYNC_CHECK_URL ?? 'https://httpbin.org/cookies', 'sync-check');
    again.ws.close();
  } else {
    c.ws.close();
  }
} catch (err) {
  console.error('FAILED:', err.message);
  process.exit(1);
}
