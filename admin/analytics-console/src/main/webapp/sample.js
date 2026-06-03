/* Analytics sample-data page — uses brand.css tokens; mirrors audit.js idiom. */

(function () {
  'use strict';

  var API = 'api';

  // Fields sent to the server as numbers; everything else as strings.
  var NUMERIC = {
    callCount: 1, servers: 1, maxTransfers: 1, minDurationSec: 1, maxDurationSec: 1,
    transferProbability: 1, abandonProbability: 1, transferFailProbability: 1
  };

  // Result keys → display labels, in render order.
  var COUNT_LABELS = [
    ['sessions', 'Sessions (calls)'],
    ['events', 'Events'],
    ['attributes', 'Attributes'],
    ['sessionKeys', 'Session keys'],
    ['applications', 'Applications'],
    ['eventTypes', 'Event types'],
    ['attributeNames', 'Attribute names'],
    ['elapsedMs', 'Elapsed (ms)']
  ];

  // ---- helpers ---------------------------------------------------------
  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function collect() {
    var form = document.getElementById('gen-form');
    var body = {};
    for (var i = 0; i < form.elements.length; i++) {
      var e = form.elements[i];
      if (!e.name) continue;
      var v = (e.value || '').trim();
      if (v === '') continue;            // omit blanks → server defaults apply
      body[e.name] = NUMERIC[e.name] ? Number(v) : v;
    }
    return body;
  }

  // ---- generate --------------------------------------------------------
  function generate(evt) {
    evt.preventDefault();
    var btn = document.getElementById('generate-btn');
    btn.disabled = true;
    setOverall('Generating…');
    setStatus('Generating…');
    document.getElementById('result-area').hidden = true;
    hideError();

    fetch(API + '/sample/generate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(collect())
    })
      .then(function (r) { return r.json().catch(function () { return { error: 'HTTP ' + r.status }; }); })
      .then(function (data) {
        if (!data || data.error) throw new Error(data && data.error ? data.error : 'unknown error');
        renderResult(data.counts || {});
        var ms = (data.counts && data.counts.elapsedMs) != null ? data.counts.elapsedMs : '?';
        setStatus('Done in ' + ms + ' ms.');
        setOverall('Done');
      })
      .catch(function (e) {
        showError(e.message);
        setStatus('');
        setOverall('Error');
      })
      .finally(function () { btn.disabled = false; });
  }

  function renderResult(counts) {
    var box = document.getElementById('result-counts');
    box.innerHTML = '';
    COUNT_LABELS.forEach(function (pair) {
      var key = pair[0];
      if (counts[key] == null) return;
      var card = el('div', 'sample-stat');
      card.appendChild(el('div', 'sample-stat-n', Number(counts[key]).toLocaleString()));
      card.appendChild(el('div', 'sample-stat-k', pair[1]));
      box.appendChild(card);
    });
    document.getElementById('result-area').hidden = false;
  }

  // ---- small DOM utils -------------------------------------------------
  function setStatus(txt) {
    var n = document.getElementById('status');
    if (n) n.textContent = txt || '';
  }
  function setOverall(txt) {
    var n = document.getElementById('overall-pill');
    if (n) n.textContent = txt;
  }
  function showError(msg) {
    var box = document.getElementById('error');
    if (box) { box.hidden = false; box.textContent = 'Error: ' + msg; }
  }
  function hideError() {
    var box = document.getElementById('error');
    if (box) { box.hidden = true; box.textContent = ''; }
  }

  function prefillDates() {
    var iso = function (d) { return d.toISOString().slice(0, 10); };
    var now = new Date();
    var weekAgo = new Date(now.getTime() - 7 * 86400000);
    var s = document.getElementById('startDate');
    var e = document.getElementById('endDate');
    if (s && !s.value) s.value = iso(weekAgo);
    if (e && !e.value) e.value = iso(now);
  }

  // ---- wire up ---------------------------------------------------------
  document.getElementById('gen-form').addEventListener('submit', generate);
  document.addEventListener('DOMContentLoaded', prefillDates);
  prefillDates();
})();
