// FSMAR Flow Editor — Route Simulator, call replay, and live heat overlay
//
// One window, three tabs, one trace format:
//
//   Simulate — runs a synthetic request through the diagram BEING EDITED
//              (exported through the same servlet as the JSON export, then
//              POSTed to /fsmarSimulate) and animates the routing path hop
//              by hop on the canvas.
//   Replay   — arms the engines' trace capture over JMX (/fsmarMetrics) and
//              replays real production calls through the same animation.
//   Live     — polls per-transition hit counters and overlays them on the
//              diagram edges (count label + stroke width scaled by traffic
//              share — width and text carry the meaning; color is only a
//              supplement).
//
// Highlights are mxCellHighlight instances drawn OVER the diagram — nothing
// touches cell styles, the model, or the undo history.

window.flowSimulate = (function() {

	var SIP_METHODS = ['INVITE', 'REGISTER', 'SUBSCRIBE', 'OPTIONS', 'MESSAGE',
			'PUBLISH', 'REFER', 'NOTIFY'];
	var DAYS = ['', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY',
			'SATURDAY', 'SUNDAY'];

	// Path/heat hue — meaning is carried by stroke width, dash and text.
	var PATH_COLOR = '#2c6e9e';
	var FINDING_COLOR = '#a05a00';

	var editorRef = null;
	var wnd = null;

	var trace = null;       // trace being viewed (simulated or replayed)
	var step = -1;          // current hop index
	var playTimer = null;

	var highlights = [];    // animation + findings highlights
	var liveHighlights = [];
	var liveCounts = null;  // 'state/METHOD/id' -> count, for edge labels
	var liveTimer = null;

	// ---------------------------------------------------------------- utils

	function esc(s) {
		return String(s == null ? '' : s)
				.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
				.replace(/"/g, '&quot;');
	}

	function graph() {
		return editorRef.graph;
	}

	// State name a vertex represents: Ingress/Egress clouds are "null".
	function vertexStateName(v) {
		if (!v || !v.value || !v.value.tagName) return null;
		var tag = v.value.tagName;
		if (tag === 'Ingress' || tag === 'Egress') return 'null';
		if (tag === 'State') return v.getAttribute('label') || '';
		return null;
	}

	// All vertices representing a state. For "null", Ingress clouds (entry
	// side) are preferred; Egress only if no Ingress exists.
	function findStateCells(stateName) {
		var model = graph().getModel();
		var ingress = [], states = [], egress = [];
		for (var id in model.cells) {
			var c = model.cells[id];
			if (!c || !c.vertex || !c.value || !c.value.tagName) continue;
			var tag = c.value.tagName;
			if (stateName === 'null') {
				if (tag === 'Ingress') ingress.push(c);
				else if (tag === 'Egress') egress.push(c);
			} else if (tag === 'State' && (c.getAttribute('label') || '') === stateName) {
				states.push(c);
			}
		}
		if (stateName === 'null') return ingress.length > 0 ? ingress : egress;
		return states;
	}

	// The Transition edge for a hop: source state + SIP method, narrowed by
	// txId when the trace carries one.
	function findEdge(stateName, method, txId) {
		var model = graph().getModel();
		var fallback = null;
		for (var id in model.cells) {
			var c = model.cells[id];
			if (!c || !c.edge || !c.value || c.value.tagName !== 'Transition') continue;
			if ((c.getAttribute('label') || '') !== method) continue;
			if (vertexStateName(c.source) !== stateName) continue;
			if (txId && (c.getAttribute('txId') || '') === txId) return c;
			if (fallback == null) fallback = c;
		}
		return txId ? null : fallback;
	}

	function addHighlight(list, cell, strokeWidth, dashed, color) {
		if (!cell) return;
		var state = graph().view.getState(cell);
		if (!state) return;
		var hl = new mxCellHighlight(graph(), color || PATH_COLOR, strokeWidth, !!dashed);
		hl.highlight(state);
		list.push(hl);
	}

	function clearList(list) {
		while (list.length > 0) {
			try { list.pop().destroy(); } catch (e) { /* already gone */ }
		}
	}

	function post(url, params, onJson, onError) {
		var req = new mxXmlRequest(url, params);
		req.send(function(resp) {
			if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
				try {
					onJson(JSON.parse(resp.getText()));
				} catch (e) {
					(onError || mxUtils.alert)('Bad response from ' + url + ': ' + e.message);
				}
			} else {
				(onError || mxUtils.alert)(url + ' failed: ' + resp.getStatus() + ' ' + resp.getText());
			}
		});
	}

	// ---------------------------------------------------------------- window

	function open(editor) {
		editorRef = editor;
		if (wnd != null) {
			refreshUndeployedList();
			wnd.setVisible(true);
			return;
		}

		var div = document.createElement('div');
		div.style.cssText = 'font-family:Arial; font-size:12px; padding:8px; ' +
				'overflow-y:auto; height:100%; box-sizing:border-box;';
		div.innerHTML =
			tabBarHtml() +
			'<div id="simtab-simulate">' + simulateTabHtml() + '</div>' +
			'<div id="simtab-replay" style="display:none;">' + replayTabHtml() + '</div>' +
			'<div id="simtab-live" style="display:none;">' + liveTabHtml() + '</div>' +
			'<div id="sim-result" style="margin-top:8px;"></div>';

		var w = document.body.clientWidth;
		wnd = new mxWindow('Route Simulator', div, Math.max(20, w - 800), 80, 440, 620, true, true);
		wnd.setClosable(true);
		wnd.destroyOnClose = false;
		// Hiding/closing the window clears the animation; a running live
		// overlay deliberately survives (watch traffic with the window shut).
		var onHide = function() {
			stopPlay();
			clearList(highlights);
		};
		wnd.addListener(mxEvent.HIDE, onHide);
		wnd.addListener(mxEvent.CLOSE, onHide);

		wireTabs(div);
		wireSimulateTab(div);
		wireReplayTab(div);
		wireLiveTab(div);
		refreshUndeployedList();

		wnd.setVisible(true);
	}

	function tabBarHtml() {
		function tab(id, label) {
			return '<button class="sim-tab" data-tab="' + id + '" style="padding:4px 10px; ' +
					'border:1px solid #999; background:#eee; cursor:pointer;">' + label + '</button>';
		}
		return '<div style="margin-bottom:8px;">' +
				tab('simulate', 'Simulate') + tab('replay', 'Replay') + tab('live', 'Live') +
				'</div>';
	}

	function wireTabs(div) {
		var tabs = div.querySelectorAll('.sim-tab');
		function select(name) {
			['simulate', 'replay', 'live'].forEach(function(t) {
				div.querySelector('#simtab-' + t).style.display = (t === name) ? 'block' : 'none';
			});
			for (var i = 0; i < tabs.length; i++) {
				var active = tabs[i].getAttribute('data-tab') === name;
				tabs[i].style.background = active ? '#fff' : '#eee';
				tabs[i].style.fontWeight = active ? 'bold' : 'normal';
			}
		}
		for (var i = 0; i < tabs.length; i++) {
			(function(btn) {
				btn.onclick = function() { select(btn.getAttribute('data-tab')); };
			})(tabs[i]);
		}
		select('simulate');
	}

	// ------------------------------------------------------------- Simulate

	function simulateTabHtml() {
		var methods = SIP_METHODS.map(function(m) { return '<option>' + m + '</option>'; }).join('');
		var days = DAYS.map(function(d) { return '<option>' + d + '</option>'; }).join('');
		return '' +
			'<div style="display:flex; gap:6px;">' +
			'  <label style="flex:1;">Method<br/><select id="sim-method" style="width:100%;">' + methods + '</select></label>' +
			'  <label style="flex:2;">Request-URI<br/><input id="sim-uri" type="text" value="sip:bob@example.com" style="width:100%; box-sizing:border-box;"/></label>' +
			'</div>' +
			'<label style="display:block; margin-top:4px;">From<br/>' +
			'  <input id="sim-from" type="text" value="&lt;sip:alice@example.com&gt;;tag=1" style="width:100%; box-sizing:border-box;"/></label>' +
			'<label style="display:block; margin-top:4px;">To<br/>' +
			'  <input id="sim-to" type="text" value="&lt;sip:bob@example.com&gt;" style="width:100%; box-sizing:border-box;"/></label>' +
			'<label style="display:block; margin-top:4px;">Call-ID<br/>' +
			'  <input id="sim-callid" type="text" value="sim-1@flow" style="width:100%; box-sizing:border-box;"/></label>' +
			'<div id="sim-headers"></div>' +
			'<div style="margin-top:4px;">' +
			'  <a href="#" id="sim-add-header" style="font-size:11px;">+ header</a> &nbsp; ' +
			'  <a href="#" id="sim-toggle-raw" style="font-size:11px;">paste SIP message&hellip;</a></div>' +
			'<div id="sim-raw" style="display:none; margin-top:4px;">' +
			'  <textarea id="sim-raw-text" placeholder="INVITE sip:bob@example.com SIP/2.0&#10;From: &lt;sip:alice@example.com&gt;;tag=1&#10;To: &lt;sip:bob@example.com&gt;&#10;&hellip;" ' +
			'   style="width:100%; height:90px; font-family:monospace; font-size:10px; box-sizing:border-box;"></textarea>' +
			'  <button id="sim-parse-raw" style="font-size:11px;">Fill form from message</button></div>' +
			'<fieldset style="margin-top:6px; border:1px solid #ccc;">' +
			'  <legend style="font-size:11px;">Pseudo-variable overrides (blank = live values)</legend>' +
			'  <div style="display:flex; gap:6px;">' +
			'    <label style="flex:1;">${hour}<br/><input id="sim-hour" type="text" style="width:100%; box-sizing:border-box;" placeholder="0-23"/></label>' +
			'    <label style="flex:2;">${dayOfWeek}<br/><select id="sim-day" style="width:100%;">' + days + '</select></label>' +
			'    <label style="flex:1;">${hash100}<br/><input id="sim-hash" type="text" style="width:100%; box-sizing:border-box;" placeholder="0-99"/></label>' +
			'  </div></fieldset>' +
			'<fieldset style="margin-top:6px; border:1px solid #ccc;">' +
			'  <legend style="font-size:11px;">Treat as undeployed (test bypass behavior) ' +
			'   <a href="#" id="sim-refresh-apps" style="font-size:10px;">refresh</a></legend>' +
			'  <div id="sim-undeployed" style="max-height:80px; overflow-y:auto;"></div></fieldset>' +
			'<div style="margin-top:8px;">' +
			'  <button id="sim-run" style="font-weight:bold;">&#9654; Run simulation</button> ' +
			'  <button id="sim-validate">Validate diagram</button> ' +
			'  <button id="sim-clear">Clear</button></div>';
	}

	function headerRowHtml() {
		return '<div class="sim-header-row" style="display:flex; gap:4px; margin-top:4px;">' +
			'<input class="sim-h-name" type="text" placeholder="Header" style="flex:1;"/>' +
			'<input class="sim-h-value" type="text" placeholder="value" style="flex:2;"/>' +
			'<a href="#" class="sim-h-del" style="align-self:center; font-size:11px;">&#10005;</a></div>';
	}

	function wireSimulateTab(div) {
		div.querySelector('#sim-add-header').onclick = function(e) {
			e.preventDefault();
			var d = document.createElement('div');
			d.innerHTML = headerRowHtml();
			var row = d.firstChild;
			row.querySelector('.sim-h-del').onclick = function(e2) {
				e2.preventDefault();
				row.parentNode.removeChild(row);
			};
			div.querySelector('#sim-headers').appendChild(row);
		};
		div.querySelector('#sim-toggle-raw').onclick = function(e) {
			e.preventDefault();
			var raw = div.querySelector('#sim-raw');
			raw.style.display = (raw.style.display === 'none') ? 'block' : 'none';
		};
		div.querySelector('#sim-parse-raw').onclick = function() {
			parseRawSip(div, div.querySelector('#sim-raw-text').value);
		};
		div.querySelector('#sim-refresh-apps').onclick = function(e) {
			e.preventDefault();
			refreshUndeployedList();
		};
		div.querySelector('#sim-run').onclick = function() { runSimulation(div); };
		div.querySelector('#sim-validate').onclick = function() { runValidation(div); };
		div.querySelector('#sim-clear').onclick = function() {
			stopPlay();
			clearList(highlights);
			trace = null;
			div.querySelector('#sim-result').innerHTML = '';
		};
	}

	// Every application named on the canvas (State labels) becomes an
	// undeployed-toggle checkbox.
	function refreshUndeployedList() {
		if (wnd == null || !editorRef) return;
		var container = wnd.div.querySelector('#sim-undeployed');
		if (!container) return;
		var prev = collectUndeployed();
		var model = graph().getModel();
		var names = {};
		for (var id in model.cells) {
			var c = model.cells[id];
			if (c && c.vertex && c.value && c.value.tagName === 'State') {
				var label = c.getAttribute('label') || '';
				if (label && label !== 'null') names[label] = true;
			}
		}
		var sorted = Object.keys(names).sort();
		if (sorted.length === 0) {
			container.innerHTML = '<span style="color:#888; font-style:italic; font-size:11px;">no states on canvas</span>';
			return;
		}
		container.innerHTML = sorted.map(function(n) {
			var checked = prev.indexOf(n) >= 0 ? ' checked' : '';
			return '<label style="display:block; font-size:11px;">' +
					'<input type="checkbox" class="sim-undep" value="' + esc(n) + '"' + checked + '/> ' +
					esc(n) + '</label>';
		}).join('');
	}

	function collectUndeployed() {
		if (wnd == null) return [];
		var out = [];
		var boxes = wnd.div.querySelectorAll('.sim-undep');
		for (var i = 0; i < boxes.length; i++) {
			if (boxes[i].checked) out.push(boxes[i].value);
		}
		return out;
	}

	// "INVITE sip:bob@x SIP/2.0" + header lines -> form fields. Unknown
	// headers become extra rows.
	function parseRawSip(div, text) {
		if (!text) return;
		var lines = text.split(/\r?\n/);
		var first = /^([A-Z]+)\s+(\S+)\s+SIP\/2\.0/.exec(lines[0] || '');
		var start = 0;
		if (first) {
			var sel = div.querySelector('#sim-method');
			for (var i = 0; i < sel.options.length; i++) {
				if (sel.options[i].value === first[1]) sel.selectedIndex = i;
			}
			div.querySelector('#sim-uri').value = first[2];
			start = 1;
		}
		// reset extra rows
		div.querySelector('#sim-headers').innerHTML = '';
		for (var j = start; j < lines.length; j++) {
			var line = lines[j];
			if (line.trim() === '') break; // body separator
			var m = /^([A-Za-z][A-Za-z0-9.!%*_+`'~-]*)\s*:\s*(.*)$/.exec(line);
			if (!m) continue;
			var name = m[1], value = m[2];
			var lower = name.toLowerCase();
			if (lower === 'from' || lower === 'f') div.querySelector('#sim-from').value = value;
			else if (lower === 'to' || lower === 't') div.querySelector('#sim-to').value = value;
			else if (lower === 'call-id' || lower === 'i') div.querySelector('#sim-callid').value = value;
			else {
				div.querySelector('#sim-add-header').click();
				var rows = div.querySelectorAll('.sim-header-row');
				var row = rows[rows.length - 1];
				row.querySelector('.sim-h-name').value = name;
				row.querySelector('.sim-h-value').value = value;
			}
		}
	}

	function buildSimRequest(div) {
		var headers = {};
		var from = div.querySelector('#sim-from').value.trim();
		var to = div.querySelector('#sim-to').value.trim();
		var callId = div.querySelector('#sim-callid').value.trim();
		if (from) headers['From'] = from;
		if (to) headers['To'] = to;
		if (callId) headers['Call-ID'] = callId;
		var rows = div.querySelectorAll('.sim-header-row');
		for (var i = 0; i < rows.length; i++) {
			var n = rows[i].querySelector('.sim-h-name').value.trim();
			var v = rows[i].querySelector('.sim-h-value').value.trim();
			if (n) headers[n] = v;
		}

		var pseudo = {};
		var hour = div.querySelector('#sim-hour').value.trim();
		var day = div.querySelector('#sim-day').value;
		var hash = div.querySelector('#sim-hash').value.trim();
		if (hour !== '') pseudo['hour'] = hour;
		if (day !== '') pseudo['dayOfWeek'] = day;
		if (hash !== '') pseudo['hash100'] = hash;

		return {
			request: {
				method: div.querySelector('#sim-method').value,
				requestUri: div.querySelector('#sim-uri').value.trim(),
				headers: headers
			},
			pseudo: pseudo,
			undeployed: collectUndeployed()
		};
	}

	function runSimulation(div) {
		var simReq = buildSimRequest(div);
		var result = div.querySelector('#sim-result');
		result.innerHTML = '<i>Exporting diagram&hellip;</i>';

		flowFsmar.getConfigJson(editorRef, function(configJson) {
			try {
				simReq.config = JSON.parse(configJson);
			} catch (e) {
				result.innerHTML = '<b>Export produced invalid JSON:</b> ' + esc(e.message);
				return;
			}
			result.innerHTML = '<i>Simulating&hellip;</i>';
			post('fsmarSimulate', 'json=' + encodeURIComponent(JSON.stringify(simReq)),
				function(t) {
					loadTrace(t);
				},
				function(msg) {
					result.innerHTML = '<b>' + esc(msg) + '</b>';
				});
		}, function(msg) {
			result.innerHTML = '<b>' + esc(msg) + '</b>';
		});
	}

	function runValidation(div) {
		var result = div.querySelector('#sim-result');
		result.innerHTML = '<i>Validating&hellip;</i>';
		stopPlay();
		clearList(highlights);

		flowFsmar.getConfigJson(editorRef, function(json) {
			post('fsmarValidate', 'json=' + encodeURIComponent(json), function(f) {
				var rows = [];
				var statesToFlag = {};
				function collect(list, label) {
					(list || []).forEach(function(m) {
						rows.push('<div><b>' + label + ':</b> ' + esc(m) + '</div>');
						var sm = /states\['([^']+)'\]/.exec(m);
						if (sm) statesToFlag[sm[1]] = true;
					});
				}
				collect(f.errors, 'ERROR');
				collect(f.warnings, 'WARNING');
				collect(f.infos, 'INFO');
				result.innerHTML = rows.length === 0
					? '<b>Validation:</b> no findings.'
					: '<div style="border:1px solid #ccc; padding:6px; max-height:200px; overflow-y:auto; font-size:11px;">' +
						'<b>Validation findings (' + rows.length + '):</b>' + rows.join('') + '</div>';
				// Dashed outline on every state a finding names — the text
				// list above is the primary channel; this just points at them.
				Object.keys(statesToFlag).forEach(function(name) {
					findStateCells(name).forEach(function(cell) {
						addHighlight(highlights, cell, 3, true, FINDING_COLOR);
					});
				});
			}, function(msg) {
				result.innerHTML = '<b>' + esc(msg) + '</b>';
			});
		});
	}

	// -------------------------------------------------------------- Replay

	function replayTabHtml() {
		return '' +
			'<div style="font-size:11px; color:#444; margin-bottom:6px;">' +
			'  Captures real calls’ routing traces on the engines (per-engine ' +
			'  count) and replays them on the diagram. Uses the FSMAR&nbsp;3 metrics ' +
			'  MBean over JMX.</div>' +
			'<div style="display:flex; gap:6px; align-items:center;">' +
			'  <button id="rep-arm">Arm capture</button>' +
			'  <input id="rep-count" type="text" value="10" style="width:42px;"/> calls' +
			'  <span id="rep-status" style="font-size:11px; color:#444;"></span></div>' +
			'<div style="margin-top:6px;">' +
			'  <button id="rep-refresh">Refresh list</button> ' +
			'  <button id="rep-clear">Discard captures</button></div>' +
			'<div id="rep-list" style="margin-top:6px; max-height:180px; overflow-y:auto; ' +
			'  border:1px solid #ccc; font-size:11px;"></div>';
	}

	function wireReplayTab(div) {
		div.querySelector('#rep-arm').onclick = function() {
			var count = parseInt(div.querySelector('#rep-count').value, 10) || 10;
			post('fsmarMetrics', 'op=capture&count=' + count, function(r) {
				div.querySelector('#rep-status').textContent =
						'armed ' + r.armed + ' per engine (' + r.servers + ' engine' +
						(r.servers === 1 ? '' : 's') + ')';
			});
		};
		div.querySelector('#rep-refresh').onclick = function() { refreshReplayList(div); };
		div.querySelector('#rep-clear').onclick = function() {
			post('fsmarMetrics', 'op=clear', function() { refreshReplayList(div); });
		};
	}

	function refreshReplayList(div) {
		var list = div.querySelector('#rep-list');
		list.innerHTML = '<i style="padding:4px; display:block;">loading&hellip;</i>';
		post('fsmarMetrics', 'op=traces', function(r) {
			var traces = r.traces || [];
			if (traces.length === 0) {
				list.innerHTML = '<i style="padding:4px; display:block;">no captured traces ' +
						'(' + r.servers + ' engine' + (r.servers === 1 ? '' : 's') + ' reachable)</i>';
				return;
			}
			list.innerHTML = '';
			traces.forEach(function(t) {
				var row = document.createElement('div');
				row.style.cssText = 'padding:3px 5px; border-bottom:1px solid #eee; cursor:pointer;';
				row.innerHTML = '<b>' + esc(t.method || '?') + '</b> ' + esc(t.requestUri || '') +
						' &rarr; ' + esc(t.finalApp || '(downstream)') +
						'<br/><span style="color:#777;">' + esc(t.callId || '') +
						(t.server ? ' · ' + esc(t.server) : '') + '</span>';
				row.onclick = function() { loadTrace(t); };
				list.appendChild(row);
			});
		}, function(msg) {
			list.innerHTML = '<b style="padding:4px; display:block;">' + esc(msg) + '</b>';
		});
	}

	// ---------------------------------------------------------------- Live

	function liveTabHtml() {
		return '' +
			'<div style="font-size:11px; color:#444; margin-bottom:6px;">' +
			'  Overlays per-transition hit counts from every engine on the diagram ' +
			'  edges. Edge thickness scales with traffic share; the count is the label.</div>' +
			'<div><button id="live-toggle">Start live overlay</button> ' +
			'  <button id="live-reset">Reset counters</button></div>' +
			'<div id="live-totals" style="margin-top:6px; font-size:11px; color:#444;"></div>';
	}

	function wireLiveTab(div) {
		div.querySelector('#live-toggle').onclick = function() {
			if (liveTimer != null) {
				stopLive(div);
			} else {
				this.textContent = 'Stop live overlay';
				pollLive(div);
				liveTimer = window.setInterval(function() { pollLive(div); }, 5000);
			}
		};
		div.querySelector('#live-reset').onclick = function() {
			post('fsmarMetrics', 'op=reset', function() { pollLive(div); });
		};
	}

	function stopLive(div) {
		if (liveTimer != null) {
			window.clearInterval(liveTimer);
			liveTimer = null;
		}
		liveCounts = null;
		clearList(liveHighlights);
		graph().refresh();
		if (div) {
			div.querySelector('#live-toggle').textContent = 'Start live overlay';
			div.querySelector('#live-totals').innerHTML = '';
		}
	}

	function pollLive(div) {
		post('fsmarMetrics', 'op=hits', function(r) {
			var hits = r.transitionHits || [];
			var counts = {};
			var max = 0;
			hits.forEach(function(h) {
				var key = h.state + '/' + h.method + '/' + (h.id || '-');
				counts[key] = h.count;
				if (h.count > max) max = h.count;
			});
			liveCounts = counts;

			clearList(liveHighlights);
			hits.forEach(function(h) {
				var edge = findEdge(h.state, h.method, h.id && h.id !== '-' ? h.id : null);
				if (edge && h.count > 0) {
					var width = 1 + Math.round(7 * (max > 0 ? h.count / max : 0));
					addHighlight(liveHighlights, edge, width, false, PATH_COLOR);
				}
			});
			graph().refresh(); // re-derive edge labels (decorate)

			div.querySelector('#live-totals').innerHTML =
				'routed <b>' + r.requestsRouted + '</b>' +
				' · fallbacks <b>' + r.defaultApplicationFallbacks + '</b>' +
				' · bypasses <b>' + r.undeployedBypasses + '</b>' +
				' · cycles <b>' + r.routingCyclesDetected + '</b>' +
				' · engines <b>' + r.servers + '</b>' +
				(r.captureRemaining > 0 ? ' · capture armed <b>' + r.captureRemaining + '</b>' : '');
		}, function(msg) {
			div.querySelector('#live-totals').innerHTML = '<b>' + esc(msg) + '</b>';
			stopLive(div);
		});
	}

	// Called from convertValueToString (wfgraph-commons.xml): appends the
	// live hit count to a Transition edge's display label.
	function decorate(cell, label) {
		if (!liveCounts || !cell || !cell.source) return label;
		var stateName = vertexStateName(cell.source);
		if (stateName == null) return label;
		var key = stateName + '/' + (cell.getAttribute('label') || '') + '/' +
				(cell.getAttribute('txId') || '-');
		var n = liveCounts[key];
		return (n != null) ? label + ' · ' + n : label;
	}

	// ----------------------------------------------------- trace animation

	function loadTrace(t) {
		trace = t;
		step = 0;
		stopPlay();
		renderStep();
	}

	function stopPlay() {
		if (playTimer != null) {
			window.clearInterval(playTimer);
			playTimer = null;
		}
	}

	function renderStep() {
		if (wnd == null || trace == null) return;
		var hops = trace.hops || [];
		if (step < 0) step = 0;
		if (step >= hops.length) step = hops.length - 1;

		// --- canvas: path so far thin, current hop thick. Dashes mark the
		// bypass: the edge that leads to an undeployed app, and that app's
		// state cell (which is the NEXT hop's state). ---
		clearList(highlights);
		for (var i = 0; i <= step && i < hops.length; i++) {
			var hop = hops[i];
			var width = (i === step) ? 5 : 2;
			var stateDashed = (i > 0 && hops[i - 1].bypassed);
			findStateCells(hop.state).forEach(function(cell) {
				addHighlight(highlights, cell, width, stateDashed, PATH_COLOR);
			});
			if (hop.matched != null || hop.next != null) {
				var edge = findEdge(hop.state, trace.method, hop.matched);
				addHighlight(highlights, edge, width, hop.bypassed, PATH_COLOR);
			}
		}

		// --- step detail panel ---
		var result = wnd.div.querySelector('#sim-result');
		var hop = hops[step];
		var html = '';

		html += '<div style="border-top:1px solid #999; padding-top:6px;">';
		html += '<div><button id="sim-prev"' + (step <= 0 ? ' disabled' : '') + '>&#9664;</button> ';
		html += '<button id="sim-next"' + (step >= hops.length - 1 ? ' disabled' : '') + '>&#9654;</button> ';
		html += '<button id="sim-play">' + (playTimer != null ? 'Pause' : 'Play') + '</button> ';
		html += '<b>Hop ' + (step + 1) + ' of ' + hops.length + '</b> — state <b>' +
				esc(hop ? hop.state : '?') + '</b>';
		if (hop && hop.bypassed) html += ' <b>[BYPASSED — undeployed]</b>';
		html += '</div>';

		if (hop) {
			var ex = hop.extracted || {};
			var exKeys = Object.keys(ex);
			if (exKeys.length > 0) {
				html += '<div style="margin-top:4px;"><b>Extracted this hop:</b>' +
						'<table style="font-size:11px; font-family:monospace; margin-left:8px;">';
				exKeys.forEach(function(k) {
					html += '<tr><td>${' + esc(k) + '}</td><td>= ' + esc(ex[k]) + '</td></tr>';
				});
				html += '</table></div>';
			}

			var evals = hop.evaluated || [];
			html += '<div style="margin-top:4px;"><b>Transitions evaluated:</b>';
			if (evals.length === 0) {
				html += ' <i>none — call continues downstream</i>';
			} else {
				html += '<div style="margin-left:8px; font-size:11px;">';
				evals.forEach(function(ev) {
					var mark = ev.fired ? '&#10003; FIRED' : '&#10007; no match';
					html += '<div><b>' + mark + '</b> — ' + esc(ev.id || '(no id)') +
							(ev.when ? ' <code>' + esc(ev.when) + '</code>' : ' <i>(unconditional)</i>') +
							'</div>';
				});
				html += '</div>';
			}
			html += '</div>';

			if (hop.next != null) {
				html += '<div style="margin-top:4px;"><b>&rarr; next:</b> ' + esc(hop.next);
				if (hop.region && hop.region !== 'NEUTRAL') html += ' · region ' + esc(hop.region);
				html += '</div>';
			}
			if (hop.routes && hop.routes.length > 0) {
				html += '<div style="margin-top:2px;"><b>Routes (' + esc(hop.routeModifier || 'ROUTE') + '):</b>' +
						'<div style="margin-left:8px; font-family:monospace; font-size:11px;">' +
						hop.routes.map(esc).join('<br/>') + '</div></div>';
			}
			if (hop.subscriberURI) {
				html += '<div style="margin-top:2px;"><b>Subscriber:</b> <code>' +
						esc(hop.subscriberURI) + '</code></div>';
			}
		}

		// --- whole-call summary ---
		html += '<div style="margin-top:6px; border-top:1px dotted #aaa; padding-top:4px;">';
		html += '<b>Final application:</b> ' + esc(trace.finalApp || '(none — routes downstream)');
		if (trace.defaultFallback) html += ' · <b>DEFAULT FALLBACK</b>';
		if (trace.cycleDetected) html += ' · <b>CYCLE DETECTED</b>';
		if (trace.server) html += ' · engine ' + esc(trace.server);
		if (trace.callId) html += '<br/><span style="color:#777; font-size:11px;">' + esc(trace.callId) + '</span>';
		(trace.problems || []).forEach(function(p) {
			html += '<div><b>NOTE:</b> ' + esc(p) + '</div>';
		});
		html += '</div></div>';

		result.innerHTML = html;

		result.querySelector('#sim-prev').onclick = function() { stopPlay(); step--; renderStep(); };
		result.querySelector('#sim-next').onclick = function() { stopPlay(); step++; renderStep(); };
		result.querySelector('#sim-play').onclick = function() {
			if (playTimer != null) {
				stopPlay();
				renderStep();
				return;
			}
			if (step >= hops.length - 1) step = -1;
			playTimer = window.setInterval(function() {
				if (step >= hops.length - 1) {
					stopPlay();
					renderStep();
				} else {
					step++;
					renderStep();
				}
			}, 900);
		};
	}

	return {
		open: open,
		decorate: decorate
	};

})();
