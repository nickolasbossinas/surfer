// Surfer desktop debug client: speaks protocol v1 like the Android app, so the
// server can be developed and tested without reinstalling the APK.
'use strict';

const $ = (id) => document.getElementById(id);
const canvas = $('screen');
const ctx = canvas.getContext('2d');
const DEVICE_KEY = 'surfer.debug.deviceId';
const FORMATS = ['image/jpeg', 'image/png', 'image/webp'];

let ws;
let clockOffset = 0; // server clock minus local clock (ms)
let rtt = 0;
let tabs = [];
let activeId = 0;
let reconnectDelay = 500;
const stats = { frames: 0, bytes: 0, fps: 0, kbps: 0, last: '' };

// The debug client keeps no browser state: every new server session starts empty.
function deviceId() {
  let id;
  try { id = localStorage.getItem(DEVICE_KEY); } catch {}
  if (!id) {
    const bytes = crypto.getRandomValues(new Uint8Array(32));
    id = btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    try { localStorage.setItem(DEVICE_KEY, id); } catch {}
  }
  return id;
}

function send(msg) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
}

function connect() {
  ws = new WebSocket(`wss://${location.host}/ws`);
  ws.binaryType = 'arraybuffer';
  ws.onopen = () => {
    reconnectDelay = 500;
    setStatus('connected');
    send({ t: 'hello', proto: 1, app: 'debug', appVersion: '0.1', deviceId: deviceId(), device: { model: navigator.userAgent.slice(0, 80) } });
  };
  ws.onmessage = (e) => (typeof e.data === 'string' ? onMessage(JSON.parse(e.data)) : onFrame(e.data));
  ws.onclose = (e) => {
    setStatus(`disconnected (${e.code} ${e.reason || ''})`);
    if (e.code === 4001) return; // taken over
    setTimeout(connect, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 2, 10000);
  };
}

function onMessage(msg) {
  switch (msg.t) {
    case 'needRestore':
      send({ t: 'restoreBegin', cookies: [], tabs: [], active: 0, settings: {} });
      send({ t: 'restoreEnd' });
      break;
    case 'ready':
      sendViewport();
      canvas.focus();
      break;
    case 'tabs':
      tabs = msg.tabs;
      activeId = msg.activeId;
      renderTabs();
      break;
    case 'tabUpdate': {
      const i = tabs.findIndex((t) => t.id === msg.id);
      const { t: _type, ...info } = msg;
      if (i >= 0) tabs[i] = info; else tabs.push(info);
      renderTabs();
      break;
    }
    case 'pong':
      rtt = Date.now() - msg.t0;
      clockOffset = msg.server + rtt / 2 - Date.now();
      break;
    case 'sessionTakenOver':
      setStatus('another device took over this session');
      break;
    default:
      console.log('server:', msg);
  }
}

// Updates paint onto the current picture, so they are applied strictly in order.
let painting = Promise.resolve();
function onFrame(buffer) {
  painting = painting.then(() => paintFrame(buffer)).catch((err) => console.error('frame', err));
}

async function paintFrame(buffer) {
  const view = new DataView(buffer);
  if (view.getUint8(0) !== 1) return;
  const flags = view.getUint8(1);
  const count = view.getUint16(2, true);
  const frameId = view.getUint32(4, true);
  const width = view.getUint16(12, true);
  const height = view.getUint16(14, true);
  try {
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
    let o = 60;
    for (let i = 0; i < count; i++) {
      const x = view.getUint16(o, true);
      const y = view.getUint16(o + 2, true);
      const format = view.getUint8(o + 8);
      const length = view.getUint32(o + 12, true);
      const bitmap = await createImageBitmap(new Blob([new Uint8Array(buffer, o + 16, length)], { type: FORMATS[format] }));
      ctx.drawImage(bitmap, x, y);
      bitmap.close();
      o += 16 + length;
    }
  } finally {
    send({ t: 'frameAck', frameId });
  }
  stats.frames++;
  stats.bytes += buffer.byteLength;
  stats.last = `${flags & 1 ? 'key ' : ''}${flags & 4 ? 'sharpen ' : ''}${count} rect(s) ${(buffer.byteLength / 1024).toFixed(1)} KB`;
}

