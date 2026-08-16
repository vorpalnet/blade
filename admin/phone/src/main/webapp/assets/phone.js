/*
 * BLADE WebRTC Phone -- UI.
 *
 * Wires the BladePhone client to the handset. Three things here exist specifically
 * because this gets demonstrated in front of people:
 *
 *   - Live audio meters on both the microphone and the received stream. "The call
 *     connected" is a claim; a moving remote meter is evidence, and it distinguishes
 *     a signalling success from an actual media path in one glance.
 *   - Real DTMF tones, synthesised locally, so pressing a key sounds like a phone.
 *   - A ringback/ring tone, so an incoming call announces itself without an asset.
 *
 * All of it is Web Audio -- no files to ship, nothing to 404.
 */

import { BladePhone } from './blade-webrtc.js';

const $ = (id) => document.getElementById(id);

const els = {
  pill: $('pill'), display: $('display'), state: $('state-label'), party: $('party'),
  timer: $('timer'), incoming: $('incoming'), incomingFrom: $('incoming-from'),
  target: $('target'), keypad: $('keypad'), log: $('log'), remote: $('remote'),
  gateway: $('gateway'), aor: $('aor'), stun: $('stun'), authState: $('auth-state'),
  micState: $('mic-state'), rxState: $('rx-state'),
  factPath: $('fact-path'), factIce: $('fact-ice'), factPair: $('fact-pair'), factCodec: $('fact-codec'),
  btn: {
    call: $('btn-call'), hangup: $('btn-hangup'), mute: $('btn-mute'),
    answer: $('btn-answer'), decline: $('btn-decline'), back: $('btn-back'),
    register: $('btn-register'), unregister: $('btn-unregister'),
  },
};

let phone = null;
let muted = false;
let callStartedAt = 0;
let timerHandle = null;

// ---------------------------------------------------------------- gateway default

// The phone is served from the ADMIN tier but the gateway lives on the ENGINE tier,
// so this page's own origin is the wrong default. Guess the engine's HTTP port on the
// same host, which is right for a single-box lab and obviously editable otherwise.
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
 * page -- so a browser can never claim an address the server did not issue it. Whether you may ASK
 * for a particular address is a separate question, and a deployment setting: on by default, because
 * a browser-to-browser call needs two addresses and most deployments have one operator account.
 *
 * The token is fetched per registration rather than held: it lives about a minute, so caching one
 * across a page that might sit open all afternoon would only produce expired-token failures.
 */

/** Ask the server who we are and what it will let us do. Fills the form in before anything is clicked. */
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
      // Non-JSON body: a container-generated error page, most likely the login
      // form after the session expired. The status is all we can honestly report.
      if (response.status === 401 || response.status === 403) {
        detail = 'your session has expired — reload the page to sign in again';
      }
    }
    throw new Error(detail);
  }
  return response.json();
}

/*
 * Prefill from the server's answer. The address arrives as this user's default; it stays editable
 * when the deployment allows a chosen one, and goes read-only when it does not -- so the field's
 * own behaviour tells you which deployment you are on, before you press anything.
 */
loadSession().then((s) => {
  els.aor.value = s.aor;
  if (s.stunServer) els.stun.value = s.stunServer;

  if (s.gateway) {
    els.gateway.value = s.gateway;
  } else {
    // Say so. The guess is this page's own hostname with the engine's default port, which is
    // right only when the engine listens on the address you happen to be browsing to. An engine
    // bound to a specific interface -- the normal case -- rejects it as "could not connect",
    // and there is nothing on screen to suggest the address was invented rather than configured.
    log(`no gateway configured; guessing ${els.gateway.value}. `
      + 'Set "gateway" in blade-phone.json if the engine listens elsewhere.', 'err');
  }

  if (s.allowChosenAddress) {
    els.aor.readOnly = false;
    els.aor.removeAttribute('aria-readonly');
    els.aor.placeholder = 'user@host';
    els.authState.textContent = `Signed in as ${s.user}. Not registered.`;
  } else {
    els.aor.readOnly = true;
    els.aor.setAttribute('aria-readonly', 'true');
    els.authState.textContent =
      `Signed in as ${s.user}. This deployment issues one address per user. Not registered.`;
  }
}).catch((e) => {
  log(`could not read this session: ${e.message}`, 'err');
});

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
  els.log.scrollTop = els.log.scrollHeight;
}

// ---------------------------------------------------------------- audio

let audioCtx = null;
const ctx = () => (audioCtx ||= new (window.AudioContext || window.webkitAudioContext)());

/** DTMF is a pair of tones; using the real frequencies costs nothing and sounds right. */
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

const SEGMENTS = 14;

function buildMeter(el) {
  el.replaceChildren(...Array.from({ length: SEGMENTS }, () => {
    const d = document.createElement('div');
    d.className = 'phone-meter-seg';
    return d;
  }));
}
buildMeter($('meter-mic'));
buildMeter($('meter-rx'));

function paintMeter(el, level) {
  const lit = Math.round(level * SEGMENTS);
  el.childNodes.forEach((seg, i) => {
    seg.dataset.on = String(i < lit);
    seg.dataset.peak = String(i === lit - 1 && lit > 0);
  });
}

