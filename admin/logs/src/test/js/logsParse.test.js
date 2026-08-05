// logsParse — the parts of the viewer that are easy to get wrong.
//
// The window arithmetic is the reason this file exists. The viewer never holds
// a whole file, so every window it shows was cut out of the middle of one at an
// arbitrary byte offset, and the cut has to land on a line boundary without
// splitting a UTF-8 character and without leaving a gap between adjacent
// windows. Those three properties are asserted directly here.

var h = require('./harness');
var test = h.test;
var assert = h.assert;
var bytes = h.bytes;

var P = h.loadParse();

// Escape sequences exactly as Color.java writes them into the file.
var RED_BOLD_BRIGHT = '[1;91m';
var YELLOW_BOLD_BRIGHT = '[1;93m';
var RESET = '[0m';

// ---- snapWindow -----------------------------------------------------------

test('a mid-file window drops the partial line at each end', function () {
	var buf = bytes('ine one\nline two\nline thr');
	var s = P.snapWindow(buf, 5000, false, true, true);

	assert.strictEqual(new TextDecoder().decode(buf.subarray(s.lo, s.hi)), 'line two\n');
	assert.strictEqual(s.needMore, false);
});

test('a window at byte 0 keeps its first line', function () {
	var buf = bytes('line one\nline two\npartial');
	var s = P.snapWindow(buf, 0, false, true, true);

	assert.strictEqual(new TextDecoder().decode(buf.subarray(s.lo, s.hi)), 'line one\nline two\n');
});

test('a window at EOF keeps a final line with no trailing newline', function () {
	var buf = bytes('ne one\nlast line with no newline');
	var s = P.snapWindow(buf, 5000, true, true, true);

	assert.strictEqual(new TextDecoder().decode(buf.subarray(s.lo, s.hi)),
		'last line with no newline');
});

test('snapping never splits a multi-byte character at either edge', function () {
	// Three-byte and four-byte characters placed so a naive byte cut would
	// land inside them. After snapping, decoding must produce no U+FFFD.
	var text = 'héllo wörld ✓\nmiddle ☎ line ✉\ntrailing 𝄞 clef\n';
	var buf = bytes(text);

	for (var cut = 1; cut < buf.length - 1; cut++) {
		var slice = buf.subarray(0, cut);
		var s = P.snapWindow(slice, 1, false, true, true);
		var out = new TextDecoder().decode(slice.subarray(s.lo, s.hi));
		assert.strictEqual(out.indexOf('�'), -1,
			'replacement character after snapping at byte ' + cut);
	}
});

test('snapEnd off preserves the exact end, so an upward extension has no gap', function () {
	// Extending upward requests [prev.start - n, prev.start). That end is
	// already a boundary; snapping it would drop the last line and leave a hole
	// between the new chunk and the one it joins.
	var whole = bytes('alpha\nbravo\ncharlie\ndelta\n');
	var prevStart = 12;                             // start of "charlie"
	var upper = whole.subarray(0, prevStart);       // "alpha\nbravo\n"

	var s = P.snapWindow(upper, 0, false, true, false);
	assert.strictEqual(s.hi, upper.length, 'end must not move');
	assert.strictEqual(new TextDecoder().decode(upper.subarray(s.lo, s.hi)), 'alpha\nbravo\n');
});

test('adjacent windows join back into the original bytes', function () {
	var whole = bytes('alpha\nbravo\ncharlie\ndelta\necho\n');

	// Lower window: an arbitrary cut in the middle, snapped both ends.
	var lowerRaw = whole.subarray(3, 20);
	var ls = P.snapWindow(lowerRaw, 3, false, true, true);
	var lowerStart = 3 + ls.lo, lowerEnd = 3 + ls.hi;

	// Upper window: exactly [lowerEnd, ...], start already a boundary.
	var upperRaw = whole.subarray(lowerEnd, whole.length);
	var us = P.snapWindow(upperRaw, lowerEnd, true, false, true);

	var joined = new TextDecoder().decode(whole.subarray(lowerStart, lowerEnd))
		+ new TextDecoder().decode(upperRaw.subarray(us.lo, us.hi));

	assert.strictEqual(joined, new TextDecoder().decode(whole.subarray(lowerStart, whole.length)));
});

test('a line longer than the window asks for more', function () {
	var buf = bytes('no newline anywhere in this window at all');
	var s = P.snapWindow(buf, 5000, false, true, true);

	assert.strictEqual(s.needMore, true);
	assert.strictEqual(s.lo, 0, 'degrades to the raw window rather than showing nothing');
});

// ---- utf8Len --------------------------------------------------------------

