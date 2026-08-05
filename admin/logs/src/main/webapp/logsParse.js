"use strict";

/*
 * Pure parsing and rendering for the log viewer — no DOM, no fetch.
 *
 * Split out from logs.js so the parts that are easy to get wrong can be run
 * outside a browser: byte-level line snapping, UTF-8 length, the two record
 * formats BLADE actually writes, and the ANSI escapes the framework bakes into
 * SEVERE and WARNING lines. See src/test/js/.
 *
 * A plain IIFE assigning to `window`, matching admin/flow's browser modules.
 */

(function (root) {

	// ---- Bytes ------------------------------------------------------------

	/// Exact UTF-8 byte length of a JS string, without allocating an encoder
	/// buffer per line. Record offsets are byte offsets, but parsing happens on
	/// decoded text, so the two have to be reconciled somewhere.
	function utf8Len(s) {
		var n = 0;
		for (var i = 0; i < s.length; i++) {
			var c = s.charCodeAt(i);
			if (c < 0x80) n += 1;
			else if (c < 0x800) n += 2;
			else if (c >= 0xd800 && c <= 0xdbff) { n += 4; i++; }  // surrogate pair
			else n += 3;
		}
		return n;
	}

	/// Trim a raw slice to whole lines.
	///
	/// This is why the viewer fetches ArrayBuffers rather than text. A window
	/// that begins and ends just after a newline can never split a multi-byte
	/// UTF-8 character, so the decode that follows is always safe; snapping
	/// after decoding would already have corrupted the edge characters.
	///
	/// `snapStart` is skipped at byte 0 and `snapEnd` at EOF — those edges are
	/// real boundaries already. Callers also switch one off when they know the
	/// edge is a boundary: extending the window upward requests exactly
	/// [prev.start - n, prev.start), and snapping that end would leave a gap
	/// between the new chunk and the one it has to join.
	///
	/// `needMore` means no newline was found where one was needed — a line
	/// longer than the request — and the caller should retry with a bigger one.
	/// The retry can fail too (a line longer than the reader's 1 MiB cap is
	/// possible; a single-line SDP dump gets close), so that path still trims
	/// to a CHARACTER boundary. Showing half a line is honest; showing a
	/// replacement glyph looks like a decoding bug in the viewer.
	function snapWindow(buf, start, eof, snapStart, snapEnd) {
		var lo = 0, hi = buf.length, needMore = false, nl;

		if (snapStart && start > 0) {
			nl = buf.indexOf(10);
			if (nl >= 0) lo = nl + 1;
			else { needMore = true; lo = charCeil(buf, 0, hi); }
		}
		if (snapEnd && !eof) {
			nl = buf.lastIndexOf(10);
			if (nl >= lo) hi = nl + 1;
			else { needMore = true; hi = charFloor(buf, hi, lo); }
		}
		if (hi < lo) hi = lo;
		return { lo: lo, hi: hi, needMore: needMore };
	}

	function isContinuation(b) { return (b & 0xc0) === 0x80; }

	/// Advance past any continuation bytes left dangling at the front.
	function charCeil(buf, lo, hi) {
		var i = lo, steps = 0;
		while (i < hi && steps < 4 && isContinuation(buf[i])) { i++; steps++; }
		return i;
	}

	/// Retreat so the end does not fall inside a multi-byte sequence.
	function charFloor(buf, hi, lo) {
		var i = hi - 1, steps = 0;
		while (i >= lo && steps < 4 && isContinuation(buf[i])) { i--; steps++; }
		if (i < lo) return hi;

		var b = buf[i], len;
		if (b < 0x80) len = 1;
		else if ((b & 0xe0) === 0xc0) len = 2;
		else if ((b & 0xf0) === 0xe0) len = 3;
		else if ((b & 0xf8) === 0xf0) len = 4;
		else return hi;                       // not a lead byte — leave it be
		return (i + len > hi) ? i : hi;
	}

	// ---- ANSI -------------------------------------------------------------
	//
	// The framework writes real escape sequences into the file: Logger wraps
	// SEVERE and WARNING message text with Color.RED_BOLD_BRIGHT and friends
	// and only strips them again on the SNMP path. Without this the pane shows
	// raw control characters.

	var CSI = /\x1b\[([0-9;?]*)([A-Za-z])/g;

	function blankStyle() {
		return { bold: false, dim: false, ital: false, under: false, fg: -1, bg: -1 };
	}

	/// Split text into styled segments. Only SGR ("m") is interpreted; other
	/// CSI sequences are dropped, which is what a file-backed log needs —
	/// cursor movement has no meaning in a scrollback pane.
	function ansiParse(text) {
		var out = [];
		var cur = blankStyle();
		var last = 0, m;

		function push(s) {
			if (!s) return;
			var cls = [];
			if (cur.bold) cls.push("a-bold");
			if (cur.dim) cls.push("a-dim");
			if (cur.ital) cls.push("a-ital");
			if (cur.under) cls.push("a-under");
			if (cur.fg >= 0) cls.push("a-fg-" + cur.fg);
			if (cur.bg >= 0) cls.push("a-bg-" + cur.bg);
			out.push({ text: s, cls: cls.join(" ") });
		}

		CSI.lastIndex = 0;
		while ((m = CSI.exec(text)) !== null) {
			push(text.slice(last, m.index));
			last = CSI.lastIndex;
			if (m[2] !== "m") continue;
			var codes = m[1].split(";");
			for (var i = 0; i < codes.length; i++) {
				var n = parseInt(codes[i], 10);
				if (isNaN(n)) n = 0;
				if (n === 0) cur = blankStyle();
				else if (n === 1) cur.bold = true;
				else if (n === 2) cur.dim = true;
				else if (n === 3) cur.ital = true;
				else if (n === 4) cur.under = true;
				else if (n === 22) { cur.bold = false; cur.dim = false; }
				else if (n === 23) cur.ital = false;
				else if (n === 24) cur.under = false;
				else if (n >= 30 && n <= 37) cur.fg = n - 30;
				else if (n === 39) cur.fg = -1;
				else if (n >= 40 && n <= 47) cur.bg = n - 40;
				else if (n === 49) cur.bg = -1;
				else if (n >= 90 && n <= 97) { cur.fg = n - 90; cur.bold = true; }
				else if (n >= 100 && n <= 107) cur.bg = n - 100;
			}
		}
		push(text.slice(last));
		return out;
	}

	function stripAnsi(text) {
		return text.replace(CSI, "");
	}

	// ---- Escaping and highlighting ----------------------------------------

	function esc(s) {
		return s.replace(/[&<>"]/g, function (c) {
			switch (c) {
				case "&": return "&amp;";
				case "<": return "&lt;";
				case ">": return "&gt;";
				default:  return "&quot;";
			}
		});
	}

	/// Escape a segment and wrap occurrences of the find needle in <mark>.
	/// Marking happens per segment, inside the colour span, so a match that
	/// straddles a colour change highlights only its first part.
	function escWithMarks(s, needle) {
		if (!needle) return esc(s);
		var lower = s.toLowerCase();
		var n = needle.toLowerCase();
		var out = "", from = 0, at;
		while ((at = lower.indexOf(n, from)) !== -1) {
			out += esc(s.slice(from, at)) + "<mark>" + esc(s.slice(at, at + n.length)) + "</mark>";
			from = at + n.length;
		}
		return out + esc(s.slice(from));
	}

	function renderText(text, ansiOn, needle) {
		if (!ansiOn) return escWithMarks(stripAnsi(text), needle);
		var segs = ansiParse(text), html = "";
		for (var i = 0; i < segs.length; i++) {
			var body = escWithMarks(segs[i].text, needle);
			html += segs[i].cls ? '<span class="' + segs[i].cls + '">' + body + "</span>" : body;
		}
		return html;
	}

	// ---- Record parsing ---------------------------------------------------

	// LogFormatter writes: padRight(level, 7) + " " + yyyy-MM-dd HH:mm:ss.SSS + " - " + message
	var VORPAL_RE = /^(SEVERE|WARNING|INFO|CONFIG|FINE|FINER|FINEST)\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) - ?([\s\S]*)$/;
	// WebLogic writes: ####<timestamp> <severity> <subsystem> <machine> <server>
	//                  <thread> <user> <txid> <context> <raw-time> <msgid> <message>
	var WLS_RE = /^####<([^>]*)>\s*<([^>]*)>\s*<([^>]*)>([\s\S]*)$/;
	var WLS_FIELD = /^\s*<[^>]*>/;

	function sevClass(token) {
		switch (String(token).toUpperCase()) {
			case "SEVERE": case "EMERGENCY": case "ALERT": case "CRITICAL": case "ERROR":
				return "error";
			case "WARNING": case "WARN":
				return "warning";
			case "INFO": case "NOTICE": case "CONFIG":
				return "info";
			case "FINE": case "FINER": case "FINEST": case "DEBUG": case "TRACE":
				return "debug";
			default:
				return "plain";
		}
	}

	/// Parse both timestamp dialects into epoch millis, or NaN.
	///
	/// Neither carries a zone the browser can trust — the vorpal formatter uses
	/// SimpleDateFormat in the server's default zone and writes no offset — so
	/// both are read as local time. Jump-to-time is therefore consistent with
	/// what the file shows, which is what an operator reading it means.
	function parseTime(s) {
		if (!s) return NaN;
		var m = /^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})\.(\d{3})$/.exec(s);
		if (m) return Date.parse(m[1] + "T" + m[2] + "." + m[3]);
		// WLS: "Aug 4, 2026 10:12:33,123 AM UTC" — drop the millis, which no
		// engine parses in that position, and let Date handle the rest.
		return Date.parse(String(s).replace(/,(\d{1,3})(?=\s)/, ""));
	}

	/// Recognise a record header. Returns null for a continuation line.
	function classify(line) {
		var m = VORPAL_RE.exec(line);
		if (m) {
			// The body starts at the timestamp. The level moves to the gutter,
			// so repeating it here would print it twice on every line.
			return {
				lvl: m[1],
				sev: sevClass(m[1]),
				ts: parseTime(m[2]),
				rest: m[2] + " - " + m[3]
			};
		}
		m = WLS_RE.exec(line);
		if (m) {
			// After timestamp/severity/subsystem, WebLogic emits eight more
			// bracketed fields and then the message, itself bracketed. Consume
			// leniently: a '>' inside an early field would mis-split, so stop as
			// soon as the remainder stops looking like a field rather than
			// counting on the format holding.
			var rest = m[4];
			for (var k = 0; k < 8 && WLS_FIELD.test(rest); k++) rest = rest.replace(WLS_FIELD, "");
			rest = rest.replace(/^\s*<([\s\S]*)>\s*$/, "$1");
			return { lvl: m[2], sev: sevClass(m[2]), ts: parseTime(m[1]), rest: m[1] + " " + rest };
		}
		return null;
	}

	/// Split decoded text into records, tracking each record's byte offset.
	///
	/// A record is a header line plus the unparsed lines beneath it — which is
	/// how a stack trace stays attached to the SEVERE that produced it, and why
	/// the severity gutter carries one token per record rather than per line.
	/// Lines that never had a header (access logs) stand alone, so filtering
	/// and offsets stay meaningful for them too.
	function parseRecords(text, baseOffset) {
		var lines = text.split("\n");
		var recs = [];
		var off = baseOffset || 0;
		var cur = null;

		for (var i = 0; i < lines.length; i++) {
			var line = lines[i];
			var isLast = (i === lines.length - 1);
			if (isLast && line === "") break;          // text ended on a newline
			var bytes = utf8Len(line) + (isLast ? 0 : 1);
			var head = classify(line);

			if (head) {
				cur = { off: off, sev: head.sev, lvl: head.lvl, ts: head.ts, lines: [head.rest] };
				recs.push(cur);
			} else if (cur && cur.sev !== "plain") {
				cur.lines.push(line);
			} else {
				cur = { off: off, sev: "plain", lvl: "", ts: NaN, lines: [line] };
				recs.push(cur);
			}
			off += bytes;
		}
		return recs;
	}

	/// Plain text of a record, escapes removed — what find matches against and
	/// what "download search matches" writes out.
	///
	/// The severity token is put back on the front, because it is displayed in
	/// the gutter rather than in the body: without this, searching the window
	/// for "SEVERE" would find nothing while the same search run on the server
	/// found every one of them.
	function recText(r) {
		if (r._plain === undefined) {
			r._plain = (r.lvl ? r.lvl + " " : "") + stripAnsi(r.lines.join("\n"));
		}
		return r._plain;
	}

	root.logsParse = {
		utf8Len: utf8Len,
		snapWindow: snapWindow,
		ansiParse: ansiParse,
		stripAnsi: stripAnsi,
		esc: esc,
		escWithMarks: escWithMarks,
		renderText: renderText,
		sevClass: sevClass,
		parseTime: parseTime,
		classify: classify,
		parseRecords: parseRecords,
		recText: recText
	};

})(typeof window !== "undefined" ? window : this);
