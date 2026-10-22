/*
 * BLADE Phone -- UI.
 *
 * Wires the BladePhone client to the handset. Four things here exist because this
 * gets demonstrated in front of people:
 *
 *   - It registers itself. A signed-in person opens the page and it says Ready;
 *     the connection settings are in a drawer for whoever needs them.
 *   - Live level meters on both directions. "The call connected" is a claim; a
 *     moving far-end meter is evidence of a media path.
 *   - Video whenever the far end sends some, and a camera that can go on mid-call.
 *   - Real DTMF tones and a ring, synthesised with Web Audio: no files to ship.
 */

import { BladePhone } from './blade-webrtc.js';

const $ = (id) => document.getElementById(id);

const els = {
  pill: $('pill'), display: $('display'), state: $('state-label'), party: $('party'), me: $('me'),
  timer: $('timer'), incoming: $('incoming'), incomingFrom: $('incoming-from'),
  live: $('live'), callFacts: $('call-facts'), meterMic: $('meter-mic'), meterRx: $('meter-rx'),
  target: $('target'), keypad: $('keypad'), log: $('log'), remote: $('remote'),
  layout: $('layout'), stage: $('stage'), stageLabel: $('stage-label'),
  remoteVideo: $('remote-video'), localVideo: $('local-video'),
  recent: $('recent'), recentList: $('recent-list'),
  acct: $('acct'), acctBadge: $('acct-badge'), acctName: $('acct-name'),
  gateway: $('gateway'), aor: $('aor'), stun: $('stun'), authState: $('auth-state'), authShort: $('auth-short'),
  factPath: $('fact-path'), factIce: $('fact-ice'), factPair: $('fact-pair'), factCodec: $('fact-codec'),
  factVideo: $('fact-video'), details: $('details'),
  peek: { connection: $('peek-connection'), media: $('peek-media'), signaling: $('peek-signaling') },
  btn: {
    call: $('btn-call'), callLabel: $('call-label'), mute: $('btn-mute'), camera: $('btn-camera'),
    answer: $('btn-answer'), decline: $('btn-decline'), back: $('btn-back'),
    register: $('btn-register'), unregister: $('btn-unregister'),
    details: $('btn-details'), detailsClose: $('btn-details-close'),
  },
};

let phone = null;
let muted = false;
let callStartedAt = 0;
let timerHandle = null;
/** Stops for the two level meters of the call in progress. */
let meterStops = [];

// ---------------------------------------------------------------- gateway default

// The phone is served from the ADMIN tier but the gateway lives on the ENGINE tier,
// so this page's own origin is the wrong default. Guess the engine's HTTP port on the
// same host, which is right for a single-box lab and editable otherwise.
const ENGINE_HTTP_PORT = 8001;
function guessGateway() {
  const secure = location.protocol === 'https:';
  const scheme = secure ? 'wss' : 'ws';
  const port = secure ? location.port : ENGINE_HTTP_PORT;
  const host = location.hostname + (port ? `:${port}` : '');
  return `${scheme}://${host}/webrtc/signal`;
}
els.gateway.value = guessGateway();

// ---------------------------------------------------------------- identity

/*
 * Whatever address is registered, the gateway learns it from a signed token rather than from this
 * page, so a browser can never claim an address the server did not issue it. Whether you may ASK
 * for a particular address is a separate question, and a deployment setting.
 *
 * The token is fetched per registration rather than held: it lives about a minute, so caching one
 * across a page that might sit open all afternoon would only produce expired-token failures.
 */