test('utf8Len matches the real encoded length', function () {
	['', 'plain ascii', 'héllo', 'wörld ✓ ☎', 'clef 𝄞 and emoji 🎯',
		'INVITE sip:alice@example.test SIP/2.0'].forEach(function (s) {
		assert.strictEqual(P.utf8Len(s), Buffer.byteLength(s, 'utf8'), JSON.stringify(s));
	});
});

// ---- Record parsing -------------------------------------------------------

test('the vorpal formatter line yields level, severity and timestamp', function () {
	var recs = P.parseRecords('INFO    2026-08-04 10:12:33.123 - callflow started\n', 0);

	assert.strictEqual(recs.length, 1);
	assert.strictEqual(recs[0].lvl, 'INFO');
	assert.strictEqual(recs[0].sev, 'info');
	assert.strictEqual(recs[0].off, 0);
	assert.strictEqual(new Date(recs[0].ts).getFullYear(), 2026);
});

test('the severity token is not repeated in the record body', function () {
	// It is rendered in the gutter, so leaving it in the body prints it twice
	// on every single line.
	var recs = P.parseRecords('INFO    2026-08-04 10:12:33.123 - callflow started\n', 0);

	assert.strictEqual(recs[0].lines[0], '2026-08-04 10:12:33.123 - callflow started');
	assert.strictEqual(recs[0].lvl, 'INFO');
});

test('find still matches the severity token even though the body omits it', function () {
	// Otherwise searching the window for SEVERE finds nothing while the same
	// search run on the server finds every one.
	var recs = P.parseRecords('SEVERE  2026-08-04 10:12:33.123 - call failed\n', 0);

	assert.ok(P.recText(recs[0]).indexOf('SEVERE') !== -1, P.recText(recs[0]));
	assert.ok(P.recText(recs[0]).indexOf('call failed') !== -1);
});

test('a stack trace stays attached to the SEVERE that produced it', function () {
	var text =
		'SEVERE  2026-08-04 10:12:33.123 - call failed\n' +
		'java.lang.NullPointerException\n' +
		'\tat org.vorpal.Example.run(Example.java:42)\n' +
		'INFO    2026-08-04 10:12:34.000 - next\n';
	var recs = P.parseRecords(text, 0);

	assert.strictEqual(recs.length, 2, 'trace lines must not become their own records');
	assert.strictEqual(recs[0].sev, 'error');
	assert.strictEqual(recs[0].lines.length, 3);
	assert.strictEqual(recs[1].lvl, 'INFO');
});

test('severity maps both vocabularies onto one filter set', function () {
	assert.strictEqual(P.sevClass('SEVERE'), 'error');
	assert.strictEqual(P.sevClass('Error'), 'error');
	assert.strictEqual(P.sevClass('Critical'), 'error');
	assert.strictEqual(P.sevClass('WARNING'), 'warning');
	assert.strictEqual(P.sevClass('Warning'), 'warning');
	assert.strictEqual(P.sevClass('INFO'), 'info');
	assert.strictEqual(P.sevClass('Notice'), 'info');
	assert.strictEqual(P.sevClass('FINEST'), 'debug');
	assert.strictEqual(P.sevClass('Debug'), 'debug');
	assert.strictEqual(P.sevClass('whatever'), 'plain');
});

test('a WebLogic #### record parses its severity and message', function () {
	var line = '####<Aug 4, 2026 10:12:33,123 AM UTC> <Warning> <WebLogicServer> <host1> ' +
		'<engine1> <[ACTIVE] ExecuteThread: 3> <<WLS Kernel>> <> <> <1785000753123> ' +
		'<BEA-000365> <Server state changed to ADMIN>';
	var recs = P.parseRecords(line + '\n', 0);

	assert.strictEqual(recs.length, 1);
	assert.strictEqual(recs[0].lvl, 'Warning');
	assert.strictEqual(recs[0].sev, 'warning');
	assert.ok(recs[0].lines[0].indexOf('Server state changed to ADMIN') !== -1,
		'message should survive field stripping: ' + recs[0].lines[0]);
});

test('lines that never had a header stand alone', function () {
	// An access log has no severity and no continuation semantics; folding it
	// into one record would make filtering and offsets meaningless.
	var text = '10.0.0.1 - - [04/Aug/2026:10:12:33] "GET /blade/logs HTTP/1.1" 200 512\n' +
		'10.0.0.2 - - [04/Aug/2026:10:12:34] "GET /blade/portal HTTP/1.1" 200 90\n';
	var recs = P.parseRecords(text, 0);

	assert.strictEqual(recs.length, 2);
	assert.strictEqual(recs[0].sev, 'plain');
	assert.strictEqual(recs[1].sev, 'plain');
});

