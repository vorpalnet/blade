/* BLADE Dashboard charts, drawn with D3 (vendor/d3.min.js, ISC). No CDN.

   Colour rule (redundant coding): colour stays, and a second channel always
   says the same thing.
   - Status or severity: the colour AND the word (a risk band pill reads
     SUSPECT / WATCH / CLEAR), with the score beside it; bands are always listed
     worst first, so position carries the order too.
   - Magnitude: the ramp or bar AND the number. Heatmap cells print their count;
     bars carry a value label wherever it fits, and a tooltip everywhere.
   - Several series on one chart: each line is named at its end.
   - Parallel categories (applications, engines, intents) are not a ranking, so
     they share ONE accent; the axis names them.
   Colours come from the CSS tokens in dashboard.css, never from hex in here. */
(function (global) {
	'use strict';

	function token(name) {
		return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
	}

	var COUNT = d3.format(',');
	var DECIMAL = d3.format(',.2~f');

	function fmt(v) {
		if (v == null) return '—';
		if (typeof v !== 'number') return String(v);
		return Number.isInteger(v) ? COUNT(v) : DECIMAL(v);
	}

	/* Severity words and the token each is drawn in. The word is always shown. */
	var BAND_TOKENS = { SUSPECT: '--bad', WATCH: '--warn', CLEAR: '--ok' };

	function bandPill(band, score) {
		var s = document.createElement('span');
		var t = BAND_TOKENS[band];
		s.className = 'pill';
		if (t) s.style.setProperty('--pill', token(t));
		s.textContent = band + (score != null ? '  ' + fmt(score) : '');
		return s;
	}

	function message(el, cls, text) {
		el.textContent = '';
		var d = document.createElement('div');
		d.className = cls;
		d.textContent = text;
		el.appendChild(d);
	}

	/* An SVG sized to its container, reused across refreshes so marks update in place. */
	function surface(el, margin) {
		var W = el.clientWidth || 600, H = el.clientHeight || 220;
		var svg = d3.select(el).selectAll('svg.chart').data([0]).join('svg').attr('class', 'chart')
			.attr('viewBox', '0 0 ' + W + ' ' + H).attr('width', W).attr('height', H);
		d3.select(el).selectAll(':scope > div').remove();
		var g = svg.selectAll('g.plot').data([0]).join('g').attr('class', 'plot')
			.attr('transform', 'translate(' + margin.l + ',' + margin.t + ')');
		return { svg: svg, g: g, w: Math.max(10, W - margin.l - margin.r), h: Math.max(10, H - margin.t - margin.b), W: W, H: H };
	}

	/* Counts never get fractional ticks ("200m" for a fifth of a call). */
	function tick(v) { return Math.abs(v) >= 1000 ? d3.format('~s')(v) : d3.format(',~')(v); }

	function countScale(max, range) {
		max = Math.max(1, max || 0);
		return d3.scaleLinear().domain([0, max]).nice(Math.min(4, Math.ceil(max))).range(range);
	}

	function gridY(g, y, w, integer) {
		var top = y.domain()[1];
		g.selectAll('g.y-axis').data([0]).join('g').attr('class', 'y-axis')
			.call(d3.axisLeft(y).ticks(integer ? Math.min(4, Math.ceil(top)) : 4).tickSize(-w).tickFormat(tick))
			.call(function (a) { a.select('.domain').remove(); });
	}

	/* Thin category labels so they never collide: at most one per label width. */
	function sparseTicks(domain, w) {
		var longest = d3.max(domain, function (d) { return shortLabel(d).length; }) || 4;
		var minPx = Math.max(44, longest * 7 + 12);
		var every = Math.max(1, Math.ceil(domain.length / Math.max(1, Math.floor(w / minPx))));
		// The last label always shows; the regular one before it gives way if the
		// two would touch.
		var last = domain.length - 1;
		return domain.filter(function (_, i) { return i === last || (i % every === 0 && last - i >= every); });
	}

	function shortLabel(s) {
		s = String(s);
		var m = /^\d{4}-(\d{2})-(\d{2})( (\d{2}):00)?$/.exec(s);
		if (m) return m[3] ? m[1] + '/' + m[2] + ' ' + m[4] + 'h' : m[1] + '/' + m[2];
		return s.length > 14 ? s.slice(0, 13) + '…' : s;
	}

	/* Vertical bars: rows of [label, value]. One accent for every bar. */
	function bar(el, rows, opts) {
		opts = opts || {};
		if (!rows || !rows.length) return message(el, 'empty', opts.empty || 'Nothing in this window.');
		var s = surface(el, { l: 40, r: 8, t: 16, b: 24 });
		var x = d3.scaleBand().domain(rows.map(function (r) { return r[0]; })).range([0, s.w]).padding(0.18);
		var integer = rows.every(function (r) { return r[1] == null || Number.isInteger(+r[1]); });
		var max = d3.max(rows, function (r) { return +r[1] || 0; });
		var y = integer ? countScale(max, [s.h, 0]) : d3.scaleLinear().domain([0, max || 1]).nice().range([s.h, 0]);
		gridY(s.g, y, s.w, integer);
		s.g.selectAll('g.x-axis').data([0]).join('g').attr('class', 'x-axis').attr('transform', 'translate(0,' + s.h + ')')
			.call(d3.axisBottom(x).tickValues(sparseTicks(x.domain(), s.w)).tickFormat(shortLabel).tickSize(0).tickPadding(6))
			.call(function (a) { a.select('.domain').attr('class', 'domain baseline'); });
		s.g.selectAll('rect.bar').data(rows, function (r) { return r[0]; }).join('rect').attr('class', 'bar')
			.attr('x', function (r) { return x(r[0]); }).attr('width', x.bandwidth())
			.attr('y', function (r) { return y(+r[1] || 0); }).attr('height', function (r) { return s.h - y(+r[1] || 0); })
			.attr('rx', Math.min(3, x.bandwidth() / 2))
			.selectAll('title').data(function (r) { return [r]; }).join('title').text(function (r) { return r[0] + ': ' + fmt(r[1]); });
		// The value on the bar wherever a label fits.
		var labelled = x.bandwidth() >= 22 ? rows : [];
		s.g.selectAll('text.value').data(labelled, function (r) { return r[0]; }).join('text').attr('class', 'value')
			.attr('x', function (r) { return x(r[0]) + x.bandwidth() / 2; }).attr('y', function (r) { return y(+r[1] || 0) - 4; })
			.attr('text-anchor', 'middle').text(function (r) { return fmt(r[1]); });
	}

	/* Stacked vertical bars. rows: [label, part0, part1, …]. opts.parts,
	   bottom first: [{ name, color (a token), explain }].
	   Two channels for every part: its colour, and its word, in the legend
	   and in each segment's tooltip with what it means. A thin line in the
	   page's ink where one colour meets the next (and nowhere else), so two
	   colours never run together. */
	function stackBar(el, rows, opts) {
		opts = opts || {};
		var parts = opts.parts, k = parts.length;
		var total = function (r) { var n = 0; for (var i = 1; i <= k; i++) n += +r[i] || 0; return n; };
		var top = d3.max(rows || [], total);
		if (!rows || !rows.length || !top) return message(el, 'empty', opts.empty || 'Nothing in this window.');
		var s = surface(el, { l: 40, r: 8, t: 30, b: 24 });
		var x = d3.scaleBand().domain(rows.map(function (r) { return r[0]; })).range([0, s.w]).padding(0.18);
		var y = countScale(top, [s.h, 0]);
		gridY(s.g, y, s.w, true);
		s.g.selectAll('g.x-axis').data([0]).join('g').attr('class', 'x-axis').attr('transform', 'translate(0,' + s.h + ')')
			.call(d3.axisBottom(x).tickValues(sparseTicks(x.domain(), s.w)).tickFormat(shortLabel).tickSize(0).tickPadding(6))
			.call(function (a) { a.select('.domain').attr('class', 'domain baseline'); });
		var segs = [];
		rows.forEach(function (r) {
			var base = 0;
			for (var i = 0; i < k; i++) {
				var v = +r[i + 1] || 0;
				if (v) segs.push({ key: r[0] + '/' + i, label: r[0], part: parts[i], y0: base, y1: base + v, v: v });
				base += v;
			}
		});
		s.g.selectAll('rect.seg').data(segs, function (d) { return d.key; }).join('rect').attr('class', 'seg')
			.style('fill', function (d) { return token(d.part.color); })
			.attr('x', function (d) { return x(d.label); }).attr('width', x.bandwidth())
			.attr('y', function (d) { return y(d.y1); }).attr('height', function (d) { return Math.max(0, y(d.y0) - y(d.y1)); })
			.selectAll('title').data(function (d) { return [d]; }).join('title')
			.text(function (d) { return shortLabel(d.label) + '\n' + d.part.name + ': ' + fmt(d.v) + '\n' + d.part.explain; });
		// Where a segment sits on another, the line between them.
		s.g.selectAll('line.seg-edge').data(segs.filter(function (d) { return d.y0 > 0; }), function (d) { return d.key; })
			.join('line').attr('class', 'seg-edge')
			.attr('x1', function (d) { return x(d.label); }).attr('x2', function (d) { return x(d.label) + x.bandwidth(); })
			.attr('y1', function (d) { return y(d.y0); }).attr('y2', function (d) { return y(d.y0); });
		var labelled = x.bandwidth() >= 22 ? rows.filter(function (r) { return total(r) > 0; }) : [];
		s.g.selectAll('text.value').data(labelled, function (r) { return r[0]; }).join('text').attr('class', 'value')
			.attr('x', function (r) { return x(r[0]) + x.bandwidth() / 2; }).attr('y', function (r) { return y(total(r)) - 4; })
			.attr('text-anchor', 'middle').text(function (r) { return fmt(total(r)); });
		// The legend, in words, bottom part first; each entry explains itself on hover.
		var legend = s.svg.selectAll('g.legend').data([0]).join('g').attr('class', 'legend').attr('transform', 'translate(40,10)');
		var at = 0;
		legend.selectAll('g.item').data(parts).join('g').attr('class', 'item').each(function (d) {
			var gi = d3.select(this).attr('transform', 'translate(' + at + ',0)');
			gi.selectAll('*').remove();
			gi.append('title').text(d.name + ': ' + d.explain);
			gi.append('rect').attr('class', 'seg').style('fill', token(d.color)).attr('width', 10).attr('height', 10);
			var t = gi.append('text').attr('class', 'legend-label').attr('x', 14).attr('y', 9).text(d.name);
			at += 14 + t.node().getComputedTextLength() + 14;
		});
	}

	/* Horizontal bars for named categories: rows of [label, value], largest first.
	   The value always sits at the end of its bar; opts.value(row) words it, with
	   opts.valueWidth the room it needs. opts.color(row) names a colour token
	   for a bar that means something (an outcome), opts.title(row) its tooltip;
	   without them every bar takes the one accent. */
	function hbar(el, rows, opts) {
		opts = opts || {};
		if (!rows || !rows.length) return message(el, 'empty', opts.empty || 'Nothing in this window.');
		rows = rows.slice(0, opts.limit || 15);
		var s = surface(el, { l: opts.labelWidth || 120, r: opts.valueWidth || 54, t: 4, b: 4 });
		// A bar never thicker than 26 px: two categories should not fill a panel.
		var y = d3.scaleBand().domain(rows.map(function (r) { return r[0]; })).range([0, Math.min(s.h, rows.length * 34)]).padding(0.22);
		var max = d3.max(rows, function (r) { return +r[1] || 0; }) || 1;
		var x = d3.scaleLinear().domain([0, max]).range([0, s.w]);
		s.g.selectAll('rect.bar').data(rows, function (r) { return r[0]; }).join('rect').attr('class', 'bar')
			.attr('x', 0).attr('y', function (r) { return y(r[0]); }).attr('height', y.bandwidth())
			.attr('width', function (r) { return Math.max(1, x(+r[1] || 0)); }).attr('rx', 3)
			.style('fill', function (r) { return opts.color ? token(opts.color(r)) : null; })
			.selectAll('title').data(function (r) { return [r]; }).join('title')
			.text(function (r) { return opts.title ? opts.title(r) : r[0] + ': ' + fmt(r[1]); });
		/* opts.open(r): where a bar leads, {href, target} or null. The bar and
		   its name open it; the name is underlined, so a link is not colour alone. */
		if (opts.open) {
			var go = function (event, r) { var o = opts.open(r); if (o) window.open(o.href, o.target || '_blank'); };
			s.g.selectAll('rect.bar').classed('bar--link', function (r) { return !!opts.open(r); }).on('click', go);
		}
		s.g.selectAll('text.cat').data(rows, function (r) { return r[0]; }).join('text').attr('class', 'cat')
			.attr('x', -8).attr('y', function (r) { return y(r[0]) + y.bandwidth() / 2; }).attr('dy', '.35em')
			.attr('text-anchor', 'end').text(function (r) { return shortLabel(r[0]); })
			.classed('cat--link', function (r) { return !!(opts.open && opts.open(r)); })
			.on('click', function (event, r) { var o = opts.open && opts.open(r); if (o) window.open(o.href, o.target || '_blank'); });
		s.g.selectAll('text.value').data(rows, function (r) { return r[0]; }).join('text').attr('class', 'value')
			.attr('x', function (r) { return x(+r[1] || 0) + 6; }).attr('y', function (r) { return y(r[0]) + y.bandwidth() / 2; })
			.attr('dy', '.35em').text(function (r) { return opts.value ? opts.value(r) : fmt(r[1]); });
	}

	/* Several lines over shared categories: series = [{name, values: [[label, v], …]}].
	   Each line is named at its end, so no legend has to be matched by colour.
	   opts.pct draws a 0..100 % axis. */
	function multiLine(el, series, opts) {
		opts = opts || {};
		series = (series || []).filter(function (sr) { return sr.values && sr.values.length; });
		if (!series.length) return message(el, 'empty', opts.empty || 'Nothing in this window.');
		var s = surface(el, { l: 40, r: 78, t: 12, b: 24 });
		var labels = series[0].values.map(function (v) { return v[0]; });
		var x = d3.scalePoint().domain(labels).range([0, s.w]);
		var integer = !opts.pct && series.every(function (sr) { return sr.values.every(function (v) { return v[1] == null || Number.isInteger(+v[1]); }); });
		var max = opts.pct ? 100 : d3.max(series, function (sr) { return d3.max(sr.values, function (v) { return +v[1] || 0; }); });
		var y = integer ? countScale(max, [s.h, 0]) : d3.scaleLinear().domain([0, max || 1]).nice().range([s.h, 0]);
		gridY(s.g, y, s.w, integer);
		// A label that reads the same as the one before it (the same minute,
		// early in a live chart) is dropped rather than printed twice.
		var tickFormat = opts.tickFormat || shortLabel, shown = null;
		var tickValues = sparseTicks(labels, s.w).filter(function (v) {
			var t = tickFormat(v);
			if (t === shown) return false;
			shown = t;
			return true;
		});
		s.g.selectAll('g.x-axis').data([0]).join('g').attr('class', 'x-axis').attr('transform', 'translate(0,' + s.h + ')')
			.call(d3.axisBottom(x).tickValues(tickValues).tickFormat(tickFormat).tickSize(0).tickPadding(6))
			.call(function (a) { a.select('.domain').attr('class', 'domain baseline'); });
		var colors = opts.colors || [token('--accent')];
		var line = d3.line().defined(function (v) { return v[1] != null; })
			.x(function (v) { return x(v[0]); }).y(function (v) { return y(+v[1] || 0); });
		var lines = s.g.selectAll('g.series').data(series, function (sr) { return sr.name; }).join('g').attr('class', 'series');
		lines.selectAll('path').data(function (sr, i) { return [{ sr: sr, color: colors[i % colors.length] }]; }).join('path')
			.attr('class', 'linepath').attr('stroke', function (d) { return d.color; }).attr('d', function (d) { return line(d.sr.values); });
		// End labels, pushed apart where lines finish at the same height, so two
		// names never print over each other.
		var ends = [];
		// opts.endLabel false: a single line whose latest value is not news.
		if (opts.endLabel !== false) series.forEach(function (sr, i) {
			var last = sr.values.filter(function (v) { return v[1] != null; }).pop();
			if (last) ends.push({ name: sr.name, last: last, color: colors[i % colors.length], y: y(+last[1] || 0) });
		});
		ends.sort(function (a, b) { return b.y - a.y; });
		for (var e = 1; e < ends.length; e++) ends[e].y = Math.min(ends[e].y, ends[e - 1].y - 12);
		lines.selectAll('text.end').data(function (sr) {
			return ends.filter(function (d) { return d.name === sr.name; });
		}).join('text').attr('class', 'end').attr('fill', function (d) { return d.color; })
			.attr('x', function (d) { return x(d.last[0]) + 6; }).attr('y', function (d) { return d.y; }).attr('dy', '.35em')
			.text(function (d) { return d.name + ' ' + fmt(d.last[1]) + (opts.pct ? '%' : ''); });
	}

	/* One line: rows of [label, value], named at its end with its latest value
	   unless opts.endLabel is false. */
	function line(el, rows, opts) {
		opts = opts || {};
		multiLine(el, rows && rows.length ? [{ name: opts.name || '', values: rows }] : [], opts);
	}

	/* Weekday x hour grid: rows of [weekday, hour, count]. The ramp shows the
	   pattern at a glance; the number in each cell is what it says. */
	function heatmap(el, rows, opts) {
		opts = opts || {};
		if (!rows || !rows.length) return message(el, 'empty', opts.empty || 'Nothing in this window.');
		var s = surface(el, { l: 36, r: 4, t: 4, b: 20 });
		var days = [], hours = d3.range(24);
		rows.forEach(function (r) { if (days.indexOf(r[0]) < 0) days.push(r[0]); });
		var x = d3.scaleBand().domain(hours).range([0, s.w]).padding(0.06);
		var y = d3.scaleBand().domain(days).range([0, s.h]).padding(0.08);
		var max = d3.max(rows, function (r) { return +r[2] || 0; }) || 1;
		// Square root, so one busy hour (a load test) leaves the ordinary ones
		// visible; the printed count says how far apart they are.
		var ramp = d3.scaleSqrt().domain([0, max]).range([0.06, 1]);
		var cells = s.g.selectAll('g.cell').data(rows, function (r) { return r[0] + ' ' + r[1]; }).join('g').attr('class', 'cell')
			.attr('transform', function (r) { return 'translate(' + x(r[1]) + ',' + y(r[0]) + ')'; });
		cells.selectAll('rect').data(function (r) { return [r]; }).join('rect').attr('class', 'heat')
			.attr('width', x.bandwidth()).attr('height', y.bandwidth()).attr('rx', 2)
			.attr('fill-opacity', function (r) { return ramp(+r[2] || 0); })
			.selectAll('title').data(function (r) { return [r]; }).join('title')
			.text(function (r) { return r[0] + ' ' + r[1] + ':00 · ' + fmt(r[2]) + ' calls'; });
		var showNumbers = x.bandwidth() >= 18 && y.bandwidth() >= 12;
		cells.selectAll('text').data(function (r) { return showNumbers && +r[2] ? [r] : []; }).join('text').attr('class', 'heat-n')
			.attr('x', x.bandwidth() / 2).attr('y', y.bandwidth() / 2).attr('dy', '.35em').attr('text-anchor', 'middle')
			.text(function (r) { return +r[2] < 1000 ? fmt(+r[2]) : d3.format('.2~s')(r[2]); });
		s.g.selectAll('g.y-axis').data([0]).join('g').attr('class', 'y-axis').call(d3.axisLeft(y).tickSize(0).tickPadding(6))
			.call(function (a) { a.select('.domain').remove(); });
		s.g.selectAll('g.x-axis').data([0]).join('g').attr('class', 'x-axis').attr('transform', 'translate(0,' + s.h + ')')
			.call(d3.axisBottom(x).tickValues(d3.range(0, 24, 3)).tickSize(0).tickPadding(6))
			.call(function (a) { a.select('.domain').remove(); });
	}

	/* A card's trend: rows of [label, value], no axes. The number it trends is
	   printed beside it in the card, so the line is never the only channel. */
	function sparkline(el, rows) {
		if (!rows || !rows.length) { el.textContent = ''; return; }
		var s = surface(el, { l: 2, r: 2, t: 4, b: 4 });
		var x = d3.scaleBand().domain(rows.map(function (r) { return r[0]; })).range([0, s.w]).padding(0.15);
		var max = d3.max(rows, function (r) { return +r[1] || 0; }) || 1;
		var y = d3.scaleLinear().domain([0, max]).range([s.h, 0]);
		s.g.selectAll('rect.bar').data(rows, function (r) { return r[0]; }).join('rect').attr('class', 'bar')
			.attr('x', function (r) { return x(r[0]); }).attr('width', x.bandwidth())
			.attr('y', function (r) { return y(+r[1] || 0); }).attr('height', function (r) { return s.h - y(+r[1] || 0); })
			.selectAll('title').data(function (r) { return [r]; }).join('title').text(function (r) { return r[0] + ': ' + fmt(r[1]); });
	}

	/* A report table: {columns, rows}. A column named like "band" renders as a
	   pill (colour + word); numbers right-align. */
	function table(el, result, opts) {
		opts = opts || {};
		if (!result || !result.rows || !result.rows.length) return message(el, 'empty', opts.empty || 'Nothing in this window.');
		el.textContent = '';
		var wrap = document.createElement('div'); wrap.className = 'table-wrap';
		var t = document.createElement('table'); t.className = 'report' + (opts.wrap ? ' report--wrap' : '');
		var thead = t.createTHead().insertRow();
		var shown = result.rows.slice(0, opts.limit || 500);
		result.columns.forEach(function (c, i) {
			var th = document.createElement('th'); th.textContent = c;
			// A column of numbers is right-aligned, its header with it.
			var first = shown.map(function (row) { return row[i]; }).find(function (v) { return v != null; });
			if (typeof first === 'number') th.className = 'num';
			thead.appendChild(th);
		});
		var body = t.createTBody();
		var bandCol = result.columns.findIndex(function (c) { return /band/.test(c); });
		var scoreCol = result.columns.findIndex(function (c) { return /score/.test(c); });
		shown.forEach(function (row) {
			var tr = body.insertRow();
			row.forEach(function (v, i) {
				var td = tr.insertCell();
				if (i === bandCol && v) td.appendChild(bandPill(v, scoreCol >= 0 ? row[scoreCol] : null));
				else td.textContent = fmt(v);
				if (typeof v === 'number') td.className = 'num';
			});
		});
		wrap.appendChild(t); el.appendChild(wrap);
	}

	/* The US by state, coloured by how its calls ended: from unhealthy to
	   healthy on a red, amber, green scale, where opts.unhealthy and
	   opts.healthy are the share completed at each end. A state with fewer than
	   opts.minCalls calls is left uncoloured rather than judged on a handful.
	   A circle at each state's centre is sized by its calls. Every state's
	   tooltip says the word (healthy, fair, unhealthy) and the numbers, and
	   the legend under the map names both ends of the scale.
	   atlas: us-atlas states-albers-10m, already projected, so geoPath() takes
	   no projection. byState: { 'Texas': [completed, ...] }, one count per
	   opts.names (the outcomes, completed first).
	   opts.foot: a line under the legend (calls the map cannot place). */
	function statesMap(el, atlas, byState, opts) {
		el.textContent = '';
		var states = topojson.feature(atlas, atlas.objects.states).features;
		var path = d3.geoPath();
		var colors = [token('--geo-bad'), token('--geo-fair'), token('--geo-good')];
		var color = d3.scaleLinear().domain([opts.unhealthy, (opts.unhealthy + opts.healthy) / 2, opts.healthy])
			.range(colors).interpolate(d3.interpolateRgb).clamp(true);
		function total(n) { return n ? n.reduce(function (a, b) { return a + b; }, 0) : 0; }
		function judged(n) { return total(n) >= opts.minCalls; }
		function word(share) { return share >= opts.healthy ? 'healthy' : share <= opts.unhealthy ? 'unhealthy' : 'fair'; }
		function pct(v, t) { return Math.round(100 * v / t) + '%'; }
		function tip(d) {
			var name = d.properties.name, n = byState[name], t = total(n);
			if (!t) return name + ': no calls';
			var parts = n.map(function (v, i) { return v ? pct(v, t) + ' ' + opts.names[i] : null; }).filter(Boolean).join(', ');
			if (!judged(n)) return name + ': ' + fmt(t) + (t === 1 ? ' call' : ' calls') + ', too few to judge · ' + parts;
			return name + ': ' + word(n[0] / t) + ' · ' + fmt(t) + ' calls · ' + parts;
		}

		var wrap = document.createElement('div');
		wrap.className = 'geo';
		el.appendChild(wrap);
		var svg = d3.select(wrap).append('svg').attr('class', 'geo__map')
			.attr('viewBox', '0 0 975 610').attr('preserveAspectRatio', 'xMidYMid meet')
			.attr('role', 'img').attr('aria-label', 'Calls by state, coloured by how they ended');
		svg.append('g').selectAll('path').data(states).join('path')
			.attr('class', function (d) { return 'geo-state' + (judged(byState[d.properties.name]) ? '' : ' geo-state--none'); })
			.attr('d', path)
			.style('fill', function (d) {
				var n = byState[d.properties.name];
				return judged(n) ? color(n[0] / total(n)) : null;
			})
			.append('title').text(tip);
		var most = d3.max(states, function (d) { return total(byState[d.properties.name]); }) || 1;
		var radius = d3.scaleSqrt().domain([0, most]).range([0, 24]);
		svg.append('g').selectAll('circle')
			.data(states.filter(function (d) { return total(byState[d.properties.name]) > 0; }))
			.join('circle').attr('class', 'geo-calls')
			.attr('cx', function (d) { return path.centroid(d)[0]; })
			.attr('cy', function (d) { return path.centroid(d)[1]; })
			.attr('r', function (d) { return Math.max(2, radius(total(byState[d.properties.name]))); });

		var legend = document.createElement('div');
		legend.className = 'geo__legend';
		legend.innerHTML = '<span class="geo__end">unhealthy</span>'
			+ '<span class="geo__scale" style="background:linear-gradient(to right,' + colors.join(',') + ')" title="'
			+ 'Share of calls completed: ' + Math.round(opts.unhealthy * 100) + '% or less is unhealthy, '
			+ Math.round(opts.healthy * 100) + '% or more is healthy, fair between"></span>'
			+ '<span class="geo__end">healthy</span>'
			+ '<span class="geo__note">completed ≤' + Math.round(opts.unhealthy * 100) + '% … ≥' + Math.round(opts.healthy * 100) + '%</span>'
			+ '<span class="geo__note"><i class="geo__key geo__key--none"></i>fewer than ' + opts.minCalls + ' calls</span>'
			+ '<span class="geo__note"><i class="geo__key geo__key--dot"></i>size: calls</span>';
		wrap.appendChild(legend);
		if (opts.foot) {
			var foot = document.createElement('div');
			foot.className = 'geo__foot';
			foot.textContent = opts.foot;
			wrap.appendChild(foot);
		}
	}

	/* The network map: each site where it is, lines from where calls arrive
	   to the cluster and from the cluster to where calls are sent, each mark in
	   the stoplight colour of its health AND a shape that says the same: a
	   circle working, a diamond in trouble, a square down, a hollow circle for
	   no data. Sites close enough to overlap share one mark, the worst of
	   them; clicking it zooms in and spreads out every server there, named,
	   with its state in words.
	   model: { sites: [{ name, city, lat, lon, state, members: [{ label, state,
	   note }] }], links: [{ from, to, state, note }] }; a state is up, warn,
	   down or none. atlases: { us, world }. opts.zoomed: the key of the place
	   to show zoomed in (kept by the caller across refreshes); opts.onZoom(key)
	   is told when the viewer zooms in or out. opts.foot: a line under it. */
	var NET_RANK = { down: 3, warn: 2, up: 1, none: 0 };
	var NET_WORD = { up: 'working', warn: 'trouble', down: 'down', none: 'no data' };

	function netWorst(states) {
		return states.reduce(function (w, s) { return (NET_RANK[s] || 0) > (NET_RANK[w] || 0) ? s : w; }, 'none');
	}

	function netColor(state) {
		return token({ up: '--geo-good', warn: '--geo-fair', down: '--geo-bad' }[state] || '--ink-3');
	}

	/* One mark: the shape carries the state as the colour does. */
	function netMark(g, state, r) {
		var mark;
		switch (state) {
		case 'down':
			mark = g.append('rect').attr('x', -r).attr('y', -r).attr('width', 2 * r).attr('height', 2 * r);
			break;
		case 'warn':
			mark = g.append('rect').attr('x', -r * 0.9).attr('y', -r * 0.9).attr('width', 1.8 * r).attr('height', 1.8 * r)
				.attr('transform', 'rotate(45)');
			break;
		default:
			mark = g.append('circle').attr('r', r);
		}
		mark.attr('class', 'net-mark net-mark--' + state).style('fill', state === 'none' ? null : netColor(state));
		return mark;
	}

	function netArc(x1, y1, x2, y2) {
		var mx = (x1 + x2) / 2, my = (y1 + y2) / 2, dx = x2 - x1, dy = y2 - y1;
		var len = Math.sqrt(dx * dx + dy * dy) || 1, bow = Math.min(60, len * 0.18);
		return 'M' + x1.toFixed(1) + ',' + y1.toFixed(1) + ' Q' + (mx - dy / len * bow).toFixed(1) + ','
			+ (my + dx / len * bow).toFixed(1) + ' ' + x2.toFixed(1) + ',' + y2.toFixed(1);
	}

	function networkMap(el, atlases, model, opts) {
		opts = opts || {};
		el.textContent = '';
		var wrap = document.createElement('div');
		wrap.className = 'net';
		el.appendChild(wrap);

		var placed = model.sites.filter(function (s) { return s.lat != null && s.lon != null; });
		/* The pre-projected US atlas is Albers USA at scale 1300 centred in
		   975 x 610 (us-atlas); points must be projected the same way. */
		var usProj = d3.geoAlbersUsa().scale(1300).translate([487.5, 305]);
		var isUS = placed.length > 0 && placed.every(function (s) { return usProj([s.lon, s.lat]) != null; });
		var W = 975, H = isUS ? 610 : 520, project;
		var svg = d3.select(wrap).append('svg').attr('class', 'net__map').attr('viewBox', '0 0 ' + W + ' ' + H)
			.attr('preserveAspectRatio', 'xMidYMid meet').attr('role', 'img').attr('aria-label', 'Network map');
		var zoom = svg.append('g');
		if (isUS) {
			var path = d3.geoPath();
			zoom.append('path').attr('class', 'net-land').attr('d', path(topojson.feature(atlases.us, atlases.us.objects.nation)));
			zoom.append('path').attr('class', 'net-borders')
				.attr('d', path(topojson.mesh(atlases.us, atlases.us.objects.states, function (a, b) { return a !== b; })));
			project = function (s) { return usProj([s.lon, s.lat]); };
		} else {
			var world = d3.geoNaturalEarth1().fitExtent([[8, 8], [W - 8, H - 8]], { type: 'Sphere' });
			zoom.append('path').attr('class', 'net-land').attr('d', d3.geoPath(world)(topojson.feature(atlases.world, atlases.world.objects.land)));
			project = function (s) { return world([s.lon, s.lat]); };
		}

		/* Places: sites close enough to overlap share one mark. */
		var places = [], byName = {};
		placed.forEach(function (s) {
			var xy = project(s);
			if (!xy) return;
			var p = places.filter(function (q) { return Math.hypot(q.x - xy[0], q.y - xy[1]) < 18; })[0];
			if (!p) { p = { x: xy[0], y: xy[1], sites: [] }; places.push(p); }
			p.sites.push(s);
			byName[s.name] = p;
		});
		places.forEach(function (p) {
			p.state = netWorst(p.sites.map(function (s) { return s.state; }));
			p.key = p.sites.map(function (s) { return s.name; }).sort().join(' + ');
			var home = p.sites.filter(function (s) { return (s.roles || []).indexOf('cluster') >= 0; })[0] || p.sites[0];
			p.label = home.name;
		});

		model.links.forEach(function (l) {
			var a = byName[l.from], b = byName[l.to];
			if (!a || !b || a === b) return;
			zoom.append('path').attr('class', 'net-link').attr('d', netArc(a.x, a.y, b.x, b.y))
				.style('stroke', netColor(l.state)).append('title').text(l.note);
		});

		places.forEach(function (p) {
			var g = zoom.append('g').attr('class', 'net-place').attr('transform', 'translate(' + p.x.toFixed(1) + ',' + p.y.toFixed(1) + ')');
			// a target a finger or a slightly-off pointer can hit; the mark itself is small
			g.append('circle').attr('class', 'net-hit').attr('r', 20);
			netMark(g, p.state, p.sites.length > 1 ? 13 : 9);
			if (p.sites.length > 1) g.append('text').attr('class', 'net-count').attr('dy', '0.35em').text(p.sites.length);
			g.append('text').attr('class', 'net-name').attr('y', -20).text(p.label);
			g.append('title').text(p.sites.map(function (s) {
				return s.name + (s.city ? ' (' + s.city + ')' : '') + ': ' + NET_WORD[s.state]
					+ (s.summary ? ' · ' + s.summary : '');
			}).join('\n') + '\nClick to zoom in.');
			g.on('click', function (ev) { ev.stopPropagation(); zoomTo(p, true); });
		});

		var detail = svg.append('g').attr('class', 'net-detail');
		var back = document.createElement('button');
		back.type = 'button';
		back.className = 'net__back';
		back.textContent = '‹ whole map';
		back.hidden = true;
		back.addEventListener('click', function () { zoomTo(null, true); });
		wrap.appendChild(back);
		svg.on('click', function () { if (!back.hidden) zoomTo(null, true); });

		function zoomTo(p, animate) {
			var k = 3.2;
			var t = p ? 'translate(' + (W / 2 - k * p.x) + ',' + (H / 2 - k * p.y) + ') scale(' + k + ')' : 'translate(0,0) scale(1)';
			(animate ? zoom.transition().duration(550) : zoom).attr('transform', t);
			detail.selectAll('*').remove();
			back.hidden = !p;
			zoom.selectAll('.net-name, .net-count').style('opacity', p ? 0 : null);
			if (opts.onZoom) opts.onZoom(p ? p.key : null);
			if (!p) return;
			var members = [];
			p.sites.forEach(function (s) {
				(s.members.length ? s.members : [{ label: s.name, state: s.state, note: 'nothing reported' }]).forEach(function (m) {
					members.push({ label: m.label, state: m.state, site: s.name, note: m.note || '',
						where: p.sites.length > 1 ? s.name + ' · ' : '' });
				});
			});
			var cx = W / 2, cy = H / 2, ring = Math.min(210, 70 + members.length * 14);
			var d = detail.attr('opacity', 0);
			d.append('circle').attr('class', 'net-backdrop').attr('cx', cx).attr('cy', cy).attr('r', ring + 95);
			members.forEach(function (m, i) {
				var a = -Math.PI / 2 + 2 * Math.PI * i / members.length;
				var x = cx + ring * Math.cos(a), y = cy + ring * Math.sin(a);
				d.append('line').attr('class', 'net-spoke').attr('x1', cx).attr('y1', cy).attr('x2', x).attr('y2', y)
					.style('stroke', netColor(m.state));
				var g = d.append('g').attr('transform', 'translate(' + x.toFixed(1) + ',' + y.toFixed(1) + ')');
				netMark(g, m.state, 9);
				var right = Math.cos(a) >= -0.01;
				g.append('text').attr('class', 'net-member').attr('x', right ? 15 : -15).attr('dy', '-0.1em')
					.attr('text-anchor', right ? 'start' : 'end').text(m.label);
				g.append('text').attr('class', 'net-member-note').attr('x', right ? 15 : -15).attr('dy', '1.1em')
					.attr('text-anchor', right ? 'start' : 'end').text(m.where + NET_WORD[m.state] + (m.note ? ' · ' + m.note : ''));
				g.append('title').text(m.site + ' · ' + m.label + ': ' + NET_WORD[m.state] + (m.note ? ' · ' + m.note : ''));
			});
			var c = d.append('g').attr('transform', 'translate(' + cx + ',' + cy + ')');
			netMark(c, p.state, 13);
			c.append('text').attr('class', 'net-name net-name--center').attr('y', -(ring + 52)).text(p.sites.map(function (s) { return s.name; }).join(' · '));
			d.on('click', function (ev) { ev.stopPropagation(); });
			(animate ? d.transition().delay(350).duration(300) : d).attr('opacity', 1);
		}

		var legend = document.createElement('div');
		legend.className = 'net__legend';
		legend.innerHTML = ['up', 'warn', 'down', 'none'].map(function (s) {
			return '<span class="net__key"><svg viewBox="-10 -10 20 20" width="12" height="12"><g></g></svg>' + NET_WORD[s] + '</span>';
		}).join('') + '<span class="net__note">a line is the route between two sites · click a site to see its servers</span>';
		wrap.appendChild(legend);
		['up', 'warn', 'down', 'none'].forEach(function (s, i) {
			netMark(d3.select(legend.querySelectorAll('.net__key g')[i]), s, 6);
		});
		if (opts.foot) {
			var foot = document.createElement('div');
			foot.className = 'net__foot';
			foot.textContent = opts.foot;
			wrap.appendChild(foot);
		}
		var zoomed = opts.zoomed && places.filter(function (p) { return p.key === opts.zoomed; })[0];
		if (zoomed) zoomTo(zoomed, false);
	}

	global.BladeCharts = {
		bar: bar, stackBar: stackBar, hbar: hbar, line: line, multiLine: multiLine, heatmap: heatmap,
		sparkline: sparkline, statesMap: statesMap, networkMap: networkMap, table: table, message: message, fmt: fmt, token: token, bandPill: bandPill
	};
})(window);