/** Attach an analyser to a stream and drive one meter until the stream ends. */
function meterStream(stream, el, label) {
  if (!stream) return;
  const c = ctx();
  if (c.state === 'suspended') c.resume();
  const src = c.createMediaStreamSource(stream);
  const analyser = c.createAnalyser();
  analyser.fftSize = 512;
  analyser.smoothingTimeConstant = 0.75;
  src.connect(analyser);
  const buf = new Uint8Array(analyser.frequencyBinCount);

  let stopped = false;
  const stop = () => { stopped = true; paintMeter(el, 0); label.textContent = 'idle'; };
  stream.getAudioTracks().forEach((t) => t.addEventListener('ended', stop));

  (function frame() {
    if (stopped) return;
    analyser.getByteFrequencyData(buf);
    // RMS over the band, scaled so ordinary speech fills most of the meter.
    let sum = 0;
    for (const v of buf) sum += v * v;
    const rms = Math.sqrt(sum / buf.length) / 255;
    paintMeter(el, Math.min(1, rms * 2.6));
    label.textContent = rms > 0.02 ? 'active' : 'silent';
    requestAnimationFrame(frame);
  })();

  return stop;
}

// ---------------------------------------------------------------- state machine

const LIVE = new Set(['ringing', 'ringing-remote', 'progress', 'established', 'calling', 'answering']);

const LABEL = {
  idle: 'Not registered', registered: 'Ready', calling: 'Calling',
  progress: 'Connecting', 'ringing-remote': 'Ringing', ringing: 'Incoming call',
  answering: 'Answering', established: 'On call',
};

function setState(state) {
  els.state.textContent = LABEL[state] || state;
  els.display.dataset.live = String(LIVE.has(state));
  els.pill.textContent = state === 'idle' ? 'Offline' : (LABEL[state] || state);

  const registered = state !== 'idle';
  const onCall = LIVE.has(state);
  els.btn.call.disabled = state !== 'registered';
  els.btn.hangup.disabled = !onCall;
  els.btn.mute.disabled = state !== 'established';
  els.btn.register.disabled = registered;
  els.btn.unregister.disabled = !registered;

  const incoming = state === 'ringing';
  els.incoming.hidden = !incoming;
  if (incoming) startRinging(); else stopRinging();

  if (state === 'established' && !callStartedAt) startTimer();
  if (!onCall) stopTimer();
}

/*
 * Report whether the gateway verified this browser.
 *
 * Spelled out in words rather than signalled by colour: "the gateway is not checking anybody" is
 * exactly the kind of fact that must not depend on noticing a shade.
 */
function setAuthPill(verified, user) {
  els.authState.dataset.verified = String(!!verified);
  const address = phone?.aor ?? els.aor.value;
  // Name the account as well as the address: with a chosen address the two differ, and "who
  // registered this" is the question a log or a puzzled colleague will actually ask.
  const who = user && user !== address ? `${address} (signed in as ${user})` : address;
  els.authState.textContent = verified
    ? `Registered as ${who} — verified by the gateway`
    : `Registered as ${address}. The gateway is not verifying browsers: any client that can reach it may claim any address.`;
}

function clearAuthPill() {
  els.authState.dataset.verified = 'unknown';
  els.authState.textContent = 'Not registered.';
}