test('record offsets are byte offsets, not character offsets', function () {
	var text =
		'INFO    2026-08-04 10:12:33.123 - café ☎\n' +
		'INFO    2026-08-04 10:12:34.000 - second\n';
	var recs = P.parseRecords(text, 1000);

	assert.strictEqual(recs[0].off, 1000);
	assert.strictEqual(recs[1].off, 1000 + Buffer.byteLength(text.split('\n')[0], 'utf8') + 1);
});

// ---- ANSI -----------------------------------------------------------------

test('the escapes Logger writes into SEVERE lines are stripped', function () {
	var raw = 'SEVERE  2026-08-04 10:12:33.123 - ' + RED_BOLD_BRIGHT + 'call failed' + RESET;
	assert.strictEqual(P.stripAnsi(raw),
		'SEVERE  2026-08-04 10:12:33.123 - call failed');
});

test('ANSI parsing turns a bright bold colour into a styled segment', function () {
	var segs = P.ansiParse('plain ' + YELLOW_BOLD_BRIGHT + 'loud' + RESET + ' plain');

	assert.strictEqual(segs.length, 3);
	assert.strictEqual(segs[0].cls, '');
	assert.strictEqual(segs[1].text, 'loud');
	assert.ok(segs[1].cls.indexOf('a-bold') !== -1, 'bold: ' + segs[1].cls);
	assert.ok(segs[1].cls.indexOf('a-fg-3') !== -1, 'yellow: ' + segs[1].cls);
	assert.strictEqual(segs[2].cls, '', 'reset must clear the style');
});

test('non-SGR escape sequences are dropped, not rendered', function () {
	assert.strictEqual(P.stripAnsi('before[2Kafter'), 'beforeafter');
});

test('turning colour off still removes the escapes', function () {
	// This is the defect the renderer fixes: with no ANSI handling at all the
	// pane shows raw control characters, which is what it did before.
	var raw = RED_BOLD_BRIGHT + 'boom' + RESET;
	var html = P.renderText(raw, false, '');

	assert.strictEqual(html, 'boom');
	assert.strictEqual(html.indexOf(''), -1);
});

test('rendering escapes HTML in the log text', function () {
	var html = P.renderText('<script>alert(1)</script> & "quoted"', false, '');
	assert.strictEqual(html.indexOf('<script>'), -1);
	assert.ok(html.indexOf('&lt;script&gt;') !== -1, html);
	assert.ok(html.indexOf('&amp;') !== -1, html);
});

test('rendering escapes HTML inside a coloured segment too', function () {
	var html = P.renderText(RED_BOLD_BRIGHT + '<b>x</b>' + RESET, true, '');
	assert.strictEqual(html.indexOf('<b>'), -1, html);
	assert.ok(html.indexOf('&lt;b&gt;') !== -1, html);
});

// ---- Find highlighting ----------------------------------------------------

test('find marks are case-insensitive and still escape', function () {
	var html = P.escWithMarks('Call FAILED for <alice>', 'failed');

	assert.ok(html.indexOf('<mark>FAILED</mark>') !== -1, html);
	assert.ok(html.indexOf('&lt;alice&gt;') !== -1, html);
});

test('a needle containing markup is escaped inside its own mark', function () {
	var html = P.escWithMarks('tag <b> here', '<b>');
	assert.ok(html.indexOf('<mark>&lt;b&gt;</mark>') !== -1, html);
	assert.strictEqual(html.indexOf('<b>'), -1);
});

// ---- Timestamps -----------------------------------------------------------

test('both timestamp dialects parse', function () {
	var vorpal = P.parseTime('2026-08-04 14:32:05.123');
	assert.ok(!isNaN(vorpal), 'vorpal timestamp');
	assert.strictEqual(new Date(vorpal).getFullYear(), 2026);

	var wls = P.parseTime('Aug 4, 2026 10:12:33,123 AM UTC');
	assert.ok(!isNaN(wls), 'WebLogic timestamp');
	assert.strictEqual(new Date(wls).getUTCFullYear(), 2026);
});

test('an unparseable timestamp reports NaN rather than a wrong date', function () {
	assert.ok(isNaN(P.parseTime('')));
	assert.ok(isNaN(P.parseTime('not a time at all')));
});

test('timestamps are ordered, which is what the time seek bisects on', function () {
	var a = P.parseTime('2026-08-04 14:32:05.123');
	var b = P.parseTime('2026-08-04 14:32:05.124');
	var c = P.parseTime('2026-08-04 14:33:00.000');
	assert.ok(a < b && b < c);
});