/** Ask the server who we are and what it will let us do. */
async function loadSession() {
  const response = await fetch('api/v1/session', {
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

async function mintToken(aor) {
  const query = aor ? `?aor=${encodeURIComponent(aor)}` : '';
  const response = await fetch(`api/v1/token${query}`, {
    method: 'POST',
    headers: { Accept: 'application/json' },
    // The admin session cookie is what authenticates this call.
    credentials: 'same-origin',
    cache: 'no-store',
  });
  if (!response.ok) {
    let detail = `HTTP ${response.status}`;
    try {
      const body = await response.json();
      if (body && body.error) detail = body.error;
    } catch {
      // Non-JSON body: a container error page, most likely the login form after the
      // session expired. The status is all we can report.
      if (response.status === 401 || response.status === 403) {
        detail = 'your session has expired; reload the page to sign in again';
      }
    }
    throw new Error(detail);
  }
  return response.json();
}

function showAccount(user) {
  if (!user) return;
  const initials = user.split(/[\s._@-]+/).filter(Boolean).slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase()).join('');
  els.acctBadge.textContent = initials || '?';
  els.acctName.textContent = user;
  els.acct.title = `Signed in as ${user}`;
  els.acct.hidden = false;
}

/*
 * Fill the form from the server's answer, then register. The address stays editable when the
 * deployment allows a chosen one and goes read-only when it does not, so the field itself tells
 * you which deployment you are on.
 */
loadSession().then((s) => {
  showAccount(s.user);
  els.aor.value = s.aor;
  if (s.stunServer) els.stun.value = s.stunServer;

  if (s.allowChosenAddress) {
    els.aor.readOnly = false;
    els.aor.removeAttribute('aria-readonly');
  }
  setAuthText('unknown', `Signed in as ${s.user}. Not registered.`);

  if (s.gateway) {
    els.gateway.value = s.gateway;
    register();
  } else {
    // The guess is right only when the engine listens on the address you browsed to, so do not
    // act on it unasked: say where it came from and let the person press Register.
    log(`no gateway configured; guessing ${els.gateway.value}. `
      + 'Set "gateway" in blade-phone.json if the engine listens elsewhere.', 'err');
    showDetails(true);
  }
}).catch((e) => {
  log(`could not read this session: ${e.message}`, 'err');
});

// ---------------------------------------------------------------- details panel

/*
 * Connection settings, media facts and the signaling log: what a demo does not need and a
 * diagnosis does. Open or closed is remembered in this browser, since whoever opens it once
 * tends to want it every time.
 */
const DETAILS_KEY = 'blade.phone.details';

function showDetails(open) {
  els.details.hidden = !open;
  els.layout.dataset.details = String(open);
  els.btn.details.setAttribute('aria-expanded', String(open));
  try { localStorage.setItem(DETAILS_KEY, open ? 'open' : 'closed'); } catch { /* private window */ }
  if (open) els.log.scrollTop = els.log.scrollHeight;
}

els.btn.details.addEventListener('click', () => showDetails(els.details.hidden));
els.btn.detailsClose.addEventListener('click', () => { showDetails(false); els.btn.details.focus(); });
try { if (localStorage.getItem(DETAILS_KEY) === 'open') showDetails(true); } catch { /* private window */ }

/* Each section folds on its own title; which ones are folded is remembered too. */
const PANELS_KEY = 'blade.phone.panels';
let folded = {};
try { folded = JSON.parse(localStorage.getItem(PANELS_KEY) || '{}') || {}; } catch { /* private window */ }
for (const panel of els.details.querySelectorAll('details[data-panel]')) {
  if (folded[panel.dataset.panel]) panel.open = false;
  panel.addEventListener('toggle', () => {
    folded[panel.dataset.panel] = !panel.open;
    try { localStorage.setItem(PANELS_KEY, JSON.stringify(folded)); } catch { /* private window */ }
    if (panel.open && panel.dataset.panel === 'signaling') els.log.scrollTop = els.log.scrollHeight;
  });
}

// ---------------------------------------------------------------- log

function log(message, dir = 'in') {
  const empty = els.log.querySelector('.phone-log-empty');
  if (empty) empty.remove();
  const li = document.createElement('li');
  li.dataset.dir = dir;
  const t = document.createElement('time');
  t.textContent = new Date().toLocaleTimeString();
  const s = document.createElement('span');
  s.textContent = message;
  li.append(t, s);
  els.log.append(li);
  els.peek.signaling.textContent = message;
  els.log.scrollTop = els.log.scrollHeight;
}

// ---------------------------------------------------------------- tones

let audioCtx = null;
const ctx = () => (audioCtx ||= new (window.AudioContext || window.webkitAudioContext)());

/** DTMF is a pair of tones; the real frequencies cost nothing and sound right. */
const DTMF = {
  1: [697, 1209], 2: [697, 1336], 3: [697, 1477],
  4: [770, 1209], 5: [770, 1336], 6: [770, 1477],
  7: [852, 1209], 8: [852, 1336], 9: [852, 1477],
  '*': [941, 1209], 0: [941, 1336], '#': [941, 1477],
};

function tone(freqs, ms, gain = 0.14) {
  const c = ctx();
  if (c.state === 'suspended') c.resume();
  const g = c.createGain();
  g.gain.value = gain;
  g.connect(c.destination);
  const now = c.currentTime;
  // Short ramps: a square-edged tone clicks, which sounds like a bug rather than a phone.
  g.gain.setValueAtTime(0, now);
  g.gain.linearRampToValueAtTime(gain, now + 0.012);
  g.gain.setValueAtTime(gain, now + ms / 1000 - 0.02);
  g.gain.linearRampToValueAtTime(0, now + ms / 1000);
  for (const f of freqs) {
    const o = c.createOscillator();
    o.frequency.value = f;
    o.connect(g);
    o.start(now);
    o.stop(now + ms / 1000);
  }
}

let ringHandle = null;
function startRinging() {
  if (ringHandle) return;
  const ring = () => tone([440, 480], 900, 0.09);
  ring();
  ringHandle = setInterval(ring, 3000);
}
function stopRinging() {
  if (ringHandle) { clearInterval(ringHandle); ringHandle = null; }
}

// ---------------------------------------------------------------- meters

const SEGMENTS = 16;

function buildMeter(el) {
  el.replaceChildren(...Array.from({ length: SEGMENTS }, () => {
    const d = document.createElement('div');
    d.className = 'phone-meter-seg';
    return d;
  }));
}
buildMeter(els.meterMic);
buildMeter(els.meterRx);

function paintMeter(el, level) {
  const lit = Math.round(level * SEGMENTS);
  el.childNodes.forEach((seg, i) => {
    seg.dataset.on = String(i < lit);
    seg.dataset.peak = String(i === lit - 1 && lit > 0);
  });
}

/**
 * Drive one meter from a stream's audio until the returned stop is called. Stopped
 * explicitly at the end of the call: a track stopped or closed locally never fires
 * 'ended', so waiting for that event left the loop running.
 */
function meterStream(stream, el) {
  if (!stream || !stream.getAudioTracks().length) return () => {};
  const c = ctx();
  if (c.state === 'suspended') c.resume();
  const src = c.createMediaStreamSource(stream);
  const analyser = c.createAnalyser();
  analyser.fftSize = 512;
  analyser.smoothingTimeConstant = 0.75;
  src.connect(analyser);
  const buf = new Uint8Array(analyser.frequencyBinCount);

  let stopped = false;
  (function frame() {
    if (stopped) return;
    analyser.getByteFrequencyData(buf);
    // RMS over the band, scaled so ordinary speech fills most of the meter.
    let sum = 0;
    for (const v of buf) sum += v * v;
    paintMeter(el, Math.min(1, (Math.sqrt(sum / buf.length) / 255) * 2.6));
    requestAnimationFrame(frame);
  })();

  return () => { stopped = true; src.disconnect(); paintMeter(el, 0); };
}

function stopMeters() {
  meterStops.forEach((stop) => stop());
  meterStops = [];
}

// ---------------------------------------------------------------- video

/**
 * Show the stage, at the top of the display, while there is something to watch: far-end video that is actually
 * arriving (a track unmutes when frames flow, so an offered but silent video section
 * shows nothing), or your own camera.
 */
function updateVideo() {
  const stream = phone?.remoteStream;
  const remote = !!stream && stream.getVideoTracks().some((t) => t.readyState === 'live' && !t.muted);
  const self = !!phone?.cameraStream;

  els.remoteVideo.hidden = !remote;
  // The display already names the party; the label speaks only when there is no picture.
  els.stageLabel.textContent = 'No video from the far end';
  els.stageLabel.hidden = remote;
  els.stage.hidden = !(remote || self);

  els.localVideo.hidden = !self;
  if (self && els.localVideo.srcObject !== phone.cameraStream) els.localVideo.srcObject = phone.cameraStream;
  if (!self) els.localVideo.srcObject = null;
}

function onRemoteTrack(stream, track) {
  // Sound always plays from the audio element; the video element is muted so it never doubles it.
  if (els.remote.srcObject !== stream) els.remote.srcObject = stream;
  if (track.kind === 'audio') {
    meterStops.push(meterStream(new MediaStream([track]), els.meterRx));
  } else if (track.kind === 'video') {
    if (els.remoteVideo.srcObject !== stream) els.remoteVideo.srcObject = stream;
    track.addEventListener('unmute', updateVideo);
    track.addEventListener('mute', updateVideo);
    track.addEventListener('ended', updateVideo);
    updateVideo();
  }
}

// ---------------------------------------------------------------- recent numbers

/*
 * Kept in this browser only. Numbers people dial do not belong on the server, and
 * this way a rep's own number stays on the rep's own machine.
 */
const RECENT_KEY = 'blade.phone.recent';
const RECENT_MAX = 8;

function readRecent() {
  try {
    const list = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]');
    return Array.isArray(list) ? list.filter((v) => typeof v === 'string') : [];
  } catch {
    return [];
  }
}

function writeRecent(list) {
  try { localStorage.setItem(RECENT_KEY, JSON.stringify(list)); } catch { /* private window */ }
  renderRecent();
}

function remember(target) {
  writeRecent([target, ...readRecent().filter((v) => v !== target)].slice(0, RECENT_MAX));
}

function renderRecent() {
  const list = readRecent();
  els.recent.hidden = !list.length;
  els.recentList.replaceChildren(...list.map((target) => {
    const item = document.createElement('span');
    item.className = 'phone-recent-item';
    const dial = document.createElement('button');
    dial.type = 'button';
    dial.className = 'phone-recent-dial';
    dial.textContent = target;
    dial.title = `Dial ${target}`;
    dial.addEventListener('click', () => { els.target.value = target; placeCall(); });
    const forget = document.createElement('button');
    forget.type = 'button';
    forget.className = 'phone-recent-forget';
    forget.textContent = '✕';
    forget.title = `Forget ${target}`;
    forget.setAttribute('aria-label', `Forget ${target}`);
    forget.addEventListener('click', () => writeRecent(readRecent().filter((v) => v !== target)));
    item.append(dial, forget);
    return item;
  }));
}
renderRecent();

// ---------------------------------------------------------------- state machine

const LIVE = new Set(['ringing', 'ringing-remote', 'progress', 'established', 'calling', 'answering']);

const LABEL = {
  idle: 'Not registered', registered: 'Ready', calling: 'Calling',
  progress: 'Connecting', 'ringing-remote': 'Ringing', ringing: 'Incoming call',
  answering: 'Answering', established: 'On call',
};

function setState(state) {
  const live = LIVE.has(state);
  els.state.textContent = LABEL[state] || state;
  els.display.dataset.live = String(live);
  els.display.dataset.ready = String(state !== 'idle');
  els.pill.textContent = state === 'idle' ? 'Offline' : (LABEL[state] || state);
  els.pill.dataset.state = state;

  // One button: Call while ready, Hang up through the whole call.
  els.btn.call.dataset.mode = live ? 'hangup' : 'call';
  els.btn.callLabel.textContent = live ? 'Hang up' : 'Call';
  els.btn.call.disabled = !(live || state === 'registered');
  els.btn.mute.disabled = state !== 'established';
  els.btn.camera.disabled = !(state === 'established' && phone?.videoTransceiver);
  els.btn.register.disabled = state !== 'idle';
  els.btn.unregister.disabled = state === 'idle';

  const incoming = state === 'ringing';
  els.incoming.hidden = !incoming;
  if (incoming) startRinging(); else stopRinging();

  els.live.hidden = state !== 'established';
  if (state === 'established' && !callStartedAt) startTimer();
  if (!live) endCall();
}

/** Put the handset back to Ready: whoever ended the call, and however. */
function endCall() {
  stopTimer();
  stopMeters();
  setParty(null);
  resetFacts();
  muted = false;
  setPressed(els.btn.mute, false, 'Mute', 'Unmute');
  setPressed(els.btn.camera, false, 'Camera', 'Camera on');
  els.remoteVideo.srcObject = null;
  updateVideo();
}

function setPressed(button, on, offLabel, onLabel) {
  button.setAttribute('aria-pressed', String(on));
  button.textContent = on ? onLabel : offLabel;
}

/*
 * Whether the gateway verified this browser, in words: "the gateway is not checking anybody"
 * must not depend on noticing a shade.
 */
function setAuthText(verified, text, short) {
  els.authState.dataset.verified = verified;
  els.authState.textContent = text;
  els.authShort.textContent = short || text;
  els.peek.connection.textContent = short || text;
}

function setAuthPill(verified, user) {
  const address = phone?.aor ?? els.aor.value;
  // Name the account as well as the address: with a chosen address the two differ.
  const who = user && user !== address ? `${address} (signed in as ${user})` : address;
  if (verified) {
    setAuthText('true', `Registered as ${who}, verified by the gateway.`, `Registered as ${address}`);
  } else {
    setAuthText('false',
      `Registered as ${address}. The gateway is not verifying browsers: any client that can reach it may claim any address.`,
      `Registered as ${address}, NOT verified`);
  }
  els.me.textContent = address;
}

function clearAuthPill() {
  setAuthText('unknown', 'Not registered.', 'Not registered');
  els.me.innerHTML = '&nbsp;';
}

function startTimer() {
  callStartedAt = Date.now();
  const paint = () => {
    const s = Math.floor((Date.now() - callStartedAt) / 1000);
    els.timer.textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  };
  paint();
  timerHandle = setInterval(paint, 500);
}

function stopTimer() {
  if (timerHandle) { clearInterval(timerHandle); timerHandle = null; }
  callStartedAt = 0;
  els.timer.textContent = '';
}

function setParty(text) {
  els.party.textContent = text || 'No call';
  els.party.classList.toggle('phone-party-empty', !text);
}

const FACTS = ['factPath', 'factIce', 'factPair', 'factCodec', 'factVideo'];
let mediaPath = '';

function resetFacts() {
  for (const f of FACTS) els[f].textContent = '–';
  els.callFacts.textContent = '';
  els.peek.media.textContent = 'no call';
  mediaPath = '';
}

// ---------------------------------------------------------------- live stats

/**
 * Poll getStats for the facts that matter in a demo: whether media is direct or
 * relayed, which codecs won, and the video actually arriving. Read from the browser
 * rather than from our own signalling, so it cannot be wrong.
 */
async function pollStats() {
  if (!phone?.pc || phone.state !== 'established') return;
  try {
    const stats = await phone.pc.getStats();
    let pair = null; let audio = null; let video = null; let inVideo = null;
    const byId = new Map();
    stats.forEach((r) => {
      byId.set(r.id, r);
      if (r.type === 'candidate-pair' && r.state === 'succeeded' && r.nominated) pair = r;
      if (r.type === 'inbound-rtp' && r.kind === 'video') inVideo = r;
      if (r.type === 'inbound-rtp' && r.kind === 'audio') audio = r;
    });
    const codecOf = (rtp) => (rtp?.codecId && byId.get(rtp.codecId)?.mimeType?.split('/')[1]) || null;

    const parts = [];
    const audioCodec = codecOf(audio);
    if (audioCodec) { els.factCodec.textContent = audioCodec; parts.push(audioCodec); }
    if (mediaPath) parts.push(mediaPath);

    if (pair) {
      const l = byId.get(pair.localCandidateId);
      const r = byId.get(pair.remoteCandidateId);
      if (l && r) {
        els.factPair.textContent = `${l.candidateType} / ${r.candidateType}`;
        const relayed = l.candidateType === 'relay' || r.candidateType === 'relay';
        els.factIce.textContent = relayed ? 'relayed (TURN)' : 'direct';
        parts.push(relayed ? 'TURN' : 'direct');
      }
    }

    if (inVideo && inVideo.frameWidth) {
      video = `${codecOf(inVideo) || 'video'} ${inVideo.frameWidth}×${inVideo.frameHeight}`
        + (inVideo.framesPerSecond ? ` ${Math.round(inVideo.framesPerSecond)} fps` : '');
      parts.push(video);
    }
    els.factVideo.textContent = video || (phone.videoTransceiver ? 'none arriving' : 'not offered');
    els.callFacts.textContent = parts.join(' · ');
    els.peek.media.textContent = els.callFacts.textContent || 'on call';
  } catch {
    // getStats is decoration; never let it break a call.
  }
}
setInterval(pollStats, 1000);

// ---------------------------------------------------------------- keypad

const KEYS = [
  ['1', ''], ['2', 'ABC'], ['3', 'DEF'],
  ['4', 'GHI'], ['5', 'JKL'], ['6', 'MNO'],
  ['7', 'PQRS'], ['8', 'TUV'], ['9', 'WXYZ'],
  ['*', ''], ['0', '+'], ['#', ''],
];

for (const [digit, letters] of KEYS) {
  const b = document.createElement('button');
  b.className = 'phone-key';
  b.type = 'button';
  b.innerHTML = `<span class="phone-key-digit">${digit}</span><span class="phone-key-letters">${letters}</span>`;
  b.addEventListener('click', () => press(digit));
  els.keypad.append(b);
}

function press(digit) {
  tone(DTMF[digit] || [800, 1000], 120);
  if (phone && phone.state === 'established') {
    phone.dtmf(digit);
    log(`DTMF ${digit}`, 'out');
  } else {
    els.target.value += digit;
  }
}

// ---------------------------------------------------------------- register

async function register() {
  els.btn.register.disabled = true;

  let grant;
  try {
    log('requesting a token for this session', 'out');
    grant = await mintToken(els.aor.value.trim());
  } catch (e) {
    log(`could not obtain a token: ${e.message}`, 'err');
    els.btn.register.disabled = false;
    showDetails(true);
    return;
  }

  // Show what was issued rather than what was asked for: they differ when the deployment
  // declined the request, and the token is what the gateway acts on.
  els.aor.value = grant.aor;

  const stun = els.stun.value.trim();
  phone = new BladePhone({
    url: els.gateway.value.trim(),
    aor: grant.aor,
    token: grant.token,
    iceServers: stun ? [{ urls: stun }] : [],
  });

  phone.onStateChange = setState;
  phone.onRegistered = (d) => {
    log(`registered as ${d?.aor ?? phone.aor}`);
    // An unauthenticated gateway is a deployment mistake, not a mode to be quiet about.
    if (d && d.authenticated === false) {
      log('this gateway is NOT authenticating browsers: anyone who can reach it may claim any address', 'err');
    }
    setAuthPill(d && d.authenticated !== false, grant.user);
  };
  phone.onIncoming = (c) => { setParty(c.from); els.incomingFrom.textContent = c.from; log(`incoming from ${c.from}`); };
  phone.onProgress = (d) => log(`progress: ${d.status ?? 'trying'}`);
  phone.onEstablished = () => {
    log('call answered');
    mediaPath = phone.trickle ? 'peer-to-peer' : 'anchored';
    els.factPath.textContent = mediaPath;
    meterStops.push(meterStream(phone.localStream, els.meterMic));
    setState(phone.state);   // the camera button depends on the video section, known by now
  };
  phone.onConnected = (d) => {
    // The ACK. When a call is up and silent, whether media was ever negotiated is the first
    // thing to look for.
    log(d.negotiated ? 'call connected' : 'call connected WITHOUT media negotiation',
        d.negotiated ? 'in' : 'err');
  };
  phone.onRenegotiated = () => {
    log('renegotiated: media server now in the path');
    mediaPath = 'anchored';
    els.factPath.textContent = mediaPath;
  };
  phone.onEnded = (d) => log(`call ended: ${d.reason ?? 'normal'}`);
  phone.onError = (e) => log(String(e), 'err');
  phone.onRemoteStream = onRemoteTrack;

  try {
    log(`connecting to ${phone.url}`, 'out');
    await phone.connect();
  } catch (e) {
    log(`could not register: ${e.message}`, 'err');
    clearAuthPill();
    setState('idle');
    showDetails(true);
  }
}

// ---------------------------------------------------------------- actions

async function placeCall() {
  if (!phone || phone.state !== 'registered') return;
  const target = els.target.value.trim();
  if (!target) return log('enter a number or address first', 'err');
  remember(target);
  setParty(target);
  log(`calling ${target}`, 'out');
  try { await phone.call(target); } catch (e) { log(`call failed: ${e.message}`, 'err'); }
}

function hangUp() {
  if (!phone) return;
  const declining = phone.state === 'ringing';
  stopRinging();
  phone.hangup();
  log(declining ? 'declined' : 'hung up', 'out');
}

els.btn.back.addEventListener('click', () => { els.target.value = els.target.value.slice(0, -1); });
els.btn.register.addEventListener('click', register);

els.btn.unregister.addEventListener('click', () => {
  phone?.disconnect();
  clearAuthPill();
  log('unregistered', 'out');
});

els.btn.call.addEventListener('click', () => {
  if (els.btn.call.dataset.mode === 'hangup') hangUp(); else placeCall();
});

els.btn.answer.addEventListener('click', async () => {
  stopRinging();
  log('answering', 'out');
  try { await phone.answer(); } catch (e) { log(`answer failed: ${e.message}`, 'err'); }
});

els.btn.decline.addEventListener('click', hangUp);

els.btn.mute.addEventListener('click', () => {
  muted = !muted;
  phone.setMuted(muted);
  setPressed(els.btn.mute, muted, 'Mute', 'Unmute');
  log(muted ? 'microphone muted' : 'microphone live', 'out');
});

els.btn.camera.addEventListener('click', async () => {
  const on = els.btn.camera.getAttribute('aria-pressed') !== 'true';
  els.btn.camera.disabled = true;
  try {
    const ok = await phone.setCamera(on);
    if (ok) {
      setPressed(els.btn.camera, on, 'Camera', 'Camera on');
      log(on ? 'camera on' : 'camera off', 'out');
    } else {
      log('the far end does not take video on this call', 'err');
    }
  } catch (e) {
    log(`camera: ${e.message}`, 'err');
  } finally {
    els.btn.camera.disabled = phone?.state !== 'established';
    updateVideo();
  }
});

// ---------------------------------------------------------------- keyboard

document.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' && e.target !== els.target) return;
  if (e.target === els.target && e.key !== 'Escape' && e.key !== 'Enter') return;
  if (/^[0-9*#]$/.test(e.key)) { press(e.key); e.preventDefault(); return; }
  if (e.key === 'Enter' && els.btn.call.dataset.mode === 'call') { placeCall(); e.preventDefault(); }
  if (e.key === 'Escape' && els.btn.call.dataset.mode === 'hangup') { hangUp(); e.preventDefault(); return; }
  if (e.key === 'Escape' && !els.details.hidden) { showDetails(false); e.preventDefault(); }
  if (e.key === 'Backspace' && e.target.tagName !== 'INPUT') { els.btn.back.click(); e.preventDefault(); }
});

setState('idle');
resetFacts();