function renderTabs() {
  const select = $('tabs');
  select.textContent = '';
  for (const tab of tabs) {
    const option = document.createElement('option');
    option.value = String(tab.id);
    option.textContent = tab.title || tab.url || 'New tab';
    option.selected = tab.id === activeId;
    select.appendChild(option);
  }
  const active = tabs.find((t) => t.id === activeId);
  if (active) {
    if (document.activeElement !== $('url')) $('url').value = active.url === 'about:blank' ? '' : active.url;
    $('back').disabled = !active.canBack;
    $('forward').disabled = !active.canForward;
    $('loading').style.width = active.loading ? `${Math.round(active.progress * 100)}%` : '0';
    document.title = `${active.title || 'Surfer'} — debug`;
  }
}

function sendViewport() {
  const rect = canvas.getBoundingClientRect();
  send({ t: 'viewport', w: Math.round(rect.width), h: Math.round(rect.height), dpr: window.devicePixelRatio || 1 });
}

function setStatus(text) {
  $('status').dataset.state = text;
  updateStatus();
}

function updateStatus() {
  $('status').textContent = `${$('status').dataset.state || ''} | ${stats.fps} fps ${stats.kbps} kB/s | rtt ${rtt} ms | ${stats.last}`;
}

setInterval(() => {
  stats.fps = stats.frames;
  stats.kbps = Math.round(stats.bytes / 1024);
  stats.frames = 0;
  stats.bytes = 0;
  updateStatus();
  send({ t: 'ping', t0: Date.now() });
}, 1000);

// --- Input -----------------------------------------------------------------

function point(e) {
  const rect = canvas.getBoundingClientRect();
  return { id: e.pointerId % 1000, x: e.clientX - rect.left, y: e.clientY - rect.top };
}

const pointers = new Map();
function sendTouch(phase) {
  send({ t: 'touch', phase, time: Date.now() + clockOffset, points: phase === 'end' || phase === 'cancel' ? [] : [...pointers.values()] });
}

canvas.addEventListener('pointerdown', (e) => {
  canvas.focus();
  canvas.setPointerCapture(e.pointerId);
  const first = pointers.size === 0;
  pointers.set(e.pointerId, point(e));
  sendTouch(first ? 'start' : 'move');
  e.preventDefault();
});
canvas.addEventListener('pointermove', (e) => {
  if (!pointers.has(e.pointerId)) return;
  pointers.set(e.pointerId, point(e));
  sendTouch('move');
});
for (const type of ['pointerup', 'pointercancel']) {
  canvas.addEventListener(type, (e) => {
    if (!pointers.delete(e.pointerId)) return;
    sendTouch(pointers.size ? 'move' : type === 'pointerup' ? 'end' : 'cancel');
  });
}
canvas.addEventListener('wheel', (e) => {
  const p = point(e);
  send({ t: 'wheel', x: p.x, y: p.y, dx: e.deltaX, dy: e.deltaY });
  e.preventDefault();
}, { passive: false });

const MODS = (e) => (e.altKey ? 1 : 0) | (e.ctrlKey ? 2 : 0) | (e.metaKey ? 4 : 0) | (e.shiftKey ? 8 : 0);
for (const type of ['keydown', 'keyup']) {
  canvas.addEventListener(type, (e) => {
    send({ t: 'key', phase: type === 'keydown' ? 'down' : 'up', key: e.key, code: e.code, keyCode: e.keyCode, mods: MODS(e) });
    e.preventDefault();
  });
}

// --- Bar -------------------------------------------------------------------

$('url').addEventListener('keydown', (e) => {
  if (e.key !== 'Enter') return;
  send({ t: 'navigate', tabId: activeId, input: $('url').value });
  canvas.focus();
});
$('back').onclick = () => send({ t: 'back', tabId: activeId });
$('forward').onclick = () => send({ t: 'forward', tabId: activeId });
$('reload').onclick = () => send({ t: 'reload', tabId: activeId });
$('newTab').onclick = () => send({ t: 'tabNew' });
$('closeTab').onclick = () => send({ t: 'tabClose', tabId: activeId });
$('tabs').onchange = (e) => send({ t: 'tabActivate', tabId: Number(e.target.value) });
$('forget').onclick = () => send({ t: 'forget' });

let resizeTimer;
new ResizeObserver(() => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(sendViewport, 150);
}).observe(canvas);

connect();