function startTimer() {
  callStartedAt = Date.now();
  timerHandle = setInterval(() => {
    const s = Math.floor((Date.now() - callStartedAt) / 1000);
    els.timer.textContent =
      `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
  }, 500);
}

function stopTimer() {
  if (timerHandle) { clearInterval(timerHandle); timerHandle = null; }
  callStartedAt = 0;
  els.timer.innerHTML = '&nbsp;';
}

function setParty(text) {
  els.party.textContent = text || 'No call';
  els.party.classList.toggle('phone-party-empty', !text);
}

function resetFacts() {
  els.factPath.textContent = '—';
  els.factPath.dataset.strong = 'false';
  els.factIce.textContent = '—';
  els.factIce.dataset.strong = 'false';
  els.factPair.textContent = '—';
  els.factCodec.textContent = '—';
}

// ---------------------------------------------------------------- live stats

/**
 * Poll getStats for the facts that actually matter in a demo: whether media is
 * peer-to-peer or relayed through the media server, and which codec won. Reading it
 * from the browser rather than from our own signalling means it cannot be wrong.
 */
async function pollStats() {
  if (!phone?.pc) return;
  try {
    const stats = await phone.pc.getStats();
    let pair = null; const candidates = new Map(); let codec = null;
    stats.forEach((r) => {
      if (r.type === 'candidate-pair' && r.state === 'succeeded' && r.nominated) pair = r;
      if (r.type === 'local-candidate' || r.type === 'remote-candidate') candidates.set(r.id, r);
      if (r.type === 'codec' && r.mimeType?.startsWith('audio/')) codec = r;
    });
    if (pair) {
      const l = candidates.get(pair.localCandidateId);
      const r = candidates.get(pair.remoteCandidateId);
      if (l && r) {
        els.factPair.textContent = `${l.candidateType} / ${r.candidateType}`;
        const relayed = l.candidateType === 'relay' || r.candidateType === 'relay';
        els.factIce.textContent = relayed ? 'relayed (TURN)' : 'direct';
        els.factIce.dataset.strong = String(!relayed);
      }
    }
    if (codec) els.factCodec.textContent = codec.mimeType.replace('audio/', '');
  } catch {
    // getStats is best-effort decoration; never let it break a call.
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

// ---------------------------------------------------------------- actions

els.btn.back.addEventListener('click', () => { els.target.value = els.target.value.slice(0, -1); });

els.btn.register.addEventListener('click', async () => {
  els.btn.register.disabled = true;

  let grant;
  try {
    log('requesting a token for this session', 'out');
    grant = await mintToken(els.aor.value.trim());
  } catch (e) {
    log(`could not obtain a token: ${e.message}`, 'err');
    els.btn.register.disabled = false;
    return;
  }

  // Show what was actually issued rather than what was asked for -- they differ when the
  // deployment declined the request, and the token is what the gateway will act on.
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
    // An unauthenticated gateway is a deployment mistake, not a mode to be quiet about: say it
    // where the operator is already looking rather than only in a server log.
    if (d && d.authenticated === false) {
      log('this gateway is NOT authenticating browsers — anyone who can reach it may claim any address', 'err');
    }
    setAuthPill(d && d.authenticated !== false, grant.user);
  };
  phone.onIncoming = (c) => { setParty(c.from); els.incomingFrom.textContent = c.from; log(`incoming from ${c.from}`); };
  phone.onProgress = (d) => log(`progress: ${d.status ?? 'trying'}`);
  phone.onEstablished = () => {
    log('call answered');
    els.factPath.textContent = phone.trickle ? 'peer-to-peer' : 'anchored';
    els.factPath.dataset.strong = String(!!phone.trickle);
    meterStream(phone.localStream, $('meter-mic'), els.micState);
  };
  phone.onConnected = (d) => {
    // The ACK, one message after the answer. Worth its own line in the log: when a call is up and
    // silent, knowing whether media was ever negotiated is the first thing you want to see.
    log(d.negotiated ? 'call connected' : 'call connected WITHOUT media negotiation',
        d.negotiated ? 'in' : 'err');
  };
  phone.onRenegotiated = () => {
    log('renegotiated — media server now in the path');
    els.factPath.textContent = 'anchored';
    els.factPath.dataset.strong = 'false';
  };
  phone.onEnded = (d) => {
    log(`call ended: ${d.reason ?? 'normal'}`);
    setParty(null); resetFacts();
    paintMeter($('meter-mic'), 0); paintMeter($('meter-rx'), 0);
    els.micState.textContent = els.rxState.textContent = 'idle';
  };
  phone.onError = (e) => log(String(e), 'err');
  phone.onRemoteStream = (stream) => {
    els.remote.srcObject = stream;
    meterStream(stream, $('meter-rx'), els.rxState);
  };

  try {
    log(`connecting to ${phone.url}`, 'out');
    await phone.connect();
  } catch (e) {
    log(`could not register: ${e.message}`, 'err');
    clearAuthPill();
    setState('idle');
  }
});

els.btn.unregister.addEventListener('click', () => {
  phone?.disconnect();
  clearAuthPill();
  log('disconnected', 'out');
});

els.btn.call.addEventListener('click', async () => {
  const target = els.target.value.trim();
  if (!target) return log('enter a number or address first', 'err');
  setParty(target);
  log(`calling ${target}`, 'out');
  try { await phone.call(target); } catch (e) { log(`call failed: ${e.message}`, 'err'); }
});

els.btn.answer.addEventListener('click', async () => {
  stopRinging();
  log('answering', 'out');
  try { await phone.answer(); } catch (e) { log(`answer failed: ${e.message}`, 'err'); }
});

els.btn.decline.addEventListener('click', () => { stopRinging(); phone?.hangup(); log('declined', 'out'); });
els.btn.hangup.addEventListener('click', () => { phone?.hangup(); log('hung up', 'out'); setParty(null); });

els.btn.mute.addEventListener('click', () => {
  muted = !muted;
  phone.setMuted(muted);
  els.btn.mute.setAttribute('aria-pressed', String(muted));
  els.btn.mute.textContent = muted ? 'Unmute' : 'Mute';
  log(muted ? 'microphone muted' : 'microphone live', 'out');
});

// ---------------------------------------------------------------- keyboard

document.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' && e.key !== 'Escape' && e.key !== 'Enter') return;
  if (/^[0-9*#]$/.test(e.key)) { press(e.key); e.preventDefault(); return; }
  if (e.key === 'Enter' && !els.btn.call.disabled) { els.btn.call.click(); e.preventDefault(); }
  if (e.key === 'Escape' && !els.btn.hangup.disabled) { els.btn.hangup.click(); e.preventDefault(); }
  if (e.key === 'Backspace' && e.target.tagName !== 'INPUT') { els.btn.back.click(); e.preventDefault(); }
});

setState('idle');
resetFacts();
