/* Generic guided-flow player. Renders and runs any flow from window.FLOWS.
   Mock mode (default) simulates responses so it works with no backend; Live
   mode fetches the real service (needs the deployed WAR + CORS). */
(function () {
  var qs = new URLSearchParams(location.search);
  var flowId = qs.get('flow') || '3pcc';
  var mode = (qs.get('mode') === 'live') ? 'live' : 'mock';
  var explorerBase = qs.get('explorer') || '/blade/api/';
  var flow = (window.FLOWS || {})[flowId];

  var ctx = {};   // inputs + captured variables
  var active = 0; // index of the next runnable step
  var running = false;
  var state = []; // per-step: 'idle' | 'active' | 'done' | 'failed'

  var $ = function (id) { return document.getElementById(id); };
  function hex(n) { var s = ''; for (var i = 0; i < n; i++) s += Math.floor(Math.random() * 16).toString(16); return s.toUpperCase(); }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function interp(s) { return String(s).replace(/\$\{(\w+)\}/g, function (m, k) { return (ctx[k] != null) ? ctx[k] : m; }); }
  function interpDeep(o) {
    if (o == null) return o;
    if (typeof o === 'string') return interp(o);
    if (Array.isArray(o)) return o.map(interpDeep);
    if (typeof o === 'object') { var r = {}; for (var k in o) r[k] = interpDeep(o[k]); return r; }
    return o;
  }

  function collectInputs() {
    (flow.inputs || []).forEach(function (inp) {
      var el = $('in_' + inp.id);
      if (el) ctx[inp.id] = el.value || el.placeholder || '';
    });
  }

  function mockResponse(step) {
    switch (step.op) {
      case 'createSession': return { status: 200, body: { sessionId: hex(8) } };
      case 'createDialog': return { status: 200, body: { sessionId: ctx.sessionId, dialogId: hex(4), status: 200, reasonPhrase: 'OK' } };
      case 'connectDialogs': return { status: 200, body: { status: 200, reasonPhrase: 'OK' } };
      case 'deleteDialog': return { status: 200, body: { status: 200, reasonPhrase: 'OK' } };
      case 'listDialogs': return { status: 200, body: { dialogs: {} } };
      default: return { status: 200, body: {} };
    }
  }

  // ---- call-state canvas (twoLegBridge) ----
  function setPhone(which, st) { var p = $('phone_' + which); if (p) { p.dataset.st = st; p.querySelector('.st').textContent = st; } }
  function setBridge(on) { var b = $('bridge'); if (b) { b.dataset.on = on ? 'true' : 'false'; b.querySelector('.lbl').textContent = on ? 'bridged' : 'not bridged'; } }
  function applyCanvas(patch) {
    if (!patch) return;
    if (patch.a) setPhone('a', patch.a);
    if (patch.b) setPhone('b', patch.b);
    if (patch.bridge) setBridge(patch.bridge === 'on');
  }

  function logExchange(step, method, url, body, res) {
    var log = $('log');
    var ok = res.status >= 200 && res.status < 300;
    var reqLine = '<span class="code">' + method + '</span> ' + esc(url) + (body ? '\n' + esc(JSON.stringify(body)) : '');
    var resLine = '<span class="code ' + (ok ? 'ok' : 'bad') + '">' + (res.status || 'ERR') + '</span> ' +
      esc(typeof res.body === 'object' ? JSON.stringify(res.body, null, 2) : String(res.body));
    var div = document.createElement('div');
    div.className = 'xchg';
    div.innerHTML = '<div class="req">' + reqLine + '</div><div class="res">' + resLine + '</div>';
    log.appendChild(div);
    log.scrollTop = log.scrollHeight;
  }

  async function runStep(i) {
    if (running) return;
    running = true;
    collectInputs();
    var step = flow.steps[i];
    var url = flow.basePath + interp(step.path);
    var body = step.body ? interpDeep(step.body) : null;
    state[i] = 'active'; renderSteps();

    if (step.op === 'createDialog') { setPhone(step.canvas.a ? 'a' : 'b', 'ringing'); }

    var res;
    if (mode === 'live') {
      try {
        var r = await fetch(url, {
          method: step.method,
          headers: body ? { 'Content-Type': 'application/json' } : {},
          body: body ? JSON.stringify(body) : undefined
        });
        var txt = await r.text(); var parsed; try { parsed = JSON.parse(txt); } catch (e) { parsed = txt; }
        res = { status: r.status, body: parsed };
      } catch (e) {
        res = { status: 0, body: { error: String(e), hint: 'Live mode needs the deployed tpcc WAR and CORS (-Dblade.cors.allowedOrigins).' } };
      }
    } else {
      await sleep(step.op === 'createDialog' ? 650 : 350);
      res = mockResponse(step);
    }

    if (step.capture && res.body && typeof res.body === 'object') {
      for (var k in step.capture) { var f = step.capture[k]; if (res.body[f] != null) ctx[k] = res.body[f]; }
    }
    applyCanvas(step.canvas);
    logExchange(step, step.method, url, body, res);

    var ok = res.status >= 200 && res.status < 300;
    state[i] = ok ? 'done' : 'failed';
    if (ok && i >= active) active = i + 1;
    running = false;
    renderSteps(); renderCtx(); updateRunAll();
  }

  async function runAll() {
    if (running) return;
    while (active < flow.steps.length) {
      var i = active;
      await runStep(i);
      if (state[i] === 'failed') break;
    }
  }

  // ---- rendering ----
  function renderSteps() {
    var ol = $('steps');
    ol.innerHTML = '';
    flow.steps.forEach(function (step, i) {
      var st = state[i] || 'idle';
      var li = document.createElement('li');
      li.className = 'step';
      li.dataset.state = st;
      var runnable = !running && i <= active;
      var verb = '<b>' + step.method + '</b> ' + esc(flow.basePath + interp(step.path));
      li.innerHTML =
        '<div class="top"><span class="num">' + (st === 'done' ? '\u2713' : (i + 1)) + '</span>' +
        '<span class="title">' + esc(step.title) + '</span>' +
        (step.optional ? '<span class="opt">optional</span>' : '') + '</div>' +
        '<div class="narr">' + step.narration + '</div>' +
        '<div class="verb">' + verb + '</div>' +
        '<div class="actions">' +
        '<button class="run" data-i="' + i + '"' + (runnable ? '' : ' disabled') + '>' + (st === 'done' ? 'Run again' : 'Run') + '</button>' +
        '<a class="explore" href="' + esc(explorerBase + '?app=' + flow.explorerApp) + '" target="_blank" rel="noopener">API Explorer</a>' +
        '</div>';
      ol.appendChild(li);
    });
    ol.querySelectorAll('.run').forEach(function (b) {
      b.addEventListener('click', function () { runStep(parseInt(b.dataset.i, 10)); });
    });
  }

  function renderCtx() {
    var box = $('ctx');
    var keys = Object.keys(ctx).filter(function (k) { return !(flow.inputs || []).some(function (inp) { return inp.id === k; }); });
    box.innerHTML = keys.length ? keys.map(function (k) {
      return '<div><span class="k">' + esc(k) + '</span><span class="v">' + esc(ctx[k]) + '</span></div>';
    }).join('') : '<div><span class="k">captured ids</span><span class="v">&mdash;</span></div>';
  }

  function updateRunAll() {
    var b = $('runall');
    if (active >= flow.steps.length) { b.disabled = true; b.textContent = 'Flow complete \u2713'; }
    else { b.disabled = running; b.textContent = active === 0 ? 'Run the whole flow' : 'Continue (step ' + (active + 1) + ')'; }
  }

  function renderMode() {
    document.querySelectorAll('.seg button').forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.mode === mode)); });
    $('modenote').textContent = mode === 'live'
      ? 'Live: real requests to ' + flow.basePath + ' (needs the deployed WAR + CORS).'
      : 'Mock: simulated responses - no backend, safe to click through.';
  }

  function boot() {
    if (!flow) { document.querySelector('.gwrap').innerHTML = '<p style="color:var(--muted)">Unknown flow: ' + esc(flowId) + '</p>'; return; }
    $('gtitle').textContent = flow.title;
    $('gblurb').textContent = flow.blurb || '';
    $('ginputs').innerHTML = (flow.inputs || []).map(function (inp) {
      return '<div class="field"><label for="in_' + inp.id + '">' + esc(inp.label) + '</label>' +
        '<input id="in_' + inp.id + '" type="text" placeholder="' + esc(inp.placeholder || '') + '"></div>';
    }).join('');
    document.querySelectorAll('.seg button').forEach(function (b) {
      b.addEventListener('click', function () { mode = b.dataset.mode; renderMode(); renderSteps(); });
    });
    $('runall').addEventListener('click', runAll);
    renderMode(); renderSteps(); renderCtx(); updateRunAll();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
