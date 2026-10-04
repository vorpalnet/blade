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
		return domain.filter(function (_, i) { return i % every === 0 || i === domain.length - 1; });
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

	/* Horizontal bars for named categories: rows of [label, value], largest first.
	   The value always sits at the end of its bar. */
	function hbar(el, rows, opts) {
		opts = opts || {};
		if (!rows || !rows.length) return message(el, 'empty', opts.empty || 'Nothing in this window.');
		rows = rows.slice(0, opts.limit || 15);
		var s = surface(el, { l: opts.labelWidth || 120, r: 54, t: 4, b: 4 });
		var y = d3.scaleBand().domain(rows.map(function (r) { return r[0]; })).range([0, s.h]).padding(0.22);
		var max = d3.max(rows, function (r) { return +r[1] || 0; }) || 1;
		var x = d3.scaleLinear().domain([0, max]).range([0, s.w]);
		s.g.selectAll('rect.bar').data(rows, function (r) { return r[0]; }).join('rect').attr('class', 'bar')
			.attr('x', 0).attr('y', function (r) { return y(r[0]); }).attr('height', y.bandwidth())
			.attr('width', function (r) { return Math.max(1, x(+r[1] || 0)); }).attr('rx', 3)
			.selectAll('title').data(function (r) { return [r]; }).join('title').text(function (r) { return r[0] + ': ' + fmt(r[1]); });
		s.g.selectAll('text.cat').data(rows, function (r) { return r[0]; }).join('text').attr('class', 'cat')
			.attr('x', -8).attr('y', function (r) { return y(r[0]) + y.bandwidth() / 2; }).attr('dy', '.35em')
			.attr('text-anchor', 'end').text(function (r) { return shortLabel(r[0]); });
		s.g.selectAll('text.value').data(rows, function (r) { return r[0]; }).join('text').attr('class', 'value')
			.attr('x', function (r) { return x(+r[1] || 0) + 6; }).attr('y', function (r) { return y(r[0]) + y.bandwidth() / 2; })
			.attr('dy', '.35em').text(function (r) { return fmt(r[1]); });
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
		s.g.selectAll('g.x-axis').data([0]).join('g').attr('class', 'x-axis').attr('transform', 'translate(0,' + s.h + ')')
			.call(d3.axisBottom(x).tickValues(sparseTicks(labels, s.w)).tickFormat(opts.tickFormat || shortLabel).tickSize(0).tickPadding(6))
			.call(function (a) { a.select('.domain').attr('class', 'domain baseline'); });
		var colors = opts.colors || [token('--accent')];
		var line = d3.line().defined(function (v) { return v[1] != null; })
			.x(function (v) { return x(v[0]); }).y(function (v) { return y(+v[1] || 0); });
		var lines = s.g.selectAll('g.series').data(series, function (sr) { return sr.name; }).join('g').attr('class', 'series');
		lines.selectAll('path').data(function (sr, i) { return [{ sr: sr, color: colors[i % colors.length] }]; }).join('path')
			.attr('class', 'linepath').attr('stroke', function (d) { return d.color; }).attr('d', function (d) { return line(d.sr.values); });
		lines.selectAll('text.end').data(function (sr, i) {
			var last = sr.values.filter(function (v) { return v[1] != null; }).pop();
			return last ? [{ name: sr.name, last: last, color: colors[i % colors.length] }] : [];
		}).join('text').attr('class', 'end').attr('fill', function (d) { return d.color; })
			.attr('x', function (d) { return x(d.last[0]) + 6; }).attr('y', function (d) { return y(+d.last[1] || 0); }).attr('dy', '.35em')
			.text(function (d) { return d.name + ' ' + fmt(d.last[1]) + (opts.pct ? '%' : ''); });
	}

	/* One line: rows of [label, value], named at its end with its latest value. */
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
		var ramp = d3.scaleLinear().domain([0, max]).range([0.06, 1]);
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
			.text(function (r) { return d3.format('~s')(r[2]); });
		s.g.selectAll('g.y-axis').data([0]).join('g').attr('class', 'y-axis').call(d3.axisLeft(y).tickSize(0).tickPadding(6))
			.call(function (a) { a.select('.domain').remove(); });
		s.g.selectAll('g.x-axis').data([0]).join('g').attr('class', 'x-axis').attr('transform', 'translate(0,' + s.h + ')')
			.call(d3.axisBottom(x).tickValues(d3.range(0, 24, 3)).tickSize(0).tickPadding(6))
			.call(function (a) { a.select('.domain').remove(); });
	}

	/* Stages top-down, width as a share of the first: rows of [stage, count]. */
	function funnel(el, rows, opts) {
		opts = opts || {};
		var top = rows && rows.length ? +rows[0][1] || 0 : 0;
		if (!top) return message(el, 'empty', opts.empty || 'No calls started in this window.');
		var s = surface(el, { l: 8, r: 8, t: 6, b: 6 });
		var y = d3.scaleBand().domain(rows.map(function (r) { return r[0]; })).range([0, s.h]).padding(0.18);
		var groups = s.g.selectAll('g.stage').data(rows, function (r) { return r[0]; }).join('g').attr('class', 'stage')
			.attr('transform', function (r) { return 'translate(0,' + y(r[0]) + ')'; });
		groups.selectAll('rect').data(function (r) { return [r]; }).join('rect').attr('class', 'bar').attr('rx', 4)
			.attr('height', y.bandwidth())
			.attr('width', function (r) { return Math.max(2, s.w * (+r[1] || 0) / top); })
			.attr('x', function (r) { return (s.w - Math.max(2, s.w * (+r[1] || 0) / top)) / 2; });
		groups.selectAll('text').data(function (r) { return [r]; }).join('text').attr('class', 'funnel-label')
			.attr('x', s.w / 2).attr('y', y.bandwidth() / 2).attr('dy', '.35em').attr('text-anchor', 'middle')
			.text(function (r) { return r[0] + '  ' + fmt(r[1]) + '  (' + Math.round((+r[1] || 0) / top * 100) + '%)'; });
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
		var t = document.createElement('table'); t.className = 'report';
		var thead = t.createTHead().insertRow();
		result.columns.forEach(function (c) {
			var th = document.createElement('th'); th.textContent = c; thead.appendChild(th);
		});
		var body = t.createTBody();
		var bandCol = result.columns.findIndex(function (c) { return /band/.test(c); });
		var scoreCol = result.columns.findIndex(function (c) { return /score/.test(c); });
		result.rows.slice(0, opts.limit || 500).forEach(function (row) {
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

	global.BladeCharts = {
		bar: bar, hbar: hbar, line: line, multiLine: multiLine, heatmap: heatmap, funnel: funnel,
		sparkline: sparkline, table: table, message: message, fmt: fmt, token: token, bandPill: bandPill
	};
})(window);
